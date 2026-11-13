"""Real failure snapshots replayed through production context selection, offline."""
from backend.paths import BACKEND_ROOT

import copy
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from backend.agent.decision import build_context
from backend.agent.runtime import AgentRuntime
from backend.agent.tools import ToolCatalog, ToolDefinition
from backend.tests.agent.context_assertions import assert_revision_receipt_visible


def failure_case(index):
    cases = json.loads((BACKEND_ROOT / "tests/fixtures/agent_context_workset_20261110.json").read_text("utf-8"))
    return cases[index]


def context_for(case):
    catalog = {tool["tool_id"]: tool for tool in case["tool_catalog"]}
    ranked_catalog = ToolCatalog()
    for tool in catalog.values():
        namespace, name = tool["tool_id"].split(".", 1)
        ranked_catalog.register(ToolDefinition(namespace, name, tool["description"], tool["input_schema"],
            effect=tool["effect"], executor=tool["executor"], source=tool["source"],
            output_schema=tool.get("output_schema"), output_key=tool.get("output_key")), lambda _: {})
    worker = AgentRuntime.__new__(AgentRuntime)
    worker.catalog = SimpleNamespace(describe=lambda identity: copy.deepcopy(catalog[identity]),
                                    discover=ranked_catalog.discover)
    descriptors, active = worker._context_tools(case["snapshot"])
    # Production includes reader contracts when any source is omitted.
    descriptors.extend(catalog[identity] for identity in ("agent.read_result", "agent.read_artifact")
                       if identity not in {tool["tool_id"] for tool in descriptors})
    context = build_context(case["snapshot"], descriptors, active_tool_ids=active,
                            namespace_index=case["namespace_index"])
    assert context.estimated_input_tokens <= 12000
    data = json.loads(context.messages[-1]["content"])
    assert data["task"]["constraints"] == case["snapshot"]["constraints"]
    return data


def test_latest_requested_page_survives_old_selection_and_two_artifacts():
    case = failure_case(0)
    data = context_for(case)
    latest = case["snapshot"]["tool_calls"][-1]["result"]["data"]
    source = next(call for call in case["snapshot"]["tool_calls"] if call["tool_call_id"] == latest["source_ref"])
    visible = next(call for call in data["tool_results"] if call["tool_call_id"] == source["tool_call_id"])
    pages = [page for page in data["evidence_pages"] if page["source_ref"] == source["tool_call_id"]]
    if visible.get("result") == source["result"]:
        assert visible["truncated"] is False
    else:
        assert pages and pages[0]["text"]
        assert pages[0]["text"] == latest["text"][:len(pages[0]["text"])]
        assert pages[0]["next_offset"] == latest["offset"] + len(pages[0]["text"])
    assert_revision_receipt_visible(data, case["snapshot"]["task_revision_receipt"])
    assert data["artifacts"][0]["artifact_id"] == case["snapshot"]["artifacts"][0]["artifact_id"]
    assert data["prior_artifacts"][0]["reference_only"] is True
    assert "agent.read_result" in {tool["tool_id"] for tool in data["tools"]}


@pytest.mark.parametrize("index,expected", [(1, "exa.web_search_exa"), (2, "amap.maps_text_search"),
                                           (3, "exa.web_search_exa"), (4, "exa.web_search_exa")])
def test_requested_contract_survives_stale_directory_and_large_source(index, expected):
    case = failure_case(index)
    data = context_for(case)
    descriptor = next((tool for tool in data["tools"] if tool["tool_id"] == expected), None)
    assert descriptor is not None, "The next decision must have the explicitly requested tool contract"
    original = next(tool for tool in case["tool_catalog"] if tool["tool_id"] == expected)
    assert descriptor["version"] == original["version"]
    assert descriptor["input_schema"]["required"] == original["input_schema"]["required"]
    assert data["tool_results"][0]["result_ref"] == case["snapshot"]["tool_calls"][0]["tool_call_id"]
    assert [o for o in data["observations"] if o["kind"] == "user_input"] == [
        {k: value for k, value in o.items() if k in {"kind", "payload", "created_at"}}
        for o in case["snapshot"]["observations"] if o["kind"] == "user_input"]


@pytest.mark.parametrize("index,stop_count", [(0, 1), (5, 2), (6, 2), (7, 1)])
def test_strengthened_delivery_contract_coexists_with_real_task_context(index, stop_count):
    from backend.tests.evaluation.test_agent_a1_evaluation import evaluator
    case = failure_case(index)
    case["snapshot"]["constraints"]["delivery_contract"] = evaluator().route_delivery_contract(stop_count)
    data = context_for(case)
    if index == 5:
        assert "onmyway.plan_route" in {t["tool_id"] for t in data["tools"]}
        state = data["tool_results"][0]
        value = state.get("result", state.get("result_view"))
        assert value["data"]["task_versions"] == {"parcel": 1, "print": 1}
        assert value["data"]["store_id"] == case["snapshot"]["tool_calls"][0]["result"]["data"]["store_id"]
        assert "agent.read_result" in {t["tool_id"] for t in data["tools"]}
    if index == 6:
        route = next(c for c in data["tool_results"] if c["tool_id"] == "onmyway.plan_route")
        assert route.get("result", route.get("result_view")) is not None
    if index == 7:
        assert "onmyway.read_tasks" in {t["tool_id"] for t in data["tools"]}
