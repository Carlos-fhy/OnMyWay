"""Durable receipts for planning against Android's immutable context snapshot.

These observations never claim the server task authority. Recovery only reads
the original execution; a lost response cannot silently launch a second plan.
"""
import hashlib
import json
import time
from contextlib import closing

from fastapi import HTTPException
from fastapi.encoders import jsonable_encoder
from pydantic import ValidationError

from backend.execution import runtime as execution
from backend.planning.amap import MapError
from backend.planning.context import require_fresh_context
from backend.planning.models import PlanResult
from backend.planning.planner import PlanningError


AUTHORITY = "android_context_snapshot"


def _identity(body, run_id, operation):
    if body.context_snapshot is None:
        raise HTTPException(422, "可恢复规划需要当前上下文快照")
    invocation = {"run_id": str(run_id), "capability_id": "plan_route", "capability_version": 1,
                  "authority": AUTHORITY, "operation": operation,
                  "snapshot_id": str(body.context_snapshot.snapshot_id),
                  "parameters": {"request": body.model_dump(mode="json")}}
    encoded = execution._canonical(invocation)
    return encoded, hashlib.sha256(encoded.encode()).hexdigest()


def _existing(db, run_id, digest):
    row = db.execute("SELECT * FROM capability_runs WHERE run_id=?", (str(run_id),)).fetchone()
    if row is not None and (row["digest"] != digest or
                            json.loads(row["invocation"]).get("authority") != AUTHORITY):
        raise HTTPException(409, "执行编号已用于不同内容或执行权限")
    return row


def _plan_result(value, body):
    if not isinstance(value, dict):
        raise ValueError("Plan result must be an object")
    original = dict(value)
    value = {key: item for key, item in value.items() if key not in ("total_meters", "extra_meters")}
    result = PlanResult.model_validate_json(execution._canonical(value), strict=True)
    if result.origin != body.origin or result.destination != body.destination or result.mode != body.mode:
        raise ValueError("Plan result belongs to another request")
    if result.requested_departure is not None and result.requested_departure != body.departure:
        raise ValueError("Plan result belongs to another departure request")
    for field in ("baseline_meters", "baseline_seconds", "travel_seconds", "service_seconds", "total_seconds"):
        if getattr(result, field) < 0:
            raise ValueError("Plan result has a negative duration or distance")
    if (result.travel_seconds != sum(leg.seconds for leg in result.legs) or
            result.service_seconds != sum(stop.service_seconds for stop in result.stops) or
            result.total_seconds != result.travel_seconds + result.service_seconds or
            not result.legs or result.arrival.utcoffset() is None):
        raise ValueError("Plan result has inconsistent route totals")
    tasks = {task.id: task for task in body.tasks}
    accounted = []
    for stop in result.stops:
        if (not stop.task_ids or len(stop.task_ids) != len(stop.task_titles) or stop.service_seconds < 0 or
                stop.arrival.utcoffset() is None or stop.departure.utcoffset() is None or
                stop.departure < stop.arrival):
            raise ValueError("Plan result has an invalid stop")
        for task_id, title in zip(stop.task_ids, stop.task_titles):
            if task_id not in tasks or tasks[task_id].title != title:
                raise ValueError("Plan result contains an unrequested task")
            accounted.append(task_id)
    for omitted in result.omitted:
        if omitted.task_id not in tasks or tasks[omitted.task_id].title != omitted.title:
            raise ValueError("Plan result contains an unrequested omitted task")
        accounted.append(omitted.task_id)
    if len(accounted) != len(set(accounted)) or set(accounted) != set(tasks):
        raise ValueError("Plan result does not account for exactly the requested tasks")
    for field in ("total_meters", "extra_meters"):
        if field in original and (type(original[field]) is not int or original[field] != getattr(result, field)):
            raise ValueError("Plan result has inconsistent distance totals")
    return result.model_dump(mode="json")


