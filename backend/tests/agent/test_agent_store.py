"""Persistent task invariants; providers and business data are not contacted."""
from backend.paths import PROJECT_ROOT

import copy
import importlib.util
import json
import sqlite3
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Barrier
from uuid import uuid4

import pytest
from fastapi import HTTPException


def payload(goal="取快递 + 打印论文 + 回宿舍", **changes):
    return {"request_id": str(uuid4()), "conversation_id": "conversation-1",
            "message_id": str(uuid4()), "goal": goal,
            "constraints": {"stops": ["parcel", "print", "dorm"]},
            "success_criteria": ["保留快递和宿舍"], **changes}


@pytest.fixture
def runtime(tmp_path):
    assert importlib.util.find_spec("backend.agent.store") is not None, "A1 durable task store is missing"
    from backend.agent.store import AgentStore
    clock = [1000.0]
    return AgentStore(tmp_path / "agent.sqlite3", clock=lambda: clock[0]), clock


def command(snapshot, **changes):
    return {"request_id": str(uuid4()), "expected_revision": snapshot["revision"], **changes}


def running(store, body=None):
    task = store.create(body or payload())
    claim = store.claim(task["current_run_id"], "test-worker")
    return task, claim["lease_token"]


def assert_conflict(call, status=409):
    with pytest.raises(HTTPException) as error:
        call()
    assert error.value.status_code == status


def test_request_and_message_retries_keep_one_task(runtime):
    store, _ = runtime
    body = payload()
    first = store.create(body)
    assert store.create(copy.deepcopy(body)) == first
    assert store.create({**body, "request_id": str(uuid4())}) == first
    assert_conflict(lambda: store.create({**body, "goal": "不同目标"}))
    assert_conflict(lambda: store.create({**body, "request_id": str(uuid4()), "goal": "不同目标"}))
    assert len(store.events(first["agent_task_id"])["events"]) == 1


def test_concurrent_creation_has_one_durable_identity(runtime):
    store, _ = runtime
    body = payload()
    with ThreadPoolExecutor(max_workers=8) as workers:
        results = list(workers.map(lambda _: store.create(body), range(16)))
    assert len({x["agent_task_id"] for x in results}) == 1
    assert len({x["current_run_id"] for x in results}) == 1


def test_concurrent_fresh_store_initialization(tmp_path):
    from backend.agent.store import AgentStore
    database = tmp_path / "fresh.sqlite3"
    body = payload()
    with ThreadPoolExecutor(max_workers=8) as workers:
        results = list(workers.map(lambda _: AgentStore(database).create(body), range(16)))
    assert len({x["agent_task_id"] for x in results}) == 1


def test_claim_is_exclusive_and_expired_lease_is_fenced(runtime):
    store, clock = runtime
    task = store.create(payload())
    def attempt(worker):
        try:
            return store.claim(task["current_run_id"], worker)
        except HTTPException as error:
            assert error.status_code == 409
    with ThreadPoolExecutor(max_workers=8) as workers:
        claims = list(workers.map(attempt, map(str, range(8))))
    winner = next(x for x in claims if x)
    assert len([x for x in claims if x]) == 1
    clock[0] += 31
    replacement = store.claim(task["current_run_id"], "replacement")
    assert replacement["lease_token"] != winner["lease_token"]
    assert_conflict(lambda: store.reserve_step(task["current_run_id"], winner["lease_token"]))
    assert store.reserve_step(task["current_run_id"], replacement["lease_token"])["steps_used"] == 1


def test_revision_cancels_old_prepared_call_and_preserves_goal_constraints(runtime):
    store, _ = runtime
    task, lease = running(store)
    call = store.prepare_call(task["current_run_id"], lease, "builtin.plan_route", "1", {"stops": ["parcel", "print", "dorm"]})
    edit = command(task, goal="取快递后回宿舍，本次不去打印", constraints={"stops": ["parcel", "dorm"]}, success_criteria=["正式打印待办仍保留"])
    revised = store.revise(task["agent_task_id"], edit)
    assert revised["revision"] == 2
    assert revised["constraints"]["stops"] == ["parcel", "dorm"]
    assert revised["current_run_id"] != task["current_run_id"]
    assert store.revise(task["agent_task_id"], edit) == revised
    assert store.get_run(task["current_run_id"])["tool_calls"][0]["state"] == "cancelled"
    assert_conflict(lambda: store.dispatch_call(call["tool_call_id"], lease))
    assert_conflict(lambda: store.publish_artifact(task["current_run_id"], lease, "route", {"stops": ["print"]}))
    assert_conflict(lambda: store.revise(task["agent_task_id"], {**edit, "request_id": str(uuid4())}))


def test_late_result_is_evidence_only_after_revision(runtime):
    store, _ = runtime
    task, lease = running(store, payload("成都四天旅行", constraints={"days": 4, "budget": 3000}))
    prepared = store.prepare_call(task["current_run_id"], lease, "weather.forecast", "v1", {"city": "成都"})
    sent = store.dispatch_call(prepared["tool_call_id"], lease)
    revised = store.revise(task["agent_task_id"], command(task, goal="第二天轻松一些", constraints={"days": 4, "budget": 3000, "day2": "轻松"}))
    receipt = store.record_result(sent["tool_call_id"], sent["receipt_token"], "succeeded", result={"rain": True})
    assert receipt["state"] == "succeeded"
    current = store.get(task["agent_task_id"])
    assert current["revision"] == revised["revision"]
    assert current["run"]["state"] == "queued"
    assert current["artifacts"] == []
    assert current["tool_calls"] == []
    assert store.get_run(task["current_run_id"])["tool_calls"][0]["result"] == {"rain": True}


