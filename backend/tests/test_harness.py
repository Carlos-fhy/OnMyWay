import sqlite3
import json
from concurrent.futures import ThreadPoolExecutor
from threading import Event
from uuid import uuid4

import pytest
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
from backend.harness import propose, status
from backend import assistant


@pytest.fixture
def database(tmp_path, monkeypatch):
    path = tmp_path / "harness.db"
    monkeypatch.setenv("ONMYWAY_HARNESS_DB", str(path))
    return path


def test_completed_result_survives_reopen(database):
    request_id = str(uuid4())
    assert propose(request_id, "chat", {"text": "test"}, lambda: {"action": "reply"}) == {"action": "reply"}
    assert propose(request_id, "chat", {"text": "test"}, lambda: pytest.fail("duplicate execution")) == {"action": "reply"}
    with sqlite3.connect(database) as db:
        assert db.execute("SELECT state FROM intent_runs").fetchone()[0] == "completed"


def test_same_id_cannot_change_payload_or_operation(database):
    request_id = str(uuid4())
    propose(request_id, "chat", {}, lambda: {})
    for operation, payload in [("navigation", {}), ("chat", {"changed": True})]:
        with pytest.raises(HTTPException) as error:
            propose(request_id, operation, payload, lambda: pytest.fail("executed conflict"))
        assert error.value.status_code == 409


def test_concurrent_duplicate_does_not_execute_twice(database):
    request_id = str(uuid4())
    entered, release = Event(), Event()
    def execute():
        entered.set()
        assert release.wait(5)
        return {}
    with ThreadPoolExecutor() as pool:
        first = pool.submit(propose, request_id, "chat", {}, execute)
        try:
            assert entered.wait(5)
            with pytest.raises(HTTPException) as error:
                propose(request_id, "chat", {}, lambda: pytest.fail("duplicate"))
            assert error.value.status_code == 409
        finally:
            release.set()
        assert first.result() == {}


def test_failed_result_is_not_reexecuted_or_stored(database):
    request_id = str(uuid4())
    def fail():
        raise HTTPException(502, "upstream secret must not be persisted")
    with pytest.raises(HTTPException):
        propose(request_id, "chat", {}, fail)
    with sqlite3.connect(database) as db:
        assert db.execute("SELECT state,result,error_status FROM intent_runs").fetchone() == ("failed", None, 502)
    with pytest.raises(HTTPException) as error:
        propose(request_id, "chat", {}, lambda: pytest.fail("retry"))
    assert error.value.status_code == 409


def test_navigation_endpoint_persists_only_validated_proposals(database, monkeypatch):
    calls = []
    def complete(*args):
        calls.append(1)
        return '{"action":"adjust","destination":"图书馆","keep_task_ids":[],"reply":"请确认"}'
    monkeypatch.setattr(assistant, "completion", complete)
    body = assistant.NavigationRequest(request_id=uuid4(), text="改去图书馆", destination="宿舍", tasks=[])
    assert assistant.navigation(body) == assistant.navigation(body)
    assert len(calls) == 1
    monkeypatch.setattr(assistant, "completion", lambda *args: '{"action":"delete_all"}')
    invalid = body.model_copy(update={"request_id": uuid4()})
    with pytest.raises(HTTPException):
        assistant.navigation(invalid)
    with sqlite3.connect(database) as db:
        assert db.execute("SELECT state FROM intent_runs WHERE request_id=?", (str(invalid.request_id),)).fetchone()[0] == "failed"


@pytest.fixture
def client(database):
    app = FastAPI()
    app.include_router(assistant.router)
    with TestClient(app) as client:
        yield client


def _body(operation="chat", request_id=None):
    identity = {"request_id": str(request_id or uuid4())}
    if operation == "chat":
        return dict(identity, messages=[{"role": "user", "content": "去图书馆"}],
                    now="2026-10-07T09:00:00+08:00")
    return dict(identity, text="改去图书馆", destination="宿舍", tasks=[])


