import copy
import json
import sqlite3
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from datetime import datetime, timedelta, timezone
from threading import Event
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from backend.main import app
from backend.tests.data_services.test_tasks import command, commit, task, TOKEN


@pytest.fixture
def runtime(tmp_path, monkeypatch):
    monkeypatch.setenv("ONMYWAY_BACKUP_DB", str(tmp_path / "tasks.db"))
    event_db = tmp_path / "events.db"
    monkeypatch.setenv("ONMYWAY_EVENT_DB", str(event_db))
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", TOKEN["X-Backup-Token"])
    with TestClient(app) as client:
        head = client.get("/tasks", headers=TOKEN).json()
        yield client, head, event_db


def invocation(capability="read_tasks", parameters=None, **changes):
    return {"run_id": str(uuid4()), "correlation_id": str(uuid4()),
            "capability_id": capability, "capability_version": 1,
            "snapshot_id": "snapshot-1", "parameters": parameters or {}, **changes}


def submit(client, body):
    return client.post("/executions", headers=TOKEN, json=body)


def prepare_navigation(client, head, *, versions=None, snapshot_changes=None):
    versions = versions or {}
    receipt = {
        "receipt_id": str(uuid4()), "subject_type": "plan", "subject_id": str(uuid4()),
        "subject_version": 1, "correlation_id": str(uuid4()),
        "confirmed_at": datetime.now(timezone.utc).isoformat(),
        "snapshot": {"route_session_id": str(uuid4()), "selected_task_ids": list(versions),
                     "task_versions": versions, "store_id": head["store_id"]},
    }
    receipt["snapshot"].update(snapshot_changes or {})
    assert client.post("/confirmations", headers=TOKEN, json=receipt).status_code == 200
    body = invocation("start_navigation", {
        "store_id": head["store_id"], "task_versions": versions,
        "plan_id": receipt["subject_id"], "plan_version": 1,
        "route_session_id": receipt["snapshot"]["route_session_id"],
        "confirmation_receipt_id": receipt["receipt_id"],
    }, correlation_id=receipt["correlation_id"])
    return body, receipt


def device_event(body, kind, **changes):
    params = body["parameters"]
    return {
        "event_id": str(uuid4()), "correlation_id": body["correlation_id"],
        "source": "device", "kind": kind,
        "occurred_at": datetime.now(timezone.utc).isoformat(),
        "payload": {"execution_id": body["run_id"], "plan_id": params["plan_id"],
                    "plan_version": params["plan_version"],
                    "route_session_id": params["route_session_id"]}, **changes,
    }


def test_registry_contract_lists_existing_capabilities(runtime):
    client, _, _ = runtime
    response = client.get("/capabilities", headers=TOKEN)
    assert response.status_code == 200
    registry = {item["id"]: item for item in response.json()["capabilities"]}
    assert set(registry) == {"read_tasks", "plan_route", "start_navigation"}
    assert registry["start_navigation"]["executor"] == "android"
    assert registry["start_navigation"]["confirmation_required"] is True
    assert registry["plan_route"]["confirmation_required"] is False
    assert all(item["version"] == 1 and item["input_schema"] and item["output_schema"] for item in registry.values())


def test_read_tasks_uses_authority_and_replays_original_result(runtime):
    client, head, _ = runtime
    assert commit(client, head, command(task("parcel", title="取快递"))).status_code == 200
    body = invocation()
    first = submit(client, body)
    assert first.status_code == 200
    result = first.json()
    assert result["state"] == "completed"
    assert result["result"]["tasks"][0]["task"]["title"] == "取快递"
    assert commit(client, head, command(task("print"))).status_code == 200
    assert submit(client, body).json()["result"] == result["result"]
    assert client.get(f'/executions/{body["run_id"]}', headers=TOKEN).json()["snapshot_id"] == "snapshot-1"
    changed = copy.deepcopy(body)
    changed["snapshot_id"] = "different"
    assert submit(client, changed).status_code == 409


