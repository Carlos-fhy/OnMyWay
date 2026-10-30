"""Context facts are bounded client observations, not server task authority."""
import copy
import json
import time
from datetime import datetime, timezone
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from backend import assistant
from backend.harness import propose
from backend.main import app
from backend.models import Leg


PLACE = {"id": "dorm", "name": "宿舍", "longitude": 120.5, "latitude": 31.2, "address": ""}


def fact(identity="print", **changes):
    return {"id": identity, "title": "打印论文", "priority": 2, "service_minutes": 5,
            "deadline": "", "place": PLACE, "place_type": "PRINT_SHOP", "status": "pending",
            "planned_date": "", "reminder_at": 0, "revision": "a" * 64, **changes}


def snapshot(*tasks, **changes):
    return {"schema_version": 1, "snapshot_id": str(uuid4()), "captured_at": int(time.time() * 1000),
            "task_scope": "all", "tasks": list(tasks), "plan": None,
            "location": {"source": "unknown", "observed_at": None, "latitude": None,
                         "longitude": None, "accuracy_m": None},
            "device": {"navigation_state": "idle"}, **changes}


def request(operation="chat", context=None):
    common = {"context_snapshot": context} if context is not None else {}
    if operation == "chat":
        return {**common, "request_id": str(uuid4()), "now": datetime.now(timezone.utc).isoformat(),
                "messages": [{"role": "user", "content": "取快递、打印论文，然后回宿舍"}]}
    if operation == "navigation":
        return {**common, "request_id": str(uuid4()), "text": "不打印了，取快递后回宿舍", "destination": "宿舍",
                "tasks": [{"id": "print", "title": "打印论文", "place_type": "PRINT_SHOP"}]}
    if operation == "candidates":
        return {**common, "now": datetime.now(timezone.utc).isoformat(), "tasks": [
            {"id": "print", "title": "打印论文", "place_type": "PRINT_SHOP", "place": PLACE,
             "deadline": None, "planned_date": ""}]}
    return {**common, "origin": PLACE, "destination": PLACE,
            "departure": datetime.now(timezone.utc).isoformat(), "tasks": [
                {"id": "print", "title": "打印论文", "priority": 2, "service_minutes": 5,
                 "deadline": None, "place": PLACE}]}


PATHS = {"chat": "/assistant/chat", "navigation": "/assistant/navigation",
         "plans": "/plans", "recommendations": "/recommendations", "candidates": "/tasks/route-candidates"}


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setenv("ONMYWAY_HARNESS_DB", str(tmp_path / "context.sqlite3"))
    monkeypatch.setattr(assistant, "completion", lambda *args: '{"action":"reply","reply":"请确认"}')
    class Maps:
        key = "test"
        def route(self, origin, destination, mode):
            return Leg(seconds=100, meters=80)
    monkeypatch.setattr(app.state, "provider", Maps())
    with TestClient(app) as http:
        yield http


@pytest.mark.parametrize("operation", PATHS)
def test_context_contract_is_accepted_by_every_decision_endpoint(client, monkeypatch, operation):
    if operation == "navigation":
        monkeypatch.setattr(assistant, "completion", lambda *args: json.dumps(
            {"action": "adjust", "destination": "宿舍", "keep_task_ids": [], "reply": "请确认"}))
    response = client.post(PATHS[operation], json=request(operation, snapshot(fact())))
    assert response.status_code == 200, response.text


@pytest.mark.parametrize("change", [
    {"schema_version": True}, {"schema_version": 2}, {"snapshot_id": "invalid"},
    {"captured_at": True}, {"captured_at": "123"}, {"task_scope": "guessed"}, {"extra": "forbidden"},
    {"tasks": [fact(revision="A" * 64)]}, {"tasks": [fact(revision="a")]},
    {"tasks": [fact(), fact()]}, {"tasks": [fact(priority=True)]},
    {"proposed_task_ids": ["missing"]}, {"tasks": [fact()], "proposed_task_ids": ["print", "print"]},
    {"tasks": [fact(reminder_at=-1)]}, {"tasks": [fact(planned_date="2026-02-30")]},
    {"tasks": [fact(deadline="2026-10-28T10:00:00")]},
    {"location": {"source": "device", "observed_at": 0, "latitude": 91, "longitude": 1, "accuracy_m": 2}},
    {"location": {"source": "unknown", "observed_at": None, "latitude": None, "longitude": 1, "accuracy_m": None}},
    {"device": {"navigation_state": "invented"}},
])
def test_malformed_snapshot_is_rejected_before_model(client, monkeypatch, change):
    monkeypatch.setattr(assistant, "completion", lambda *args: pytest.fail("invalid context reached model"))
    response = client.post(PATHS["chat"], json=request(context=snapshot(**change)))
    assert response.status_code == 422


