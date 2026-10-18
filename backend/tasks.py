"""Versioned task authority in the computer SQLite database.

Both the task API and the Room snapshot gateway use the same command processor.
JSON is a transport/receipt encoding; task business records are SQL columns.
"""
import hashlib
import json
import math
import uuid
from contextlib import closing
from datetime import date, datetime

from fastapi import APIRouter, Header, HTTPException, Request
from starlette.concurrency import run_in_threadpool

from .backup import MAX_BYTES, PREFERENCE_KEYS, ROOM_VERSION, TABLE_COLUMNS, _authorize, _column_definition, _columns_for, _read_snapshot, _stored_value, _validate, _write_snapshot

router = APIRouter(prefix="/tasks", tags=["tasks"])
COLUMNS = tuple(name for name, _ in TABLE_COLUMNS["tasks"])
FIELDS = ", ".join(f'"{name}"' for name in COLUMNS)
INTEGER_MAX = 2**63 - 1
PLACE_TYPES = {"SUPERMARKET", "PRINT_SHOP", "PHARMACY", "PARCEL", "TAKEOUT"}


def _digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                    separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def _ensure_schema(db):
    """Called inside sync's IMMEDIATE initialization transaction, exactly once."""
    fields = ", ".join(_column_definition(name, kind) + (" PRIMARY KEY" if name == "id" else "")
                       for name, kind in TABLE_COLUMNS["tasks"])
    db.execute(f"CREATE TABLE IF NOT EXISTS task_records ({fields}, version INTEGER NOT NULL, deleted INTEGER NOT NULL CHECK(deleted IN (0, 1)))")
    if "plannedDate" not in {row[1] for row in db.execute("PRAGMA table_info(task_records)")}:
        db.execute("ALTER TABLE task_records ADD COLUMN plannedDate TEXT NOT NULL DEFAULT ''")
    if "reminderAt" not in {row[1] for row in db.execute("PRAGMA table_info(task_records)")}:
        db.execute("ALTER TABLE task_records ADD COLUMN reminderAt INTEGER NOT NULL DEFAULT 0")
    db.execute("CREATE TABLE IF NOT EXISTS task_schema (id INTEGER PRIMARY KEY CHECK(id=1), protocol INTEGER NOT NULL)")
    db.execute("CREATE TABLE IF NOT EXISTS task_command_receipts (command_id TEXT PRIMARY KEY, digest TEXT NOT NULL, task_id TEXT NOT NULL, version INTEGER NOT NULL, deleted INTEGER NOT NULL)")
    db.execute("CREATE TABLE IF NOT EXISTS task_batch_receipts (request_id TEXT PRIMARY KEY, digest TEXT NOT NULL, response TEXT NOT NULL)")
    marker = db.execute("SELECT protocol FROM task_schema WHERE id=1").fetchone()
    if marker is None:
        # No new validation is applied to legacy rows. Never truncate legacy data.
        db.execute(f"INSERT INTO task_records ({FIELDS}, version, deleted) SELECT {FIELDS}, 1, 0 FROM backup_tasks")
        db.execute("INSERT INTO task_schema VALUES (1, 1)")
    elif marker[0] != 1:
        raise HTTPException(409, "任务数据库版本较新，请升级服务后再操作")


def _metadata(db):
    return {"task_protocol": 1, "task_versions": dict(db.execute("SELECT id, version FROM task_records ORDER BY id"))}


def _active_tasks(db):
    return [dict(zip(COLUMNS, row)) for row in db.execute(f"SELECT {FIELDS} FROM task_records WHERE deleted=0 ORDER BY id")]


def _require_snapshot_tasks(db, rows):
    # Snapshot order is not a business change (Room and old backups differ).
    # Omitted additive fields only equal their defaults. An old snapshot must
    # never silently erase a scheduled date or an explicit reminder.
    normalized = [{"plannedDate": "", "reminderAt": 0, **row} for row in rows]
    if {row["id"]: row for row in normalized} != {row["id"]: row for row in _active_tasks(db)}:
        raise HTTPException(409, "待办记录与已确认的任务命令不一致；请升级 App 或重新同步，手机修改已保留")


def _invalid(detail):
    raise HTTPException(422, detail)


def _identity(value, label):
    if not isinstance(value, str) or not value.strip() or len(value) > 128:
        _invalid(f"Invalid {label}")


