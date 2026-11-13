"""Audit production evaluation records; all models and MCP data stay local."""
from copy import deepcopy
import importlib.util
import json
from uuid import uuid4

import pytest

from backend.agent.mcp import McpClient, McpServerConfig
from backend.agent.store import AgentStore
from backend.agent.tools import ToolCatalog
from backend.evaluation.agent import EvaluationSession
from backend.tests.agent.test_agent_mcp import query_tool, server


def validate(record):
    assert importlib.util.find_spec("backend.evaluation.delegation_validation") is not None, "Delegation fact validator is missing"
    from backend.evaluation.delegation_validation import validate_delegation_phase
    return validate_delegation_phase(record)


def make_record(path, *, delegated=True, direct_source=False):
    store, catalog = AgentStore(path / "agent.sqlite3"), ToolCatalog()
    task = store.create({"request_id": str(uuid4()), "conversation_id": "delegation-audit-fixture",
        "message_id": str(uuid4()), "goal": "Use a read-only child to query public place facts and deliver sourced text.",
        "budget": {"max_steps": 24, "max_tool_calls": 12, "max_duration_seconds": 300}})
    steps = {}

    def completion(messages, model, structured=False):
        data = json.loads(messages[-1]["content"])
        child = bool(data.get("delegation"))
        step = steps[child] = steps.get(child, 0) + 1
        publish = {"action": "publish_artifact", "kind": "text", "title": "Public place facts",
                   "content": {"address": "Synthetic public address", "coordinates": [104.0, 30.6]}, "evidence_call_ids": []}
        finish = {"action": "finish", "summary": "Delivered sourced facts; no navigation or write occurred."}
        if not delegated:
            action = publish if step == 1 else finish
        elif child:
            if step == 1:
                action = {"action": "discover_tools", "query": "places.search"}
            elif step == 2:
                action = {"action": "call_tool", "tool_id": "places.search", "arguments": {"city": "成都"}}
            elif step == 3:
                action = {**publish, "evidence_call_ids": [data["tool_results"][-1]["tool_call_id"]]}
            else:
                action = finish
        elif step == 1:
            action = {"action": "delegate_task", "delegation_key": "public_facts", "goal": "Query places.search for public place facts.",
                      "allowed_tools": ["places.search"], "budget": {"max_steps": 6, "max_tool_calls": 2, "max_duration_seconds": 120}}
        elif step == 2:
            action = {"action": "wait_children"}
        elif step == 3:
            action = {"action": "discover_tools", "query": "agent.read_artifact"}
        elif step == 4:
            output = data["delegations"][0]["outputs"][0]
            action = {"action": "call_tool", "tool_id": "agent.read_artifact", "arguments": {
                "artifact_id": output["artifact_id"], "version": output["version"]}}
        elif step == 5:
            action = {"action": "review_delegation", "delegation_key": "public_facts", "verdict": "accept", "reason": "Read the complete submitted version."}
        elif step == 6:
            if direct_source:
                child_snapshot = store.get(data["delegations"][0]["child_task_id"])
                source = child_snapshot["tool_calls"][0]["tool_call_id"]
            else:
                source = store.get(task["agent_task_id"])["tool_calls"][-1]["tool_call_id"]
            action = {**publish, "evidence_call_ids": [source]}
        else:
            action = finish
        return json.dumps(action, ensure_ascii=False)

    with server(label="places", tools=[query_tool()]) as (url, remote):
        remote["result"] = {"address": "Synthetic public address", "coordinates": [104.0, 30.6]}
        with McpClient(McpServerConfig("places", url, {"search": "read"})) as client:
            client.register_tools(catalog)
            session = EvaluationSession(store, catalog, path / "report.json", completion_fn=completion,
                                        delegation_enabled=True, max_model_calls=24, max_tool_calls=12)
            record = session.run(task["agent_task_id"], "delegation")
        assert remote["calls"] == int(delegated)
    assert record["execution_status"] == "completed", record["snapshot"]["run"].get("reason")
    return record


@pytest.fixture(scope="module")
def phase(tmp_path_factory):
    return make_record(tmp_path_factory.mktemp("delegation-record"))


def test_actual_store_mcp_and_evaluation_chain_passes_without_semantic_claim(phase):
    before = deepcopy(phase)
    result = validate(phase)
    assert result["status"] == "passed", result["failures"]
    assert result["semantic_review"] == "not_run"
    assert result["evidence"]["delegations"]
    assert result["evidence"]["external_sources"] == ["mcp:fixture:places"]
    assert result["evidence"]["official_sources_only"] is False
    assert result["checks"]["official_sources_only"] is False
    assert phase == before


def test_publication_event_may_follow_artifact_storage(phase):
    record = deepcopy(phase)
    publication = next(item for item in record["events"] if item["kind"] == "artifact_published")
    completed = next(item for item in record["events"] if item["kind"] == "run_completed")
    publication["created_at"] += 0.001
    completed["created_at"] = max(completed["created_at"], publication["created_at"] + 0.001)
    result = validate(record)
    assert result["status"] == "passed", result["failures"]


def test_parent_may_cite_explicitly_shared_child_receipt(tmp_path):
    result = validate(make_record(tmp_path, direct_source=True))
    assert result["status"] == "passed", result["failures"]


def test_enabled_and_completed_without_a_child_is_not_exercised(tmp_path):
    phase = make_record(tmp_path, delegated=False)
    phase["counts"] = {"model_calls": 100, "tool_calls": 100}
    phase["delegation_enabled"] = True
    result = validate(phase)
    assert result["status"] == "not_exercised"
    assert result["semantic_review"] == "not_run"


