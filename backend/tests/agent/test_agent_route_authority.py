"""Agent route revisions fence the production confirmation and H2 start APIs."""
from copy import deepcopy
from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor
import inspect
from threading import Event
from uuid import uuid4

import pytest

from backend import main
from backend.agent.builtin import build_executor, register_builtin
from backend.agent.store import AgentStore
from backend.agent.tools import ToolCatalog
from backend.tests.agent.test_agent_builtin import runtime as business_runtime
from backend.tests.execution.test_execution_runtime import invocation, prepare_route, submit
from backend.tests.data_services.test_tasks import TOKEN


@pytest.fixture
def routes(tmp_path, business_runtime):
    client, head, maps = business_runtime
    parameters = prepare_route(client, head)["parameters"]
    store = AgentStore(tmp_path / "agent.sqlite3")
    current = store.create({"request_id": str(uuid4()), "conversation_id": "route-authority",
                            "message_id": str(uuid4()), "goal": "取快递 + 打印论文 + 回宿舍"})
    catalog = ToolCatalog()
    register_builtin(catalog, main.app, TOKEN["X-Backup-Token"])
    # Run the regression against the pre-fix API as well, so RED exercises the
    # missing business fence rather than failing at a newly added parameter.
    options = {"store": store} if "store" in inspect.signature(build_executor).parameters else {}
    execute = build_executor(main.app, TOKEN["X-Backup-Token"], **options)
    return client, store, current, catalog, execute, parameters, maps


def plan(routes, current=None, parameters=None):
    client, store, first, catalog, execute, original, maps = routes
    current = current or first
    run_id = current["current_run_id"]
    claimed = store.claim(run_id, "route-test")
    token = claimed["lease_token"]
    identity = str(uuid4())
    call = store.prepare_call(run_id, token, "onmyway.plan_route", catalog.describe("onmyway.plan_route")["version"],
                              parameters or original, tool_call_id=identity, legacy_run_id=identity)
    dispatched = store.dispatch_call(call["tool_call_id"], token)
    result = execute(dispatched, catalog)
    store.record_result(identity, dispatched["receipt_token"], "succeeded", result=result)
    return result["data"], token


def receipt(result, parameters, **changes):
    return {"receipt_id": str(uuid4()), "subject_type": "plan", "subject_id": result["legacy_run_id"],
            "subject_version": 1, "correlation_id": result["correlation_id"],
            "confirmed_at": datetime.now(timezone.utc).isoformat(),
            "snapshot": {"route_session_id": str(uuid4()), "selected_task_ids": list(parameters["task_versions"]),
                         "task_versions": parameters["task_versions"], "store_id": parameters["store_id"]}, **changes}


def navigation(received):
    return invocation("start_navigation", {"store_id": received["snapshot"]["store_id"],
        "task_versions": received["snapshot"]["task_versions"], "plan_id": received["subject_id"],
        "plan_version": received["subject_version"], "route_session_id": received["snapshot"]["route_session_id"],
        "confirmation_receipt_id": received["receipt_id"]}, correlation_id=received["correlation_id"])


def revise(routes):
    _, store, current, _, _, parameters, _ = routes
    reduced = deepcopy(parameters)
    reduced["task_versions"].pop("print")
    reduced["request"]["tasks"] = [item for item in reduced["request"]["tasks"] if item["id"] != "print"]
    updated = store.revise(current["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1,
        "goal": "本次不去打印，只取快递后回宿舍；保留正式打印待办"})
    return updated, reduced


def test_revised_agent_route_rejects_old_confirmation_without_changing_todos(routes):
    client, store, current, _, _, parameters, _ = routes
    original = client.get("/tasks", headers=TOKEN).json()
    old, _ = plan(routes)
    updated, reduced = revise(routes)
    assert client.post("/confirmations", headers=TOKEN, json=receipt(old, parameters)).status_code == 409
    new, _ = plan(routes, updated, reduced)
    assert new["plan_id"] == new["legacy_run_id"] and new["plan_version"] == 1
    assert client.post("/confirmations", headers=TOKEN, json=receipt(new, reduced)).status_code == 200
    assert client.get("/tasks", headers=TOKEN).json() == original
    assert {identity for stop in new["plan"]["stops"] for identity in stop["task_ids"]} == {"parcel"}
    assert new["plan"]["destination"]["name"] == "宿舍"


