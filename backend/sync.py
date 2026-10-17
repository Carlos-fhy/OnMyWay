"""Revision-checked, single-user SQLite synchronization for the local backend."""
import hashlib
import json
import uuid
from contextlib import closing

from fastapi import APIRouter, Header, HTTPException, Request
from starlette.concurrency import run_in_threadpool

from .backup import MAX_BYTES, TABLE_COLUMNS, PREFERENCE_KEYS, _columns_for, _position_column, _ensure_added_columns, _column_definition, _authorize, _connect, _read_snapshot, _validate, _write_snapshot
from .tasks import _ensure_schema, _metadata, _apply_commands, _require_snapshot_tasks

router = APIRouter(prefix="/sync", tags=["sync"])


def _open():
    db = _connect()
    try:
        with db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("CREATE TABLE IF NOT EXISTS sync_head (id INTEGER PRIMARY KEY CHECK(id=1), store_id TEXT NOT NULL, revision INTEGER NOT NULL)")
            db.execute("CREATE TABLE IF NOT EXISTS sync_receipts (request_id TEXT PRIMARY KEY, digest TEXT NOT NULL, revision INTEGER NOT NULL)")
            if "task_metadata" not in {row[1] for row in db.execute("PRAGMA table_info(sync_receipts)")}:
                db.execute("ALTER TABLE sync_receipts ADD COLUMN task_metadata TEXT")
            db.execute("CREATE TABLE IF NOT EXISTS sync_versions (revision INTEGER PRIMARY KEY, room_version INTEGER NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
            for name, columns in TABLE_COLUMNS.items():
                fields = ", ".join(_column_definition(column, affinity) for column, affinity in columns)
                position = _position_column(name)
                db.execute(f'CREATE TABLE IF NOT EXISTS "sync_version_{name}" (revision INTEGER NOT NULL, "{position}" INTEGER NOT NULL, {fields}, PRIMARY KEY(revision, "{position}"))')
                _ensure_added_columns(db, f"sync_version_{name}", columns)

            db.execute("CREATE TABLE IF NOT EXISTS sync_version_preferences (revision INTEGER NOT NULL, namespace TEXT NOT NULL, key TEXT NOT NULL, kind TEXT NOT NULL, text_value TEXT, integer_value INTEGER, real_value REAL, PRIMARY KEY(revision, namespace, key))")
            # Concurrent initializers must never replace the stable store identity.
            exists = db.execute("SELECT 1 FROM backup_meta WHERE id=1").fetchone()
            db.execute("INSERT OR IGNORE INTO sync_head VALUES (1, ?, ?)", (str(uuid.uuid4()), 1 if exists else 0))
            _ensure_schema(db)
        return db
    except Exception:
        db.close()
        raise


def _archive(db, revision):
    meta = db.execute("SELECT room_version FROM backup_meta WHERE id=1").fetchone()
    if meta is None or db.execute("SELECT 1 FROM sync_versions WHERE revision=?", (revision,)).fetchone():
        return
    db.execute("INSERT INTO sync_versions (revision, room_version) VALUES (?, ?)", (revision, meta[0]))
    for name, columns in TABLE_COLUMNS.items():
        fields = ", ".join(f'"{column}"' for column, _ in columns)
        position = _position_column(name)
        db.execute(
            f'INSERT INTO "sync_version_{name}" (revision, "{position}", {fields}) '
            f'SELECT ?, "{position}", {fields} FROM "backup_{name}"', (revision,)
        )
    db.execute("INSERT INTO sync_version_preferences SELECT ?, namespace, key, kind, text_value, integer_value, real_value FROM backup_preferences", (revision,))


@router.get("")
def read_sync(x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)
    with closing(_open()) as db:
        # A single read transaction pairs the revision with the matching rows.
        db.execute("BEGIN")
        store_id, revision = db.execute("SELECT store_id, revision FROM sync_head WHERE id=1").fetchone()
        snapshot = _read_snapshot(db)
        task_metadata = _metadata(db)
        db.commit()
    return {"store_id": store_id, "revision": revision, "snapshot": snapshot, **task_metadata}


def _commit(body):
    if not isinstance(body, dict):
        raise HTTPException(422, "Invalid sync request")
    revision = body.get("base_revision")
    request_id, store_id = body.get("request_id"), body.get("store_id")
    if type(revision) is not int or revision < 0 or not isinstance(request_id, str) or not 1 <= len(request_id) <= 128 or not isinstance(store_id, str):
        raise HTTPException(422, "Invalid sync identity or revision")
    snapshot = _validate(body.get("snapshot"))
    digest = hashlib.sha256(json.dumps(body, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()).hexdigest()
    with closing(_open()) as db, db:
        db.execute("BEGIN IMMEDIATE")
        actual_store, current = db.execute("SELECT store_id, revision FROM sync_head WHERE id=1").fetchone()
        if actual_store != store_id:
            raise HTTPException(409, "数据服务身份已变化；请重新连接，手机记录已保留")
        receipt = db.execute("SELECT digest, revision, task_metadata FROM sync_receipts WHERE request_id=?", (request_id,)).fetchone()
        if receipt:
            if receipt[0] != digest:
                raise HTTPException(409, "同步请求标识被重复用于不同内容")
            # Old receipt replay remains byte-semantically compatible; never
            # attach today's task versions to a historical acknowledgement.
            return {"store_id": actual_store, "revision": receipt[1], **(json.loads(receipt[2]) if receipt[2] else {})}
        if revision != current:
            raise HTTPException(409, "电脑记录已被其他同步更新；手机修改已保留，请先处理版本冲突")
        previous = db.execute("SELECT room_version FROM backup_meta WHERE id=1").fetchone()
        if previous and previous[0] > snapshot["room_version"]:
            raise HTTPException(409, "旧版 App 不能覆盖新版数据")
        _archive(db, current)
        if "task_commands" in body:
            _apply_commands(db, body["task_commands"])
        _require_snapshot_tasks(db, snapshot["tables"]["tasks"])
        _write_snapshot(db, snapshot)
        next_revision = current + 1
        db.execute("UPDATE sync_head SET revision=? WHERE id=1", (next_revision,))
        task_metadata = _metadata(db)
        db.execute("INSERT INTO sync_receipts (request_id, digest, revision, task_metadata) VALUES (?, ?, ?, ?)",
                   (request_id, digest, next_revision, json.dumps(task_metadata, separators=(",", ":"))))
        _archive(db, next_revision)
    return {"store_id": store_id, "revision": next_revision, **task_metadata}


@router.put("")
async def write_sync(request: Request, x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)
    data = bytearray()
    async for chunk in request.stream():
        data.extend(chunk)
        if len(data) > MAX_BYTES:
            raise HTTPException(413, "Sync exceeds 8 MB")
    try:
        body = json.loads(data)
    except (ValueError, UnicodeDecodeError):
        raise HTTPException(422, "Invalid sync JSON") from None
    try:
        return await run_in_threadpool(_commit, body)
    except ValueError:
        raise HTTPException(422, "Invalid sync values") from None


@router.get("/versions")
def versions(x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)
    with closing(_open()) as db, db:
        db.execute("BEGIN IMMEDIATE")
        store_id, revision = db.execute("SELECT store_id, revision FROM sync_head WHERE id=1").fetchone()
        _archive(db, revision)
        rows = db.execute("SELECT revision, room_version, created_at FROM sync_versions ORDER BY revision DESC LIMIT 50").fetchall()
    return {"store_id": store_id, "versions": [{"revision": r[0], "room_version": r[1], "created_at": r[2]} for r in rows]}


@router.get("/versions/{revision}")
def historical_snapshot(revision: int, x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)
    with closing(_open()) as db:
        db.execute("BEGIN")
        meta = db.execute("SELECT room_version FROM sync_versions WHERE revision=?", (revision,)).fetchone()
        if meta is None:
            raise HTTPException(404, "历史版本不存在")
        data = {}
        for name, columns in _columns_for(meta[0]).items():
            names = [column for column, _ in columns]
            fields = ", ".join(f'"{column}"' for column in names)
            rows = db.execute(f'SELECT {fields} FROM "sync_version_{name}" WHERE revision=? ORDER BY "{_position_column(name)}"', (revision,)).fetchall()
            data[name] = [dict(zip(names, row)) for row in rows]
        preferences = {namespace: {} for namespace in PREFERENCE_KEYS}
        for namespace, key, kind, text, integer, real in db.execute(
            "SELECT namespace, key, kind, text_value, integer_value, real_value FROM sync_version_preferences WHERE revision=?", (revision,)):
            preferences[namespace][key] = bool(integer) if kind == "boolean" else integer if kind == "integer" else real if kind == "real" else text
        store_id = db.execute("SELECT store_id FROM sync_head WHERE id=1").fetchone()[0]
        db.commit()
    return {"store_id": store_id, "revision": revision, "snapshot": {
        "format": 1, "room_version": meta[0], "tables": data, "preferences": preferences}}