def test_unresolved_old_write_blocks_new_revision_until_receipt_is_checked(runtime):
    store, _ = runtime
    task, lease = running(store)
    prepared = store.prepare_call(task["current_run_id"], lease, "records.save", "1", {}, effect="write")
    store.approve_call(task["agent_task_id"], prepared["tool_call_id"], command(task))
    lease = store.claim(task["current_run_id"], "approved")["lease_token"]
    sent = store.dispatch_call(prepared["tool_call_id"], lease)
    revised = store.revise(task["agent_task_id"], command(task, goal="修改之前的记录", constraints={}))
    assert revised["unresolved_calls"][0]["tool_call_id"] == sent["tool_call_id"]
    assert_conflict(lambda: store.claim(revised["current_run_id"], "new-revision"))
    assert_conflict(lambda: store.resume(task["agent_task_id"], command(revised)))
    stored = store.get_call(sent["tool_call_id"], include_receipt=True)
    assert stored["receipt_token"] == sent["receipt_token"]
    assert "receipt_token" not in store.get_call(sent["tool_call_id"])
    store.record_result(sent["tool_call_id"], stored["receipt_token"], "succeeded", result={"receipt": "one"})
    resumed = store.resume(task["agent_task_id"], command(revised))
    assert resumed["run"]["state"] == "queued"
    assert resumed["unresolved_calls"] == []


def test_cancel_blocks_further_dispatch_but_keeps_inflight_receipt(runtime):
    store, _ = runtime
    task, lease = running(store)
    call = store.prepare_call(task["current_run_id"], lease, "records.save", "1", {}, effect="write")
    assert call["state"] == "waiting_approval"
    approve = command(task)
    store.approve_call(task["agent_task_id"], call["tool_call_id"], approve)
    lease = store.claim(task["current_run_id"], "approved-worker")["lease_token"]
    sent = store.dispatch_call(call["tool_call_id"], lease)
    cancel = command(task)
    cancelled = store.cancel(task["agent_task_id"], cancel)
    assert store.cancel(task["agent_task_id"], cancel) == cancelled
    assert cancelled["run"]["state"] == "cancelled"
    assert_conflict(lambda: store.prepare_call(task["current_run_id"], lease, "records.save", "1", {}))
    store.record_result(sent["tool_call_id"], sent["receipt_token"], "succeeded", result={"saved": True})
    assert store.get(task["agent_task_id"])["run"]["state"] == "cancelled"
    assert_conflict(lambda: store.resume(task["agent_task_id"], command(task)))


def test_expired_dispatched_write_is_unknown_and_never_redispatched(runtime):
    store, clock = runtime
    task, lease = running(store)
    prepared = store.prepare_call(task["current_run_id"], lease, "records.save", "1", {}, effect="write")
    store.approve_call(task["agent_task_id"], prepared["tool_call_id"], command(task))
    lease = store.claim(task["current_run_id"], "worker2")["lease_token"]
    sent = store.dispatch_call(prepared["tool_call_id"], lease)
    clock[0] += 31
    assert_conflict(lambda: store.claim(task["current_run_id"], "recovery"))
    recovered = store.get(task["agent_task_id"])
    assert recovered["run"]["state"] == "paused"
    assert recovered["tool_calls"][0]["state"] == "unknown"
    assert_conflict(lambda: store.resume(task["agent_task_id"], command(task)))
    store.record_result(sent["tool_call_id"], sent["receipt_token"], "succeeded", result={"receipt": "external-1"})
    resumed = store.resume(task["agent_task_id"], command(task))
    assert resumed["current_run_id"] == task["current_run_id"]
    assert resumed["run"]["tool_calls_used"] == 1
    next_lease = store.claim(task["current_run_id"], "recovery")["lease_token"]
    assert_conflict(lambda: store.dispatch_call(sent["tool_call_id"], next_lease))


def test_prepared_call_survives_worker_replacement_without_using_another_budget_slot(runtime):
    store, clock = runtime
    task, lease = running(store)
    call = store.prepare_call(task["current_run_id"], lease, "query.find", "1", {}, tool_call_id="stable-call")
    clock[0] += 31
    new = store.claim(task["current_run_id"], "recovery")["lease_token"]
    assert store.prepare_call(task["current_run_id"], new, "query.find", "1", {}, tool_call_id="stable-call") == call
    assert store.get(task["agent_task_id"])["run"]["tool_calls_used"] == 1
    assert store.dispatch_call(call["tool_call_id"], new)["state"] == "dispatched"


def test_results_are_receipt_bound_and_terminal_immutable(runtime):
    store, _ = runtime
    task, lease = running(store)
    prepared = store.prepare_call(task["current_run_id"], lease, "query.find", "1", {})
    call = store.dispatch_call(prepared["tool_call_id"], lease)
    assert_conflict(lambda: store.record_result(call["tool_call_id"], "wrong", "succeeded", result={}))
    result = store.record_result(call["tool_call_id"], call["receipt_token"], "succeeded", result={"found": 1})
    assert store.record_result(call["tool_call_id"], call["receipt_token"], "succeeded", result={"found": 1}) == result
    assert_conflict(lambda: store.record_result(call["tool_call_id"], call["receipt_token"], "failed", error="late failure"))
    assert_conflict(lambda: store.record_result(call["tool_call_id"], call["receipt_token"], "succeeded", result={"found": 2}))
    public = json.dumps(store.get(task["agent_task_id"]))
    assert call["receipt_token"] not in public
    assert lease not in public


