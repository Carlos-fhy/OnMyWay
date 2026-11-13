"""Production replay of the failed revision, without model or network calls."""
from backend.paths import BACKEND_ROOT

import copy
import json
from pathlib import Path

import pytest

from backend.tests.agent.test_agent_context_workset import context_for
from backend.agent.tools import ToolCatalog, ToolDefinition
from backend.tests.agent.context_assertions import assert_revision_receipt_visible


def case_at(index):
    path = BACKEND_ROOT / "tests/fixtures/agent_revision_workset_20261110.json"
    return next(case for case in json.loads(path.read_text("utf-8")) if case["model_index"] == index)


@pytest.mark.parametrize("index", [14, 23, 28, 31])
def test_successful_field_read_is_present_in_next_decision(index):
    case = case_at(index)
    before = copy.deepcopy(case)
    data = context_for(case)
    page = case["snapshot"]["tool_calls"][-1]["result"]["data"]
    source = next(c for c in case["snapshot"]["tool_calls"] if c["tool_call_id"] == page["source_ref"])
    result = next(r for r in data["tool_results"] if r["tool_call_id"] == source["tool_call_id"])
    selections = [s for s in data["evidence_selections"] if s["source_ref"] == page["source_ref"]]
    assert result.get("result") == source["result"] or any(s["values"] == page["values"] for s in selections)
    assert "agent.read_result" in {t["tool_id"] for t in data["tools"]}
    assert_revision_receipt_visible(data, case["snapshot"]["task_revision_receipt"])
    assert data["prior_artifacts"][0]["content_ref"] == case["snapshot"]["prior_artifacts"][0]["artifact_id"]
    assert case == before


@pytest.mark.parametrize("index", [10, 16, 23, 28, 31])
def test_revision_keeps_a_browsable_namespace_index(index):
    data = context_for(case_at(index))
    assert {n["namespace"] for n in data["available_namespaces"]["items"]} == {"agent", "amap", "exa", "onmyway"}


def test_old_read_error_stops_displacing_successful_current_read():
    case = case_at(28)
    data = context_for(case)
    assert not any(r["tool_id"] == "agent.read_result" and r["state"] == "failed" for r in data["tool_results"])
    assert data["omitted"]["reader_failures"] == 1
    assert not any(o["kind"] == "decision_error" for o in data["observations"])
    assert any(c["state"] == "failed" for c in case["snapshot"]["tool_calls"])


def test_general_identifier_words_are_searchable_without_domain_aliases():
    catalog = ToolCatalog()
    for name, description in [("maps_direction_transit_integrated", "公交路线"),
                              ("maps_direction_walking", "步行路线"),
                              ("invoice_export_csv", "导出发票"), ("readUserProfile", "读取用户资料")]:
        catalog.register(ToolDefinition("service", name, description, {"type": "object"}), lambda _: {})
    assert catalog.discover_page("walking route planning")["tools"][0]["tool_id"] == "service.maps_direction_walking"
    assert catalog.discover_page("invoice export")["tools"][0]["tool_id"] == "service.invoice_export_csv"
    assert catalog.discover_page("user profile")["tools"][0]["tool_id"] == "service.readUserProfile"
    assert catalog.discover_page("service.maps_direction_walking")["total"] == 1
    assert catalog.discover_page("unrelated absent")["total"] == 0


