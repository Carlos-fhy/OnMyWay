"""User-supplied delivery requirements are enforced before persistence/completion."""
from uuid import uuid4

import pytest
from fastapi import HTTPException

from backend.agent.store import AgentStore


def create(store, contract):
    return store.create({"request_id": str(uuid4()), "conversation_id": "delivery",
        "message_id": str(uuid4()), "goal": "Produce the requested record", "success_criteria": [],
        "constraints": {"delivery_contract": contract}})


@pytest.mark.parametrize("contract", [
    {"kind": "bad kind"}, {"kind": "route", "permission": "approved"},
    {"completion_mode": "execute"}, {"content_schema": {"type": "invalid"}},
    {"content_schema": {"type": "object", "properties": {"x": {"$ref": "https://example.org/schema"}}}},
    {"content_schema": {"type": "object", "description": "x" * 9000}},
    None,
    {"max_artifacts": True}, {"max_artifacts": 0}, {"max_artifacts": 9},
])
def test_invalid_delivery_contract_is_rejected_at_creation(tmp_path, contract):
    store = AgentStore(tmp_path / "agent.sqlite3")
    with pytest.raises(HTTPException) as error:
        create(store, contract)
    assert error.value.status_code == 422
    assert not store.list_tasks()


def test_store_rejects_wrong_kind_and_missing_fields_without_persisting(tmp_path):
    store = AgentStore(tmp_path / "agent.sqlite3")
    task = create(store, {"kind": "record", "content_schema": {
        "type": "object", "properties": {"count": {"type": "integer"}}, "required": ["count"]}})
    lease = store.claim(task["current_run_id"], "test")["lease_token"]
    for kind, value in [("text", {"count": 7}), ("record", {}), ("record", {"count": "7"})]:
        with pytest.raises(HTTPException) as error:
            store.publish_artifact(task["current_run_id"], lease, kind, {"title": "Test", "content": value})
        assert error.value.status_code == 422
        assert not store.get(task["agent_task_id"])["artifacts"]
    artifact = store.publish_artifact(task["current_run_id"], lease, "record", {"title": "Test", "content": {"count": 7}})
    assert artifact["version"] == 1


def test_waiting_contract_blocks_completion_and_revision_can_replace_it(tmp_path):
    store = AgentStore(tmp_path / "agent.sqlite3")
    task = create(store, {"completion_mode": "await_user"})
    lease = store.claim(task["current_run_id"], "test")["lease_token"]
    store.publish_artifact(task["current_run_id"], lease, "text", {"content": "A draft awaiting a decision"})
    with pytest.raises(HTTPException) as error:
        store.transition(task["current_run_id"], lease, "completed", reason="Waiting for confirmation")
    assert error.value.status_code == 422
    assert store.get(task["agent_task_id"])["run"]["state"] == "running"
    store.transition(task["current_run_id"], lease, "waiting_user", reason="Please decide")
    revised = store.revise(task["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1,
        "goal": "Cancel draft and deliver the rest", "constraints": {}, "success_criteria": []})
    lease = store.claim(revised["current_run_id"], "test")["lease_token"]
    store.publish_artifact(revised["current_run_id"], lease, "text", {"content": "Remaining plan"})
    assert store.transition(revised["current_run_id"], lease, "completed", reason="Delivered")["state"] == "completed"


def test_single_delivery_rejects_second_identity_but_allows_versioned_update(tmp_path):
    store = AgentStore(tmp_path / "agent.sqlite3")
    task = create(store, {"max_artifacts": 1})
    lease = store.claim(task["current_run_id"], "test")["lease_token"]
    first = store.publish_artifact(task["current_run_id"], lease, "text", {"content": "draft"})
    with pytest.raises(HTTPException) as error:
        store.publish_artifact(task["current_run_id"], lease, "text", {"content": "changed"})
    assert error.value.status_code == 422
    assert error.value.detail["code"] == "artifact_contract_mismatch"
    assert first["artifact_id"] in error.value.detail["message"]
    updated = store.publish_artifact(task["current_run_id"], lease, "text", {"content": "changed"},
                                     artifact_id=first["artifact_id"], expected_version=1)
    assert updated["version"] == 2 and len(store.get(task["agent_task_id"])["artifacts"]) == 1
