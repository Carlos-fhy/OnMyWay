"""Durable capability execution, separate from model proposal request deduplication.

The PC runtime guards synchronized task versions. Android's offline runtime
guards Room state and SDK permission itself. Neither recorded confirmation nor
a dispatch claim is evidence that the device executed the command.
"""
import hashlib
import json
import sqlite3
import time
import uuid
from contextlib import closing, contextmanager
from datetime import datetime

from fastapi import APIRouter, Header, HTTPException, Request

from backend.execution import capabilities
from backend.execution import confirmations
from backend.data_services import tasks
from backend.planning.amap import MapError
from backend.execution.capabilities import Contract, Invocation, PlanRoute, StartNavigation
from backend.planning.context import require_fresh_context
from backend.planning.planner import PlanningError
from backend.planning.models import Place
from backend.data_services.backup import _authorize
from backend.agent.route_authority import execution_guard


router = APIRouter(tags=["execution"])
UNKNOWN_AFTER_SECONDS = 300


class StartConfirmation(Contract):
    confirmation_id: uuid.UUID


def _canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def _open():
    db = confirmations._open()
    try:
        with db:
            db.execute("""CREATE TABLE IF NOT EXISTS capability_runs (
                run_id TEXT PRIMARY KEY, digest TEXT NOT NULL, invocation TEXT NOT NULL,
                state TEXT NOT NULL, created_at REAL NOT NULL, updated_at REAL NOT NULL,
                confirmation_id TEXT, result TEXT, error_status INTEGER,
                device_execution_observed INTEGER NOT NULL DEFAULT 0)""")
            db.execute("""CREATE TABLE IF NOT EXISTS capability_steps (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL,
                step TEXT NOT NULL, state TEXT NOT NULL, observed_at REAL NOT NULL,
                evidence_id TEXT)""")
            db.execute("CREATE INDEX IF NOT EXISTS capability_steps_run ON capability_steps(run_id, sequence)")
            db.execute("""CREATE TABLE IF NOT EXISTS navigation_dispatch_claims (
                plan_id TEXT NOT NULL, plan_version INTEGER NOT NULL,
                route_session_id TEXT NOT NULL, run_id TEXT NOT NULL,
                PRIMARY KEY(plan_id,plan_version,route_session_id))""")
        db.row_factory = sqlite3.Row
        return db
    except BaseException:
        db.close()
        raise


def _row(db, run_id):
    row = db.execute("SELECT * FROM capability_runs WHERE run_id=?", (str(run_id),)).fetchone()
    if row is None:
        raise HTTPException(404, "执行记录不存在")
    return row


def _step(db, run_id, step, state, evidence_id=None):
    db.execute("INSERT INTO capability_steps(run_id,step,state,observed_at,evidence_id) VALUES (?,?,?,?,?)",
               (str(run_id), step, state, time.time(), evidence_id))


def _view(db, row, dispatch=False):
    invocation = json.loads(row["invocation"])
    result = json.loads(row["result"]) if row["result"] else None
    state = row["state"]
    if state == "running" and time.time() - row["updated_at"] >= UNKNOWN_AFTER_SECONDS:
        # A matching SDK start remains active until a terminal observation.
        if not row["device_execution_observed"]:
            state = "unknown"
    return {**invocation, "state": state, "result": result,
            "error_status": row["error_status"], "confirmation_id": row["confirmation_id"],
            "dispatch_allowed": dispatch, "device_execution_observed": bool(row["device_execution_observed"]),
            "recorded_is_not_executed": True,
            "created_at": row["created_at"], "updated_at": row["updated_at"],
            "steps": [dict(item) for item in db.execute(
                "SELECT sequence,step,state,observed_at,evidence_id FROM capability_steps WHERE run_id=? ORDER BY sequence",
                (row["run_id"],))]}