def _validate_task(task, task_id, previous):
    if not isinstance(task, dict) or set(task) != set(COLUMNS) or task.get("id") != task_id:
        _invalid("Invalid task columns or identity")
    for name, kind in TABLE_COLUMNS["tasks"]:
        value = task[name]
        if kind == "TEXT" and not isinstance(value, str):
            _invalid("Invalid task text")
        if kind == "INTEGER" and (type(value) is not int or not -INTEGER_MAX - 1 <= value <= INTEGER_MAX):
            _invalid("Invalid task integer")
        # Legacy values remain editable without silently normalizing other fields.
        # For example a reminder can update a pre-existing incomplete location.
        if previous is not None and previous[name] == value:
            continue
        if kind == "INTEGER" and value < 0:
            _invalid("Invalid negative task integer")
        if name == "title" and (not value.strip() or len(value) > 100):
            _invalid("任务标题需要为 1 至 100 字")
        if name == "priority" and value not in (1, 2, 3):
            _invalid("任务优先级需要为 1 至 3")
        if name == "serviceMinutes" and not 1 <= value <= 240:
            _invalid("任务耗时需要为 1 至 240 分钟")
        if name == "status" and value not in ("pending", "deferred", "done"):
            _invalid("Invalid task status")
        if name == "reminderCount" and value > 2**31 - 1:
            _invalid("Invalid reminder count")
        if name == "reminderPending" and value not in (0, 1):
            _invalid("Invalid reminder state")
        if name == "deadline" and value:
            try:
                if len(value) > 100 or datetime.fromisoformat(value).utcoffset() is None:
                    raise ValueError()
            except ValueError:
                _invalid("截止时间需要为带时区的日期时间")
        if name == "plannedDate" and value:
            try:
                if len(value) != 10 or date.fromisoformat(value).isoformat() != value:
                    raise ValueError()
            except ValueError:
                _invalid("计划日期需要为 YYYY-MM-DD")
        if name == "placeType" and value not in PLACE_TYPES:
            _invalid("Invalid task place type")
        if name == "placeJson" and value:
            _validate_place(value)
    if previous is None or any(previous[key] != task[key] for key in ("status", "completedAt")):
        if (task["status"] == "done") != (task["completedAt"] > 0):
            _invalid("完成状态与完成时间不一致")
    if previous is None or any(previous[key] != task[key] for key in ("status", "reminderPending")):
        if task["status"] != "pending" and task["reminderPending"]:
            _invalid("只有待办任务可以处于待提醒状态")
    if previous is None or any(previous[key] != task[key] for key in ("status", "reminderAt")):
        if task["status"] != "pending" and task["reminderAt"]:
            _invalid("Only pending tasks can have a scheduled reminder")


def _validate_place(value):
    try:
        place = json.loads(value)
        if not isinstance(place, dict) or set(place) != {"id", "name", "longitude", "latitude", "address"}:
            raise ValueError()
        for key, maximum in (("id", 120), ("name", 200), ("address", 500)):
            if not isinstance(place[key], str) or len(place[key]) > maximum or (key != "address" and not place[key].strip()):
                raise ValueError()
        for key, bound in (("longitude", 180), ("latitude", 90)):
            coord = place[key]
            if type(coord) not in (int, float) or not -bound <= coord <= bound or not math.isfinite(coord):
                raise ValueError()
    except (ValueError, TypeError):
        _invalid("Invalid task place")


def _apply_commands(db, commands):
    """Validate/version/receipt each command; caller commits the entire batch."""
    if not isinstance(commands, list) or len(commands) > 100_000:
        _invalid("Invalid task commands")
    results, changed = [], False
    for command in commands:
        if not isinstance(command, dict) or not set(command) <= {"command_id", "operation", "task_id", "expected_version", "task"}:
            _invalid("Invalid task command")
        command_id, task_id = command.get("command_id"), command.get("task_id")
        _identity(command_id, "task command identity")
        if not isinstance(task_id, str) or not task_id.strip():
            _invalid("Invalid task identity")
        try:
            uuid.UUID(command_id)
        except ValueError:
            _invalid("Task command identity must be a UUID")
        expected = command.get("expected_version")
        operation = command.get("operation")
        if type(expected) is not int or not 0 <= expected < INTEGER_MAX or operation not in ("upsert", "delete"):
            _invalid("Invalid task command operation or version")
        if operation == "delete" and command.get("task") is not None:
            _invalid("Delete command cannot include task contents")
        digest = _digest(command)
        receipt = db.execute("SELECT digest, task_id, version, deleted FROM task_command_receipts WHERE command_id=?", (command_id,)).fetchone()
        if receipt is not None:
            if receipt[0] != digest:
                raise HTTPException(409, "任务命令标识被重复用于不同内容")
            results.append({"command_id": command_id, "task_id": receipt[1], "version": receipt[2], "deleted": bool(receipt[3])})
            continue
        stored = db.execute(f"SELECT {FIELDS}, version, deleted FROM task_records WHERE id=?", (task_id,)).fetchone()
        if stored is None and len(task_id) > 100:
            _invalid("New task identity exceeds the route planner limit")
        previous = dict(zip(COLUMNS, stored)) if stored is not None else None
        version, deleted = stored[-2:] if stored is not None else (0, 0)
        if version != expected:
            raise HTTPException(409, "任务已被其他操作更新；请重新同步，手机修改已保留")
        if operation == "upsert":
            if deleted:
                raise HTTPException(409, "任务已删除；如需重新创建，请使用新任务标识")
            task = command.get("task")
            if isinstance(task, dict):
                # Hash the original wire command above, then fill only the new
                # optional columns. Existing durable receipts remain replayable.
                # An old client completing/deferring a task cancels its reminder,
                # while an unrelated edit of a pending task preserves it.
                reminder = previous["reminderAt"] if previous and task.get("status") == "pending" else 0
                task = {"plannedDate": previous["plannedDate"] if previous else "", "reminderAt": reminder, **task}
            _validate_task(task, task_id, previous)
            version += 1
            markers = ", ".join("?" for _ in COLUMNS)
            updates = ", ".join(f'"{name}"=excluded."{name}"' for name in COLUMNS if name != "id")
            db.execute(f"INSERT INTO task_records ({FIELDS}, version, deleted) VALUES ({markers}, ?, 0) ON CONFLICT(id) DO UPDATE SET {updates}, version=excluded.version, deleted=0",
                       (*[task[name] for name in COLUMNS], version))
            changed = True
        else:
            if stored is None:
                raise HTTPException(409, "任务不存在或尚未同步，不能删除")
            if not deleted:
                version += 1
                db.execute("UPDATE task_records SET deleted=1, version=? WHERE id=?", (version, task_id))
                changed = True
            deleted = 1
        db.execute("INSERT INTO task_command_receipts VALUES (?, ?, ?, ?, ?)", (command_id, digest, task_id, version, deleted))
        results.append({"command_id": command_id, "task_id": task_id, "version": version, "deleted": bool(deleted)})
    return results, changed


