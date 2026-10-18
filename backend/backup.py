"""Single-user, local SQLite backup store. JSON is only the HTTP wire format."""

import hmac
import json
import math
import os
import sqlite3
from contextlib import closing
from pathlib import Path

from fastapi import APIRouter, Header, HTTPException, Request

router = APIRouter(prefix="/backup", tags=["backup"])
MAX_BYTES = 8 * 1024 * 1024
ROOM_VERSION = 13
LEGACY_ROOM_VERSION = 7
# Keep this in lockstep with the Room v13 schema. Names are fixed, never user SQL.
TABLE_COLUMNS = {
    "tasks": (
        ("id", "TEXT"), ("title", "TEXT"), ("priority", "INTEGER"),
        ("serviceMinutes", "INTEGER"), ("deadline", "TEXT"),
        ("placeJson", "TEXT"), ("placeType", "TEXT"), ("status", "TEXT"),
        ("createdAt", "INTEGER"), ("updatedAt", "INTEGER"),
        ("completedAt", "INTEGER"), ("lastRemindedAt", "INTEGER"),
        ("reminderCount", "INTEGER"), ("reminderPending", "INTEGER"),
        ("plannedDate", "TEXT"),
        ("reminderAt", "INTEGER"),
    ),
    "saved_trip": (
        ("id", "INTEGER"), ("requestJson", "TEXT"), ("resultJson", "TEXT"),
        ("started", "INTEGER"), ("sessionId", "TEXT"), ("startedAt", "INTEGER"),
        ("plannedAt", "INTEGER"), ("needsReplan", "INTEGER"), ("planId", "TEXT"),
    ),
    "route_history": (
        ("id", "TEXT"), ("sessionId", "TEXT"), ("state", "TEXT"),
        ("timestamp", "INTEGER"), ("requestJson", "TEXT"), ("resultJson", "TEXT"), ("planId", "TEXT"),
    ),
    "suggestion_feedback": (
        ("id", "TEXT"), ("shownAt", "INTEGER"), ("mode", "TEXT"),
        ("offeredTaskCount", "INTEGER"), ("offeredExtraSeconds", "INTEGER"),
        ("decision", "TEXT"), ("selectedTaskCount", "INTEGER"),
        ("selectedExtraSeconds", "INTEGER"), ("decidedAt", "INTEGER"),
        ("timeBand", "TEXT"), ("routeSessionId", "TEXT"),
    ),
    "agent_events": (
        ("id", "TEXT"), ("type", "TEXT"), ("occurredAt", "INTEGER"),
        ("source", "TEXT"), ("correlationId", "TEXT"), ("routeSessionId", "TEXT"),
        ("actionId", "TEXT"), ("mode", "TEXT"), ("timeBand", "TEXT"),
        ("itemCount", "INTEGER"), ("extraSeconds", "INTEGER"),
    ),
    "assistant_messages": (
        ("id", "TEXT"), ("role", "TEXT"), ("text", "TEXT"), ("createdAt", "INTEGER"),
        ("routeHistoryId", "TEXT"), ("conversationId", "TEXT"),
    ),
    "assistant_draft": (
        ("id", "INTEGER"), ("action", "TEXT"), ("title", "TEXT"),
        ("placeType", "TEXT"), ("deadline", "TEXT"), ("destination", "TEXT"),
        ("mode", "TEXT"), ("knownPlaceId", "TEXT"), ("knownPlaceName", "TEXT"),
        ("knownLongitude", "REAL"), ("knownLatitude", "REAL"),
        ("knownAddress", "TEXT"), ("savedAt", "INTEGER"), ("expiresAt", "INTEGER"), ("draftId", "TEXT"),
        ("conversationId", "TEXT"), ("planId", "TEXT"),
    ),
    "planning_draft": (
        ("id", "INTEGER"), ("destination", "TEXT"), ("mode", "TEXT"), ("budget", "INTEGER"),
        ("placeId", "TEXT"), ("placeName", "TEXT"), ("longitude", "REAL"),
        ("latitude", "REAL"), ("address", "TEXT"), ("savedAt", "INTEGER"), ("draftId", "TEXT"), ("planId", "TEXT"),
    ),
    "planning_draft_tasks": (("id", "TEXT"), ("selected", "INTEGER")),
    "assistant_clarification": (
        ("id", "INTEGER"), ("draftId", "TEXT"), ("planId", "TEXT"),
        ("kind", "TEXT"), ("prompt", "TEXT"), ("destination", "TEXT"),
        ("mode", "TEXT"), ("savedAt", "INTEGER"),
    ),
    "assistant_place_choices": (
        ("id", "TEXT"), ("draftId", "TEXT"), ("name", "TEXT"),
        ("longitude", "REAL"), ("latitude", "REAL"), ("address", "TEXT"),
        ("position", "INTEGER"),
    ),
    "assistant_task_drafts": (
        ("id", "TEXT"), ("planId", "TEXT"), ("position", "INTEGER"),
        ("version", "INTEGER"), ("title", "TEXT"), ("placeType", "TEXT"),
        ("deadline", "TEXT"), ("plannedDate", "TEXT"), ("priority", "INTEGER"),
        ("serviceMinutes", "INTEGER"), ("placeJson", "TEXT"),
    ),

}
LEGACY_TABLE_NAMES = tuple(TABLE_COLUMNS)[:5]


