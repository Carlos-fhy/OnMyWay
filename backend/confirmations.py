"""Confirmation receipts for proposals and route plans.

An event records an observation. A confirmation receipt records that the user
accepted one specific version of a proposal or plan. Receipts are kept separate
from device execution evidence: a stored confirmation is never treated as proof
that navigation started or that a task was carried out.

Identity is `(subject_type, subject_id, subject_version)`. A retry reuses the
same receipt id and version; a changed proposal or replanned route uses the next
version. The server only rejects versions older than the newest one it has
already stored; ordering and content are enforced here, while version numbers
are still declared by the client.
"""
import hashlib
import json
import sqlite3
import time
import uuid
from contextlib import closing
from datetime import datetime, timedelta, timezone
from typing import Any, Literal

from fastapi import APIRouter, Header, HTTPException, Query
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .backup import _authorize
from .events import _open as _open_event_store

router = APIRouter(tags=["confirmations"])
MAX_SNAPSHOT_BYTES = 64 * 1024
MAX_FUTURE_SKEW = timedelta(minutes=5)
SubjectType = Literal["proposal", "plan"]


class _Model(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True, allow_inf_nan=False)


class ConfirmationReceipt(_Model):
    schema_version: Literal[1] = 1
    receipt_id: uuid.UUID
    subject_type: SubjectType
    subject_id: str = Field(min_length=1, max_length=200)
    subject_version: int = Field(ge=1, le=2_147_483_646)
    correlation_id: uuid.UUID
    confirmed_at: datetime
    valid_until: datetime | None = None
    snapshot: dict[str, Any] = Field(default_factory=dict)

    @field_validator("confirmed_at", "valid_until")
    @classmethod
    def aware_time(cls, value: datetime | None) -> datetime | None:
        if value is not None and value.utcoffset() is None:
            raise ValueError("确认时间需要包含时区")
        return value

    @model_validator(mode="after")
    def bounded_receipt(self):
        if self.valid_until is not None and self.valid_until < self.confirmed_at:
            raise ValueError("确认有效期不能早于确认时间")
        if self.confirmed_at > datetime.now(timezone.utc) + MAX_FUTURE_SKEW:
            raise ValueError("确认时间不能晚于当前时间")
        try:
            encoded = json.dumps(self.snapshot, sort_keys=True, ensure_ascii=False,
                                 separators=(",", ":"), allow_nan=False)
        except (TypeError, ValueError):
            raise ValueError("确认内容必须为有限 JSON")
        if len(encoded.encode("utf-8")) > MAX_SNAPSHOT_BYTES:
            raise ValueError("确认内容过大")
        return self


def _open():
    db = _open_event_store()
    try:
        with db:
            db.execute("""CREATE TABLE IF NOT EXISTS confirmation_receipts (
                receipt_id TEXT PRIMARY KEY,
                subject_type TEXT NOT NULL,
                subject_id TEXT NOT NULL,
                subject_version INTEGER NOT NULL,
                correlation_id TEXT NOT NULL,
                confirmed_at TEXT NOT NULL,
                confirmed_epoch REAL NOT NULL,
                valid_until TEXT,
                valid_until_epoch REAL,
                snapshot TEXT NOT NULL,
                digest TEXT NOT NULL,
                received_at REAL NOT NULL,
                UNIQUE(subject_type, subject_id, subject_version))""")
            db.execute("""CREATE INDEX IF NOT EXISTS confirmation_receipts_correlation
                ON confirmation_receipts (correlation_id, confirmed_epoch, receipt_id)""")
        return db
    except BaseException:
        db.close()
        raise


def _canonical(value: object) -> str:
    return json.dumps(value, sort_keys=True, ensure_ascii=False,
                      separators=(",", ":"), allow_nan=False)


def _stamp(value: datetime | None) -> str | None:
    return value.astimezone(timezone.utc).isoformat() if value is not None else None


def _record(body: ConfirmationReceipt) -> dict:
    data = body.model_dump(mode="json")
    return {
        "schema_version": data["schema_version"],
        "subject_type": data["subject_type"],
        "subject_id": data["subject_id"],
        "subject_version": data["subject_version"],
        "correlation_id": data["correlation_id"],
        "confirmed_at": _stamp(body.confirmed_at),
        "valid_until": _stamp(body.valid_until),
        "snapshot": data["snapshot"],
    }


def _digest(record: dict) -> str:
    # The receipt id is transport identity, not content: a second client id for
    # the same subject version and content is the same confirmation.
    return hashlib.sha256(_canonical(record).encode()).hexdigest()