@contextmanager
def _task_guard(arguments):
    if not isinstance(arguments, (PlanRoute, StartNavigation)):
        yield
        return
    from backend.data_services.sync import _open as open_tasks
    # Fixed lock order: task authority, then execution/event store. No map call
    # runs with this lock held; validate the resulting route again on completion.
    with closing(open_tasks()) as db:
        db.execute("BEGIN IMMEDIATE")
        if db.execute("SELECT store_id FROM sync_head WHERE id=1").fetchone()[0] != arguments.store_id:
            raise HTTPException(409, "任务服务身份已变化")
        for task_id, version in arguments.task_versions.items():
            row = db.execute(f"SELECT {tasks.FIELDS},version,deleted FROM task_records WHERE id=?", (task_id,)).fetchone()
            if row is None or row[-2] != version or row[-1] or row[tasks.COLUMNS.index("status")] != "pending":
                raise HTTPException(409, "任务版本已变化，请重新规划")
            if isinstance(arguments, PlanRoute):
                current = dict(zip(tasks.COLUMNS, row))
                requested = next(task for task in arguments.request.tasks if task.id == task_id)
                if (requested.title, requested.priority, requested.service_minutes) != (
                        current["title"], current["priority"], current["serviceMinutes"]):
                    raise HTTPException(409, "规划任务与当前版本内容不一致")
                try:
                    actual_place = Place.model_validate_json(current["placeJson"]) if current["placeJson"] else None
                    actual_deadline = datetime.fromisoformat(current["deadline"]) if current["deadline"] else None
                except (ValueError, TypeError):
                    raise HTTPException(409, "当前任务的地点或截止时间无效，请先修正任务") from None
                if requested.place != actual_place or requested.place_type != (None if actual_place else current["placeType"]):
                    raise HTTPException(409, "规划地点与当前任务不一致")
                if requested.deadline != actual_deadline:
                    raise HTTPException(409, "规划截止时间与当前任务不一致")
        try:
            yield
        finally:
            db.rollback()


def _require_plan(db, body, arguments):
    if not isinstance(arguments, StartNavigation):
        return
    row = db.execute("SELECT * FROM confirmation_receipts WHERE receipt_id=?",
                     (str(arguments.confirmation_receipt_id),)).fetchone()
    if row is None:
        raise HTTPException(409, "尚未记录该路线确认")
    snapshot = json.loads(row["snapshot"])
    if (row["subject_type"], row["subject_id"], row["subject_version"], row["correlation_id"],
            snapshot.get("route_session_id")) != (
            "plan", arguments.plan_id, arguments.plan_version, str(body.correlation_id), arguments.route_session_id):
        raise HTTPException(409, "路线确认与执行身份不一致")
    # Legacy confirmations remain readable but cannot authorize the new API.
    versions = snapshot.get("task_versions")
    if (not isinstance(versions, dict) or any(type(value) is not int for value in versions.values()) or
            versions != arguments.task_versions or snapshot.get("store_id") != arguments.store_id):
        raise HTTPException(409, "路线确认缺少匹配的任务版本，请重新确认")
    selected = snapshot.get("selected_task_ids")
    if (not isinstance(selected, list) or not all(isinstance(item, str) for item in selected) or
            len(selected) != len(set(selected)) or set(selected) != set(arguments.task_versions)):
        raise HTTPException(409, "路线确认的任务集合不一致")
    latest = db.execute("SELECT MAX(subject_version) FROM confirmation_receipts WHERE subject_type='plan' AND subject_id=?",
                        (arguments.plan_id,)).fetchone()[0]
    if latest != arguments.plan_version:
        raise HTTPException(409, "路线版本已变化，请重新确认")
    newest = db.execute("""SELECT subject_id FROM confirmation_receipts
        WHERE subject_type='plan' AND correlation_id=? ORDER BY confirmed_epoch DESC,received_at DESC LIMIT 1""",
        (str(body.correlation_id),)).fetchone()
    if newest is not None and newest[0] != arguments.plan_id:
        raise HTTPException(409, "当前行程已有其他确认方案")
    if row["valid_until_epoch"] is not None and row["valid_until_epoch"] < time.time():
        raise HTTPException(409, "路线确认已过期")