def _columns_for(version: int) -> dict:
    if version not in (LEGACY_ROOM_VERSION, 8, 9, 10, 11, 12, ROOM_VERSION):
        raise HTTPException(409, "Backup was written by an unsupported app version")
    return {
        name: tuple(
            column for column in columns
            if not (column[0] == "draftId" and version < 10)
            and not (column[0] == "planId" and version < 11)
            and not (column[0] == "plannedDate" and version < 12)
            and not (column[0] == "reminderAt" and version < 13)
        )
        for name, columns in TABLE_COLUMNS.items()
        if not (version == 7 and name not in LEGACY_TABLE_NAMES)
        and not (version < 9 and name in ("planning_draft", "planning_draft_tasks"))
        and not (version < 11 and name in ("assistant_clarification", "assistant_place_choices"))
        and not (version < 12 and name == "assistant_task_drafts")
    }


def _position_column(name: str) -> str:
    # Candidate and draft item order are data, separate from snapshot serialization order.
    return "_snapshot_position" if name in ("assistant_place_choices", "assistant_task_drafts") else "position"


def _ensure_added_columns(db: sqlite3.Connection, table: str, columns: tuple) -> None:
    """Upgrade known additive columns without rewriting or dropping stored versions."""
    existing = {row[1] for row in db.execute(f'PRAGMA table_info("{table}")')}
    for column, _ in columns:
        if column not in existing and column == "reminderAt":
            db.execute(f'ALTER TABLE "{table}" ADD COLUMN "{column}" INTEGER NOT NULL DEFAULT 0')
        elif column not in existing and column in ("draftId", "planId", "plannedDate"):
            default = "legacy-draft" if column == "draftId" else ""
            db.execute(f'ALTER TABLE "{table}" ADD COLUMN "{column}" TEXT NOT NULL DEFAULT \'{default}\'')


def _column_definition(column: str, affinity: str) -> str:
    if column == "reminderAt":
        return f'"{column}" {affinity} NOT NULL DEFAULT 0'
    if column in ("planId", "plannedDate"):
        return f'"{column}" {affinity} NOT NULL DEFAULT \'\''
    return f'"{column}" {affinity}'


def _stored_value(row: dict, column: str):
    if column == "reminderAt":
        return row.get(column, 0)
    if column == "draftId":
        return row.get(column, "legacy-draft")
    if column in ("planId", "plannedDate"):
        return row.get(column, "")
    return row[column]


PREFERENCE_KEYS = {
    "settings": {"city", "familiar_places", "preferred_detour_minutes",
                 "walking_detour_minutes", "cycling_detour_minutes", "learning_enabled"},
    "assistant_conversation": {"lines", "proposal", "proposal_saved_at"},
    "navigation_context": {"snapshot"},
}


def _authorize(token: str | None) -> None:
    expected = os.getenv("ONMYWAY_BACKUP_TOKEN", "")
    if len(expected) < 32:
        raise HTTPException(503, "Backup is not configured on this computer")
    if not token or not hmac.compare_digest(token.encode("utf-8"), expected.encode("utf-8")):
        raise HTTPException(401, "Invalid backup token")