@pytest.mark.parametrize("changes", [
    {"capability_id": "shell"}, {"capability_version": 2},
    {"parameters": {"unexpected": True}}, {"capability_version": True},
])
def test_unknown_capability_version_or_arguments_rejected(runtime, changes):
    client, _, _ = runtime
    assert submit(client, invocation(**changes)).status_code == 422


def test_runtime_requires_authorization(runtime):
    client, _, _ = runtime
    assert client.get("/capabilities").status_code == 401
    assert client.post("/executions", json=invocation()).status_code == 401


def test_navigation_waits_for_separate_start_and_never_dispatches_twice(runtime):
    client, head, _ = runtime
    body, _ = prepare_navigation(client, head)
    first = submit(client, body)
    assert first.status_code == 200
    assert first.json()["state"] == "waiting_confirmation"
    assert first.json()["dispatch_allowed"] is False
    confirm = {"confirmation_id": str(uuid4())}
    path = f'/executions/{body["run_id"]}/confirm'
    started = client.post(path, headers=TOKEN, json=confirm)
    assert started.status_code == 200
    assert started.json()["state"] == "running"
    assert started.json()["dispatch_allowed"] is True
    assert started.json()["device_execution_observed"] is False
    assert client.post(path, headers=TOKEN, json=confirm).json()["dispatch_allowed"] is False
    assert client.post(path, headers=TOKEN, json={"confirmation_id": str(uuid4())}).json()["dispatch_allowed"] is False
    assert submit(client, body).json()["dispatch_allowed"] is False


def test_cancel_print_invalidates_waiting_start_without_deleting_other_tasks(runtime):
    client, head, _ = runtime
    assert commit(client, head, command(task("parcel", title="取快递")), command(task("print"))).status_code == 200
    body, _ = prepare_navigation(client, head, versions={"parcel": 1, "print": 1})
    assert submit(client, body).status_code == 200
    assert commit(client, head, command(task_id="print", operation="delete", version=1)).status_code == 200
    response = client.post(f'/executions/{body["run_id"]}/confirm', headers=TOKEN,
                           json={"confirmation_id": str(uuid4())})
    assert response.status_code == 409
    records = client.get("/tasks", headers=TOKEN).json()["tasks"]
    assert next(row for row in records if row["task_id"] == "parcel")["deleted"] is False
    assert client.get(f'/executions/{body["run_id"]}', headers=TOKEN).json()["state"] == "waiting_confirmation"


def test_new_plan_version_rejects_old_waiting_start(runtime):
    client, head, _ = runtime
    body, receipt = prepare_navigation(client, head)
    assert submit(client, body).status_code == 200
    receipt.update(receipt_id=str(uuid4()), subject_version=2)
    assert client.post("/confirmations", headers=TOKEN, json=receipt).status_code == 200
    assert client.post(f'/executions/{body["run_id"]}/confirm', headers=TOKEN,
                       json={"confirmation_id": str(uuid4())}).status_code == 409


def test_unknown_navigation_reconciles_matching_device_receipts_without_redispatch(runtime):
    client, head, db_path = runtime
    body, _ = prepare_navigation(client, head)
    assert submit(client, body).status_code == 200
    path = f'/executions/{body["run_id"]}'
    assert client.post(path + "/confirm", headers=TOKEN, json={"confirmation_id": str(uuid4())}).status_code == 200
    with sqlite3.connect(db_path) as db:
        db.execute("UPDATE capability_runs SET updated_at=0 WHERE run_id=?", (body["run_id"],))
    assert client.get(path, headers=TOKEN).json()["state"] == "unknown"
    wrong = device_event(body, "NAVIGATION_ARRIVED")
    wrong["payload"]["execution_id"] = str(uuid4())
    assert client.post("/events", headers=TOKEN, json=wrong).status_code == 200
    assert client.post(path + "/reconcile", headers=TOKEN).json()["state"] == "unknown"
    started = device_event(body, "NAVIGATION_STARTED")
    assert client.post("/events", headers=TOKEN, json=started).status_code == 200
    observed = client.post(path + "/reconcile", headers=TOKEN).json()
    assert observed["state"] == "running" and observed["device_execution_observed"]
    arrived = device_event(body, "NAVIGATION_ARRIVED")
    assert client.post("/events", headers=TOKEN, json=arrived).status_code == 200
    completed = client.post(path + "/reconcile", headers=TOKEN).json()
    assert completed["state"] == "completed"
    assert completed["result"]["event_id"] == arrived["event_id"]
    assert completed["dispatch_allowed"] is False
    assert client.post(path + "/confirm", headers=TOKEN, json={"confirmation_id": str(uuid4())}).json()["dispatch_allowed"] is False