@pytest.mark.parametrize("operation", ["navigation", "plans", "recommendations", "candidates"])
@pytest.mark.parametrize("changed", [{"status": "done"}, {"title": "打印两份论文"}, {"id": "removed"}])
def test_request_tasks_must_match_pending_snapshot_facts(client, monkeypatch, operation, changed):
    monkeypatch.setattr(assistant, "completion", lambda *args: pytest.fail("conflicting context reached model"))
    response = client.post(PATHS[operation], json=request(operation, snapshot(fact(**changed))))
    assert response.status_code == 422


@pytest.mark.parametrize("operation,changed", [
    ("plans", {"priority": 3}), ("plans", {"service_minutes": 15}),
    ("plans", {"deadline": "2099-10-28T10:00:00+08:00"}),
    ("navigation", {"place_type": "PARCEL"}), ("candidates", {"planned_date": "2026-10-28"}),
])
def test_endpoint_specific_task_fields_are_checked(client, operation, changed):
    response = client.post(PATHS[operation], json=request(operation, snapshot(fact(**changed))))
    assert response.status_code == 422


def test_plan_may_use_a_user_selected_place_override(client):
    body = request("plans", snapshot(fact()))
    body["tasks"][0]["place"] = {**PLACE, "id": "other-shop", "name": "另一家打印店"}
    assert client.post(PATHS["plans"], json=body).status_code == 200


@pytest.mark.parametrize("operation", PATHS)
def test_expired_snapshot_cannot_submit_new_work(client, monkeypatch, operation):
    monkeypatch.setattr(assistant, "completion", lambda *args: pytest.fail("expired context reached model"))
    old = snapshot(fact(), captured_at=int(time.time() * 1000) - 31 * 60 * 1000)
    response = client.post(PATHS[operation], json=request(operation, old))
    assert response.status_code == 409


def test_model_receives_structured_current_facts_with_observation_boundary(client, monkeypatch):
    calls = []
    def complete(messages, *_):
        calls.append(messages)
        return '{"action":"reply","reply":"请确认"}'
    monkeypatch.setattr(assistant, "completion", complete)
    body = request(context=snapshot(fact(), fact("parcel", title="取快递", place_type="PARCEL")))
    assert client.post(PATHS["chat"], json=body).status_code == 200
    prompt = calls[0][0]["content"]
    assert "context_snapshot=" in prompt
    assert "打印论文" in prompt and "取快递" in prompt
    assert "不是执行授权" in prompt


@pytest.mark.parametrize("operation", ["chat", "navigation"])
def test_snapshot_revision_binds_request_identity_and_status(client, monkeypatch, operation):
    output = {"action": "reply", "reply": "请确认"} if operation == "chat" else {
        "action": "adjust", "keep_task_ids": [], "reply": "请确认"}
    monkeypatch.setattr(assistant, "completion", lambda *args: json.dumps(output))
    body = request(operation, snapshot(fact()))
    initial = client.post(PATHS[operation], json=body)
    assert initial.status_code == 200
    changed = copy.deepcopy(body)
    changed["context_snapshot"]["tasks"][0]["revision"] = "b" * 64
    assert client.post(PATHS[operation], json=changed).status_code == 409
    assert client.post(PATHS[operation] + "/status", json=changed).status_code == 409
    monkeypatch.setattr(assistant, "completion", lambda *args: pytest.fail("recovery repeated model"))
    import backend.context as context
    monkeypatch.setattr(context.time, "time", lambda: body["context_snapshot"]["captured_at"] / 1000 + 31 * 60)
    recovered = client.post(PATHS[operation] + "/status", json=body)
    assert recovered.status_code == 200
    assert recovered.json()["result"] == initial.json()


