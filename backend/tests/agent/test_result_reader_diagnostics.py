"""Bounded correction hints for original-result reads; no network services."""
from copy import deepcopy
import json

import pytest

from backend.agent.runtime import AgentRuntime
from backend.agent.tools import ToolError
from backend.tests.agent.test_agent_runtime import create_task, parts, run, seed_selection_source


def failure(value, paths):
    with pytest.raises(ToolError) as raised:
        AgentRuntime._selection(value, {"paths": paths})
    assert raised.value.code == "selection_path_not_found"
    return raised.value.details


def test_missing_nested_key_identifies_failure_and_real_sibling_without_reading_it():
    value = {"data": {"plan": {f"field-{n}": n for n in range(20)}, "plan_id": "private-source-value"}}
    paths = ["/data/plan/field-0", "/data/plan/plan_id"]
    before = deepcopy((value, paths))
    details = failure(value, paths)
    assert details["failed_path"] == "/data/plan/plan_id"
    assert details["failed_segment"] == "plan_id"
    assert details["candidate_paths"] == ["/data/plan_id"]
    assert details["candidates_truncated"] is False
    assert details["selections"][0]["path"] == "/data/plan"
    assert "private-source-value" not in json.dumps(details)
    assert (value, paths) == before


def test_multiple_same_name_keys_are_candidates_without_choosing_a_replacement():
    value = {"data": {"plan": {}, "left": {"plan_id": "left-value"},
                      "right": [{"plan_id": "right-value"}]}}
    details = failure(value, ["/data/plan/plan_id"])
    assert set(details["candidate_paths"]) == {"/data/left/plan_id", "/data/right/0/plan_id"}
    assert "left-value" not in json.dumps(details) and "right-value" not in json.dumps(details)
    assert "replacement" not in details and "values" not in details


def test_failed_segment_is_the_first_missing_segment_not_the_requested_leaf():
    details = failure({"data": {"price": 12}}, ["/data/absent/price"])
    assert details["failed_path"] == "/data/absent/price"
    assert details["failed_segment"] == "absent"
    assert details["candidate_paths"] == []


def test_candidates_escape_json_pointer_segments_and_require_an_explicit_new_read():
    value = {"data": {"wrong": {}, "a/b": {"~id": "original-value"}}}
    details = failure(value, ["/data/wrong/~0id"])
    assert details["failed_segment"] == "~id"
    assert details["candidate_paths"] == ["/data/a~1b/~0id"]
    assert "original-value" not in json.dumps(details)
    selected = AgentRuntime._selection(value, {"paths": details["candidate_paths"]})
    assert selected["values"] == {"/data/a~1b/~0id": "original-value"}


@pytest.mark.parametrize("path", ["/data/plan/plan_id", "/data/encoded/plan_id"])
def test_json_text_remains_a_scalar_and_does_not_create_candidate_paths(path):
    value = {"data": {"plan": {}, "encoded": json.dumps({"plan_id": "never-decoded"})}}
    details = failure(value, [path])
    assert details["failed_path"] == path and details["failed_segment"] == "plan_id"
    assert details["candidate_paths"] == []
    assert "never-decoded" not in json.dumps(details)


def test_candidate_search_stops_at_a_bounded_number_of_source_entries():
    value = {"data": {"plan": {}}, "noise": [{"padding": n} for n in range(1000)],
             "later": {"plan_id": "outside-search-budget"}}
    details = failure(value, ["/data/plan/plan_id"])
    assert details["candidate_paths"] == []
    assert details["candidates_truncated"] is True
    assert "outside-search-budget" not in json.dumps(details)


def test_deep_source_does_not_turn_diagnostics_into_an_unbounded_walk():
    nested = {"plan_id": "outside-depth-budget"}
    for _ in range(40):
        nested = {"nested": nested}
    details = failure({"data": {"plan": {}}, "other": nested}, ["/data/plan/plan_id"])
    assert details["candidate_paths"] == []
    assert details["candidates_truncated"] is True