def _existing(db, body, digest):
    row = db.execute("SELECT * FROM capability_runs WHERE run_id=?", (str(body.run_id),)).fetchone()
    if row is not None and row["digest"] != digest:
        raise HTTPException(409, "执行编号已用于不同内容")
    return row


def _server_invocation(row):
    invocation = json.loads(row["invocation"])
    if "authority" in invocation:
        raise HTTPException(409, "客户端规划观察不能授权服务端执行")
    return Invocation.model_validate(invocation)


@router.get("/capabilities")
def read_capabilities(x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)
    return capabilities.registry()


@router.post("/executions")
def execute(body: Invocation, request: Request, x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)
    encoded = _canonical(body.model_dump(mode="json"))
    digest = hashlib.sha256(encoded.encode()).hexdigest()
    arguments = body.arguments()
    # Replay immutable results even if their original task versions are now old.
    with closing(_open()) as db:
        row = _existing(db, body, digest)
        if row is not None:
            return _view(db, row)
    if isinstance(arguments, PlanRoute):
        require_fresh_context(arguments.request.context_snapshot)
    with execution_guard(body, arguments), _task_guard(arguments), closing(_open()) as db, db:
        db.execute("BEGIN IMMEDIATE")
        row = _existing(db, body, digest)
        if row is not None:
            return _view(db, row)
        _require_plan(db, body, arguments)
        now = time.time()
        state = "waiting_confirmation" if isinstance(arguments, StartNavigation) else "running"
        db.execute("INSERT INTO capability_runs(run_id,digest,invocation,state,created_at,updated_at) VALUES (?,?,?,?,?,?)",
                   (str(body.run_id), digest, encoded, state, now, now))
        _step(db, body.run_id, "validated", state)
    if isinstance(arguments, StartNavigation):
        return read_execution(body.run_id, x_backup_token)
    try:
        if isinstance(arguments, PlanRoute):
            from backend.main import planning_slot
            from backend.planning.planner import plan
            if not planning_slot.acquire(blocking=False):
                raise HTTPException(429, "已有规划正在运行，请稍后重试")
            try:
                result = plan(arguments.request, request.app.state.provider).model_dump(mode="json")
            finally:
                planning_slot.release()
        else:
            result = tasks.read_tasks(x_backup_token)
        result = capabilities.validate_result(body.capability_id, result)
        with _task_guard(arguments), closing(_open()) as db, db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("UPDATE capability_runs SET state='completed',result=?,updated_at=? WHERE run_id=?",
                       (_canonical(result), time.time(), str(body.run_id)))
            _step(db, body.run_id, "result_saved", "completed")
    except Exception as error:
        status = error.status_code if isinstance(error, HTTPException) else 422 if isinstance(error, PlanningError) else 503 if isinstance(error, MapError) else 500
        state = "failed" if isinstance(error, (HTTPException, PlanningError, MapError)) else "unknown"
        with closing(_open()) as db, db:
            db.execute("UPDATE capability_runs SET state=?,error_status=?,updated_at=? WHERE run_id=?",
                       (state, status, time.time(), str(body.run_id)))
            _step(db, body.run_id, "execution_error", state)
    return read_execution(body.run_id, x_backup_token)


@router.get("/executions/{run_id}")
def read_execution(run_id: uuid.UUID, x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)
    with closing(_open()) as db:
        return _view(db, _row(db, run_id))