def test_large_observation_history_does_not_prevent_user_cancellation(runtime):
    store, _ = runtime
    task, lease = running(store)
    for index in range(2):
        prepared = store.prepare_call(task["current_run_id"], lease, "query.large", "1", {"page": index})
        sent = store.dispatch_call(prepared["tool_call_id"], lease)
        store.record_result(sent["tool_call_id"], sent["receipt_token"], "succeeded", result={"text": "x" * 300000})
    cancel = command(task)
    cancelled = store.cancel(task["agent_task_id"], cancel)
    assert cancelled["run"]["state"] == "cancelled"
    assert store.cancel(task["agent_task_id"], cancel) == cancelled


def test_heartbeat_extends_only_the_current_lease(runtime):
    store, clock = runtime
    task, lease = running(store)
    clock[0] += 20
    assert store.heartbeat(task["current_run_id"], lease)["lease_until"] == 1050.0
    clock[0] += 15
    assert_conflict(lambda: store.claim(task["current_run_id"], "competitor"))
    store.cancel(task["agent_task_id"], command(task))
    assert_conflict(lambda: store.heartbeat(task["current_run_id"], lease))


def test_approval_is_bound_to_call_task_and_revision(runtime):
    store, _ = runtime
    task, lease = running(store)
    call = store.prepare_call(task["current_run_id"], lease, "records.save", "1", {}, effect="write")
    assert_conflict(lambda: store.dispatch_call(call["tool_call_id"], lease))
    other = store.create(payload())
    assert_conflict(lambda: store.approve_call(other["agent_task_id"], call["tool_call_id"], command(other)))
    store.revise(task["agent_task_id"], command(task, goal="不要保存", constraints={}))
    assert_conflict(lambda: store.approve_call(task["agent_task_id"], call["tool_call_id"], command(task)))


@pytest.mark.parametrize("budget,operation,used", [
    ({"max_steps": 1}, "step", "steps_used"),
    ({"max_tool_calls": 1}, "call", "tool_calls_used"),
    ({"max_duration_seconds": 1}, "duration", "steps_used"),
])
def test_budget_exhaustion_persists_and_resume_does_not_reset(runtime, budget, operation, used):
    store, clock = runtime
    task, lease = running(store, payload(budget=budget))
    if operation == "call":
        act = lambda: store.prepare_call(task["current_run_id"], lease, "query.find", "1", {})
    else:
        act = lambda: store.reserve_step(task["current_run_id"], lease)
    if operation == "duration":
        clock[0] += 2
    else:
        act()
    assert_conflict(act)
    result = store.get(task["agent_task_id"])
    assert result["run"]["state"] == "paused"
    assert result["run"]["reason"].startswith("budget_")
    assert_conflict(lambda: store.resume(task["agent_task_id"], command(task)))


def test_user_wait_across_days_does_not_spend_active_duration(runtime):
    store, clock = runtime
    task, lease = running(store)
    store.reserve_step(task["current_run_id"], lease)
    clock[0] += 5
    store.transition(task["current_run_id"], lease, "waiting_user", reason="请补充出发地")
    clock[0] += 86401
    resumed = store.resume(task["agent_task_id"], command(task, reason="从北京出发"))
    assert resumed["run"]["steps_used"] == 1
    assert resumed["run"]["active_elapsed"] == 5
    assert resumed["run"]["active_since"] is None
    lease = store.claim(task["current_run_id"], "next-day")["lease_token"]
    clock[0] += 3
    store.reserve_step(task["current_run_id"], lease)
    assert store.get(task["agent_task_id"])["run"]["active_used_seconds"] == 8


def test_approval_wait_does_not_spend_duration_or_reset_call_budget(runtime):
    store, clock = runtime
    task, lease = running(store)
    store.reserve_step(task["current_run_id"], lease)
    clock[0] += 7
    call = store.prepare_call(task["current_run_id"], lease, "records.save", "1", {}, effect="write")
    clock[0] += 301
    approved = store.approve_call(task["agent_task_id"], call["tool_call_id"], command(task))
    assert approved["run"]["active_elapsed"] == 7
    assert approved["run"]["tool_calls_used"] == 1
    assert approved["run"]["steps_used"] == 1
    lease = store.claim(task["current_run_id"], "approved")["lease_token"]
    sent = store.dispatch_call(call["tool_call_id"], lease)
    clock[0] += 4
    store.record_result(sent["tool_call_id"], sent["receipt_token"], "succeeded", result={"saved": True})
    assert store.get(task["agent_task_id"])["run"]["active_used_seconds"] == 11