def test_terminal_receipt_is_not_overwritten_by_late_start(runtime):
    client, head, _ = runtime
    body, _ = prepare_navigation(client, head)
    assert submit(client, body).status_code == 200
    path = f'/executions/{body["run_id"]}'
    assert client.post(path + "/confirm", headers=TOKEN, json={"confirmation_id": str(uuid4())}).status_code == 200
    assert client.post("/events", headers=TOKEN, json=device_event(body, "NAVIGATION_ARRIVED")).status_code == 200
    assert client.post("/events", headers=TOKEN, json=device_event(body, "NAVIGATION_STARTED")).status_code == 200
    assert client.post(path + "/reconcile", headers=TOKEN).json()["state"] == "completed"


def prepare_route(client, head):
    from backend.tests.planning.test_planner import START, END, A, B
    parcel = task("parcel", title="取快递", placeType="PARCEL", placeJson=json.dumps(A.model_dump()))
    printing = task("print", title="打印论文", placeJson=json.dumps(B.model_dump()))
    assert commit(client, head, command(parcel), command(printing)).status_code == 200
    request = {
        "origin": START.model_dump(), "destination": {**END.model_dump(), "name": "宿舍"},
        "departure": datetime.now(timezone.utc).isoformat(),
        "tasks": [{"id": row["id"], "title": row["title"], "place": json.loads(row["placeJson"]),
                   "priority": row["priority"], "service_minutes": row["serviceMinutes"]}
                  for row in (parcel, printing)],
    }
    return invocation("plan_route", {"store_id": head["store_id"],
                                      "task_versions": {"parcel": 1, "print": 1}, "request": request})


def test_planning_uses_existing_worker_and_replay_does_not_call_it_again(runtime, monkeypatch):
    from backend.tests.planning.test_planner import Maps
    client, head, _ = runtime
    provider = Maps()
    monkeypatch.setattr(app.state, "provider", provider)
    body = prepare_route(client, head)
    planned = submit(client, body).json()
    assert planned["state"] == "completed", planned
    assert planned["result"]["destination"]["name"] == "宿舍"
    assert {task_id for stop in planned["result"]["stops"] for task_id in stop["task_ids"]} == {"parcel", "print"}
    calls = len(provider.modes)
    assert calls > 0
    assert submit(client, body).json()["result"] == planned["result"]
    assert len(provider.modes) == calls
    assert commit(client, head, command(task_id="print", operation="delete", version=1)).status_code == 200
    stale = copy.deepcopy(body)
    stale["run_id"] = str(uuid4())
    assert submit(client, stale).status_code == 409
    stale["parameters"]["request"]["tasks"] = stale["parameters"]["request"]["tasks"][:1]
    stale["parameters"]["task_versions"].pop("print")
    remaining = submit(client, stale).json()
    assert remaining["state"] == "completed"
    assert remaining["result"]["destination"]["name"] == "宿舍"
    assert {task_id for stop in remaining["result"]["stops"] for task_id in stop["task_ids"]} == {"parcel"}


@pytest.mark.parametrize("field,value,status", [
    ("title", "伪造标题", 409), ("priority", 3, 409), ("service_minutes", 7, 409),
    ("priority", True, 422), ("service_minutes", "5", 422),
    ("place", {"id": "other", "name": "伪造地点", "longitude": 120, "latitude": 30}, 409),
])
def test_planning_requires_strict_parameters_and_matching_authority(runtime, monkeypatch, field, value, status):
    from backend.tests.planning.test_planner import Maps
    client, head, _ = runtime
    provider = Maps()
    monkeypatch.setattr(app.state, "provider", provider)
    body = prepare_route(client, head)
    body["parameters"]["request"]["tasks"][0][field] = value
    assert submit(client, body).status_code == status
    assert not provider.modes