@router.post("/executions/{run_id}/confirm")
def confirm_execution(run_id: uuid.UUID, confirmation: StartConfirmation,
                      x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)
    with closing(_open()) as db:
        row = _row(db, run_id)
        body = _server_invocation(row)
        if body.capability_id != "start_navigation":
            raise HTTPException(409, "该能力不需要启动确认")
        if row["state"] != "waiting_confirmation":
            return _view(db, row)
    arguments = body.arguments()
    with execution_guard(body, arguments), _task_guard(arguments), closing(_open()) as db, db:
        db.execute("BEGIN IMMEDIATE")
        row = _row(db, run_id)
        if row["state"] != "waiting_confirmation":
            return _view(db, row)
        _require_plan(db, body, arguments)
        claim = db.execute("""SELECT run_id FROM navigation_dispatch_claims
            WHERE plan_id=? AND plan_version=? AND route_session_id=?""",
            (arguments.plan_id, arguments.plan_version, arguments.route_session_id)).fetchone()
        if claim is not None:
            raise HTTPException(409, "该路线已申请启动，请先核对已有执行记录")
        db.execute("INSERT INTO navigation_dispatch_claims VALUES (?,?,?,?)",
                   (arguments.plan_id, arguments.plan_version, arguments.route_session_id, str(run_id)))
        db.execute("UPDATE capability_runs SET state='running',confirmation_id=?,updated_at=? WHERE run_id=?",
                   (str(confirmation.confirmation_id), time.time(), str(run_id)))
        _step(db, run_id, "dispatch_claimed", "running", str(confirmation.confirmation_id))
        return _view(db, _row(db, run_id), dispatch=True)


@router.post("/executions/{run_id}/reconcile")
def reconcile_execution(run_id: uuid.UUID, x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)
    with closing(_open()) as db, db:
        db.execute("BEGIN IMMEDIATE")
        row = _row(db, run_id)
        body = _server_invocation(row)
        if body.capability_id != "start_navigation" or row["state"] in ("waiting_confirmation", "completed", "failed"):
            return _view(db, row)
        arguments = body.arguments()
        observations = []
        for event in db.execute("""SELECT event_id,kind,occurred_at,payload FROM business_events
                WHERE correlation_id=? AND source='device' ORDER BY occurred_epoch,event_id""", (str(body.correlation_id),)):
            payload = json.loads(event["payload"])
            if event["kind"] not in {"NAVIGATION_STARTED", "NAVIGATION_ARRIVED", "NAVIGATION_CANCELLED", "NAVIGATION_FAILED"}:
                continue
            if (payload.get("execution_id"), payload.get("plan_id"), payload.get("plan_version"), payload.get("route_session_id")) != (
                    str(run_id), arguments.plan_id, arguments.plan_version, arguments.route_session_id):
                continue
            if type(payload.get("plan_version")) is not int:
                continue
            observations.append(event)
        if observations:
            terminal = next((event for event in observations if event["kind"] != "NAVIGATION_STARTED"), None)
            event = terminal or observations[-1]
            state = "completed" if event["kind"] == "NAVIGATION_ARRIVED" else "running" if event["kind"] == "NAVIGATION_STARTED" else "failed"
            result = capabilities.validate_result(body.capability_id, {
                "event_id": event["event_id"], "kind": event["kind"], "observed_at": event["occurred_at"],
                "execution_id": str(run_id), "plan_id": arguments.plan_id,
                "plan_version": arguments.plan_version, "route_session_id": arguments.route_session_id,
            })
            encoded = _canonical(result)
            if row["result"] != encoded:
                db.execute("UPDATE capability_runs SET state=?,result=?,device_execution_observed=1,updated_at=? WHERE run_id=?",
                           (state, encoded, time.time(), str(run_id)))
                _step(db, run_id, "device_receipt", state, event["event_id"])
        elif _view(db, row)["state"] == "unknown" and row["state"] != "unknown":
            db.execute("UPDATE capability_runs SET state='unknown' WHERE run_id=?", (str(run_id),))
            _step(db, run_id, "reconciliation_pending", "unknown")
        return _view(db, _row(db, run_id))