def test_active_budget_accumulates_through_pauses_and_store_reopen(runtime):
    from backend.agent.store import AgentStore
    store, clock = runtime
    task, lease = running(store, payload(budget={"max_duration_seconds": 10}))
    clock[0] += 4
    store.transition(task["current_run_id"], lease, "paused", reason="provider_error")
    clock[0] += 1000
    reopened = AgentStore(store.path, clock=lambda: clock[0])
    resumed = reopened.resume(task["agent_task_id"], command(task))
    assert resumed["run"]["active_elapsed"] == 4
    lease = reopened.claim(task["current_run_id"], "after-reopen")["lease_token"]
    clock[0] += 3
    reopened.transition(task["current_run_id"], lease, "paused", reason="provider_error")
    clock[0] += 1000
    reopened.resume(task["agent_task_id"], command(task))
    lease = reopened.claim(task["current_run_id"], "third")["lease_token"]
    clock[0] += 3
    assert_conflict(lambda: reopened.reserve_step(task["current_run_id"], lease))
    stopped = reopened.get(task["agent_task_id"])["run"]
    assert stopped["state"] == "paused" and stopped["reason"] == "budget_duration"
    assert stopped["active_elapsed"] == 10 and stopped["active_since"] is None
    assert_conflict(lambda: reopened.resume(task["agent_task_id"], command(task)))


def test_expired_worker_charges_only_its_lease_and_never_resets_budget(runtime):
    from backend.agent.store import AgentStore
    store, clock = runtime
    task = store.create(payload(budget={"max_duration_seconds": 10}))
    first = store.claim(task["current_run_id"], "crashed", lease_seconds=4)
    clock[0] += 1000
    reopened = AgentStore(store.path, clock=lambda: clock[0])
    replacement = reopened.claim(task["current_run_id"], "replacement", lease_seconds=4)
    assert replacement["active_elapsed"] == 4
    assert_conflict(lambda: reopened.reserve_step(task["current_run_id"], first["lease_token"]))
    clock[0] += 1000
    third = reopened.claim(task["current_run_id"], "third", lease_seconds=4)
    assert third["active_elapsed"] == 8
    clock[0] += 2
    assert_conflict(lambda: reopened.heartbeat(task["current_run_id"], third["lease_token"]))
    assert reopened.get(task["agent_task_id"])["run"]["active_elapsed"] == 10


def test_unknown_call_reconciliation_wait_preserves_active_time(runtime):
    store, clock = runtime
    task = store.create(payload(budget={"max_duration_seconds": 10}))
    lease = store.claim(task["current_run_id"], "worker", lease_seconds=4)["lease_token"]
    call = store.prepare_call(task["current_run_id"], lease, "query.find", "1", {})
    sent = store.dispatch_call(call["tool_call_id"], lease)
    clock[0] += 1000
    assert_conflict(lambda: store.claim(task["current_run_id"], "recovery"))
    unknown = store.get(task["agent_task_id"])
    assert unknown["run"]["active_elapsed"] == 4
    assert unknown["tool_calls"][0]["state"] == "unknown"
    clock[0] += 1000
    assert_conflict(lambda: store.resume(task["agent_task_id"], command(task)))
    store.record_result(sent["tool_call_id"], sent["receipt_token"], "succeeded", result={"found": 1})
    resumed = store.resume(task["agent_task_id"], command(task))
    assert resumed["run"]["active_elapsed"] == 4
    assert resumed["run"]["tool_calls_used"] == 1


def test_version_one_database_migrates_waiting_and_active_time(runtime):
    from backend.agent.store import AgentStore
    store, clock = runtime
    task, lease = running(store)
    clock[0] += 5
    store.transition(task["current_run_id"], lease, "waiting_user", reason="请补充日期")
    active = store.create(payload())
    store.claim(active["current_run_id"], "crashed", lease_seconds=10)
    with sqlite3.connect(store.path) as db:
        columns = {row[1] for row in db.execute("PRAGMA table_info(agent_runs)")}
        for name in ("active_elapsed", "active_since"):
            if name in columns:
                db.execute(f"ALTER TABLE agent_runs DROP COLUMN {name}")
        db.execute("PRAGMA user_version=1")
    clock[0] += 1000
    migrated = AgentStore(store.path, clock=lambda: clock[0])
    resumed = migrated.resume(task["agent_task_id"], command(task, reason="明天出发"))
    assert resumed["run"]["active_elapsed"] == 5
    assert migrated.get(active["agent_task_id"])["run"]["active_used_seconds"] == 10
    with sqlite3.connect(store.path) as db:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 3
        db.execute("PRAGMA user_version=999")
    assert_conflict(lambda: AgentStore(store.path, clock=lambda: clock[0]))


def test_migration_recovers_approval_paused_by_old_wall_clock_budget(runtime):
    from backend.agent.store import AgentStore
    store, clock = runtime
    task, lease = running(store)
    clock[0] += 7
    call = store.prepare_call(task["current_run_id"], lease, "records.save", "1", {}, effect="write")
    clock[0] += 301
    with sqlite3.connect(store.path) as db:
        # v1 incorrectly changed the waiting run to paused when approval arrived.
        db.execute("UPDATE agent_runs SET state='paused',reason='budget_duration',updated_at=?", (clock[0],))
        db.execute("INSERT INTO agent_events(agent_task_id,agent_run_id,revision,kind,payload_json,created_at) VALUES(?,?,1,'run_paused',?,?)",
                   (task["agent_task_id"], task["current_run_id"], json.dumps({"reason": "budget_duration"}), clock[0]))
        db.execute("ALTER TABLE agent_runs DROP COLUMN active_elapsed")
        db.execute("ALTER TABLE agent_runs DROP COLUMN active_since")
        db.execute("PRAGMA user_version=1")
    migrated = AgentStore(store.path, clock=lambda: clock[0])
    approved = migrated.approve_call(task["agent_task_id"], call["tool_call_id"], command(task))
    assert approved["run"]["state"] == "queued"
    assert approved["run"]["active_elapsed"] == 7
    assert approved["tool_calls"][0]["state"] == "prepared"


