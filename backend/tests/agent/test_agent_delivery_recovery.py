"""Regressions for the November 10 authorized live batch; no network calls."""
from backend.paths import BACKEND_ROOT

import copy
import json
from pathlib import Path
from uuid import uuid4

import pytest

from backend.agent.decision import build_context
from backend.agent.review import build_review_context
from backend.agent.runtime import AgentRuntime
from backend.agent.store import AgentStore
from backend.agent.tools import ToolCatalog, ToolError
from backend.tests.agent.context_assertions import assert_revision_receipt_visible


def make_task(store, **changes):
    return store.create({"request_id": str(uuid4()), "conversation_id": "recovery",
                         "message_id": str(uuid4()), "goal": "Deliver the requested plan",
                         "constraints": {}, "success_criteria": [], **changes})


def test_large_selection_exposes_real_child_paths_without_values():
    value = {"data": {"legs": [{"distance_m": 1226, "steps": "secret-value" * 1000}]}}
    with pytest.raises(ToolError) as error:
        AgentRuntime._selection(value, {"paths": ["/data/legs"]})
    assert error.value.code == "selection_too_large"
    hint = error.value.details["selections"][0]
    assert hint["path"] == "/data/legs"
    assert hint["children"][0]["path"] == "/data/legs/0"
    assert hint["children"][0]["bytes"] > 2400
    assert "secret-value" not in json.dumps(error.value.details)


def test_bad_selection_identifies_nearest_existing_parent_and_escaped_keys():
    with pytest.raises(ToolError) as error:
        AgentRuntime._selection({"data": {"a/b": {"~item": 3}}}, {"paths": ["/data/absent/value"]})
    assert error.value.code == "selection_path_not_found"
    hint = error.value.details["selections"][0]
    assert hint["path"] == "/data"
    assert hint["children"][0]["path"] == "/data/a~1b"
    assert error.value.details["requested_paths"] == ["/data/absent/value"]


def test_reader_diagnostics_do_not_crowd_out_current_source_or_pending_write(tmp_path):
    store = AgentStore(tmp_path / "agent.sqlite3")
    task = make_task(store)
    snapshot = store.get(task["agent_task_id"])
    original = {"data": {"plan": {"distance_m": 1226, "legs": [{"details": "路线" * 3000}]}}}
    source = {"tool_call_id": "route", "tool_id": "demo.route", "state": "succeeded",
              "result": original, "updated_at": 1}
    snapshot["tool_calls"] = [source] + [
        {"tool_call_id": f"failed-{i}", "tool_id": "agent.read_result", "state": "failed",
         "arguments": {"tool_call_id": "route", "paths": ["/data/plan/legs"]},
         "result": None, "error": {"code": "selection_too_large", "message": "所选字段超过大小限制"},
         "updated_at": i + 2} for i in range(20)]
    pending = {"tool_call_id": "write", "tool_id": "demo.save", "state": "unknown",
               "arguments": {"record_id": "must-reconcile"}, "effect": "write"}
    snapshot["tool_calls"].append(pending)
    runtime = AgentRuntime(store, ToolCatalog(), None, memory_reader=lambda _: [])
    context = build_context(snapshot, [runtime.catalog.describe("agent.read_result")])
    data = json.loads(context.messages[-1]["content"])
    assert context.estimated_input_tokens <= 12000
    assert data["pending_calls"][0]["arguments"] == pending["arguments"]
    failures = [c for c in data["tool_results"] if c["tool_id"] == "agent.read_result"]
    assert len(failures) == 1 and failures[0]["tool_call_id"] == "failed-19"
    assert failures[0]["arguments"] == snapshot["tool_calls"][-2]["arguments"]
    assert data["omitted"]["reader_failures"] == 19
    route = next(c for c in data["tool_results"] if c["tool_call_id"] == "route")
    assert route["result_view"]["data"]["plan"]["distance_m"] == 1226


def test_task_revision_receipt_survives_reopen_and_is_not_business_execution(tmp_path):
    path = tmp_path / "agent.sqlite3"
    store = AgentStore(path)
    first = make_task(store, constraints={"drafts": [{"id": "print", "title": "打印论文"}],
                                         "stops": ["parcel", "dorm"]})
    second = store.revise(first["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1,
        "goal": "Remove the unconfirmed draft and deliver the route", "success_criteria": [],
        "constraints": {"drafts": [], "stops": ["parcel", "dorm"]}})
    snapshot = AgentStore(path).get(second["agent_task_id"])
    receipt = snapshot["task_revision_receipt"]
    assert receipt["scope"] == "task_definition_only"
    assert (receipt["from_revision"], receipt["to_revision"]) == (1, 2)
    change = next(c for c in receipt["changes"] if c["path"] == "/constraints/drafts")
    assert change["before"] == first["constraints"]["drafts"] and change["after"] == []
    assert not any(c["path"] == "/constraints/stops" for c in receipt["changes"])
    decision_data = json.loads(build_context(snapshot).messages[-1]["content"])
    assert_revision_receipt_visible(decision_data, receipt)
    review = build_review_context(snapshot, [])
    review_data = json.loads(review.messages[-1]["content"])
    assert_revision_receipt_visible(review_data, receipt)
    changed = copy.deepcopy(snapshot)
    changed["task_revision_receipt"]["changes"][0]["after"] = "different"
    assert build_review_context(changed, []).fingerprint != review.fingerprint


