"""Current explicit intent and its input facts survive a bounded workset."""
from copy import deepcopy
import json
from types import SimpleNamespace

import pytest

from backend.agent.decision import build_context, pending_schema_request, _compact_schema
from backend.agent.runtime import AgentRuntime
from backend.agent.tools import ToolCatalog, ToolDefinition, ToolError
from backend.paths import BACKEND_ROOT
from backend.tests.agent.context_assertions import assert_revision_receipt_visible
from backend.tests.agent.test_agent_decision import snapshot


def worker_for(descriptors):
    catalog = {item["tool_id"]: item for item in descriptors}
    ranked = ToolCatalog()
    for item in descriptors:
        namespace, name = item["tool_id"].split(".", 1)
        ranked.register(ToolDefinition(namespace, name, item.get("description") or item["tool_id"],
            item.get("input_schema", {"type": "object"})), lambda _: {})

    def describe(identity):
        if identity not in catalog:
            raise ToolError("unknown_tool", "Tool is unavailable")
        return deepcopy(catalog[identity])

    worker = AgentRuntime.__new__(AgentRuntime)
    worker.catalog = SimpleNamespace(describe=describe, discover=ranked.discover)
    return worker


def discovery(query, tools, *, cursor=2, created_at=2, revision=2):
    return {"kind": "tools_discovered", "cursor": cursor, "revision": revision,
        "created_at": created_at, "payload": {"query": query, "namespace": None,
        "offset": 0, "next_offset": None, "total": len(tools), "directory_version": "directory-v1",
        "tools": [{k: v for k, v in item.items() if k not in {"input_schema", "output_schema"}}
                  for item in tools]}}


def schema_error(tool_id, *, cursor=1, created_at=1, revision=2):
    return {"kind": "tool_error", "cursor": cursor, "revision": revision,
        "created_at": created_at, "payload": {"code": "discover_tool_schema_first", "tool_id": tool_id}}


@pytest.mark.parametrize("error_time,selection_time", [(1, 2), (2, 2), (3, 2)])
def test_new_exact_selection_supersedes_old_error_by_event_order(error_time, selection_time):
    task = snapshot()
    old, new = {"tool_id": "records.old"}, {"tool_id": "research.lookup"}
    task["observations"] = [schema_error(old["tool_id"], created_at=error_time),
        discovery(new["tool_id"], [new], created_at=selection_time)]
    before = deepcopy(task)
    descriptors, active = worker_for([old, new])._context_tools(task)
    assert active[0] == new["tool_id"]
    assert pending_schema_request(task) is None
    assert task == before


def test_old_revision_error_cannot_override_current_exact_selection():
    task = snapshot()
    old, new = {"tool_id": "records.old"}, {"tool_id": "research.lookup"}
    task["observations"] = [discovery(new["tool_id"], [new]),
        schema_error(old["tool_id"], cursor=999, created_at=999, revision=1)]
    _, active = worker_for([old, new])._context_tools(task)
    assert active[0] == new["tool_id"]
    assert pending_schema_request(task) is None


def test_broad_discovery_is_not_a_new_selection_or_permission():
    task = snapshot()
    tools = [{"tool_id": identity, "effect": "write"} for identity in ("records.export", "records.erase")]
    task["observations"] = [discovery("records", tools)]
    before = deepcopy(task)
    _, active = worker_for(tools)._context_tools(task)
    assert active == [] and pending_schema_request(task) is None
    assert task == before
    task["observations"].insert(0, schema_error("agent.read_result"))
    _, active = worker_for([*tools, {"tool_id": "agent.read_result"}])._context_tools(task)
    assert active[0] == "agent.read_result"


def test_revoked_selected_tool_is_not_replaced_by_an_old_error_target():
    task = snapshot()
    old, removed = {"tool_id": "records.old"}, {"tool_id": "research.revoked"}
    task["observations"] = [schema_error(old["tool_id"]), discovery(removed["tool_id"], [removed])]
    descriptors, active = worker_for([old])._context_tools(task)
    assert active == []
    assert removed["tool_id"] not in {item["tool_id"] for item in descriptors}
    assert pending_schema_request(task) is None