def test_completion_requires_current_artifact_and_no_pending_calls(runtime):
    store, _ = runtime
    task, lease = running(store)
    assert_conflict(lambda: store.transition(task["current_run_id"], lease, "completed", reason="done"))
    artifact = store.publish_artifact(task["current_run_id"], lease, "route", {"stops": ["parcel", "dorm"]}, sources=[{"source": "map", "observed_at": "2026-11-08"}])
    revised_artifact = store.publish_artifact(task["current_run_id"], lease, "route", {"stops": ["parcel", "dorm"], "minutes": 20}, artifact_id=artifact["artifact_id"])
    assert revised_artifact["version"] == 2
    assert store.transition(task["current_run_id"], lease, "completed", reason="已生成路线，尚未导航")["state"] == "completed"
    assert_conflict(lambda: store.reserve_step(task["current_run_id"], lease))
    assert_conflict(lambda: store.transition(task["current_run_id"], lease, "failed", reason="late"))


def test_revision_exposes_previous_artifact_as_reference_only(runtime):
    store, _ = runtime
    task, lease = running(store, payload("成都四天旅行", constraints={"days": 4, "budget": 3000}))
    first = store.publish_artifact(task["current_run_id"], lease, "itinerary", {"day1": "博物馆", "day2": "两处景点"})
    latest = store.publish_artifact(task["current_run_id"], lease, "itinerary", {"day1": "博物馆", "day2": "三处景点"}, artifact_id=first["artifact_id"])
    revised = store.revise(task["agent_task_id"], command(task, goal="第二天只安排一处，第一天保持", constraints={"days": 4, "budget": 3000, "day2": "一处景点"}))
    assert revised["artifacts"] == []
    assert len(revised["prior_artifacts"]) == 1
    prior = revised["prior_artifacts"][0]
    assert prior["artifact_id"] == latest["artifact_id"] and prior["version"] == 2
    assert prior["revision"] == 1 and prior["reference_only"] is True
    assert prior["content"]["day1"] == "博物馆"
    assert store.get_run(task["current_run_id"])["goal"] == "成都四天旅行"
    fetched = store.get_artifact(first["artifact_id"], version=1)
    assert fetched["version"] == 1 and fetched["agent_task_id"] == task["agent_task_id"]
    assert fetched["revision"] == 1
    assert store.get_artifact(first["artifact_id"])["version"] == 2
    assert_conflict(lambda: store.get_artifact(first["artifact_id"], version=99), 404)


def test_artifact_expected_version_appends_without_rewriting_history(runtime):
    store, _ = runtime
    task, lease = running(store)
    first = store.publish_artifact(task["current_run_id"], lease, "route", {"stops": ["parcel", "print", "dorm"]})
    updated = store.publish_artifact(task["current_run_id"], lease, "route", {"stops": ["parcel", "dorm"]},
                                     artifact_id=first["artifact_id"], expected_version=1)
    assert updated["artifact_id"] == first["artifact_id"] and updated["version"] == 2
    assert store.get_artifact(first["artifact_id"], 1)["content"] == first["content"]
    assert store.get(task["agent_task_id"])["artifacts"] == [updated]


def test_artifact_expected_version_conflict_preserves_content_and_events(runtime):
    store, _ = runtime
    task, lease = running(store)
    first = store.publish_artifact(task["current_run_id"], lease, "text", {"text": "original"})
    latest = store.publish_artifact(task["current_run_id"], lease, "text", {"text": "accepted"},
                                    artifact_id=first["artifact_id"])
    events = store.events(task["agent_task_id"])
    assert_conflict(lambda: store.publish_artifact(task["current_run_id"], lease, "text", {"text": "stale"},
                                                 artifact_id=first["artifact_id"], expected_version=1))
    assert store.get_artifact(first["artifact_id"])["content"] == latest["content"]
    assert store.get_artifact(first["artifact_id"], 1)["content"] == first["content"]
    assert store.events(task["agent_task_id"]) == events


def test_artifact_expected_version_requires_existing_identity(runtime):
    store, _ = runtime
    task, lease = running(store)
    events = store.events(task["agent_task_id"])
    assert_conflict(lambda: store.publish_artifact(task["current_run_id"], lease, "text", {"text": "new"},
                                                 expected_version=1), 422)
    assert_conflict(lambda: store.publish_artifact(task["current_run_id"], lease, "text", {"text": "new"},
                                                 artifact_id=str(uuid4()), expected_version=1))
    assert store.get(task["agent_task_id"])["artifacts"] == []
    assert store.events(task["agent_task_id"]) == events


@pytest.mark.parametrize("version", [True, 0, -1, "1", 1.5])
def test_artifact_expected_version_rejects_invalid_values(runtime, version):
    store, _ = runtime
    task, lease = running(store)
    first = store.publish_artifact(task["current_run_id"], lease, "text", {"text": "original"})
    events = store.events(task["agent_task_id"])
    assert_conflict(lambda: store.publish_artifact(task["current_run_id"], lease, "text", {"text": "invalid"},
                                                 artifact_id=first["artifact_id"], expected_version=version), 422)
    assert store.get(task["agent_task_id"])["artifacts"] == [first]
    assert store.events(task["agent_task_id"]) == events