def test_large_unicode_requests_and_many_candidates_keep_metadata_bounded():
    value = {"data": {"plan": {}}, **{f"branch-{n}-" + "界" * 80: {"plan_id": "hidden-value"} for n in range(20)}}
    details = failure(value, ["/data/plan/plan_id"])
    assert len(details["candidate_paths"]) <= 8
    assert details["candidates_truncated"] is True
    assert len(json.dumps(details, ensure_ascii=False, separators=(",", ":")).encode()) <= 1800
    assert "hidden-value" not in json.dumps(details)
    for path in details["candidate_paths"]:
        assert AgentRuntime._selection(value, {"paths": [path]})["values"][path] == "hidden-value"
    long_paths = ["/" + "键" * 500 + str(n) for n in range(8)]
    details = failure({}, long_paths)
    assert "failed_path" in details and "failed_segment" in details
    assert details["requested_paths_omitted"] == 8
    assert len(json.dumps(details, ensure_ascii=False, separators=(",", ":")).encode()) <= 1800


def test_diagnostics_do_not_increase_the_selection_or_character_page_limit():
    value = {"data": {"text": "值" * 1000}}
    with pytest.raises(ToolError) as raised:
        AgentRuntime._selection(value, {"paths": ["/data/text"]})
    assert raised.value.code == "selection_too_large"
    assert len(json.dumps(raised.value.details, ensure_ascii=False, separators=(",", ":")).encode()) <= 1800
    page = AgentRuntime._page(value, {"max_chars": 2000})
    assert len(page["text"].encode()) <= 2400 and page["has_more"] is True


def test_other_task_cannot_obtain_diagnostic_candidates(parts):
    task, call, worker = seed_selection_source(parts, {"data": {"plan": {}, "plan_id": "private-value"}})
    other = create_task(parts.store)
    scope = worker._active_task.set(other["agent_task_id"])
    try:
        with pytest.raises(ToolError) as raised:
            worker._read_result({"tool_call_id": call["tool_call_id"], "paths": ["/data/plan/plan_id"]})
    finally:
        worker._active_task.reset(scope)
    assert raised.value.code == "evidence_not_found"
    assert not hasattr(raised.value, "details")


def test_durable_failed_read_exposes_candidates_in_the_next_model_input(parts):
    source_ids = []
    parts.catalog.register(parts.Tool("demo", "query", "Read structured public facts", {"type": "object"}),
                           lambda _: {"plan": {}, "plan_id": "actual-id"})
    task = create_task(parts.store, goal="Read public facts and publish their actual identifier")
    def bad_read(messages):
        data = json.loads(messages[-1]["content"])
        source_ids.append(next(c["tool_call_id"] for c in data["tool_results"] if c["tool_id"] == "demo.query"))
        return {"action": "call_tool", "tool_id": "agent.read_result", "arguments": {
            "tool_call_id": source_ids[0], "paths": ["/data/plan/plan_id"]}}
    def correct_read(messages):
        data = json.loads(messages[-1]["content"])
        failed = next(c for c in data["tool_results"] if c["tool_id"] == "agent.read_result")
        details = failed["error"]["details"]
        assert details["failed_path"] == "/data/plan/plan_id"
        assert details["candidate_paths"] == ["/data/plan_id"]
        return {"action": "call_tool", "tool_id": "agent.read_result", "arguments": {
            "tool_call_id": source_ids[0], "paths": details["candidate_paths"]}}
    def publish(messages):
        return {"action": "publish_artifact", "kind": "text", "title": "Identifier",
                "content": {"id": "actual-id"}, "evidence_call_ids": source_ids}
    result = run(parts, task, [
        {"action": "discover_tools", "query": "demo.query"},
        {"action": "call_tool", "tool_id": "demo.query", "arguments": {}},
        {"action": "discover_tools", "query": "agent.read_result"}, bad_read, correct_read, publish,
        {"action": "finish", "summary": "Delivered the sourced identifier"}])
    assert result["run"]["state"] == "completed"
    reads = [c for c in result["tool_calls"] if c["tool_id"] == "agent.read_result"]
    assert [c["state"] for c in reads] == ["failed", "succeeded"]
    assert reads[0]["error"]["details"]["candidate_paths"] == ["/data/plan_id"]
    assert reads[1]["result"]["data"]["values"] == {"/data/plan_id": "actual-id"}
