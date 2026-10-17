"""Durable single-user intent runs. A completed run is a proposal, never an executed action."""
import hashlib
import json
import os
import sqlite3
import time
from contextlib import closing
from pathlib import Path

from fastapi import HTTPException


UNRESOLVED_AFTER_SECONDS = 300


def _input_hash(payload):
    return hashlib.sha256(json.dumps(payload, sort_keys=True, ensure_ascii=False,
                                    separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def _check_identity(row, operation, digest):
    if row[0] != operation or row[1] != digest:
        raise HTTPException(409, "请求编号已用于其他内容，请重新提交。")


def _open():
    path = Path(os.getenv("ONMYWAY_HARNESS_DB", "").strip() or
                Path(__file__).with_name("data") / "harness.db")
    path.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(path, timeout=10)
    try:
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("""CREATE TABLE IF NOT EXISTS intent_runs (
            request_id TEXT PRIMARY KEY, operation TEXT NOT NULL,
            input_hash TEXT NOT NULL, state TEXT NOT NULL,
            created_at REAL NOT NULL, updated_at REAL NOT NULL,
            result TEXT, error_status INTEGER)""")
        db.commit()
        return db
    except BaseException:
        db.close()
        raise


def propose(request_id, operation, payload, execute):
    digest = _input_hash(payload)
    with closing(_open()) as db:
        db.execute("BEGIN IMMEDIATE")
        row = db.execute("SELECT operation,input_hash,state,result FROM intent_runs WHERE request_id=?",
                         (request_id,)).fetchone()
        if row:
            db.rollback()
            _check_identity(row, operation, digest)
            if row[2] == "completed":
                return json.loads(row[3])
            # Do not reissue paid upstream calls after crashes or uncertain delivery.
            raise HTTPException(409, "该请求正在处理或未完成，请稍后查看；重新发起请使用新的请求编号。")
        now = time.time()
        db.execute("INSERT INTO intent_runs VALUES (?,?,?,?,?,?,NULL,NULL)",
                   (request_id, operation, digest, "running", now, now))
        db.commit()
    try:
        result = execute()
        encoded = json.dumps(result, ensure_ascii=False, allow_nan=False)
        with closing(_open()) as db, db:
            db.execute("UPDATE intent_runs SET state='completed',result=?,updated_at=? WHERE request_id=?",
                       (encoded, time.time(), request_id))
        return result
    except Exception as error:
        with closing(_open()) as db, db:
            db.execute("UPDATE intent_runs SET state='failed',error_status=?,updated_at=? WHERE request_id=?",
                       (error.status_code if isinstance(error, HTTPException) else 500, time.time(), request_id))
        raise


def status(request_id, operation, payload):
    """Read one matching run without submitting work or changing its durable state.

    An old running row may represent a crashed process or a slow upstream request.
    It remains running in SQLite so that a late completion can still be recovered.
    """
    digest = _input_hash(payload)
    with closing(_open()) as db:
        row = db.execute(
            "SELECT operation,input_hash,state,result,error_status,updated_at "
            "FROM intent_runs WHERE request_id=?", (request_id,)
        ).fetchone()
    response = dict(request_id=request_id, operation=operation, state="not_found",
                    result=None, error_status=None)
    if row is None:
        return response
    _check_identity(row, operation, digest)
    state = row[2]
    if state == "completed":
        try:
            result = json.loads(row[3])
            if not isinstance(result, dict):
                raise ValueError("invalid cached proposal")
        except (ValueError, TypeError):
            response["state"] = "unresolved"
        else:
            response.update(state="completed", result=result)
    elif state == "failed":
        code = row[4]
        response.update(state="failed", error_status=code if isinstance(code, int) and 400 <= code < 600 else 500)
    elif state == "running":
        response["state"] = ("unresolved" if time.time() - row[5] >= UNRESOLVED_AFTER_SECONDS else "running")
    else:
        response["state"] = "unresolved"
    return response