def test_artifact_expected_version_keeps_run_and_lease_fences(runtime):
    store, _ = runtime
    task, lease = running(store)
    first = store.publish_artifact(task["current_run_id"], lease, "text", {"text": "original"})
    current = store.revise(task["agent_task_id"], command(task, goal="only revise day two"))
    current_lease = store.claim(current["current_run_id"], "current-worker")["lease_token"]
    other, other_lease = running(store)
    original_events = store.events(task["agent_task_id"])
    other_events = store.events(other["agent_task_id"])
    for run_id, token in ((task["current_run_id"], lease), (current["current_run_id"], current_lease),
                          (other["current_run_id"], other_lease)):
        assert_conflict(lambda: store.publish_artifact(run_id, token, "text", {"text": "invalid"},
                                                     artifact_id=first["artifact_id"], expected_version=1))
    assert store.get_artifact(first["artifact_id"])["content"] == first["content"]
    assert store.get(task["agent_task_id"])["artifacts"] == []
    assert store.get(other["agent_task_id"])["artifacts"] == []
    assert store.events(task["agent_task_id"]) == original_events
    assert store.events(other["agent_task_id"]) == other_events


def test_artifact_expected_version_allows_one_concurrent_update(runtime):
    store, _ = runtime
    task, lease = running(store)
    first = store.publish_artifact(task["current_run_id"], lease, "text", {"text": "original"})
    barrier = Barrier(2)

    def update(text):
        barrier.wait(timeout=10)
        try:
            return store.publish_artifact(task["current_run_id"], lease, "text", {"text": text},
                                          artifact_id=first["artifact_id"], expected_version=1)
        except HTTPException as error:
            assert error.status_code == 409
            return None

    with ThreadPoolExecutor(max_workers=2) as workers:
        results = list(workers.map(update, ["candidate A", "candidate B"]))
    winners = [item for item in results if item is not None]
    assert len(winners) == 1 and winners[0]["version"] == 2
    assert store.get(task["agent_task_id"])["artifacts"] == winners
    assert store.get_artifact(first["artifact_id"], 1)["content"] == first["content"]
    assert_conflict(lambda: store.get_artifact(first["artifact_id"], 3), 404)
    published = [event for event in store.events(task["agent_task_id"])["events"] if event["kind"] == "artifact_published"]
    assert [event["payload"]["version"] for event in published] == [1, 2]


def test_artifact_cannot_publish_while_call_unresolved(runtime):
    store, _ = runtime
    task, lease = running(store)
    store.prepare_call(task["current_run_id"], lease, "query.find", "1", {})
    assert_conflict(lambda: store.publish_artifact(task["current_run_id"], lease, "text", {"text": "all done"}))


def test_rejected_prepared_call_is_terminal_without_a_dispatch_receipt(runtime):
    store, _ = runtime
    task, lease = running(store)
    prepared = store.prepare_call(task["current_run_id"], lease, "query.removed", "old", {})
    rejected = store.reject_prepared(prepared["tool_call_id"], lease, {"code": "tool_unavailable"})
    assert rejected["state"] == "failed"
    assert rejected["error"] == {"code": "tool_unavailable"}
    assert store.get_call(prepared["tool_call_id"], include_receipt=True)["receipt_token"] is None
    assert store.get(task["agent_task_id"])["run"]["tool_calls_used"] == 1
    assert store.reserve_step(task["current_run_id"], lease)["steps_used"] == 1
    assert_conflict(lambda: store.dispatch_call(prepared["tool_call_id"], lease))
    next_call = store.prepare_call(task["current_run_id"], lease, "query.new", "new", {})
    dispatched = store.dispatch_call(next_call["tool_call_id"], lease)
    assert_conflict(lambda: store.reject_prepared(dispatched["tool_call_id"], lease, {"code": "late"}))


def test_waiting_user_can_resume_same_run_with_idempotent_request(runtime):
    store, _ = runtime
    task, lease = running(store)
    store.reserve_step(task["current_run_id"], lease)
    store.transition(task["current_run_id"], lease, "waiting_user", reason="请补充出发地")
    resume = command(task, reason="从北京出发")
    result = store.resume(task["agent_task_id"], resume)
    assert result["run"]["state"] == "queued"
    assert result["current_run_id"] == task["current_run_id"]
    assert result["run"]["steps_used"] == 1
    assert store.resume(task["agent_task_id"], resume) == result
    assert result["observations"][-1]["kind"] == "user_input"
    assert result["observations"][-1]["payload"]["text"] == "从北京出发"


def test_observations_and_queue_do_not_claim_waiting_tasks(runtime):
    store, clock = runtime
    queued = store.create(payload())
    task, lease = running(store)
    assert store.ready_runs() == [queued["current_run_id"]]
    observation = store.record_observation(task["current_run_id"], lease, "tool_error", {"code": "unavailable"})
    assert store.get(task["agent_task_id"])["observations"][-1] == observation
    store.transition(task["current_run_id"], lease, "waiting_user", reason="请补充日期")
    clock[0] += 100
    assert store.ready_runs() == [queued["current_run_id"]]
    assert {x["agent_task_id"] for x in store.list_tasks()} == {queued["agent_task_id"], task["agent_task_id"]}