@pytest.mark.parametrize("damage", [
    "missing_child", "unlinked_child", "old_parent_revision", "duplicate_child", "nested_child",
    "wrong_call_owner", "wrong_dispatch_owner", "wrong_dispatch_tool", "wrong_dispatch_version", "missing_dispatch", "duplicate_dispatch",
    "wrong_model_owner", "missing_child_model", "wrong_model_arguments", "outside_scope", "write", "unknown",
    "builtin_only", "wrong_result_tool", "wrong_result_version", "mcp_error",
    "missing_submission", "wrong_submission_version", "missing_accept_event", "rejected", "missing_accept_action",
    "implicit_read_version", "partial_read", "forged_complete_read", "wrong_read_version", "read_after_accept",
    "unreferenced_child_source", "unreferenced_parent_source", "unshared_source", "missing_parent_publish", "publish_before_accept",
    "missing_parent_finish", "parent_not_completed", "child_not_completed",
])
def test_incomplete_or_tampered_chains_cannot_pass(phase, damage):
    record = deepcopy(phase)
    root = record["snapshot"]
    child = record["children"][0]["snapshot"]
    link = root["delegations"][0]
    source, read = child["tool_calls"][0], root["tool_calls"][0]
    models = record["model_calls"]
    child_model = next(item for item in models if item["agent_run_id"] == child["current_run_id"] and (item.get("action") or {}).get("action") == "call_tool")
    read_model = next(item for item in models if (item.get("action") or {}).get("tool_id") == "agent.read_artifact")
    accept = next(item for item in record["events"] if item["kind"] == "observation:child_reviewed")
    publication = next(item for item in record["events"] if item["kind"] == "artifact_published")
    if damage == "missing_child": record["children"] = []
    elif damage == "unlinked_child": link["child_run_id"] = "another-run"
    elif damage == "old_parent_revision": child["coordinator_requirements"]["revision"] = 99
    elif damage == "duplicate_child": record["children"].append(deepcopy(record["children"][0]))
    elif damage == "nested_child": child["delegations"] = [deepcopy(link)]
    elif damage == "wrong_call_owner": source["agent_run_id"] = root["current_run_id"]
    elif damage == "wrong_dispatch_owner": record["tool_dispatches"][0]["agent_run_id"] = root["current_run_id"]
    elif damage == "wrong_dispatch_tool": record["tool_dispatches"][0]["tool_id"] = "places.other"
    elif damage == "wrong_dispatch_version": record["tool_dispatches"][0]["tool_version"] = "other"
    elif damage == "missing_dispatch": record["tool_dispatches"] = record["tool_dispatches"][1:]
    elif damage == "duplicate_dispatch": record["tool_dispatches"].append(deepcopy(record["tool_dispatches"][0]))
    elif damage == "wrong_model_owner": child_model["agent_task_id"] = root["agent_task_id"]
    elif damage == "missing_child_model": record["model_calls"] = [item for item in models if item["agent_run_id"] != child["current_run_id"]]
    elif damage == "wrong_model_arguments": child_model["action"]["arguments"] = {"city": "different"}
    elif damage == "outside_scope": link["allowed_tools"] = child["delegation"]["allowed_tools"] = []
    elif damage == "write": source["effect"] = "write"
    elif damage == "unknown": source["state"] = "unknown"
    elif damage == "builtin_only": source["result"]["source"] = "builtin:places"
    elif damage == "wrong_result_tool": source["result"]["tool_id"] = "places.other"
    elif damage == "wrong_result_version": source["result"]["version"] = "other"
    elif damage == "mcp_error": source["result"]["data"]["isError"] = True
    elif damage == "missing_submission": record["events"] = [item for item in record["events"] if item["kind"] != "observation:child_result"]
    elif damage == "wrong_submission_version": link["outputs"][0]["version"] += 1
    elif damage == "missing_accept_event": record["events"].remove(accept)
    elif damage == "rejected": link["status"] = "rejected"
    elif damage == "missing_accept_action": record["model_calls"] = [item for item in models if (item.get("action") or {}).get("action") != "review_delegation"]
    elif damage == "implicit_read_version": read["arguments"].pop("version"); read_model["action"]["arguments"].pop("version")
    elif damage == "partial_read": read["result"]["data"]["has_more"] = True
    elif damage == "forged_complete_read":
        page = read["result"]["data"]
        page.update(text="{}", next_offset=2, total_chars=2, has_more=False)
    elif damage == "wrong_read_version": read["result"]["data"]["version"] += 1
    elif damage == "read_after_accept": read["updated_at"] = accept["created_at"] + 10
    elif damage == "unreferenced_child_source": child["artifacts"][0]["sources"] = []
    elif damage == "unreferenced_parent_source": root["artifacts"][0]["sources"] = []
    elif damage == "unshared_source": root["artifacts"][0]["sources"] = [{"tool_call_id": "other-task-call"}]
    elif damage == "missing_parent_publish": record["events"].remove(publication)
    elif damage == "publish_before_accept": publication["created_at"] = accept["created_at"] - 1
    elif damage == "missing_parent_finish": record["model_calls"] = [item for item in models if not (item["agent_run_id"] == root["current_run_id"] and (item.get("action") or {}).get("action") == "finish")]
    elif damage == "parent_not_completed": root["run"]["state"] = "paused"
    elif damage == "child_not_completed": child["run"]["state"] = "paused"
    result = validate(record)
    assert result["status"] == "failed", (damage, result)
    assert result["failures"]
    assert result["semantic_review"] == "not_run"


@pytest.mark.parametrize("record", [None, {}, {"snapshot": []}, {"snapshot": {"run": {}}}])
def test_malformed_record_fails_closed(record):
    assert validate(record)["status"] == "failed"
