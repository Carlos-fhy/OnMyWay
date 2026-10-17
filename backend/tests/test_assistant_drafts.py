"""M1 structured draft contract; model calls are stubbed, never paid requests."""
import copy
import json
from uuid import uuid4

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from backend import assistant
from backend.harness import propose


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setenv("ONMYWAY_HARNESS_DB", str(tmp_path / "draft-runs.sqlite3"))
    app = FastAPI()
    app.include_router(assistant.router)
    with TestClient(app) as client:
        yield client


def task(**changes):
    return {"title": "取快递", "place_type": "PARCEL", "deadline": "",
            "planned_date": "2026-10-15", "priority": 2, **changes}


def body(*items):
    return {"request_id": str(uuid4()), "now": "2026-10-15T09:00:00+08:00",
            "messages": [{"role": "user", "content": "今天取快递、打印论文"}],
            "draft_items": list(items)}


def item(identity="parcel", version=1, **changes):
    return {"item_id": identity, "version": version, **task(**changes)}


def output(monkeypatch, value):
    monkeypatch.setattr(assistant, "completion", lambda *args: json.dumps(value, ensure_ascii=False))


def test_main_scenario_create_two_tasks_and_correct_only_one(client, monkeypatch):
    output(monkeypatch, {"action": "create_tasks", "reply": "请逐项确认", "tasks": [
        task(), task(title="打印论文", place_type="PRINT_SHOP")]})
    created = client.post("/assistant/chat", json=body())
    assert created.status_code == 200
    assert [row["title"] for row in created.json()["tasks"]] == ["取快递", "打印论文"]
    assert all(row["deadline"] == "" for row in created.json()["tasks"])

    current = body(item(), item("print", 4, title="打印论文", place_type="PRINT_SHOP"))
    current["messages"][-1]["content"] = "打印改成明天，优先处理"
    patch = {"item_id": "print", "expected_version": 4, "planned_date": "2026-10-16", "priority": 3}
    output(monkeypatch, {"action": "update_task_drafts", "reply": "请核对打印事项", "updates": [patch]})
    changed = client.post("/assistant/chat", json=current)
    assert changed.status_code == 200
    assert changed.json()["updates"] == [patch]
    # Omitted fields stay omitted both on wire and when the result is recovered.
    recovered = client.post("/assistant/chat/status", json=current).json()
    assert recovered["state"] == "completed"
    assert recovered["result"]["updates"] == [patch]


@pytest.mark.parametrize("tasks", [[], [task()] * 11, [task(title=" ")],
    [task(planned_date="2026-02-30")], [task(planned_date="20261015")],
    [task(planned_date="2026-W42-4")], [task(planned_date="2026-10-15T00:00:00")],
    [task(deadline="2026-10-15T12:00:00")], [task(deadline="tomorrow")],
    [task(priority=0)], [task(priority=4)], [task(priority=True)], [task(priority="1")],
    [task(place_type="OTHER")], [task(item_id="model-generated")]])
def test_invalid_creation_never_becomes_executable(client, monkeypatch, tasks):
    output(monkeypatch, {"action": "create_tasks", "reply": "请确认", "tasks": tasks})
    current = body()
    assert client.post("/assistant/chat", json=current).status_code == 502
    result = client.post("/assistant/chat/status", json=current).json()
    assert result["state"] == "failed" and result["result"] is None


@pytest.mark.parametrize("updates", [[],
    [{"item_id": "missing", "expected_version": 1, "title": "改名"}],
    [{"item_id": "parcel", "expected_version": 2, "title": "改名"}],
    [{"item_id": "parcel", "expected_version": 1, "title": "改名"}] * 2,
    [{"item_id": "parcel", "expected_version": 1}],
    [{"item_id": "parcel", "expected_version": 1, "deadline": None}],
    [{"item_id": "parcel", "expected_version": 1, "title": " "}],
    [{"item_id": "parcel", "expected_version": 1, "planned_date": "2026-02-30"}],
    [{"item_id": "parcel", "expected_version": 1, "priority": False}],
    [{"item_id": "parcel", "expected_version": True, "title": "改名"}],
    [{"item_id": "parcel", "expected_version": 1, "place_id": "guessed"}],
])
def test_invalid_updates_are_rejected_atomically(client, monkeypatch, updates):
    output(monkeypatch, {"action": "update_task_drafts", "reply": "请确认", "updates": updates})
    assert client.post("/assistant/chat", json=body(item())).status_code == 502