_SELECT = """SELECT receipt_id, subject_type, subject_id, subject_version,
    correlation_id, confirmed_at, valid_until, snapshot, received_at
    FROM confirmation_receipts"""


def _stored(row) -> dict:
    return {
        "receipt_id": row[0],
        "subject_type": row[1],
        "subject_id": row[2],
        "subject_version": row[3],
        "correlation_id": row[4],
        "confirmed_at": row[5],
        "valid_until": row[6],
        "snapshot": json.loads(row[7]),
        "received_at": row[8],
    }


def _reply(record: dict, status: str, receipt_id: str) -> dict:
    return {
        "receipt_id": receipt_id,
        "correlation_id": record["correlation_id"],
        "subject_type": record["subject_type"],
        "subject_id": record["subject_id"],
        "subject_version": record["subject_version"],
        "status": status,
        "recorded": True,
        "recorded_is_not_executed": True,
    }


@router.post("/confirmations")
def append_confirmation(body: ConfirmationReceipt,
                        x_backup_token: str | None = Header(default=None)):
    """Record one accepted proposal/plan version. Storage is not execution."""
    _authorize(x_backup_token)
    record = _record(body)
    digest = _digest(record)
    receipt_id = str(body.receipt_id)
    subject = (record["subject_type"], record["subject_id"], record["subject_version"])
    with closing(_open()) as db:
        try:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                "SELECT digest FROM confirmation_receipts WHERE receipt_id=?",
                (receipt_id,)).fetchone()
            if row is not None:
                db.rollback()
                if row[0] != digest:
                    raise HTTPException(409, "确认收据编号已用于不同内容")
                return _reply(record, "duplicate", receipt_id)

            row = db.execute(
                """SELECT receipt_id, digest FROM confirmation_receipts
                WHERE subject_type=? AND subject_id=? AND subject_version=?""",
                subject).fetchone()
            if row is not None:
                db.rollback()
                if row[1] != digest:
                    raise HTTPException(409, "该确认版本已记录不同内容")
                return _reply(record, "duplicate", receipt_id=row[0])

            latest = db.execute(
                """SELECT MAX(subject_version) FROM confirmation_receipts
                WHERE subject_type=? AND subject_id=?""",
                subject[:2]).fetchone()[0]
            if latest is not None and subject[2] < latest:
                db.rollback()
                raise HTTPException(409, "确认版本已过期，服务器已记录更新的版本")

            db.execute("""INSERT INTO confirmation_receipts (
                receipt_id, subject_type, subject_id, subject_version,
                correlation_id, confirmed_at, confirmed_epoch,
                valid_until, valid_until_epoch, snapshot, digest, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", (
                receipt_id, subject[0], subject[1], subject[2],
                record["correlation_id"], record["confirmed_at"], body.confirmed_at.timestamp(),
                record["valid_until"],
                body.valid_until.timestamp() if body.valid_until is not None else None,
                _canonical(record["snapshot"]), digest, time.time()))
            db.commit()
        except sqlite3.IntegrityError:
            db.rollback()
            row = db.execute(
                """SELECT receipt_id, digest FROM confirmation_receipts
                WHERE subject_type=? AND subject_id=? AND subject_version=?""",
                subject).fetchone()
            if row is not None and row[1] == digest:
                return _reply(record, "duplicate", receipt_id=row[0])
            raise HTTPException(409, "该确认版本已记录不同内容")
        except BaseException:
            db.rollback()
            raise
    return _reply(record, "accepted", receipt_id)


@router.get("/confirmations")
def read_confirmations(correlation_id: uuid.UUID = Query(...),
                       limit: int = Query(100, ge=1, le=500),
                       x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)
    with closing(_open()) as db:
        rows = db.execute(f"""{_SELECT} WHERE correlation_id=?
            ORDER BY confirmed_epoch, receipt_id LIMIT ?""",
            (str(correlation_id), limit)).fetchall()
    return {"correlation_id": str(correlation_id), "count": len(rows),
            "confirmations": [_stored(row) for row in rows]}


_EXECUTION_STATES = {
    "NAVIGATION_STARTED": "started",
    "NAVIGATION_ARRIVED": "arrived",
    "NAVIGATION_CANCELLED": "cancelled",
    "NAVIGATION_FAILED": "failed",
}


def _plan_identity(payload: dict) -> dict:
    """Missing legacy identity remains unknown; never infer a device revision."""
    def identifier(key):
        value = payload.get(key)
        return value if isinstance(value, str) and value.strip() else None

    version = payload.get("plan_version")
    return {
        "plan_id": identifier("plan_id"),
        "plan_version": version if type(version) is int and 0 < version <= 2_147_483_646 else None,
        "route_session_id": identifier("route_session_id"),
    }


def _matches_plan(plan: dict, observed: dict) -> bool | None:
    pairs = [(plan[key], observed[key]) for key in ("plan_id", "plan_version", "route_session_id")]
    if any(left is not None and right is not None and left != right for left, right in pairs):
        return False
    return True if all(left is not None and right is not None for left, right in pairs) else None


def _execution_view(observations: list[dict], identity: dict | None = None,
                    protect_terminal: bool = False) -> dict:
    latest = observations[-1] if observations else None
    if protect_terminal:
        # Ended trips cannot restart; a failed attempt can recover within the same revision.
        latest = next((item for item in reversed(observations)
                       if item["kind"] in {"NAVIGATION_ARRIVED", "NAVIGATION_CANCELLED"}), latest)
    return {
        "state": _EXECUTION_STATES[latest["kind"]] if latest else "not_started",
        **(identity if identity is not None else
           latest["identity"] if latest else _plan_identity({})),
        "observed_at": latest["occurred_at"] if latest else None,
        "evidence": [{key: item[key] for key in ("event_id", "kind", "occurred_at")}
                     for item in observations],
    }


@router.get("/state")
def read_state(correlation_id: uuid.UUID = Query(...),
               x_backup_token: str | None = Header(default=None)):
    """Receipts and observed execution, never authorization to execute.

    `execution` is the last raw navigation observation. `current_plan_execution`
    reduces only evidence for the exact current plan revision and route session.
    """
    _authorize(x_backup_token)
    with closing(_open()) as db:
        receipts = [_stored(row) for row in db.execute(
            f"""{_SELECT} WHERE correlation_id=?
            ORDER BY confirmed_epoch, receipt_id""", (str(correlation_id),)).fetchall()]
        event_rows = db.execute("""SELECT event_id, kind, occurred_at, payload
            FROM business_events WHERE correlation_id=?
            ORDER BY occurred_epoch, event_id""", (str(correlation_id),)).fetchall()

    proposals = [receipt for receipt in receipts if receipt["subject_type"] == "proposal"]
    plans = [receipt for receipt in receipts if receipt["subject_type"] == "plan"]
    navigation = [{"event_id": row[0], "kind": row[1], "occurred_at": row[2],
                   "identity": _plan_identity(json.loads(row[3]))}
                  for row in event_rows if row[1] in _EXECUTION_STATES]
    execution = _execution_view(navigation)

    # Versions are comparable only inside one subject. Delivery/replay order does
    # not change which subject was most recently confirmed by the user.
    latest_plan = (max((receipt for receipt in plans
                       if receipt["subject_id"] == plans[-1]["subject_id"]),
                      key=lambda receipt: receipt["subject_version"]) if plans else None)
    current_plan = None
    current_execution = None
    matches = None
    if latest_plan is not None:
        identity = _plan_identity({
            "plan_id": latest_plan["subject_id"],
            "plan_version": latest_plan["subject_version"],
            "route_session_id": latest_plan["snapshot"].get("route_session_id"),
        })
        current_plan = {**identity, "receipt_id": latest_plan["receipt_id"],
                        "confirmed_at": latest_plan["confirmed_at"]}
        matched = [item for item in navigation if _matches_plan(identity, item["identity"]) is True]
        current_execution = _execution_view(matched, identity, protect_terminal=True)
        if not matched and (any(value is None for value in identity.values()) or
                            any(_matches_plan(identity, item["identity"]) is None for item in navigation)):
            current_execution["state"] = "unknown"
        if navigation:
            matches = _matches_plan(identity, navigation[-1]["identity"])

    return {
        "correlation_id": str(correlation_id),
        "recorded": {
            "count": len(receipts),
            "proposals": proposals,
            "plans": plans,
            "latest_plan_version": latest_plan["subject_version"] if latest_plan else None,
        },
        "execution": execution,
        "current_plan": current_plan,
        "current_plan_execution": current_execution,
        "execution_matches_recorded_plan": matches,
        "recorded_is_not_executed": True,
        "observations": {
            "event_count": len(event_rows),
            "kinds": [row[1] for row in event_rows],
        },
    }