def test_user_inputs_survive_more_than_one_hundred_later_observations(runtime):
    store, _ = runtime
    task, lease = running(store)
    store.transition(task["current_run_id"], lease, "waiting_user", reason="请补充出发地")
    store.resume(task["agent_task_id"], command(task, reason="从北京出发，不乘飞机"))
    lease = store.claim(task["current_run_id"], "continued")["lease_token"]
    for index in range(105):
        if index == 50:
            store.record_observation(task["current_run_id"], lease, "user_input", {"text": "还要保留最后一天上午的空闲时间"})
        store.record_observation(task["current_run_id"], lease, "tool_error", {"attempt": index})
    observations = store.get(task["agent_task_id"])["observations"]
    assert [x["payload"]["text"] for x in observations if x["kind"] == "user_input"] == [
        "从北京出发，不乘飞机", "还要保留最后一天上午的空闲时间"]
    assert [x["payload"]["attempt"] for x in observations if x["kind"] == "tool_error"] == list(range(5, 105))
    assert [x["cursor"] for x in observations] == sorted(x["cursor"] for x in observations)


def test_events_are_ordered_task_scoped_and_cursor_bounded(runtime):
    store, _ = runtime
    task, lease = running(store)
    store.reserve_step(task["current_run_id"], lease)
    store.create(payload())
    first = store.events(task["agent_task_id"], limit=2)
    assert len(first["events"]) == 2 and first["has_more"]
    second = store.events(task["agent_task_id"], after=first["next_cursor"], limit=2)
    assert second["events"] and not second["has_more"]
    assert min(e["cursor"] for e in second["events"]) > first["next_cursor"]
    assert all(e["agent_task_id"] == task["agent_task_id"] for e in first["events"] + second["events"])
    assert_conflict(lambda: store.events(task["agent_task_id"], limit=1001), 422)


def test_real_process_exit_keeps_dispatched_call_and_identity(tmp_path):
    assert importlib.util.find_spec("backend.agent.store") is not None, "A1 durable task store is missing"
    from backend.agent.store import AgentStore
    database = tmp_path / "process.sqlite3"
    result_file = tmp_path / "identity.json"
    script = """
import json, os, sys
from backend.agent.store import AgentStore
s=AgentStore(sys.argv[1], clock=lambda: 1000.0)
t=s.create(json.loads(sys.argv[3]))
l=s.claim(t['current_run_id'],'child')['lease_token']
c=s.prepare_call(t['current_run_id'],l,'query.lookup','1',{})
c=s.dispatch_call(c['tool_call_id'],l)
with open(sys.argv[2],'w',encoding='utf-8') as f:
    json.dump({'task':t,'call':c},f)
os._exit(23)
"""
    child = subprocess.run([sys.executable, "-X", "utf8", "-c", script, str(database), str(result_file), json.dumps(payload())], cwd=PROJECT_ROOT, capture_output=True, text=True)
    assert child.returncode == 23, child.stderr
    original = json.loads(result_file.read_text(encoding="utf-8"))
    store = AgentStore(database, clock=lambda: 1100.0)
    task = store.get(original["task"]["agent_task_id"])
    assert task["current_run_id"] == original["task"]["current_run_id"]
    assert task["tool_calls"][0]["tool_call_id"] == original["call"]["tool_call_id"]
    assert task["tool_calls"][0]["state"] == "dispatched"
    assert_conflict(lambda: store.claim(task["current_run_id"], "parent"))
    assert store.get(task["agent_task_id"])["tool_calls"][0]["state"] == "unknown"