def test_broad_candidate_contract_and_multiple_original_inputs_precede_directory():
    task = snapshot()
    task["goal"] = "Prepare an invoice export from the observed account and invoice records"
    task["constraints"] = {"format": "csv", "permission": "draft_only"}
    descriptor = {"tool_id": "accounts.prepare_export", "description": "Prepare an export draft",
        "version": "v1", "effect": "read", "input_schema": {"type": "object",
        "properties": {"account_id": {"type": "string"}, "invoice_ids": {"type": "array", "items": {"type": "string"}},
                       "format": {"enum": ["csv", "json"]}},
        "required": ["account_id", "invoice_ids", "format"], "additionalProperties": False}}
    tools = [descriptor, *[{"tool_id": f"accounts.option{index}", "description": "Optional directory explanation " * 15,
                           "version": "v1", "effect": "read"} for index in range(4)]]
    task["tool_calls"] = [{"tool_call_id": identity, "tool_id": tool, "state": "succeeded", "effect": "read",
        "created_at": 1, "updated_at": 2, "result": result} for identity, tool, result in [
            ("account-source", "accounts.read_account", {"account_id": "a-1", "version": 4, "name": "account" * 50}),
            ("invoice-source", "billing.read_invoices", {"invoice_ids": ["i-1", "i-2"], "version": 7, "terms": "terms" * 60})]]
    task["observations"] = [discovery("export records", tools, created_at=3)]
    before = deepcopy(task)
    # Tight enough that optional descriptions displace the two independent facts.
    context = build_context(task, [descriptor], max_input_tokens=7200)
    data = json.loads(context.messages[-1]["content"])
    assert data["tools"] == [descriptor]
    for call in task["tool_calls"]:
        shown = next(item for item in data["tool_results"] if item["tool_call_id"] == call["tool_call_id"])
        assert shown.get("result") == call["result"]
        assert shown["truncated"] is False
    assert context.estimated_input_tokens <= 7200 and task == before


@pytest.mark.parametrize("delegation_enabled", [False, True])
def test_entire_real_revision_sequence_keeps_requested_contract_and_original_inputs(delegation_enabled):
    fixture = json.loads((BACKEND_ROOT / "tests/fixtures/agent_tool_focus_sequence_20261110.json").read_text("utf-8"))
    assert len(fixture["steps"]) == 24
    original = deepcopy(fixture)
    worker = worker_for(fixture["tool_catalog"])
    catalog = {item["tool_id"]: item for item in fixture["tool_catalog"]}
    planned_steps = []
    for step in fixture["steps"]:
        task = deepcopy(fixture["snapshot"])
        if delegation_enabled:
            task["delegation_enabled"] = True
        task["run"].update(step["run"])
        task["observations"] = [item for item in task["observations"] if item["cursor"] < step["before_event_cursor"]]
        task["tool_calls"] = [item for item in task["tool_calls"] if item["created_at"] < step["at"]]
        before = deepcopy(task)
        descriptors, active = worker._context_tools(task)
        descriptors.extend(catalog[identity] for identity in ("agent.read_result", "agent.read_artifact")
            if identity not in {item["tool_id"] for item in descriptors})
        context = build_context(task, descriptors, active_tool_ids=active,
            namespace_index=fixture["namespace_index"], max_input_tokens=fixture["input_token_limit"])
        data = json.loads(context.messages[-1]["content"])
        assert context.estimated_input_tokens <= 12000
        assert data["task"]["constraints"] == task["constraints"]
        assert_revision_receipt_visible(data, task["task_revision_receipt"])
        assert data["task"]["constraints"]["selected_task_ids"] == ["parcel"]
        assert task == before
        # These recorded boundaries follow either broad planning discovery or
        # exact planning selection, with real task facts already available.
        if step["model_index"] in {10, 15, 19, 21, 28, 29}:
            schema = next((item for item in data["tools"] if item["tool_id"] == "onmyway.plan_route"), {})
            assert schema.get("input_schema") == _compact_schema(catalog["onmyway.plan_route"]["input_schema"]), step["model_index"]
            source = next(item for item in reversed(task["tool_calls"]) if item["tool_id"] == "onmyway.read_tasks")
            shown = next((item for item in data["tool_results"] if item["tool_call_id"] == source["tool_call_id"]), {})
            assert shown.get("result") == source["result"], step["model_index"]
            planned_steps.append(step["model_index"])
    assert len(planned_steps) == 6 and fixture == original
