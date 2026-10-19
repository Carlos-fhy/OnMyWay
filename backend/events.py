"""Append-only business event receipts with a stable correlation identity.

An event proves that a device or service observed something, not that a later
action succeeded. Ingestion is idempotent by event id and content; callers must
use a new event id for a new observation and the same correlation id to trace a
task confirmation, route confirmation and device execution.
"""
import hashlib
import json
import os
import sqlite3
import time
import uuid
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Literal

from fastapi import APIRouter, Header, HTTPException, Query
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .backup import _authorize

router = APIRouter(prefix="/events", tags=["events"])
PROTOCOL = 1
MAX_EVENT_BYTES = 64 * 1024
MAX_EVIDENCE_ITEMS = 10

EventSource = Literal["device", "server", "assistant", "notification", "calendar", "user"]
EventKind = Literal[
    "TASK_CONFIRMED",
    "TASK_COMPLETED",
    "ROUTE_CONFIRMED",
    "NAVIGATION_STARTED",
    "NAVIGATION_ARRIVED",
    "NAVIGATION_CANCELLED",
    "NAVIGATION_FAILED",
]


class _Model(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True, allow_inf_nan=False)


class EventEvidence(_Model):
    kind: str = Field(min_length=1, max_length=40)
    ref: str = Field(min_length=1, max_length=200)


class BusinessEvent(_Model):
    schema_version: Literal[1] = 1
    event_id: uuid.UUID
    correlation_id: uuid.UUID
    source: EventSource
    kind: EventKind
    occurred_at: datetime
    valid_until: datetime | None = None
    evidence: list[EventEvidence] = Field(default_factory=list, max_length=MAX_EVIDENCE_ITEMS)
    payload: dict[str, Any] = Field(default_factory=dict)

    @field_validator("occurred_at", "valid_until")
    @classmethod
    def aware_time(cls, value: datetime | None) -> datetime | None:
        if value is not None and value.utcoffset() is None:
            raise ValueError("事件时间需要包含时区")
        return value

    @model_validator(mode="after")
    def bounded_event(self):
        if self.valid_until is not None and self.valid_until < self.occurred_at:
            raise ValueError("事件有效期不能早于发生时间")
        try:
            encoded = json.dumps(self.model_dump(mode="json"), sort_keys=True,
                                 ensure_ascii=False, separators=(",", ":"), allow_nan=False)
        except (TypeError, ValueError):
            raise ValueError("事件内容必须为有限 JSON")
        if len(encoded.encode("utf-8")) > MAX_EVENT_BYTES:
            raise ValueError("事件内容过大")
        return self


def _open():
    path = Path(os.getenv("ONMYWAY_EVENT_DB", "").strip() or
                Path(__file__).with_name("data") / "events.sqlite3")
    path.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(path, timeout=10)
    try:
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA busy_timeout=10000")
        with db:
            db.execute("""CREATE TABLE IF NOT EXISTS business_events (
                event_id TEXT PRIMARY KEY,
                correlation_id TEXT NOT NULL,
                source TEXT NOT NULL,
                kind TEXT NOT NULL,
                schema_version INTEGER NOT NULL,
                occurred_at TEXT NOT NULL,
                occurred_epoch REAL NOT NULL,
                valid_until TEXT,
                valid_until_epoch REAL,
                evidence TEXT NOT NULL,
                payload TEXT NOT NULL,
                digest TEXT NOT NULL,
                received_at REAL NOT NULL)""")
            db.execute("""CREATE INDEX IF NOT EXISTS business_events_correlation
                ON business_events (correlation_id, occurred_epoch, event_id)""")
            db.execute("CREATE TABLE IF NOT EXISTS event_schema (id INTEGER PRIMARY KEY CHECK(id=1), protocol INTEGER NOT NULL)")
            marker = db.execute("SELECT protocol FROM event_schema WHERE id=1").fetchone()
            if marker is None:
                db.execute("INSERT INTO event_schema VALUES (1, ?)", (PROTOCOL,))
            elif marker[0] != PROTOCOL:
                raise HTTPException(409, "事件数据库版本较新，请升级服务后再操作")
        return db
    except BaseException:
        db.close()
        raise