@pytest.mark.parametrize("index", [14, 23, 28, 31])
def test_revision_can_load_next_contract_and_its_actual_input_facts(index):
    from types import SimpleNamespace
    from backend.agent.builtin import register_builtin
    case = case_at(index)
    snapshot = case["snapshot"]
    catalog = ToolCatalog()
    register_builtin(catalog, SimpleNamespace(), "synthetic-test-token")
    descriptor = catalog.describe("onmyway.plan_route")
    case["tool_catalog"] = [descriptor if t["tool_id"] == descriptor["tool_id"] else t for t in case["tool_catalog"]]
    snapshot["observations"].append({"kind": "tools_discovered",
        "created_at": snapshot["tool_calls"][-1]["updated_at"] + 1,
        "payload": {"query": descriptor["tool_id"], "namespace": None, "offset": 0, "next_offset": None,
                    "total": 1, "directory_version": "replay-next-contract",
                    "tools": [{k: v for k, v in descriptor.items() if k not in {"input_schema", "output_schema", "output_key"}}]}})
    data = context_for(case)
    assert "onmyway.plan_route" in {t["tool_id"] for t in data["tools"]}
    source = next(c for c in reversed(snapshot["tool_calls"]) if c["tool_id"] == "onmyway.read_tasks")
    visible = next(r for r in data["tool_results"] if r["tool_call_id"] == source["tool_call_id"])
    assert visible.get("result") == source["result"], "An executable contract needs its complete versioned inputs"
    # Build the next call entirely from visible user requirements and receipts.
    # Validate with both the displayed Schema and the production business model.
    from jsonschema import Draft202012Validator
    from backend.execution.capabilities import PlanRoute
    facts, constraints = visible["result"]["data"], data["task"]["constraints"]
    selected = constraints["selected_task_ids"]
    tasks = []
    for row in facts["tasks"]:
        if row["task_id"] in selected:
            task = row["task"]
            tasks.append({"id": row["task_id"], "title": task["title"], "priority": task["priority"],
                          "service_minutes": task["serviceMinutes"], "place": json.loads(task["placeJson"])})
    arguments = {"store_id": facts["store_id"], "task_versions": {key: facts["task_versions"][key] for key in selected},
                 "request": {**{key: constraints[key] for key in ("origin", "destination", "departure", "mode")}, "tasks": tasks}}
    shown = next(t for t in data["tools"] if t["tool_id"] == "onmyway.plan_route")
    Draft202012Validator(shown["input_schema"]).validate(arguments)
    plan = PlanRoute.model_validate(arguments)
    assert [task.id for task in plan.request.tasks] == ["parcel"]
    assert next(row for row in facts["tasks"] if row["task_id"] == "print")["task"]["status"] == "pending"


def test_compact_revision_receipt_preserves_null_missing_escaped_and_stale_values():
    from backend.agent.decision import _revision_context
    from backend.agent.task_evidence import revision_receipt
    before = {"constraints": {"a/b": "before", "gone": 3, "nullable": "old"}, "goal": "old"}
    after = {"constraints": {"a/b": "after" * 60, "nullable": None, "~added": True}, "goal": "new" * 80}
    receipt = revision_receipt(before, after, 1, 2)
    snapshot = copy.deepcopy(receipt)
    compact = _revision_context(receipt, after)
    assert any("after_ref" in change for change in compact["changes"])
    assert_revision_receipt_visible({"task": after, "task_revision_receipt": compact}, receipt)
    stale = {**after, "goal": "stale snapshot"}
    compact_stale = _revision_context(receipt, stale)
    assert next(c for c in compact_stale["changes"] if c["path"] == "/goal")["after"] == after["goal"]
    assert_revision_receipt_visible({"task": stale, "task_revision_receipt": compact_stale}, receipt)
    assert receipt == snapshot


def test_repeated_reads_keep_identity_and_time_without_collapsing_writes_or_new_facts():
    from backend.agent.decision import build_context, observed_at
    from backend.tests.agent.test_agent_decision import snapshot
    task = snapshot()
    for index, (effect, count) in enumerate([("read", 1), ("read", 1), ("write", 1), ("read", 2)]):
        task["tool_calls"].append({"tool_call_id": str(index), "tool_id": "demo.records", "effect": effect,
            "state": "succeeded", "updated_at": index + 1, "result": {"data": {"count": count},
                "observed_at": str(index), "source": "fixture:demo", "tool_id": "demo.records", "version": "v1"}})
    before = copy.deepcopy(task)
    data = json.loads(build_context(task).messages[-1]["content"])
    assert [r["tool_call_id"] for r in data["tool_results"]] == ["1", "2", "3"]
    assert data["repeated_reads"] == [{"same_data_as": "1", "receipts": [
        {"result_ref": "0", "observed_at": observed_at(task["tool_calls"][0]), "source_time": "0"}]}]
    assert task == before