def _result(value, body, operation):
    try:
        value = jsonable_encoder(value)
        if operation == "plan":
            return _plan_result(value, body)
        if (not isinstance(value, dict) or set(value) != {"direct", "suggestion", "note", "assessments"} or
                not isinstance(value["note"], str) or not isinstance(value["assessments"], list)):
            raise ValueError("Invalid recommendation envelope")
        direct = _plan_result(value["direct"], body.model_copy(update={"tasks": []}))
        suggestion = None if value["suggestion"] is None else _plan_result(value["suggestion"], body)
        tasks = {task.id: task for task in body.tasks}
        assessed = []
        for item in value["assessments"]:
            if (not isinstance(item, dict) or item.get("task_id") not in tasks or
                    item.get("title") != tasks[item["task_id"]].title or
                    type(item.get("within_budget")) is not bool or not isinstance(item.get("reason"), str) or
                    "extra_seconds" not in item or
                    (item["extra_seconds"] is not None and type(item["extra_seconds"]) is not int) or
                    ("extra_meters" in item and type(item["extra_meters"]) is not int)):
                raise ValueError("Invalid task assessment")
            assessed.append(item["task_id"])
        if len(assessed) != len(set(assessed)) or set(assessed) != set(tasks):
            raise ValueError("Recommendation does not assess exactly the requested tasks")
        return {**value, "direct": direct, "suggestion": suggestion}
    except (ValidationError, ValueError, TypeError, KeyError):
        raise HTTPException(502, "规划执行结果不符合已登记契约") from None


def status(body, run_id, operation):
    _, digest = _identity(body, run_id, operation)
    with closing(execution._open()) as db:
        row = _existing(db, run_id, digest)
        state = "not_found" if row is None else row["state"]
        if state == "running" and time.time() - row["updated_at"] >= execution.UNKNOWN_AFTER_SECONDS:
            state = "unknown"
        result = _result(json.loads(row["result"]), body, operation) if state == "completed" else None
        return {"run_id": str(run_id), "capability_id": "plan_route", "operation": operation,
                "state": state, "result": result,
                "error_status": None if row is None else row["error_status"]}


def run(body, run_id, operation, worker):
    encoded, digest = _identity(body, run_id, operation)
    with closing(execution._open()) as db, db:
        db.execute("BEGIN IMMEDIATE")
        row = _existing(db, run_id, digest)
        if row is not None:
            if row["state"] == "completed":
                return _result(json.loads(row["result"]), body, operation)
            raise HTTPException(409, "该规划已有执行记录，请核对执行状态；未重新提交")
        require_fresh_context(body.context_snapshot)
        now = time.time()
        db.execute("INSERT INTO capability_runs(run_id,digest,invocation,state,created_at,updated_at) VALUES (?,?,?,?,?,?)",
                   (str(run_id), digest, encoded, "running", now, now))
        execution._step(db, run_id, "validated", "running")
    try:
        result = _result(worker(), body, operation)
        with closing(execution._open()) as db, db:
            db.execute("UPDATE capability_runs SET state='completed',result=?,updated_at=? WHERE run_id=?",
                       (execution._canonical(result), time.time(), str(run_id)))
            execution._step(db, run_id, "result_saved", "completed")
        return result
    except Exception as error:
        status_code = (error.status_code if isinstance(error, HTTPException) else
                       422 if isinstance(error, PlanningError) else 503 if isinstance(error, MapError) else 500)
        known = isinstance(error, (HTTPException, PlanningError, MapError))
        state = "failed" if known else "unknown"
        with closing(execution._open()) as db, db:
            db.execute("UPDATE capability_runs SET state=?,error_status=?,updated_at=? WHERE run_id=?",
                       (state, status_code, time.time(), str(run_id)))
            execution._step(db, run_id, "execution_error", state)
        if known:
            raise
        raise HTTPException(500, "规划响应未能确认，请核对原执行状态；未自动重试") from None