@pytest.mark.parametrize("operation", ["chat", "navigation"])
def test_status_completed_survives_reopen_and_normalizes_original_body(client, database, monkeypatch, operation):
    calls = []
    output = ({"action": "plan_route", "destination": "图书馆", "reply": "请确认"} if operation == "chat"
              else {"action": "adjust", "destination": "图书馆", "keep_task_ids": [], "reply": "请确认"})
    def complete(*args):
        calls.append(1)
        return json.dumps(output, ensure_ascii=False)
    monkeypatch.setattr(assistant, "completion", complete)
    body = _body(operation)
    initial = client.post(f"/assistant/{operation}", json=body)
    assert initial.status_code == 200
    # Pydantic defaults must hash identically to an explicitly populated body.
    model = assistant.ChatRequest if operation == "chat" else assistant.NavigationRequest
    normalized = model.model_validate(body).model_dump(mode="json")
    monkeypatch.setattr(assistant, "completion", lambda *args: pytest.fail("status called upstream"))
    recovered = client.post(f"/assistant/{operation}/status", json=normalized)
    assert recovered.status_code == 200
    assert recovered.json() == dict(request_id=body["request_id"], operation=operation,
                                   state="completed", result=initial.json(), error_status=None)
    # The read must not consume the result or prevent an idempotent original POST.
    assert client.post(f"/assistant/{operation}", json=body).json() == initial.json()
    assert len(calls) == 1
    with sqlite3.connect(database) as db:
        assert db.execute("SELECT COUNT(*) FROM intent_runs").fetchone()[0] == 1


@pytest.mark.parametrize("operation", ["chat", "navigation"])
def test_missing_status_does_not_insert_a_run_or_call_model(client, database, monkeypatch, operation):
    monkeypatch.setattr(assistant, "completion", lambda *args: pytest.fail("lookup executed model"))
    body = _body(operation)
    response = client.post(f"/assistant/{operation}/status", json=body)
    assert response.status_code == 200
    assert response.json() == dict(request_id=body["request_id"], operation=operation,
                                  state="not_found", result=None, error_status=None)
    with sqlite3.connect(database) as db:
        assert db.execute("SELECT COUNT(*) FROM intent_runs").fetchone()[0] == 0


@pytest.mark.parametrize("operation", ["chat", "navigation"])
@pytest.mark.parametrize("identity", [None, "not-a-uuid", ""])
def test_status_requires_valid_non_null_uuid(client, monkeypatch, operation, identity):
    monkeypatch.setattr(assistant, "completion", lambda *args: pytest.fail("invalid status called model"))
    body = _body(operation)
    body["request_id"] = identity
    assert client.post(f"/assistant/{operation}/status", json=body).status_code == 422
    del body["request_id"]
    assert client.post(f"/assistant/{operation}/status", json=body).status_code == 422


def test_concurrent_status_reports_running_without_reexecution(database):
    request_id = str(uuid4())
    entered, release = Event(), Event()
    def execute():
        entered.set()
        assert release.wait(5)
        return {"action": "reply"}
    with ThreadPoolExecutor() as pool:
        first = pool.submit(propose, request_id, "chat", {}, execute)
        try:
            assert entered.wait(5)
            with ThreadPoolExecutor() as readers:
                results = list(readers.map(lambda _: status(request_id, "chat", {}), range(6)))
            assert all(item == dict(request_id=request_id, operation="chat", state="running",
                                    result=None, error_status=None) for item in results)
        finally:
            release.set()
        first.result()
    assert status(request_id, "chat", {})["state"] == "completed"


