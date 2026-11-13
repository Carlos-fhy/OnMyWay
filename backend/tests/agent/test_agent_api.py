"""Public task contracts exercise the production router with isolated persistence."""
import copy
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from backend.main import app


TOKEN = {"X-Backup-Token": "agent-foundation-test-token-0000000000"}


def goal_request(**changes):
    return {
        "request_id": str(uuid4()), "conversation_id": "conversation-travel",
        "message_id": str(uuid4()), "goal": "成都四天旅行，预算三千，别太累",
        "constraints": {"days": 4, "budget_cny": 3000, "pace": "relaxed"},
        "success_criteria": ["预算和日期有明确口径", "查询结果有来源"],
        **changes,
    }


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("ONMYWAY_AGENT_DB", str(tmp_path / "agent.sqlite3"))
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", TOKEN["X-Backup-Token"])
    monkeypatch.setenv("ONMYWAY_AGENT_WORKER_ENABLED", "false")
    with TestClient(app) as instance:
        yield instance


def create(client, payload=None):
    response = client.post("/agent/tasks", json=payload or goal_request(), headers=TOKEN)
    assert response.status_code == 201, response.text
    return response.json()


def test_delivery_contract_is_validated_and_preserved_by_http(client):
    contract = {"kind": "record", "completion_mode": "await_user", "content_schema": {
        "type": "object", "required": ["title"], "properties": {"title": {"type": "string"}}}}
    task = create(client, goal_request(constraints={"delivery_contract": contract}))
    read = client.get('/agent/tasks/' + task['agent_task_id'], headers=TOKEN)
    assert read.json()["constraints"]["delivery_contract"] == contract
    bad = client.post('/agent/tasks/' + task['agent_task_id'] + '/revisions', headers=TOKEN, json={
        "request_id": str(uuid4()), "expected_revision": 1, "goal": "Revise", "success_criteria": [],
        "constraints": {"delivery_contract": {"completion_mode": "execute"}}})
    assert bad.status_code == 422
    assert client.get('/agent/tasks/' + task['agent_task_id'], headers=TOKEN).json()['revision'] == 1


def test_create_replay_survives_new_client_without_duplicate_task(client):
    payload = goal_request()
    first = create(client, payload)
    with TestClient(app) as second:
        replay = create(second, payload)
        tasks = second.get("/agent/tasks", headers=TOKEN)
    assert replay["agent_task_id"] == first["agent_task_id"]
    assert replay["current_run_id"] == first["current_run_id"]
    assert first["revision"] == 1
    assert first["run"]["state"] == "queued"
    assert len(tasks.json()["tasks"]) == 1


def test_conflicting_request_and_message_identity_cannot_create_second_task(client):
    payload = goal_request()
    original = create(client, payload)
    changed = {**payload, "goal": "换成重庆"}
    assert client.post("/agent/tasks", json=changed, headers=TOKEN).status_code == 409
    same_message_new_request = {**changed, "request_id": str(uuid4())}
    assert client.post("/agent/tasks", json=same_message_new_request, headers=TOKEN).status_code == 409
    assert client.get(f'/agent/tasks/{original["agent_task_id"]}', headers=TOKEN).json()["goal"] == payload["goal"]


def test_concurrent_submission_has_one_task_and_one_run(client):
    payload = goal_request()
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda _: create(client, payload), range(4)))
    assert len({r["agent_task_id"] for r in results}) == 1
    assert len({r["current_run_id"] for r in results}) == 1


def test_revision_is_atomic_and_old_revision_cannot_change_new_goal(client):
    first = create(client)
    task_id = first["agent_task_id"]
    command = {
        "request_id": str(uuid4()), "expected_revision": 1,
        "goal": "成都四天，第二天只安排一个景点",
        "constraints": {"days": 4, "day2_max_stops": 1, "budget_cny": 3000},
        "success_criteria": ["第二天轻松"],
    }
    updated = client.post(f"/agent/tasks/{task_id}/revisions", json=command, headers=TOKEN)
    assert updated.status_code == 200, updated.text
    assert updated.json()["revision"] == 2
    assert updated.json()["current_run_id"] != first["current_run_id"]
    assert client.post(f"/agent/tasks/{task_id}/revisions", json=command, headers=TOKEN).json() == updated.json()
    stale = {**command, "request_id": str(uuid4()), "goal": "旧计划"}
    assert client.post(f"/agent/tasks/{task_id}/revisions", json=stale, headers=TOKEN).status_code == 409
    assert client.get(f"/agent/tasks/{task_id}", headers=TOKEN).json()["goal"] == command["goal"]