def _validate(snapshot: object) -> dict:
    if not isinstance(snapshot, dict) or snapshot.get("format") != 1 or snapshot.get("room_version") not in (LEGACY_ROOM_VERSION, 8, 9, 10, 11, 12, ROOM_VERSION):
        raise HTTPException(422, "Unsupported backup format")
    tables = snapshot.get("tables")
    columns_by_table = _columns_for(snapshot["room_version"])
    if not isinstance(tables, dict) or set(tables) != set(columns_by_table):
        raise HTTPException(422, "Invalid backup tables")
    for name, columns in columns_by_table.items():
        rows = tables[name]
        if not isinstance(rows, list) or len(rows) > 100_000:
            raise HTTPException(422, "Invalid backup rows")
        expected = {column for column, _ in columns}
        ids = set()
        nullable = {"assistant_messages": {"routeHistoryId"},
                    "assistant_draft": {"knownLongitude", "knownLatitude"}}.get(name, set())
        for row in rows:
            if not isinstance(row, dict) or set(row) != expected:
                raise HTTPException(422, "Invalid backup row columns")
            for column, affinity in columns:
                value = row[column]
                if value is None:
                    if column not in nullable:
                        raise HTTPException(422, "Required backup value is null")
                    continue
                if affinity == "INTEGER" and (not isinstance(value, int) or isinstance(value, bool)):
                    raise HTTPException(422, "Invalid backup integer")
                if column == "reminderAt" and not 0 <= value <= 2**63 - 1:
                    raise HTTPException(422, "Invalid backup reminder time")
                if affinity == "REAL" and (not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(value)):
                    raise HTTPException(422, "Invalid backup real")
                if affinity == "TEXT" and not isinstance(value, str):
                    raise HTTPException(422, "Invalid backup text")
            if row["id"] in ids or row["id"] in ("", None):
                raise HTTPException(422, "Duplicate or missing record identity")
            ids.add(row["id"])
            if name in ("saved_trip", "assistant_draft", "planning_draft", "assistant_clarification") and row["id"] != 1:
                raise HTTPException(422, "Invalid singleton identity")
    preferences = snapshot.get("preferences")
    if not isinstance(preferences, dict) or set(preferences) != set(PREFERENCE_KEYS):
        raise HTTPException(422, "Invalid backup preferences")
    for namespace, allowed in PREFERENCE_KEYS.items():
        values = preferences[namespace]
        if not isinstance(values, dict) or not set(values) <= allowed:
            raise HTTPException(422, "Invalid backup preference keys")
        if any(not isinstance(value, (str, int, float, bool)) for value in values.values()):
            raise HTTPException(422, "Invalid backup preference value")
    return snapshot


def _write_snapshot(db: sqlite3.Connection, snapshot: dict) -> None:
    for name, columns in TABLE_COLUMNS.items():
        db.execute(f'DELETE FROM "backup_{name}"')
        names = [column for column, _ in columns]
        fields = ", ".join(f'"{column}"' for column in names)
        markers = ", ".join("?" for _ in range(len(names) + 1))
        db.executemany(
            f'INSERT INTO "backup_{name}" ("{_position_column(name)}", {fields}) VALUES ({markers})',
            ((index, *(_stored_value(row, column) for column in names))
             for index, row in enumerate(snapshot["tables"].get(name, []))),
        )
    db.execute("DELETE FROM backup_preferences")
    preferences = []
    for namespace, values in snapshot["preferences"].items():
        for key, value in values.items():
            if isinstance(value, bool):
                kind, text, integer, real = "boolean", None, int(value), None
            elif isinstance(value, int):
                kind, text, integer, real = "integer", None, value, None
            elif isinstance(value, float):
                kind, text, integer, real = "real", None, None, value
            else:
                kind, text, integer, real = "text", value, None, None
            preferences.append((namespace, key, kind, text, integer, real))
    db.executemany(
        "INSERT INTO backup_preferences (namespace, key, kind, text_value, integer_value, real_value) VALUES (?, ?, ?, ?, ?, ?)",
        preferences,
    )
    db.execute(
        "INSERT INTO backup_meta (id, format, room_version, updated_at) VALUES (1, 1, ?, CURRENT_TIMESTAMP) "
        "ON CONFLICT(id) DO UPDATE SET format=excluded.format, room_version=excluded.room_version, updated_at=CURRENT_TIMESTAMP",
        (snapshot["room_version"],),
    )


