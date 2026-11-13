"""Large independent sources keep quotable evidence under a fixed budget."""
from copy import deepcopy
import json
from pathlib import Path

import pytest

from backend.agent.decision import DecisionError, estimate_input_tokens
from backend.agent.review import build_review_context, parse_review_result


@pytest.fixture
def travel_case():
    return json.loads((Path(__file__).parents[1] / "fixtures/a1_review_travel_sources_20261111.json").read_text("utf-8"))


def shown(context):
    return json.loads(context.messages[-1]["content"])


def quote_result(call_id, quote):
    return json.dumps({"status": "contradicted", "findings": [{
        "location": "artifact:result/fact", "issue": "The original evidence differs from the claimed fact.",
        "source_call_id": call_id, "quote": quote}]})


@pytest.mark.parametrize("correction", [False, True])
def test_recorded_large_sources_each_keep_original_body_windows(travel_case, correction):
    before = deepcopy(travel_case)
    context = build_review_context(travel_case["snapshot"], travel_case["sources"], correct_format=correction,
                                   rejected_review=travel_case["rejected_review"] if correction else None)
    data = shown(context)
    for original, source in zip(travel_case["sources"], data["sources"]):
        if original["tool_id"].startswith("exa."):
            text = original["result"]["data"]["content"][0]["text"]
            windows = [w for w in source["windows"] if w["path"] == "/data/content/0/text"]
            assert windows, "A successful search needs visible body evidence, not only source metadata."
            assert sum(len(w["text"]) for w in windows) >= 32
            assert all(w["text"] == text[w["start"]:w["end"]] for w in windows)
            assert not source["coverage"]["complete"]
            if not correction:
                assert parse_review_result(quote_result(source["tool_call_id"], windows[0]["text"]), context).status == "contradicted"
    assert data["artifacts"][0]["content"] == travel_case["snapshot"]["artifacts"][0]["content"]
    assert data["completion_summary"] == travel_case["snapshot"]["completion_summary"]
    assert data["requirements"]["user_input"] == [o["payload"]["text"] for o in travel_case["snapshot"]["observations"] if o["kind"] == "user_input"]
    assert context.estimated_input_tokens == estimate_input_tokens(context.messages) <= 12000
    assert context.fingerprint == json.loads(travel_case["recorded_contexts"][int(correction)][-1]["content"])["fingerprint"]
    assert travel_case == before
    if correction:
        assert data["rejected_review"] == travel_case["rejected_review"]
        assert parse_review_result(travel_case["raw_reviews"][1], context).status == "insufficient"
    with pytest.raises(DecisionError):
        parse_review_result(travel_case["raw_reviews"][0], context)


def source_case():
    claims = {"astronomy": "The observatory is available on Monday.",
              "computing": "The package supports Python 3.15.",
              "agriculture": "The planting area has alkaline soil."}
    facts = {"astronomy": "The observatory is closed on Monday.",
             "computing": "The package supports Python 3.12 only.",
             "agriculture": "The planting area has acidic soil."}
    sources = []
    for name, fact in facts.items():
        sources.append({"tool_call_id": name, "tool_id": "library.search", "state": "succeeded", "effect": "read",
                        "result": {"observed_at": "2026-11-11T08:00:00Z", "source": "public-fixture", "version": 1,
                                   "data": {"title": name, "text": fact + " Background reference. " * 800}}})
    noise = {"tool_call_id": "catalog", "tool_id": "catalog.list", "state": "succeeded", "effect": "read",
             "result": {"data": {"names": ["catalog item " + str(n) for n in range(200)]}}}
    sources.insert(0, noise)
    snapshot = {"agent_task_id": "multiple-domains", "revision": 1,
                "goal": "Check the observatory, package and planting area using the original references.",
                "constraints": {}, "success_criteria": [], "observations": [], "completion_summary": "Comparative report.",
                "artifacts": [{"artifact_id": "result", "version": 1, "kind": "report", "content": claims}],
                "tool_calls": [{k: c[k] for k in ("tool_call_id", "tool_id", "state", "effect")} for c in sources]}
    return snapshot, sources, facts


def evidence_budget(snapshot, sources, extra):
    from backend.agent.review import _messages
    context = build_review_context(snapshot, sources, max_input_tokens=12000)
    data = shown(context)
    for source, coverage in zip(data["sources"], context.coverage):
        source.pop("document", None)
        source["values"], source["windows"] = {}, []
        source["coverage"] = {key: value for key, value in coverage.items() if key != "tool_call_id"}
        source["coverage"].update(complete=False, visible_characters=0,
                                  omitted_characters=source["coverage"]["total_characters"],
                                  omitted_values=source["coverage"]["total_values"])
    return estimate_input_tokens(_messages(data, compact_metadata=True)) + extra


@pytest.mark.parametrize("reverse", [False, True])
def test_cross_domain_large_sources_do_not_starve_behind_short_values(reverse):
    snapshot, sources, facts = source_case()
    if reverse:
        sources.reverse()
        snapshot["tool_calls"].reverse()
    budget = evidence_budget(snapshot, sources, 1000)
    context = build_review_context(snapshot, sources, max_input_tokens=budget)
    data = shown(context)
    for source in data["sources"]:
        if source["tool_call_id"] in facts:
            fact = facts[source["tool_call_id"]]
            assert any(fact in w["text"] for w in source["windows"])
            assert parse_review_result(quote_result(source["tool_call_id"], fact), context).status == "contradicted"
    assert context.estimated_input_tokens <= budget < 12000
    assert data["artifacts"][0]["content"] == snapshot["artifacts"][0]["content"]


def test_no_room_keeps_evidence_explicitly_omitted():
    snapshot, sources, _ = source_case()
    budget = evidence_budget(snapshot, sources, 5)
    context = build_review_context(snapshot, sources, max_input_tokens=budget)
    assert context.estimated_input_tokens <= budget
    assert context.omitted["sources_without_visible_text"] == len(sources)
    assert all(s["omitted_characters"] == s["total_characters"] and not s["complete"] for s in context.coverage)


def test_selected_windows_never_overlap_or_overstate_coverage(travel_case):
    context = build_review_context(travel_case["snapshot"], travel_case["sources"], correct_format=True,
                                   rejected_review=travel_case["rejected_review"])
    for source in shown(context)["sources"]:
        windows = sorted(source["windows"], key=lambda w: (w["path"], w["start"]))
        for first, second in zip(windows, windows[1:]):
            assert first["path"] != second["path"] or first["end"] <= second["start"]
        visible = sum(len(text) for group in source["values"].values() for text in group.values()) + sum(len(w["text"]) for w in windows)
        assert visible == source["coverage"]["visible_characters"]
        assert visible + source["coverage"]["omitted_characters"] == source["coverage"]["total_characters"]