def test_planning_rechecks_versions_after_worker_and_keeps_durable_failure(runtime, monkeypatch):
    from backend.tests.planning.test_planner import Maps
    client, head, _ = runtime
    body = prepare_route(client, head)
    class ChangingMaps(Maps):
        changed = False
        def route(self, *args):
            if not self.changed:
                self.changed = True
                assert commit(client, head, command(task_id="print", operation="delete", version=1)).status_code == 200
            return super().route(*args)
    monkeypatch.setattr(app.state, "provider", ChangingMaps())
    result = submit(client, body).json()
    assert result["state"] == "failed"
    assert result["error_status"] == 409
    assert result["result"] is None
    assert submit(client, body).json()["state"] == "failed"


def test_planning_shares_existing_limit_and_never_replays_failed_run(runtime, monkeypatch):
    from backend.main import planning_slot
    from backend.tests.planning.test_planner import Maps
    client, head, _ = runtime
    provider = Maps()
    monkeypatch.setattr(app.state, "provider", provider)
    body = prepare_route(client, head)
    assert planning_slot.acquire(blocking=False)
    try:
        result = submit(client, body).json()
    finally:
        planning_slot.release()
    assert result["state"] == "failed" and result["error_status"] == 429
    assert submit(client, body).json()["state"] == "failed"
    assert not provider.modes


def test_concurrent_plan_duplicate_returns_running_without_second_worker(runtime, monkeypatch):
    from backend.tests.planning.test_planner import Maps
    client, head, _ = runtime
    body = prepare_route(client, head)
    entered, release = Event(), Event()
    class BlockingMaps(Maps):
        def route(self, *args):
            entered.set()
            assert release.wait(10)
            return super().route(*args)
    monkeypatch.setattr(app.state, "provider", BlockingMaps())
    with ThreadPoolExecutor() as pool:
        first = pool.submit(submit, client, body)
        try:
            assert entered.wait(10)
            second = submit(client, body)
            assert second.status_code == 200 and second.json()["state"] == "running"
        finally:
            release.set()
        assert first.result().json()["state"] == "completed"


def test_uncertain_worker_failure_does_not_replay_or_leak_error(runtime, monkeypatch):
    from backend.planning import planner
    client, head, _ = runtime
    body = prepare_route(client, head)
    calls = []
    def fail(*args):
        calls.append(1)
        raise RuntimeError("private-upstream-sentinel")
    monkeypatch.setattr(planner, "plan", fail)
    result = submit(client, body)
    assert result.json()["state"] == "unknown"
    assert "private-upstream-sentinel" not in result.text
    assert submit(client, body).json()["state"] == "unknown"
    assert client.post(f'/executions/{body["run_id"]}/reconcile', headers=TOKEN).json()["state"] == "unknown"
    assert calls == [1]


def test_same_plan_with_new_run_id_cannot_dispatch_twice_even_concurrently(runtime):
    client, head, _ = runtime
    first, _ = prepare_navigation(client, head)
    second = {**first, "run_id": str(uuid4())}
    assert submit(client, first).status_code == 200
    assert submit(client, second).status_code == 200
    def confirm(body):
        return client.post(f'/executions/{body["run_id"]}/confirm', headers=TOKEN,
                           json={"confirmation_id": str(uuid4())})
    with ThreadPoolExecutor() as pool:
        responses = list(pool.map(confirm, (first, second)))
    assert sorted(response.status_code for response in responses) == [200, 409]
    assert sum(response.json().get("dispatch_allowed", False) for response in responses) == 1


@pytest.mark.parametrize("changes", [
    {"selected_task_ids": [{}]}, {"selected_task_ids": ["parcel", "parcel"]},
    {"task_versions": None}, {"task_versions": {"parcel": True}},
    {"store_id": "another-store"}, {"task_versions": {}},
])
def test_legacy_or_malformed_receipt_is_not_execution_authorization(runtime, changes):
    client, head, _ = runtime
    assert commit(client, head, command(task("parcel"))).status_code == 200
    body, _ = prepare_navigation(client, head, versions={"parcel": 1}, snapshot_changes=changes)
    assert submit(client, body).status_code == 409


