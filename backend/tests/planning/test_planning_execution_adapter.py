"""The existing Android planning API has a durable, body-bound execution receipt."""
import asyncio
import copy
import json
import sqlite3
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from threading import Event
from uuid import uuid4

import httpx
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from backend import main
from backend.tests.planning.test_context_snapshot import fact, snapshot
from backend.tests.planning.test_planner import START, END, A, B, Maps


@pytest.fixture
def local_runtime(tmp_path, monkeypatch):
    path = tmp_path / "events.db"
    monkeypatch.setenv("ONMYWAY_EVENT_DB", str(path))
    monkeypatch.setenv("ONMYWAY_BACKUP_DB", str(tmp_path / "tasks.db"))
    monkeypatch.delenv("ONMYWAY_BACKUP_TOKEN", raising=False)
    provider = Maps()
    monkeypatch.setattr(main.app.state, "provider", provider)
    with TestClient(main.app) as client:
        yield client, provider, path


def request():
    return {"origin": START.model_dump(), "destination": END.model_dump(),
            "tasks": [], "departure": datetime.now(timezone.utc).isoformat(),
            "mode": "walking", "context_snapshot": snapshot()}


def execution_header():
    return {"X-Execution-Id": str(uuid4())}


@pytest.mark.parametrize("path,operation", [("/plans", "plan"), ("/recommendations", "recommend")])
def test_live_adapter_persists_result_and_status_recovers_without_map_calls(local_runtime, path, operation):
    client, provider, database = local_runtime
    body, headers = request(), execution_header()
    result = client.post(path, json=body, headers=headers)
    assert result.status_code == 200, result.text
    calls = len(provider.modes)
    assert calls > 0
    assert client.post(path, json=body, headers=headers).json() == result.json()
    status = client.post(path + "/status", json=body, headers=headers)
    assert status.status_code == 200
    assert status.json() == {
        "run_id": headers["X-Execution-Id"], "capability_id": "plan_route", "operation": operation,
        "state": "completed", "result": result.json(), "error_status": None,
    }
    assert len(provider.modes) == calls
    assert not database.with_name("tasks.db").exists()
    with sqlite3.connect(database) as db:
        row = db.execute("SELECT state,invocation FROM capability_runs").fetchone()
        assert row[0] == "completed"
        assert json.loads(row[1])["authority"] == "android_context_snapshot"
        assert db.execute("SELECT COUNT(*) FROM capability_steps").fetchone()[0] >= 2


@pytest.mark.parametrize("path", ["/plans", "/recommendations"])
def test_status_not_found_has_no_execution_side_effect(local_runtime, path):
    client, provider, _ = local_runtime
    headers = execution_header()
    result = client.post(path + "/status", json=request(), headers=headers)
    assert result.status_code == 200
    assert result.json()["state"] == "not_found"
    assert result.json()["result"] is None
    assert provider.modes == []


def test_legacy_planning_without_execution_header_keeps_original_response(local_runtime):
    client, provider, database = local_runtime
    body = request()
    body.pop("context_snapshot")
    assert client.post("/plans", json=body).status_code == 200
    calls = len(provider.modes)
    assert client.post("/plans", json=body).status_code == 200
    assert len(provider.modes) > calls
    assert not database.exists()


def test_unknown_header_identity_or_missing_snapshot_never_invokes_map(local_runtime):
    client, provider, _ = local_runtime
    assert client.post("/plans", json=request(), headers={"X-Execution-Id": "bad"}).status_code == 422
    assert client.post("/plans/status", json=request()).status_code == 422
    body = request()
    body.pop("context_snapshot")
    assert client.post("/plans", json=body, headers=execution_header()).status_code == 422
    assert provider.modes == []


def test_body_and_operation_are_part_of_durable_identity(local_runtime):
    client, provider, _ = local_runtime
    body, headers = request(), execution_header()
    assert client.post("/plans", json=body, headers=headers).status_code == 200
    calls = len(provider.modes)
    changed = {**body, "mode": "cycling"}
    for path, value in [("/plans", changed), ("/plans/status", changed),
                        ("/recommendations", body), ("/recommendations/status", body)]:
        assert client.post(path, json=value, headers=headers).status_code == 409
    assert len(provider.modes) == calls


def test_concurrent_duplicate_returns_conflict_and_status_only_observes(local_runtime, monkeypatch):
    client, _, _ = local_runtime
    entered, release = Event(), Event()
    class BlockingMaps(Maps):
        def route(self, *args):
            entered.set()
            assert release.wait(10)
            return super().route(*args)
    provider = BlockingMaps()
    monkeypatch.setattr(main.app.state, "provider", provider)
    body, headers = request(), execution_header()
    with ThreadPoolExecutor() as pool:
        first = pool.submit(client.post, "/plans", json=body, headers=headers)
        try:
            assert entered.wait(10)
            assert client.post("/plans", json=body, headers=headers).status_code == 409
            assert client.post("/plans/status", json=body, headers=headers).json()["state"] == "running"
        finally:
            release.set()
        assert first.result().status_code == 200
    assert len(provider.modes) == 1


