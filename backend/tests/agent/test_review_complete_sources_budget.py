"""Original evidence must coexist with a complete delivery at the fixed budget."""
from copy import deepcopy
import json
from pathlib import Path

import pytest

from backend.agent.review import build_review_context, parse_review_result


@pytest.mark.parametrize("correct_format", [False, True])
@pytest.mark.parametrize("contradict", [False, True])
def test_recorded_review_keeps_full_sources_and_source_binding(correct_format, contradict):
    case = json.loads((Path(__file__).parents[1] / "fixtures/a1_review_complete_sources_20261111.json").read_text("utf-8"))
    if contradict:
        case["sources"][1]["result"]["data"]["plan"]["legs"][2]["meters"] = 9001
    before = deepcopy(case)
    context = build_review_context(case["snapshot"], case["sources"], correct_format=correct_format)
    data = json.loads(context.messages[-1]["content"])
    assert data["requirements"]["constraints"] == case["snapshot"]["constraints"]
    assert data["artifacts"][0]["content"] == case["snapshot"]["artifacts"][0]["content"]
    assert data["completion_summary"] == case["snapshot"]["completion_summary"]
    assert all(source["coverage"]["complete"] for source in data["sources"])
    assert [source["document"] for source in data["sources"]] == [source["result"] for source in case["sources"]]
    assert all(fact["effect"] == "read" for fact in data["tool_call_facts"])
    assert context.estimated_input_tokens <= 12000 and before == case
    assert len(context.source_refs) == 2 and all(ref["content_hash"] for ref in context.source_refs)
    assert context.artifact_refs[0]["content_hash"] and data["fingerprint"] == context.fingerprint
    if contradict:
        verdict = {"status": "contradicted", "findings": [{
            "location": "legs/2/meters", "issue": "Published distance differs from the original receipt.",
            "source_call_id": case["sources"][1]["tool_call_id"], "quote": "9001"}]}
        assert parse_review_result(json.dumps(verdict), context).status == "contradicted"
