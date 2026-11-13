"""Bind Agent-produced routes to their durable, current task revision.

Lock order is Agent store -> task authority (when used) -> event store. Map
queries never run under this guard. The event-side binding is immutable and
created only by the built-in adapter, not accepted from a confirmation body.
"""
from contextlib import closing, contextmanager, nullcontext
import hmac
import json
from pathlib import Path
import sqlite3

from fastapi import HTTPException


def _stale():
    raise HTTPException(409, "Agent 路线已失效或来源不匹配，请读取当前任务并重新规划")


def _open():
    from backend.execution.confirmations import _open as open_events
    db = open_events()
    try:
        with db:
            db.execute("""CREATE TABLE IF NOT EXISTS agent_route_sources (
                plan_id TEXT PRIMARY KEY, agent_run_id TEXT NOT NULL,
                agent_task_id TEXT, revision INTEGER, agent_db TEXT NOT NULL,
                parameters_json TEXT NOT NULL)""")
            db.execute("CREATE INDEX IF NOT EXISTS agent_route_run ON agent_route_sources(agent_run_id)")
        db.row_factory = sqlite3.Row
        return db
    except BaseException:
        db.close()
        raise


def _current(db, source, *, dispatch=None, now=None):
    row = db.execute("""SELECT c.*,r.agent_task_id,r.revision,r.state AS run_state,
        r.lease_until,t.current_run_id,t.revision AS current_revision
        FROM agent_tool_calls c JOIN agent_runs r ON r.agent_run_id=c.agent_run_id
        JOIN agent_tasks t ON t.agent_task_id=r.agent_task_id WHERE c.tool_call_id=?""",
        (source["plan_id"],)).fetchone()
    if row is None or (row["agent_run_id"], row["agent_task_id"], row["revision"]) != (
            source["agent_run_id"], source["agent_task_id"], source["revision"]):
        _stale()
    if (row["current_run_id"] != row["agent_run_id"] or row["current_revision"] != row["revision"] or
            row["run_state"] in {"cancelled", "failed"} or row["tool_id"] != "onmyway.plan_route" or
            row["legacy_run_id"] != source["plan_id"] or
            json.loads(row["arguments_json"]) != json.loads(source["parameters_json"])):
        _stale()
    latest = db.execute("""SELECT tool_call_id FROM agent_tool_calls
        WHERE agent_run_id=? AND tool_id='onmyway.plan_route' ORDER BY rowid DESC LIMIT 1""",
        (row["agent_run_id"],)).fetchone()
    if latest is None or latest[0] != source["plan_id"]:
        _stale()
    if dispatch is not None:
        secret = dispatch.get("receipt_token")
        if (row["state"] not in {"dispatched", "succeeded"} or not isinstance(secret, str) or
                not row["receipt_token"] or not hmac.compare_digest(row["receipt_token"], secret) or
                row["state"] == "dispatched" and (row["lease_until"] is None or row["lease_until"] <= now)):
            _stale()
    elif row["state"] != "succeeded":
        _stale()


def register(call, parameters, store):
    """Persist source before planning; a crash never yields an unbound Agent id."""
    source = {"plan_id": call["tool_call_id"], "agent_run_id": call["agent_run_id"],
              "agent_task_id": None, "revision": None,
              "agent_db": str(store.path.resolve()) if store is not None else "",
              "parameters_json": json.dumps(parameters, sort_keys=True, ensure_ascii=False, separators=(",", ":"))}
    with store._transaction() if store is not None else nullcontext(None) as agent_db:
        if store is not None:
            run = store._run(agent_db, call["agent_run_id"])
            source.update(agent_task_id=run["agent_task_id"], revision=run["revision"])
            _current(agent_db, source, dispatch=call, now=store.clock())
        with closing(_open()) as db, db:
            db.execute("BEGIN IMMEDIATE")
            previous = db.execute("SELECT * FROM agent_route_sources WHERE plan_id=?", (source["plan_id"],)).fetchone()
            if previous is not None:
                if dict(previous) != source:
                    _stale()
            else:
                db.execute("INSERT INTO agent_route_sources VALUES (:plan_id,:agent_run_id,:agent_task_id,:revision,:agent_db,:parameters_json)", source)
    return {"plan_id": source["plan_id"], "plan_version": 1}


@contextmanager
def guard(plan_id, plan_version, correlation_id, snapshot):
    """Fence receipt insertion/consumption against revisions across processes."""
    with closing(_open()) as events:
        source = events.execute("SELECT * FROM agent_route_sources WHERE plan_id=?", (plan_id,)).fetchone()
        related = events.execute("SELECT 1 FROM agent_route_sources WHERE agent_run_id=? LIMIT 1", (str(correlation_id),)).fetchone()
        # Pre-upgrade Agent invocations have an immutable agent-call snapshot,
        # but no authoritative source binding; require replanning, never adopt it.
        historical = None
        if source is None and events.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='capability_runs'").fetchone():
            historical = events.execute("""SELECT 1 FROM capability_runs WHERE
                json_extract(invocation,'$.snapshot_id') LIKE 'agent-call:%' AND
                (run_id=? OR json_extract(invocation,'$.correlation_id')=?) LIMIT 1""",
                (plan_id, str(correlation_id))).fetchone()
    if source is None:
        if related is not None or historical is not None:
            _stale()
        yield
        return
    source = dict(source)
    parameters = json.loads(source["parameters_json"])
    selected = snapshot.get("selected_task_ids")
    versions = snapshot.get("task_versions")
    if (plan_version != 1 or str(correlation_id) != source["agent_run_id"] or not source["agent_db"] or
            snapshot.get("store_id") != parameters["store_id"] or versions != parameters["task_versions"] or
            not isinstance(versions, dict) or any(type(value) is not int for value in versions.values()) or
            not isinstance(selected, list) or not all(isinstance(item, str) for item in selected) or
            len(selected) != len(set(selected)) or set(selected) != set(versions)):
        _stale()
    try:
        db = sqlite3.connect(Path(source["agent_db"]).as_uri() + "?mode=rw", uri=True, timeout=10)
        db.row_factory = sqlite3.Row
        with closing(db):
            db.execute("BEGIN IMMEDIATE")
            try:
                _current(db, source)
                yield
            finally:
                db.rollback()
    except sqlite3.Error:
        _stale()


def confirmation_guard(body):
    if body.subject_type != "plan":
        return nullcontext()
    return guard(body.subject_id, body.subject_version, body.correlation_id, body.snapshot)


def execution_guard(body, arguments):
    from backend.execution.capabilities import StartNavigation
    if not isinstance(arguments, StartNavigation):
        return nullcontext()
    return guard(arguments.plan_id, arguments.plan_version, body.correlation_id,
                 {"store_id": arguments.store_id, "task_versions": arguments.task_versions,
                  "selected_task_ids": list(arguments.task_versions)})