@pytest.mark.parametrize("operation", ["chat", "navigation"])
def test_absent_or_null_context_recovers_pre_context_receipts(client, monkeypatch, operation):
    body = request(operation)
    model = assistant.ChatRequest if operation == "chat" else assistant.NavigationRequest
    payload = model.model_validate(body).model_dump(mode="json", exclude={"request_id", "context_snapshot"})
    if operation == "chat":
        payload.pop("draft_items", None)
    output = {"action": "reply", "reply": "old result"} if operation == "chat" else {
        "action": "adjust", "keep_task_ids": [], "reply": "old result"}
    propose(body["request_id"], operation, payload, lambda: output)
    monkeypatch.setattr(assistant, "completion", lambda *args: pytest.fail("legacy recovery repeated model"))
    for include_null in (False, True):
        if include_null:
            body["context_snapshot"] = None
        response = client.post(PATHS[operation] + "/status", json=body)
        assert response.status_code == 200, response.text
        assert response.json()["state"] == "completed"


def plan(**changes):
    return {"plan_id": "parcel-print-dorm", "plan_version": 2, "session_id": "trip-1",
            "revision": "c" * 64, "started": False, "needs_replan": False, **changes}


def location(source="device", **changes):
    return {"source": source, "observed_at": int(time.time() * 1000), "latitude": 31.2,
            "longitude": 120.5, "accuracy_m": 4.0, **changes}


@pytest.mark.parametrize("observed", [
    location(observed_at=None), location(latitude=None, longitude=None),
    location("cached", observed_at=None), location("selected"),
    location("selected", observed_at=None, latitude=None, longitude=None),
    location("unknown", observed_at=None),
    location("unknown", observed_at=None, latitude=None, longitude=None),
])
def test_location_source_requires_corresponding_evidence(client, observed):
    response = client.post(PATHS["chat"], json=request(context=snapshot(location=observed)))
    assert response.status_code == 422


@pytest.mark.parametrize("observed", [location(), location("cached"),
    location("selected", observed_at=None),
    location("unknown", observed_at=None, latitude=None, longitude=None, accuracy_m=None),
])
def test_valid_location_sources_are_accepted(client, observed):
    assert client.post(PATHS["chat"], json=request(context=snapshot(location=observed))).status_code == 200


@pytest.mark.parametrize("source", ["device", "cached"])
def test_old_device_location_cannot_drive_a_new_decision(client, source):
    observed = location(source, observed_at=int(time.time() * 1000) - 6 * 60 * 1000)
    assert client.post(PATHS["chat"], json=request(context=snapshot(location=observed))).status_code == 409


@pytest.mark.parametrize("current_plan,state", [
    (None, "started"), (plan(), "idle"), (plan(), "started"),
    (plan(started=True), "ready"), (plan(needs_replan=True), "ready"),
    (plan(started=True, needs_replan=True), "started"),
])
def test_navigation_state_must_agree_with_current_plan(client, current_plan, state):
    value = snapshot(plan=current_plan, device={"navigation_state": state})
    assert client.post(PATHS["chat"], json=request(context=value)).status_code == 422


@pytest.mark.parametrize("current_plan,state", [
    (None, "idle"), (plan(), "ready"), (plan(started=True), "started"),
    (plan(needs_replan=True), "needs_replan"), (plan(started=True, needs_replan=True), "needs_replan"),
])
def test_valid_plan_navigation_state_is_accepted(client, current_plan, state):
    value = snapshot(plan=current_plan, device={"navigation_state": state})
    assert client.post(PATHS["chat"], json=request(context=value)).status_code == 200


def test_main_scenario_cancelled_printing_does_not_reappear_from_history(client, monkeypatch):
    value = snapshot(fact("parcel", title="取快递", place_type="PARCEL"), fact(status="done"))
    old = request("navigation", value)
    old["tasks"].append({"id": "parcel", "title": "取快递", "place_type": "PARCEL"})
    assert client.post(PATHS["navigation"], json=old).status_code == 422
    fresh = copy.deepcopy(old)
    fresh["tasks"] = [fresh["tasks"][1]]
    monkeypatch.setattr(assistant, "completion", lambda *args: json.dumps(
        {"action": "adjust", "destination": "宿舍", "keep_task_ids": ["parcel"], "reply": "取快递后回宿舍"}))
    result = client.post(PATHS["navigation"], json=fresh)
    assert result.status_code == 200
    assert result.json()["keep_task_ids"] == ["parcel"]
    assert result.json()["destination"] == "宿舍"