def test_adapter_shares_existing_planning_slot_and_preserves_failed_result(local_runtime):
    client, provider, _ = local_runtime
    body, headers = request(), execution_header()
    assert main.planning_slot.acquire(blocking=False)
    try:
        assert client.post("/plans", json=body, headers=headers).status_code == 429
    finally:
        main.planning_slot.release()
    assert client.post("/plans", json=body, headers=headers).status_code == 409
    status = client.post("/plans/status", json=body, headers=headers).json()
    assert status["state"] == "failed" and status["error_status"] == 429
    assert provider.modes == []


def test_unexpected_worker_failure_is_unknown_and_not_replayed(local_runtime, monkeypatch):
    client, _, _ = local_runtime
    calls = []
    def fail(*args):
        calls.append(1)
        raise RuntimeError("private-map-sentinel")
    monkeypatch.setattr(main, "plan", fail)
    body, headers = request(), execution_header()
    response = client.post("/plans", json=body, headers=headers)
    assert response.status_code == 500
    assert "private-map-sentinel" not in response.text
    assert client.post("/plans", json=body, headers=headers).status_code == 409
    state = client.post("/plans/status", json=body, headers=headers).json()
    assert state["state"] == "unknown" and state["error_status"] == 500
    assert calls == [1]


def test_stale_running_status_becomes_unknown_without_mutation_or_replay(local_runtime):
    client, provider, database = local_runtime
    body, headers = request(), execution_header()
    assert client.post("/plans", json=body, headers=headers).status_code == 200
    calls = len(provider.modes)
    with sqlite3.connect(database) as db:
        db.execute("UPDATE capability_runs SET state='running',result=NULL,updated_at=0")
    for _ in range(2):
        assert client.post("/plans/status", json=body, headers=headers).json()["state"] == "unknown"
    assert client.post("/plans", json=body, headers=headers).status_code == 409
    assert len(provider.modes) == calls
    with sqlite3.connect(database) as db:
        assert db.execute("SELECT state,updated_at FROM capability_runs").fetchone() == ("running", 0)


def test_expired_snapshot_does_not_hide_previously_completed_result(local_runtime, monkeypatch):
    from backend.planning import context
    client, provider, _ = local_runtime
    body, headers = request(), execution_header()
    result = client.post("/plans", json=body, headers=headers).json()
    calls = len(provider.modes)
    original_time = context.time.time
    monkeypatch.setattr(context.time, "time", lambda: original_time() + 3600)
    assert client.post("/plans/status", json=body, headers=headers).json()["result"] == result
    assert client.post("/plans", json=body, headers=headers).json() == result
    assert len(provider.modes) == calls


@pytest.mark.parametrize("path,worker,result", [
    ("/plans", "plan", {}),
    ("/recommendations", "recommend", {"direct": {}, "suggestion": None, "note": "", "assessments": []}),
    ("/recommendations", "recommend", {"direct": None, "suggestion": {}, "note": "", "assessments": []}),
])
def test_invalid_worker_result_is_not_cached_as_completed(local_runtime, monkeypatch, path, worker, result):
    client, _, _ = local_runtime
    monkeypatch.setattr(main, worker, lambda *args: result)
    body, headers = request(), execution_header()
    assert client.post(path, json=body, headers=headers).status_code == 502
    status = client.post(path + "/status", json=body, headers=headers).json()
    assert status["state"] == "failed" and status["result"] is None and status["error_status"] == 502


def test_local_endpoint_cannot_read_or_overwrite_server_authority_run(local_runtime, monkeypatch):
    client, _, _ = local_runtime
    token = "s" * 32
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", token)
    headers = execution_header()
    server = {"run_id": headers["X-Execution-Id"], "correlation_id": str(uuid4()),
              "capability_id": "read_tasks", "capability_version": 1,
              "snapshot_id": "server-snapshot", "parameters": {}}
    recorded = client.post("/executions", json=server, headers={"X-Backup-Token": token})
    assert recorded.status_code == 200
    for path in ("/plans", "/plans/status", "/recommendations/status"):
        assert client.post(path, json=request(), headers=headers).status_code == 409
    current = client.get("/executions/" + headers["X-Execution-Id"], headers={"X-Backup-Token": token}).json()
    assert current == recorded.json()