def test_small_formal_state_retains_complete_collection_under_review_pressure(tmp_path):
    store = AgentStore(tmp_path / "agent.sqlite3")
    snapshot = make_task(store)
    snapshot["artifacts"] = [{"artifact_id": "a", "version": 1, "kind": "text", "content": {
        "route": "x" * 4000, "claim": "Only parcel exists; no formal print task."}}]
    result = {"data": {"tasks": [{"id": "parcel", "status": "pending", "title": "取快递"}], "version": 7}}
    calls = [{"tool_call_id": "tasks", "tool_id": "demo.read_tasks", "state": "succeeded", "result": result},
             {"tool_call_id": "route", "tool_id": "demo.route", "state": "succeeded",
              "result": {"data": {"instructions": "route " * 4000}}}]
    snapshot["tool_calls"] = calls
    context = build_review_context(snapshot, calls)
    data = json.loads(context.messages[-1]["content"])
    source = next(s for s in data["sources"] if s["tool_call_id"] == "tasks")
    assert source["document"] == result
    assert source["coverage"]["complete"] is True
    assert context.estimated_input_tokens <= 12000


@pytest.mark.parametrize("index", [0, 1], ids=["draft_cancel", "formal_errands"])
def test_live_failure_snapshots_keep_usable_source_context(index):
    cases = json.loads((BACKEND_ROOT / "tests/fixtures/agent_delivery_context_20261110.json").read_text("utf-8"))
    case = cases[index]
    context = build_context(case["snapshot"], case["descriptors"], active_tool_ids=case["active_tool_ids"],
                            namespace_index=case["namespace_index"])
    data = json.loads(context.messages[-1]["content"])
    assert context.estimated_input_tokens <= 12000
    assert "agent.read_result" in {t["tool_id"] for t in data["tools"]}
    assert any("result" in c or "result_view" in c for c in data["tool_results"] if c["state"] == "succeeded")
    assert data["task"]["constraints"] == case["snapshot"]["constraints"]


def test_complete_result_is_preferred_when_it_fits_without_projection(tmp_path):
    store = AgentStore(tmp_path / "agent.sqlite3")
    task = make_task(store)
    task["tool_calls"] = [{"tool_call_id": "route", "tool_id": "demo.route", "state": "succeeded",
                           "result": {"data": {"details": "abc" * 1500}}, "updated_at": 1}]
    context = build_context(task)
    data = json.loads(context.messages[-1]["content"])
    assert data["tool_results"][0]["result"] == task["tool_calls"][0]["result"]
    assert data["tool_results"][0]["truncated"] is False


def test_revision_receipt_is_bounded_and_does_not_invent_omitted_state():
    from backend.agent.task_evidence import revision_receipt
    before = {"constraints": {str(i): "a" * 500 for i in range(50)}}
    after = {"constraints": {str(i): "b" * 900 for i in range(50)}}
    receipt = revision_receipt(before, after, 1, 2)
    assert len(json.dumps(receipt, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()) <= 2400
    assert receipt["omitted_changes"] > 0
    assert receipt["before_sha256"] != receipt["after_sha256"]
    for change in receipt["changes"]:
        assert "after" not in change and change["after_omitted"]["bytes"] == 902


def test_invalid_constraint_prefix_cannot_bypass_supplied_input():
    from backend.agent.delivery import DeliveryError, validate_question
    with pytest.raises(DeliveryError) as error:
        validate_question({"dates": "already supplied", "delivery_contract": {"kind": "text"}},
                          "missing_input", {"/constraints/dates": "required"})
    assert error.value.code == "invalid_missing_input"


@pytest.mark.parametrize("after_reads", [False, True], ids=["route_returned", "corrected_selection"])
def test_revalidation_keeps_formal_state_and_route_together(after_reads):
    case = json.loads((BACKEND_ROOT / "tests/fixtures/agent_revalidation_context_20261110.json").read_text("utf-8"))
    snapshot = copy.deepcopy(case["snapshot"])
    if not after_reads:
        snapshot["tool_calls"] = snapshot["tool_calls"][:2]
    context = build_context(snapshot, case["descriptors"], active_tool_ids=case["active_tool_ids"],
                            namespace_index=case["namespace_index"])
    data = json.loads(context.messages[-1]["content"])
    assert context.estimated_input_tokens <= 12000
    assert data["task"]["constraints"] == snapshot["constraints"]
    assert "agent.read_result" in {t["tool_id"] for t in data["tools"]}
    tasks, route = snapshot["tool_calls"][:2]
    state_entry = next(c for c in data["tool_results"] if c["tool_call_id"] == tasks["tool_call_id"])
    assert state_entry["result"] == tasks["result"]
    route_entry = next(c for c in data["tool_results"] if c["tool_call_id"] == route["tool_call_id"])
    result = route_entry.get("result", route_entry.get("result_view"))
    assert result is not None
    expected = route["result"]["data"]["plan"]
    actual = result["data"]["plan"]
    for key in ("departure", "arrival", "total_meters", "total_seconds", "service_seconds", "travel_seconds", "coordinate_system"):
        assert actual[key] == expected[key]
    assert [s["task_ids"] for s in actual["stops"]] == [["parcel"], ["print"]]
    assert result["data"]["plan_id"] == route["tool_call_id"]
    # Complete small state is visible once, with its original identity. A stale
    # character page cannot crowd out the other source or force a false pause.
    assert not any(p["source_ref"] == tasks["tool_call_id"] for p in data["evidence_pages"])
    assert not any(p["source_ref"] == tasks["tool_call_id"] for p in data["evidence_selections"])
    if after_reads:
        assert data["omitted"]["reader_failures"] == 1
        failure = next(c for c in snapshot["tool_calls"] if c["state"] == "failed")
        assert failure["error"]["code"] == "selection_path_not_found"
        assert failure["error"]["details"]["requested_paths"] == failure["arguments"]["paths"]
