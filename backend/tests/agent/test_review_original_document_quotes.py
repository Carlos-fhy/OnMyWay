"""Whole original JSON is quotable; generated field renderings are not."""
import json
from pathlib import Path

import pytest

from backend.agent.decision import DecisionError
from backend.agent.review import build_review_context, parse_review_result


def case():
    return json.loads((Path(__file__).parents[1] / "fixtures/a1_review_original_document_20261111.json").read_text("utf-8"))


@pytest.mark.parametrize("review_index", [0, 1])
def test_recorded_contradiction_cites_a_contiguous_visible_original_document(review_index):
    value = case()
    context = build_review_context(value["snapshot"], value["sources"])
    data = json.loads(context.messages[-1]["content"])
    original = value["raw_reviews"][review_index]
    result = json.loads(original)
    source = next(s for s in data["sources"] if s["tool_call_id"] == result["findings"][0]["source_call_id"])
    document = json.dumps(source["document"], ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    assert result["findings"][0]["quote"] in document
    assert parse_review_result(original, context).status == "contradicted"
    assert context.estimated_input_tokens <= 12000


@pytest.mark.parametrize("quote", ['"task_id":"parcel","version":99', '"task_id":"parcel","task_id":"print"'])
def test_whole_document_does_not_authorize_changed_or_joined_json(quote):
    value = case()
    context = build_review_context(value["snapshot"], value["sources"])
    result = json.loads(value["raw_reviews"][0])
    result["findings"][0]["quote"] = quote
    with pytest.raises(DecisionError):
        parse_review_result(json.dumps(result), context)


def test_omitted_document_structure_is_not_credited_as_visible():
    value = case()
    source = value["sources"][0]
    source["result"]["large_unrelated_blob"] = "unrelated " * 1500
    context = build_review_context(value["snapshot"], value["sources"])
    data = json.loads(context.messages[-1]["content"])
    shown = next(s for s in data["sources"] if s["tool_call_id"] == source["tool_call_id"])
    assert "document" not in shown
    with pytest.raises(DecisionError):
        parse_review_result(value["raw_reviews"][0], context)