def test_server_endpoints_cannot_confirm_local_planning_run(local_runtime, monkeypatch):
    client, _, _ = local_runtime
    token = "s" * 32
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", token)
    headers = execution_header()
    assert client.post("/plans", json=request(), headers=headers).status_code == 200
    path = "/executions/" + headers["X-Execution-Id"]
    assert client.post(path + "/confirm", headers={"X-Backup-Token": token},
                       json={"confirmation_id": str(uuid4())}).status_code == 409
    assert client.post(path + "/reconcile", headers={"X-Backup-Token": token}).status_code == 409


def test_remote_peer_cannot_submit_or_query_planning_executions():
    async def call():
        transport = httpx.ASGITransport(app=main.app, client=("198.51.100.7", 12345))
        async with httpx.AsyncClient(transport=transport, base_url="http://local") as client:
            return [await client.post(path, json=request(), headers=execution_header())
                    for path in ("/plans", "/plans/status", "/recommendations/status")]
    assert all(response.status_code == 403 for response in asyncio.run(call()))


@pytest.mark.parametrize("path", ["/plans", "/recommendations"])
@pytest.mark.parametrize("printing_place", [A, B], ids=["shared-stop", "separate-stops"])
def test_task_route_recovers_then_changed_request_preserves_parcel_and_dorm(local_runtime, path, printing_place):
    client, provider, _ = local_runtime
    body = request()
    body["destination"]["name"] = "宿舍"
    body["tasks"] = [
        {"id": identity, "title": title, "priority": 2, "service_minutes": 5,
         "place": place.model_dump()}
        for identity, title, place in [("parcel", "取快递", A), ("print", "打印论文", printing_place)]
    ]
    body["context_snapshot"] = snapshot(*[
        fact(item["id"], title=item["title"], place=item["place"]) for item in body["tasks"]
    ])
    headers = execution_header()
    result = client.post(path, json=body, headers=headers)
    assert result.status_code == 200, result.text
    route = result.json() if path == "/plans" else result.json()["suggestion"]
    assert {task_id for stop in route["stops"] for task_id in stop["task_ids"]} == {"parcel", "print"}
    if printing_place == A:
        assert len(route["stops"]) == 1 and len(route["legs"]) == 3
    calls = len(provider.modes)
    assert client.post(path + "/status", json=body, headers=headers).json()["result"] == result.json()
    assert len(provider.modes) == calls
    changed = copy.deepcopy(body)
    changed["tasks"] = changed["tasks"][:1]
    changed["context_snapshot"] = snapshot(fact("parcel", title="取快递", place=A.model_dump()))
    assert client.post(path, json=changed, headers=headers).status_code == 409
    remaining = client.post(path, json=changed, headers=execution_header())
    assert remaining.status_code == 200, remaining.text
    route = remaining.json() if path == "/plans" else remaining.json()["suggestion"]
    assert route["destination"]["name"] == "宿舍"
    assert {task_id for stop in route["stops"] for task_id in stop["task_ids"]} == {"parcel"}


@pytest.mark.parametrize("field,value", [
    ("mode", "cycling"), ("total_seconds", -1), ("travel_seconds", "600"),
    ("total_meters", True), ("legs", []),
])
def test_worker_result_must_match_request_and_have_consistent_totals(local_runtime, monkeypatch, field, value):
    client, _, _ = local_runtime
    original_plan = main.plan
    def malformed(*args):
        result = original_plan(*args).model_dump(mode="json")
        result[field] = value
        return result
    monkeypatch.setattr(main, "plan", malformed)
    body, headers = request(), execution_header()
    assert client.post("/plans", json=body, headers=headers).status_code == 502
    status = client.post("/plans/status", json=body, headers=headers).json()
    assert status["state"] == "failed" and status["result"] is None


def test_new_execution_requires_fresh_context_without_running_worker(local_runtime):
    client, provider, _ = local_runtime
    body, headers = request(), execution_header()
    body["context_snapshot"]["captured_at"] -= 3600 * 1000
    assert client.post("/plans", json=body, headers=headers).status_code == 409
    assert client.post("/plans/status", json=body, headers=headers).json()["state"] == "not_found"
    assert provider.modes == []


def test_corrupt_cached_route_is_never_returned_or_replayed(local_runtime):
    client, provider, database = local_runtime
    body, headers = request(), execution_header()
    assert client.post("/plans", json=body, headers=headers).status_code == 200
    calls = len(provider.modes)
    with sqlite3.connect(database) as db:
        db.execute("UPDATE capability_runs SET result='{}'")
    for path in ("/plans", "/plans/status"):
        assert client.post(path, json=body, headers=headers).status_code == 502
    assert len(provider.modes) == calls