def _read_snapshot(db: sqlite3.Connection) -> dict | None:
    meta = db.execute("SELECT format, room_version FROM backup_meta WHERE id=1").fetchone()
    if meta is None:
        return None
    tables = {}
    for name, columns in _columns_for(meta[1]).items():
        names = [column for column, _ in columns]
        fields = ", ".join(f'"{column}"' for column in names)
        rows = db.execute(f'SELECT {fields} FROM "backup_{name}" ORDER BY "{_position_column(name)}"').fetchall()
        tables[name] = [dict(zip(names, row)) for row in rows]
    preferences = {namespace: {} for namespace in PREFERENCE_KEYS}
    for namespace, key, kind, text, integer, real in db.execute(
        "SELECT namespace, key, kind, text_value, integer_value, real_value FROM backup_preferences"
    ):
        if kind == "boolean":
            value = bool(integer)
        elif kind == "integer":
            value = integer
        elif kind == "real":
            value = real
        else:
            value = text
        preferences[namespace][key] = value
    return {"format": meta[0], "room_version": meta[1],
            "tables": tables, "preferences": preferences}


def _connect() -> sqlite3.Connection:
    value = os.getenv("ONMYWAY_BACKUP_DB", "").strip()
    path = Path(value) if value else Path(__file__).with_name("data") / "backups.sqlite3"
    path.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(path, timeout=10)
    try:
        db.execute("PRAGMA busy_timeout=10000")
        db.execute("PRAGMA secure_delete=ON")
        with db:
            db.execute(
                "CREATE TABLE IF NOT EXISTS backup_meta (id INTEGER PRIMARY KEY CHECK(id=1), "
                "format INTEGER NOT NULL, room_version INTEGER NOT NULL, updated_at TEXT NOT NULL)"
            )
            for name, columns in TABLE_COLUMNS.items():
                fields = ", ".join(_column_definition(column, affinity) for column, affinity in columns)
                db.execute(f'CREATE TABLE IF NOT EXISTS "backup_{name}" ("{_position_column(name)}" INTEGER PRIMARY KEY, {fields})')
                _ensure_added_columns(db, f"backup_{name}", columns)

            db.execute(
                "CREATE TABLE IF NOT EXISTS backup_preferences (namespace TEXT NOT NULL, "
                "key TEXT NOT NULL, kind TEXT NOT NULL, text_value TEXT, integer_value INTEGER, "
                "real_value REAL, PRIMARY KEY(namespace, key))"
            )
            # Migrate the first backup implementation without dropping any user records.
            legacy = db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='backups'").fetchone()
            if legacy:
                row = db.execute("SELECT payload FROM backups WHERE id=1").fetchone()
                if row and _read_snapshot(db) is None:
                    _write_snapshot(db, _validate(json.loads(row[0])))
                db.execute("DROP TABLE backups")
        if legacy:
            db.execute("VACUUM")
        return db
    except Exception:
        db.close()
        raise


@router.put("")
async def save_backup(request: Request, x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)
    if request.headers.get("content-length", "").isdigit() and int(request.headers["content-length"]) > MAX_BYTES:
        raise HTTPException(413, "Backup exceeds 8 MB")
    chunks = []
    size = 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > MAX_BYTES:
            raise HTTPException(413, "Backup exceeds 8 MB")
        chunks.append(chunk)
    try:
        snapshot = _validate(json.loads(b"".join(chunks)))
    except (ValueError, UnicodeDecodeError):
        raise HTTPException(422, "Invalid backup JSON") from None
    with closing(_connect()) as db, db:
        db.execute("BEGIN IMMEDIATE")
        managed = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='sync_head'").fetchone()
        if managed and db.execute("SELECT 1 FROM sync_head WHERE id=1").fetchone():
            raise HTTPException(409, "电脑数据库已启用版本同步；请使用新版同步入口")
        existing = db.execute("SELECT room_version FROM backup_meta WHERE id=1").fetchone()
        if existing:
            raise HTTPException(409, "已有电脑记录；请使用新版同步接口，旧版无版本写入不能覆盖记录")
        _write_snapshot(db, snapshot)
    return {"status": "ok"}


@router.get("")
def load_backup(x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)
    with closing(_connect()) as db:
        snapshot = _read_snapshot(db)
    if snapshot is None:
        raise HTTPException(404, "No backup on this computer")
    return snapshot