@pytest.mark.parametrize("changes", [{"source": "assistant"}, {"source": "user"}])
def test_only_device_receipts_can_complete_run(runtime, changes):
    client, head, _ = runtime
    body, _ = prepare_navigation(client, head)
    assert submit(client, body).status_code == 200
    path = f'/executions/{body["run_id"]}'
    assert client.post(path + "/confirm", headers=TOKEN, json={"confirmation_id": str(uuid4())}).status_code == 200
    assert client.post("/events", headers=TOKEN, json=device_event(body, "NAVIGATION_ARRIVED", **changes)).status_code == 200
    assert client.post(path + "/reconcile", headers=TOKEN).json()["state"] == "running"


@pytest.mark.parametrize("field,value", [
    ("plan_id", "different-plan"), ("plan_version", 2),
    ("route_session_id", "different-session"), ("confirmation_receipt_id", str(uuid4())),
])
def test_navigation_cannot_borrow_another_confirmation(runtime, field, value):
    client, head, _ = runtime
    body, _ = prepare_navigation(client, head)
    body["parameters"][field] = value
    assert submit(client, body).status_code == 409


@pytest.mark.parametrize("terminal", ["NAVIGATION_FAILED", "NAVIGATION_CANCELLED"])
def test_failed_or_cancelled_navigation_is_durable_and_not_replayed(runtime, terminal):
    client, head, _ = runtime
    body, _ = prepare_navigation(client, head)
    assert submit(client, body).status_code == 200
    path = f'/executions/{body["run_id"]}'
    assert client.post(path + "/confirm", headers=TOKEN, json={"confirmation_id": str(uuid4())}).status_code == 200
    event = device_event(body, terminal)
    assert client.post("/events", headers=TOKEN, json=event).status_code == 200
    result = client.post(path + "/reconcile", headers=TOKEN).json()
    assert result["state"] == "failed" and result["result"]["kind"] == terminal
    assert result["steps"][-1]["evidence_id"] == event["event_id"]
    assert submit(client, body).json()["state"] == "failed"
    assert client.post(path + "/confirm", headers=TOKEN, json={"confirmation_id": str(uuid4())}).json()["dispatch_allowed"] is False
    # Reconciliation and event retries do not create new checkpoints.
    assert client.post("/events", headers=TOKEN, json=event).status_code == 200
    assert client.post(path + "/reconcile", headers=TOKEN).json()["steps"] == result["steps"]


def test_expired_confirmation_cannot_authorize_start(runtime):
    client, head, db_path = runtime
    body, _ = prepare_navigation(client, head)
    assert submit(client, body).status_code == 200
    with sqlite3.connect(db_path) as db:
        db.execute("UPDATE confirmation_receipts SET valid_until_epoch=0")
    assert client.post(f'/executions/{body["run_id"]}/confirm', headers=TOKEN,
                       json={"confirmation_id": str(uuid4())}).status_code == 409


def test_invalid_worker_output_is_a_persisted_failure(runtime, monkeypatch):
    from backend.planning import planner
    client, head, _ = runtime
    body = prepare_route(client, head)
    class InvalidResult:
        def model_dump(self, **kwargs):
            return {"unsupported": True}
    monkeypatch.setattr(planner, "plan", lambda *args: InvalidResult())
    response = submit(client, body).json()
    assert response["state"] == "failed" and response["error_status"] == 502
    assert response["result"] is None


def test_planning_refuses_malformed_legacy_place_without_internal_error(runtime, monkeypatch):
    from backend.tests.planning.test_planner import Maps
    from backend.data_services.sync import _open
    client, head, _ = runtime
    provider = Maps()
    monkeypatch.setattr(app.state, "provider", provider)
    body = prepare_route(client, head)
    with closing(_open()) as db, db:
        db.execute("UPDATE task_records SET placeJson='not-json' WHERE id='parcel'")
    assert submit(client, body).status_code == 409
    assert not provider.modes