def _canonical(value: object) -> str:
    return json.dumps(value, sort_keys=True, ensure_ascii=False,
                      separators=(",", ":"), allow_nan=False)


def _stamp(value: datetime | None) -> str | None:
    # The instant is the audit value; the original offset is not part of event identity.
    return value.astimezone(timezone.utc).isoformat() if value is not None else None


def _record(event: BusinessEvent) -> dict:
    data = event.model_dump(mode="json")
    return {
        "schema_version": data["schema_version"],
        "event_id": data["event_id"],
        "correlation_id": data["correlation_id"],
        "source": data["source"],
        "kind": data["kind"],
        "occurred_at": _stamp(event.occurred_at),
        "valid_until": _stamp(event.valid_until),
        "evidence": data["evidence"],
        "payload": data["payload"],
    }


def _digest(record: dict) -> str:
    return hashlib.sha256(_canonical(record).encode()).hexdigest()


def _stored(row) -> dict:
    return {
        "schema_version": row[0],
        "event_id": row[1],
        "correlation_id": row[2],
        "source": row[3],
        "kind": row[4],
        "occurred_at": row[5],
        "valid_until": row[6],
        "evidence": json.loads(row[7]),
        "payload": json.loads(row[8]),
        "received_at": row[9],
    }


_SELECT = """SELECT schema_version, event_id, correlation_id, source, kind,
    occurred_at, valid_until, evidence, payload, received_at FROM business_events"""


@router.post("")
def append_event(body: BusinessEvent, x_backup_token: str | None = Header(default=None)):
    """Record one event. `accepted`/`duplicate` describe storage only, not execution."""
    _authorize(x_backup_token)
    record = _record(body)
    digest = _digest(record)
    event_id, correlation_id = record["event_id"], record["correlation_id"]
    with closing(_open()) as db:
        try:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT digest FROM business_events WHERE event_id=?", (event_id,)).fetchone()
            if row is not None:
                db.rollback()
                if row[0] != digest:
                    raise HTTPException(409, "事件编号已用于不同内容")
                return {"event_id": event_id, "correlation_id": correlation_id,
                        "status": "duplicate", "recorded": True}
            received_at = time.time()
            db.execute("""INSERT INTO business_events (
                event_id, correlation_id, source, kind, schema_version,
                occurred_at, occurred_epoch, valid_until, valid_until_epoch,
                evidence, payload, digest, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", (
                event_id, correlation_id, record["source"], record["kind"],
                record["schema_version"], record["occurred_at"], body.occurred_at.timestamp(),
                record["valid_until"],
                body.valid_until.timestamp() if body.valid_until is not None else None,
                _canonical(record["evidence"]), _canonical(record["payload"]),
                digest, received_at))
            db.commit()
        except sqlite3.IntegrityError:
            db.rollback()
            row = db.execute("SELECT digest FROM business_events WHERE event_id=?", (event_id,)).fetchone()
            if row is not None and row[0] == digest:
                return {"event_id": event_id, "correlation_id": correlation_id,
                        "status": "duplicate", "recorded": True}
            raise HTTPException(409, "事件编号已用于不同内容")
        except BaseException:
            db.rollback()
            raise
    return {"event_id": event_id, "correlation_id": correlation_id,
            "status": "accepted", "recorded": True}


@router.get("")
def read_correlation(correlation_id: uuid.UUID = Query(...), limit: int = Query(100, ge=1, le=500),
                     x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)
    with closing(_open()) as db:
        rows = db.execute(f"""{_SELECT} WHERE correlation_id=?
            ORDER BY occurred_epoch, event_id LIMIT ?""", (str(correlation_id), limit)).fetchall()
    return {"correlation_id": str(correlation_id), "count": len(rows),
            "events": [_stored(row) for row in rows]}


@router.get("/{event_id}")
def read_event(event_id: uuid.UUID, x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)
    with closing(_open()) as db:
        row = db.execute(f"{_SELECT} WHERE event_id=?", (str(event_id),)).fetchone()
    if row is None:
        raise HTTPException(404, "事件不存在")
    return _stored(row)