def test_cancel_is_repeatable_and_does_not_rewrite_goal(client):
    first = create(client)
    task_id = first["agent_task_id"]
    command = {"request_id": str(uuid4()), "expected_revision": 1, "reason": "暂时不安排"}
    cancelled = client.post(f"/agent/tasks/{task_id}/cancel", json=command, headers=TOKEN)
    assert cancelled.status_code == 200, cancelled.text
    assert cancelled.json()["run"]["state"] == "cancelled"
    assert cancelled.json()["goal"] == first["goal"]
    assert client.post(f"/agent/tasks/{task_id}/cancel", json=command, headers=TOKEN).status_code == 200
    assert client.post(f"/agent/tasks/{task_id}/resume", json={**command, "request_id": str(uuid4())}, headers=TOKEN).status_code == 409


def test_event_cursor_replays_without_gaps_or_duplicate_events(client):
    task = create(client)
    url = f'/agent/tasks/{task["agent_task_id"]}/events'
    command = {"request_id": str(uuid4()), "expected_revision": 1, "reason": "取消此次规划"}
    client.post(f'/agent/tasks/{task["agent_task_id"]}/cancel', json=command, headers=TOKEN)
    all_events = client.get(url, headers=TOKEN).json()["events"]
    cursors, after = [], 0
    for _ in range(20):
        response = client.get(url, params={"after": after, "limit": 1}, headers=TOKEN)
        assert response.status_code == 200
        page = response.json()
        cursors.extend(e["cursor"] for e in page["events"])
        after = page["next_cursor"]
        if not page["has_more"]:
            break
    assert cursors == [e["cursor"] for e in all_events]
    assert len(cursors) == len(set(cursors)) and cursors


@pytest.mark.parametrize("changes", [
    {"goal": " "}, {"request_id": ""}, {"extra": "forbidden"},
    {"budget": {"max_steps": True}}, {"budget": {"max_tool_calls": 0}},
    {"constraints": {"large": "x" * 70000}},
])
def test_invalid_create_does_not_write_task(client, changes):
    assert client.post("/agent/tasks", json=goal_request(**changes), headers=TOKEN).status_code == 422
    assert client.get("/agent/tasks", headers=TOKEN).json()["tasks"] == []


def test_auth_required_and_worker_secrets_not_exposed(client):
    assert client.post("/agent/tasks", json=goal_request()).status_code == 401
    task = create(client)
    assert client.get(f'/agent/tasks/{task["agent_task_id"]}').status_code == 401
    assert client.get(f'/agent/tasks/{task["agent_task_id"]}/events').status_code == 401
    encoded = str(task)
    assert "receipt_token" not in encoded and "lease_token" not in encoded
    assert client.get(f'/agent/tasks/{uuid4()}', headers=TOKEN).status_code == 404


def test_cancel_draft_scenario_keeps_unrelated_constraints(client):
    before = create(client, goal_request(
        conversation_id="conversation-errands", goal="取快递 + 打印论文 + 回宿舍",
        constraints={"draft_ids": ["parcel", "print"], "destination_id": "dorm"},
    ))
    command = {"request_id": str(uuid4()), "expected_revision": 1,
               "goal": "取消未确认打印草稿，取快递后回宿舍",
               "constraints": {"draft_ids": ["parcel"], "destination_id": "dorm"},
               "success_criteria": ["不创建正式打印任务"]}
    response = client.post(f'/agent/tasks/{before["agent_task_id"]}/revisions', json=command, headers=TOKEN)
    assert response.status_code == 200
    assert response.json()["constraints"] == {"draft_ids": ["parcel"], "destination_id": "dorm"}