@pytest.mark.parametrize("waiting", [False, True])
def test_existing_receipt_cannot_be_consumed_after_revision(routes, waiting):
    client, _, _, _, _, parameters, _ = routes
    old, _ = plan(routes)
    accepted = receipt(old, parameters)
    assert client.post("/confirmations", headers=TOKEN, json=accepted).status_code == 200
    body = navigation(accepted)
    if waiting:
        assert submit(client, body).json()["state"] == "waiting_confirmation"
    revise(routes)
    response = (client.post(f'/executions/{body["run_id"]}/confirm', headers=TOKEN,
                           json={"confirmation_id": str(uuid4())}) if waiting else submit(client, body))
    assert response.status_code == 409
    assert client.post("/confirmations", headers=TOKEN, json=accepted).status_code == 409


def test_later_call_in_same_revision_supersedes_old_route(routes):
    client, store, current, catalog, execute, parameters, _ = routes
    old, token = plan(routes)
    old_receipt = receipt(old, parameters)
    assert client.post("/confirmations", headers=TOKEN, json=old_receipt).status_code == 200
    newer = deepcopy(parameters)
    newer["task_versions"].pop("print")
    newer["request"]["tasks"] = [item for item in newer["request"]["tasks"] if item["id"] != "print"]
    identity = str(uuid4())
    call = store.prepare_call(current["current_run_id"], token, "onmyway.plan_route",
        catalog.describe("onmyway.plan_route")["version"], newer, tool_call_id=identity, legacy_run_id=identity)
    dispatched = store.dispatch_call(call["tool_call_id"], token)
    assert submit(client, navigation(old_receipt)).status_code == 409
    new = execute(dispatched, catalog)
    store.record_result(identity, dispatched["receipt_token"], "succeeded", result=new)
    assert client.post("/confirmations", headers=TOKEN, json=receipt(old, parameters)).status_code == 409
    assert client.post("/confirmations", headers=TOKEN, json=receipt(new["data"], newer)).status_code == 200