def test_multiple_processes_initialize_and_create_one_task(tmp_path):
    database = tmp_path / "concurrent.sqlite3"
    script = """
import json, sys
from backend.agent.store import AgentStore
sys.stdin.readline()
print(json.dumps(AgentStore(sys.argv[1]).create(json.loads(sys.argv[2]))))
"""
    args = [sys.executable, "-X", "utf8", "-c", script, str(database), json.dumps(payload())]
    children = [subprocess.Popen(args, cwd=PROJECT_ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8") for _ in range(4)]
    try:
        for child in children:
            child.stdin.write("start\n")
            child.stdin.flush()
        results = []
        for child in children:
            stdout, stderr = child.communicate(timeout=30)
            assert child.returncode == 0, stderr
            results.append(json.loads(stdout))
        assert len({x["agent_task_id"] for x in results}) == 1
        assert len({x["current_run_id"] for x in results}) == 1
    finally:
        for child in children:
            if child.poll() is None:
                child.kill()
                child.wait(timeout=5)


@pytest.mark.parametrize("change", [{"goal": ""}, {"constraints": []}, {"budget": {"max_steps": True}}, {"success_criteria": "bad"}])
def test_invalid_payload_does_not_create_data(runtime, change):
    store, _ = runtime
    assert_conflict(lambda: store.create(payload(**change)), 422)


def evidence_note(identity, quote="周一闭馆", **changes):
    return {"id": identity, "quote": quote, "source_call_id": "source-" + identity,
            "observed_at": "2026-11-08T10:00:00+00:00", "source_revision": 1, **changes}


def test_evidence_notes_survive_observation_window_reopen_and_task_isolation(runtime):
    from backend.agent.store import AgentStore
    store, clock = runtime
    task, lease = running(store)
    assert task["working_notes"] == []
    note = evidence_note("opening")
    saved = store.update_evidence_notes(task["current_run_id"], lease, [note], [])
    assert saved == [{**note, "revision": 1, "reference_only": False}]
    for index in range(105):
        store.record_observation(task["current_run_id"], lease, "tool_error", {"attempt": index})
    reopened = AgentStore(store.path, clock=lambda: clock[0])
    snapshot = reopened.get(task["agent_task_id"])
    assert snapshot["working_notes"] == saved
    assert len(snapshot["observations"]) == 100
    assert all(item["kind"] != "evidence_notes" for item in snapshot["observations"])
    assert reopened.create(payload())["working_notes"] == []
    events = reopened.events(task["agent_task_id"], limit=1000)["events"]
    assert [item["payload"] for item in events if item["kind"] == "observation:evidence_notes"] == [
        [{**note, "revision": 1}]]
    reopened.update_evidence_notes(task["current_run_id"], lease, [], ["opening"])
    assert AgentStore(store.path).get(task["agent_task_id"])["working_notes"] == []


def test_evidence_notes_preserve_source_age_across_revision_and_upsert(runtime):
    store, _ = runtime
    task, lease = running(store)
    original = evidence_note("opening")
    other = evidence_note("cost", quote="门票免费")
    store.update_evidence_notes(task["current_run_id"], lease, [original, other], [])
    revised = store.revise(task["agent_task_id"], command(task, goal="只改第二天", constraints={}))
    assert revised["working_notes"] == [
        {**note, "revision": 1, "reference_only": True} for note in (original, other)]
    new_lease = store.claim(revised["current_run_id"], "new-revision")["lease_token"]
    refreshed = {**original, "quote": "周一闭馆，法定节假日除外", "revision": 999, "reference_only": True}
    saved = store.update_evidence_notes(revised["current_run_id"], new_lease, [refreshed], [])
    assert saved[0] == {**refreshed, "revision": 2, "reference_only": False}
    assert saved[0]["source_revision"] == 1
    assert saved[0]["observed_at"] == original["observed_at"]
    assert saved[1] == {**other, "revision": 1, "reference_only": True}
    old_view = store.get_run(task["current_run_id"])["working_notes"]
    assert old_view[0]["reference_only"] is True and old_view[1]["reference_only"] is False


def test_evidence_notes_count_limit_checks_final_atomic_state(runtime):
    store, _ = runtime
    task, lease = running(store)
    notes = [evidence_note(str(index)) for index in range(8)]
    before = store.update_evidence_notes(task["current_run_id"], lease, notes, [])
    events = store.events(task["agent_task_id"])
    assert_conflict(lambda: store.update_evidence_notes(task["current_run_id"], lease,
        [evidence_note("new-a"), evidence_note("new-b")], ["0"]), 422)
    assert store.get(task["agent_task_id"])["working_notes"] == before
    assert store.events(task["agent_task_id"]) == events
    changed = store.update_evidence_notes(task["current_run_id"], lease, [evidence_note("new-a")], ["0"])
    assert len(changed) == 8 and {item["id"] for item in changed} == {str(i) for i in range(1, 8)} | {"new-a"}


def test_evidence_notes_utf8_limit_includes_all_stored_metadata(runtime):
    store, _ = runtime
    task, lease = running(store)
    note = evidence_note("boundary", quote="", metadata={"provider": "官方来源"})
    encoded = lambda value: json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    remaining = 2400 - len(encoded([{**note, "revision": 1}]))
    note["quote"] = "证" * (remaining // 3) + "x" * (remaining % 3)
    assert len(encoded([{**note, "revision": 1}])) == 2400
    before = store.update_evidence_notes(task["current_run_id"], lease, [note], [])
    events = store.events(task["agent_task_id"])
    too_large = {**note, "quote": note["quote"] + "x"}
    assert_conflict(lambda: store.update_evidence_notes(task["current_run_id"], lease, [too_large], []), 422)
    assert store.get(task["agent_task_id"])["working_notes"] == before
    assert store.events(task["agent_task_id"]) == events


@pytest.mark.parametrize("upserts,delete_ids", [
    ([evidence_note("a"), evidence_note("a", quote="其他内容")], []),
    ([evidence_note("a")], ["a"]),
    ([], ["a", "a"]),
])
def test_evidence_notes_reject_ambiguous_batch_without_partial_delete(runtime, upserts, delete_ids):
    store, _ = runtime
    task, lease = running(store)
    before = store.update_evidence_notes(task["current_run_id"], lease, [evidence_note("a")], [])
    events = store.events(task["agent_task_id"])
    assert_conflict(lambda: store.update_evidence_notes(task["current_run_id"], lease, upserts, delete_ids), 422)
    assert store.get(task["agent_task_id"])["working_notes"] == before
    assert store.events(task["agent_task_id"]) == events


def test_evidence_notes_fence_expired_lease_and_superseded_revision(runtime):
    store, clock = runtime
    task, lease = running(store)
    before = store.update_evidence_notes(task["current_run_id"], lease, [evidence_note("a")], [])
    clock[0] += 31
    assert_conflict(lambda: store.update_evidence_notes(task["current_run_id"], lease, [], ["a"]))
    new_lease = store.claim(task["current_run_id"], "replacement")["lease_token"]
    assert_conflict(lambda: store.update_evidence_notes(task["current_run_id"], lease, [], ["a"]))
    assert store.get(task["agent_task_id"])["working_notes"] == before
    revised = store.revise(task["agent_task_id"], command(task, goal="新要求", constraints={}))
    expected = revised["working_notes"]
    events = store.events(task["agent_task_id"])
    assert_conflict(lambda: store.update_evidence_notes(task["current_run_id"], new_lease, [], ["a"]))
    assert store.get(task["agent_task_id"])["working_notes"] == expected
    assert store.events(task["agent_task_id"]) == events