@router.get("")
def read_tasks(x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)
    from .sync import _open
    with closing(_open()) as db:
        db.execute("BEGIN")
        store_id, revision = db.execute("SELECT store_id, revision FROM sync_head WHERE id=1").fetchone()
        rows = db.execute(f"SELECT {FIELDS}, version, deleted FROM task_records ORDER BY id").fetchall()
        result = {"store_id": store_id, "revision": revision, **_metadata(db), "tasks": [
            {"task_id": row[0], "version": row[-2], "deleted": bool(row[-1]),
             "task": None if row[-1] else dict(zip(COLUMNS, row))} for row in rows]}
        db.commit()
    return result


def _commit_commands(body):
    if not isinstance(body, dict) or set(body) != {"store_id", "request_id", "commands"}:
        _invalid("Invalid task request")
    _identity(body["store_id"], "store identity")
    _identity(body["request_id"], "request identity")
    digest = _digest(body)
    from .sync import _archive, _open
    with closing(_open()) as db, db:
        db.execute("BEGIN IMMEDIATE")
        store_id, revision = db.execute("SELECT store_id, revision FROM sync_head WHERE id=1").fetchone()
        if store_id != body["store_id"]:
            raise HTTPException(409, "数据服务身份已变化；请重新连接")
        receipt = db.execute("SELECT digest, response FROM task_batch_receipts WHERE request_id=?", (body["request_id"],)).fetchone()
        if receipt:
            if receipt[0] != digest:
                raise HTTPException(409, "任务请求标识被重复用于不同内容")
            return json.loads(receipt[1])
        _archive(db, revision)
        results, changed = _apply_commands(db, body["commands"])
        if changed:
            snapshot = _read_snapshot(db) or {"format": 1, "room_version": ROOM_VERSION,
                "tables": {name: [] for name in TABLE_COLUMNS}, "preferences": {name: {} for name in PREFERENCE_KEYS}}
            active = _active_tasks(db)
            required_version = 13 if any(row["reminderAt"] for row in active) else 12 if any(row["plannedDate"] for row in active) else 7
            if snapshot["room_version"] < required_version:
                # Upgrade only to the version needed by the new fields. Archived
                # snapshots keep their original shape and Room version.
                snapshot["tables"] = {
                    name: [{column: _stored_value(row, column) for column, _ in columns}
                           for row in snapshot["tables"].get(name, [])]
                    for name, columns in _columns_for(required_version).items()
                }
                snapshot["room_version"] = required_version
            task_columns = _columns_for(snapshot["room_version"])["tasks"]
            snapshot["tables"]["tasks"] = [{column: row[column] for column, _ in task_columns} for row in active]
            _validate(snapshot)
            if len(json.dumps(snapshot, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()) > MAX_BYTES:
                raise HTTPException(413, "电脑记录已达到当前同步容量上限；本次任务修改未保存")
            _write_snapshot(db, snapshot)
            revision += 1
            db.execute("UPDATE sync_head SET revision=? WHERE id=1", (revision,))
            _archive(db, revision)
        response = {"store_id": store_id, "revision": revision, **_metadata(db), "results": results}
        db.execute("INSERT INTO task_batch_receipts VALUES (?, ?, ?)", (body["request_id"], digest, json.dumps(response, separators=(",", ":"))))
    return response


@router.post("/commands")
async def write_commands(request: Request, x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)
    data = bytearray()
    async for chunk in request.stream():
        data.extend(chunk)
        if len(data) > MAX_BYTES:
            raise HTTPException(413, "Task request exceeds 8 MB")
    try:
        body = json.loads(data)
        return await run_in_threadpool(_commit_commands, body)
    except (ValueError, UnicodeDecodeError):
        raise HTTPException(422, "Invalid task JSON or values") from None