def test_one_unknown_update_invalidates_the_entire_patch(client, monkeypatch):
    output(monkeypatch, {"action": "update_task_drafts", "reply": "请确认", "updates": [
        {"item_id": "parcel", "expected_version": 1, "priority": 1},
        {"item_id": "already-confirmed", "expected_version": 1, "title": "不得恢复"}]})
    assert client.post("/assistant/chat", json=body(item())).status_code == 502


def test_clear_date_is_explicit_and_does_not_reinsert_other_fields(client, monkeypatch):
    patch = {"item_id": "parcel", "expected_version": 1, "planned_date": "", "deadline": ""}
    output(monkeypatch, {"action": "update_task_drafts", "reply": "已修改草稿", "updates": [patch]})
    response = client.post("/assistant/chat", json=body(item(deadline="2026-10-15T18:00:00+08:00")))
    assert response.status_code == 200
    assert response.json()["updates"] == [patch]


@pytest.mark.parametrize("proposal", [
    {"action": "create_task", "title": "打印论文"},
    {"action": "create_tasks", "tasks": [task()]},
    {"action": "plan_route", "destination": "宿舍"},
])
def test_pending_items_cannot_be_replaced_or_lost(client, monkeypatch, proposal):
    output(monkeypatch, {"reply": "请确认", **proposal})
    assert client.post("/assistant/chat", json=body(item())).status_code == 502


def test_update_requires_current_structured_items(client, monkeypatch):
    output(monkeypatch, {"action": "update_task_drafts", "reply": "请确认", "updates": [
        {"item_id": "parcel", "expected_version": 1, "title": "取两件快递"}]})
    assert client.post("/assistant/chat", json=body()).status_code == 502


@pytest.mark.parametrize("items", [[item(), item()], [item(version=0)], [item(version=True)],
    [item(identity=" ")], [item(priority=4)], [item(planned_date="2026-02-30")],
    [item(str(index)) for index in range(11)]])
def test_invalid_current_items_never_call_the_model(client, monkeypatch, items):
    monkeypatch.setattr(assistant, "completion", lambda *args: pytest.fail("invalid request reached model"))
    assert client.post("/assistant/chat", json=body(*items)).status_code == 422


def test_prompt_carries_current_id_version_and_actual_empty_dates(client, monkeypatch):
    current = body(item("print", 8, planned_date="", deadline=""))
    def complete(messages, *args):
        prompt = messages[0]["content"]
        assert '"item_id": "print"' in prompt and '"version": 8' in prompt
        assert '"planned_date": ""' in prompt and '"deadline": ""' in prompt
        assert "不得从旧对话恢复其他条目" in prompt
        assert "1=普通，2=较重要，3=重要（最高）" in prompt
        return json.dumps({"action": "reply", "reply": "请说明修改哪一项。"})
    monkeypatch.setattr(assistant, "completion", complete)
    assert client.post("/assistant/chat", json=current).status_code == 200


def test_old_request_receipt_hash_is_unchanged_when_items_absent_or_empty(client, monkeypatch):
    current = body()
    # Reproduce the old ChatRequest wire fingerprint without the new field.
    legacy = assistant.ChatRequest.model_validate(current).model_dump(mode="json", exclude={"request_id", "draft_items"})
    legacy_result = {"action": "create_task", "reply": "请确认", "title": "取快递", "place_type": "PARCEL"}
    propose(current["request_id"], "chat", legacy, lambda: legacy_result)
    monkeypatch.setattr(assistant, "completion", lambda *args: pytest.fail("legacy receipt reexecuted"))
    for include_empty in (False, True):
        wire = copy.deepcopy(current)
        if not include_empty:
            wire.pop("draft_items")
        result = client.post("/assistant/chat", json=wire)
        assert result.status_code == 200 and result.json()["action"] == "create_task"
        recovered = client.post("/assistant/chat/status", json=wire).json()
        assert recovered["state"] == "completed"
        assert recovered["result"] == result.json()


def test_receipt_is_bound_to_the_exact_current_items(client, monkeypatch):
    current = body(item())
    output(monkeypatch, {"action": "reply", "reply": "请确认剩余草稿"})
    assert client.post("/assistant/chat", json=current).status_code == 200
    current["draft_items"][0]["version"] = 2
    monkeypatch.setattr(assistant, "completion", lambda *args: pytest.fail("conflicting receipt reexecuted"))
    for endpoint in ("/assistant/chat", "/assistant/chat/status"):
        assert client.post(endpoint, json=current).status_code == 409