def test_expired_running_status_is_unresolved_without_mutating_and_can_finish(database):
    request_id = str(uuid4())
    entered, release = Event(), Event()
    def execute():
        entered.set()
        assert release.wait(5)
        return {"action": "reply"}
    with ThreadPoolExecutor() as pool:
        first = pool.submit(propose, request_id, "chat", {}, execute)
        try:
            assert entered.wait(5)
            with sqlite3.connect(database) as db:
                db.execute("UPDATE intent_runs SET updated_at=updated_at-301 WHERE request_id=?", (request_id,))
            assert status(request_id, "chat", {}) == dict(request_id=request_id, operation="chat",
                                                         state="unresolved", result=None, error_status=None)
            with sqlite3.connect(database) as db:
                assert db.execute("SELECT state,error_status FROM intent_runs").fetchone() == ("running", None)
            with pytest.raises(HTTPException) as error:
                propose(request_id, "chat", {}, lambda: pytest.fail("expired run retried"))
            assert error.value.status_code == 409
        finally:
            release.set()
        first.result()
    assert status(request_id, "chat", {})["state"] == "completed"


@pytest.mark.parametrize("operation", ["chat", "navigation"])
def test_failed_status_returns_only_safe_code_and_does_not_retry(client, database, monkeypatch, operation):
    def fail(*args):
        raise HTTPException(502, "secret upstream detail")
    monkeypatch.setattr(assistant, "completion", fail)
    body = _body(operation)
    assert client.post(f"/assistant/{operation}", json=body).status_code == 502
    monkeypatch.setattr(assistant, "completion", lambda *args: pytest.fail("failed run retried"))
    response = client.post(f"/assistant/{operation}/status", json=body)
    assert response.status_code == 200
    assert response.json() == dict(request_id=body["request_id"], operation=operation,
                                  state="failed", result=None, error_status=502)
    assert "secret" not in response.text
    assert client.post(f"/assistant/{operation}", json=body).status_code == 409


def test_status_rejects_changed_content_or_operation(client, monkeypatch):
    monkeypatch.setattr(assistant, "completion", lambda *args: '{"action":"reply","reply":"好"}')
    body = _body()
    assert client.post("/assistant/chat", json=body).status_code == 200
    monkeypatch.setattr(assistant, "completion", lambda *args: pytest.fail("conflict called upstream"))
    changed = dict(body, route_active=True)
    assert client.post("/assistant/chat/status", json=changed).status_code == 409
    other = _body("navigation", body["request_id"])
    assert client.post("/assistant/navigation/status", json=other).status_code == 409


@pytest.mark.parametrize("operation,invalid", [
    ("chat", '{"action":"plan_route","destination":"","reply":"invalid"}'),
    ("chat", '{"action":"create_task","title":"","reply":"invalid"}'),
    ("chat", '{"action":"reply","deadline":"2026-10-07T09:00:00","reply":"invalid"}'),
    ("navigation", '{"action":"adjust","keep_task_ids":["not-in-request"]}'),
    ("navigation", '{"action":"add_stop","new_task_title":"","new_place_type":"SUPERMARKET"}'),
    ("chat", '{broken'),
    ("chat", '[]'),
])
def test_invalid_cached_proposal_is_unresolved_and_not_disclosed(client, database, monkeypatch, operation, invalid):
    valid = ('{"action":"reply","reply":"ok"}' if operation == "chat" else '{"action":"adjust"}')
    monkeypatch.setattr(assistant, "completion", lambda *args: valid)
    body = _body(operation)
    assert client.post(f"/assistant/{operation}", json=body).status_code == 200
    with sqlite3.connect(database) as db:
        db.execute("UPDATE intent_runs SET result=? WHERE request_id=?", (invalid, body["request_id"]))
    monkeypatch.setattr(assistant, "completion", lambda *args: pytest.fail("invalid cache triggered model"))
    response = client.post(f"/assistant/{operation}/status", json=body)
    assert response.status_code == 200
    assert response.json() == dict(request_id=body["request_id"], operation=operation,
                                  state="unresolved", result=None, error_status=None)


def test_status_checks_identity_before_exposing_any_state(database):
    request_id = str(uuid4())
    with pytest.raises(HTTPException):
        propose(request_id, "chat", {"message": "private"}, lambda: (_ for _ in ()).throw(HTTPException(502)))
    with pytest.raises(HTTPException) as error:
        status(request_id, "chat", {"message": "different"})
    assert error.value.status_code == 409
