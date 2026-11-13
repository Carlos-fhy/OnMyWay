"""A requested artifact page precedes older tool facts in a bounded context."""
from copy import deepcopy
import json
from pathlib import Path

from backend.agent.decision import build_context
from backend.tests.agent.test_agent_tool_focus import worker_for


def test_recorded_artifact_read_survives_before_runtime_reader_injection():
    case = json.loads((Path(__file__).parents[1] / "fixtures/agent_artifact_reader_context_20261111.json").read_text("utf-8"))
    task = case["snapshot"]
    before = deepcopy(task)
    catalog = {tool["tool_id"]: tool for tool in case["tool_catalog"]}
    descriptors, active = worker_for(case["tool_catalog"])._context_tools(task)
    # Match the production two-pass sequence: its first pass must not raise
    # before the runtime can supply the recovery-reader contracts.
    context = build_context(task, descriptors, active_tool_ids=active, namespace_index=case["namespace_index"])
    if any(context.omitted.get(key, 0) for key in ("result_bytes", "artifact_bytes", "evidence_pages", "evidence_page_bytes")):
        descriptors.extend(catalog[identity] for identity in ("agent.read_result", "agent.read_artifact")
                           if identity not in {tool["tool_id"] for tool in descriptors})
        context = build_context(task, descriptors, active_tool_ids=active, namespace_index=case["namespace_index"])
    data = json.loads(context.messages[-1]["content"])
    read = task["tool_calls"][-1]
    original_page = read["result"]["data"]
    page = next(item for item in data["evidence_pages"] if item["source_ref"] == original_page["source_ref"])
    assert page["source_kind"] == "artifact" and page["version"] == original_page["version"]
    assert page["evidence_call_ids"] == [read["tool_call_id"]]
    assert page["text"] and page["text"] == original_page["text"][:len(page["text"])]
    assert page["offset"] == original_page["offset"]
    assert page["next_offset"] == page["offset"] + len(page["text"])
    assert page["total_chars"] == original_page["total_chars"]
    assert "agent.read_artifact" in {tool["tool_id"] for tool in data["tools"]}
    assert data["artifact_focus"]["version"] == task["artifacts"][0]["version"]
    assert data["task"]["constraints"] == task["constraints"]
    assert context.estimated_input_tokens <= 12000 and task == before
