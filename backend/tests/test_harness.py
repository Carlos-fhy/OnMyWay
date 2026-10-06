import sqlite3
from concurrent.futures import ThreadPoolExecutor
from threading import Event
from uuid import uuid4

import pytest
from fastapi import HTTPException
from backend.harness import propose
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