def test_cancelled_task_rejects_route_confirmation(routes):
    client, store, current, _, _, parameters, _ = routes
    old, _ = plan(routes)
    store.cancel(current["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1})
    assert client.post("/confirmations", headers=TOKEN, json=receipt(old, parameters)).status_code == 409


@pytest.mark.parametrize("change", ["plan_id", "correlation_id", "task_versions", "plan_version"])
def test_agent_route_confirmation_must_keep_registered_identity_and_snapshot(routes, change):
    client, _, _, _, _, parameters, _ = routes
    current, _ = plan(routes)
    body = receipt(current, parameters)
    if change == "plan_id":
        body["subject_id"] = str(uuid4())
    elif change == "correlation_id":
        body["correlation_id"] = str(uuid4())
    elif change == "plan_version":
        body["subject_version"] = 2
    else:
        body["snapshot"]["task_versions"] = {"parcel": 1}
        body["snapshot"]["selected_task_ids"] = ["parcel"]
    assert client.post("/confirmations", headers=TOKEN, json=body).status_code == 409


@pytest.mark.parametrize("state", ["prepared", "failed"])
def test_unfinished_replacement_never_restores_old_route(routes, state):
    client, store, current, catalog, _, parameters, _ = routes
    old, token = plan(routes)
    identity = str(uuid4())
    store.prepare_call(current["current_run_id"], token, "onmyway.plan_route",
        catalog.describe("onmyway.plan_route")["version"], parameters, tool_call_id=identity, legacy_run_id=identity)
    if state == "failed":
        store.reject_prepared(identity, token, {"code": "invalid_arguments"})
    assert client.post("/confirmations", headers=TOKEN, json=receipt(old, parameters)).status_code == 409


@pytest.mark.parametrize("replace_database", [False, True])
def test_missing_or_replaced_agent_database_fails_closed_without_creating_it(routes, replace_database):
    client, store, _, _, _, parameters, _ = routes
    old, _ = plan(routes)
    store.path.rename(store.path.with_name("retained-agent.sqlite3"))
    if replace_database:
        AgentStore(store.path)
    assert client.post("/confirmations", headers=TOKEN, json=receipt(old, parameters)).status_code == 409
    assert store.path.exists() is replace_database


def test_reopened_store_revision_invalidates_persisted_binding(routes):
    client, store, current, _, _, parameters, _ = routes
    old, _ = plan(routes)
    restarted = AgentStore(store.path)
    restarted.revise(current["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1,
                                             "goal": "移除打印停靠，保留正式打印待办"})
    assert client.post("/confirmations", headers=TOKEN, json=receipt(old, parameters)).status_code == 409


def test_confirmation_transaction_serializes_revision_from_another_store(routes, monkeypatch):
    from backend.agent import route_authority as agent_route_authority
    client, store, current, _, _, parameters, _ = routes
    old, _ = plan(routes)
    restarted = AgentStore(store.path)
    entered, release, revision_started = Event(), Event(), Event()
    original = agent_route_authority._current
    def blocked(db, source, **kwargs):
        original(db, source, **kwargs)
        if kwargs.get("dispatch") is None:
            entered.set()
            assert release.wait(10)
    monkeypatch.setattr(agent_route_authority, "_current", blocked)
    accepted = receipt(old, parameters)
    def revision():
        revision_started.set()
        return restarted.revise(current["agent_task_id"], {"request_id": str(uuid4()),
            "expected_revision": 1, "goal": "移除打印停靠，保留正式打印待办"})
    with ThreadPoolExecutor() as pool:
        confirmation = pool.submit(client.post, "/confirmations", headers=TOKEN, json=accepted)
        try:
            assert entered.wait(10)
            changed = pool.submit(revision)
            assert revision_started.wait(10)
            assert not changed.done()
        finally:
            release.set()
        assert confirmation.result(timeout=10).status_code == 200
        assert changed.result(timeout=10)["revision"] == 2
    assert client.post("/confirmations", headers=TOKEN, json=accepted).status_code == 409


def test_revision_during_map_query_does_not_block_or_revive_late_route(routes, monkeypatch):
    from backend.planning import planner
    client, _, _, _, _, parameters, _ = routes
    entered, release = Event(), Event()
    original = planner.plan
    def blocked(*args, **kwargs):
        entered.set()
        assert release.wait(10)
        return original(*args, **kwargs)
    monkeypatch.setattr(planner, "plan", blocked)
    with ThreadPoolExecutor() as pool:
        planning = pool.submit(plan, routes)
        try:
            assert entered.wait(10)
            changed, _ = revise(routes)
            assert changed["revision"] == 2
        finally:
            release.set()
        old, _ = planning.result(timeout=10)
    assert client.post("/confirmations", headers=TOKEN, json=receipt(old, parameters)).status_code == 409


def test_current_completed_route_survives_store_reopen_and_lease_release(routes):
    client, store, current, _, _, parameters, _ = routes
    planned, token = plan(routes)
    store.publish_artifact(current["current_run_id"], token, "route", planned["plan"])
    store.transition(current["current_run_id"], token, "completed", reason="路线已保存，等待用户确认")
    assert AgentStore(store.path).get(current["agent_task_id"])["run"]["state"] == "completed"
    accepted = receipt(planned, parameters)
    assert client.post("/confirmations", headers=TOKEN, json=accepted).status_code == 200
    body = navigation(accepted)
    assert submit(client, body).json()["state"] == "waiting_confirmation"
    response = client.post(f'/executions/{body["run_id"]}/confirm', headers=TOKEN,
                           json={"confirmation_id": str(uuid4())})
    assert response.status_code == 200 and response.json()["dispatch_allowed"] is True
    assert response.json()["device_execution_observed"] is False
