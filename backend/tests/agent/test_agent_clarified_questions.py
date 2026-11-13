"""New answers retire completed question reviews only from decision display."""
from copy import deepcopy
from datetime import datetime
import json

import pytest

from backend.agent import decision
from backend.paths import BACKEND_ROOT
from backend.tests.agent.test_agent_decision import snapshot


FIXTURE = BACKEND_ROOT / "tests/fixtures/agent_travel_clarified_questions.json"


def shown(item):
    return {key: item[key] for key in ("kind", "payload", "created_at")}


def observation(kind, cursor, **payload):
    return {"kind": kind, "cursor": cursor, "created_at": cursor, "payload": payload}


def context_for(observations):
    task = snapshot()
    task.update(goal="Prepare a sourced delivery", constraints={"authorization": "read only"},
                observations=observations)
    before = deepcopy(task)
    context = decision.build_context(task)
    assert task == before
    return context, json.loads(context.messages[-1]["content"])


@pytest.mark.parametrize("index", [0, 1, 2], ids=["model3-resume", "model10-repeated-budget", "model12-repeated-preference"])
def test_recorded_answer_hides_old_review_and_preserves_current_feedback_and_tools(index, monkeypatch):
    case = json.loads(FIXTURE.read_text(encoding="utf-8"))["cases"][index]
    before = deepcopy(case)

    class FrozenClock(datetime):
        @classmethod
        def now(cls, tz=None):
            return datetime.fromisoformat(case["evaluated_at"])

    monkeypatch.setattr(decision, "datetime", FrozenClock)
    context = decision.build_context(
        case["snapshot"], case["tool_descriptors"], namespace_index=case["namespace_index"],
        active_tool_ids=case["active_tool_ids"], max_input_tokens=case["max_input_tokens"],
    )
    data = json.loads(context.messages[-1]["content"])
    history = case["snapshot"]["observations"]
    old_fingerprint = history[0]["payload"]["fingerprint"]
    assert not any(item["payload"].get("fingerprint") == old_fingerprint for item in data["observations"])
    assert [item for item in data["observations"] if item["kind"] == "user_input"] == [
        shown(item) for item in history if item["kind"] == "user_input"]
    assert data["task"] == {key: case["snapshot"][key] for key in
                            ("agent_task_id", "revision", "goal", "constraints", "success_criteria")}
    if case["model_index"] == 12:
        for item in history[-2:]:
            assert shown(item) in data["observations"]
        assert history[-1]["payload"]["status"] == "unnecessary"
    expected_tools = ("onmyway.search_places",) if index == 0 else (
        "amap.maps_direction_transit_integrated", "agent.read_result")
    tools = {item["tool_id"]: item for item in data["tools"]}
    for identity in expected_tools:
        assert tools[identity] == next(item for item in case["tool_descriptors"] if item["tool_id"] == identity)
    assert context.estimated_input_tokens <= case["max_input_tokens"] == 12000
    assert case == before


@pytest.mark.parametrize("ordering", ["cursor-ties", "cursor-clock-correction", "legacy-ties"])
def test_reply_boundary_uses_event_order_and_keeps_the_new_review(ordering):
    events = [
        observation("question_review_requested", 1, fingerprint="old", input_cursor=0),
        observation("question_review_result", 2, fingerprint="old", status="necessary", reason="Missing input"),
        observation("user_input", 3, text="The missing value is supplied; other preferences may be estimated."),
        observation("question_review_requested", 4, fingerprint="new", input_cursor=3),
        observation("question_review_result", 5, fingerprint="new", status="unnecessary", reason="Already answered"),
    ]
    for item in events:
        item["created_at"] = 100 if ordering != "cursor-clock-correction" else 100 - item["cursor"]
    expected = [shown(item) for item in events[2:]]
    if ordering == "legacy-ties":
        for item in events:
            item.pop("cursor")
    else:
        events = [events[3], events[0], events[4], events[2], events[1]]
    _, data = context_for(events)
    assert data["observations"] == expected


@pytest.mark.parametrize("kind", ["question_review_requested", "question_review_result"])
def test_unpaired_old_review_is_not_declared_completed(kind):
    events = [observation(kind, 1, fingerprint="unpaired"), observation("user_input", 2, text="An answer")]
    _, data = context_for(events)
    assert data["observations"] == [shown(item) for item in events]


def test_review_completed_after_answer_is_not_hidden_as_an_old_pair():
    events = [
        observation("question_review_requested", 1, fingerprint="in-flight", input_cursor=0),
        observation("user_input", 2, text="An answer"),
        observation("question_review_result", 3, fingerprint="in-flight", status="unnecessary", reason="Already answered"),
    ]
    _, data = context_for(events)
    assert data["observations"] == [shown(item) for item in events]


def test_matching_fingerprint_does_not_hide_a_new_review_after_answer():
    events = [
        observation("question_review_requested", 1, fingerprint="reused", input_cursor=0),
        observation("question_review_result", 2, fingerprint="reused", status="necessary", reason="Missing input"),
        observation("user_input", 3, text="An answer"),
        observation("question_review_requested", 4, fingerprint="reused", input_cursor=3),
        observation("question_review_result", 5, fingerprint="reused", status="unnecessary", reason="Already answered"),
    ]
    _, data = context_for(events)
    assert data["observations"] == [shown(item) for item in events[2:]]


def test_all_answers_survive_when_latest_answer_retires_a_completed_review():
    events = [
        observation("user_input", 1, text="First required value"),
        observation("question_review_requested", 2, fingerprint="second-field", input_cursor=1),
        observation("question_review_result", 3, fingerprint="second-field", status="necessary", reason="Another value missing"),
        observation("user_input", 4, text="Second required value"),
    ]
    _, data = context_for(events)
    assert data["observations"] == [shown(events[0]), shown(events[-1])]


@pytest.mark.parametrize("status", ["necessary", "error"])
def test_no_answer_or_unnecessary_feedback_preserves_existing_prompt_and_reviews(status):
    events = [
        observation("question_review_requested", 1, fingerprint="initial", input_cursor=0),
        observation("question_review_result", 2, fingerprint="initial", status=status, reason="Initial assessment"),
    ]
    context, data = context_for(events)
    assert data["observations"] == [shown(item) for item in events]
    assert context.messages[0]["content"] == decision.SYSTEM_PROMPT + decision.EVIDENCE_NOTES_HELP


def test_supplied_answers_receive_resume_guidance_without_weakening_action_contract():
    context, _ = context_for([observation("user_input", 1, text="All required fields are supplied")])
    prompt = context.messages[0]["content"]
    assert "user_input=answers/new requirements" in prompt
    assert "No re-asking answered fields or estimable preferences" in prompt
    for safeguard in ("Return exactly one JSON object", "Host owns permissions/budgets", "unknown/dispatched write replay"):
        assert safeguard in prompt
    question = {"action": "ask_user", "question": "The source is unavailable; which supported alternative is acceptable?",
                "need": "missing_input", "missing_inputs": {"/alternative": "No feasible source remains"}}
    assert decision.parse_decision(json.dumps(question)).model_dump() == question


def test_unnecessary_feedback_guides_execution_even_before_any_user_reply():
    events = [
        observation("question_review_requested", 1, fingerprint="optional", input_cursor=0),
        observation("question_review_result", 2, fingerprint="optional", status="unnecessary", reason="Optional preference"),
    ]
    context, data = context_for(events)
    assert data["observations"] == [shown(item) for item in events]
    prompt = context.messages[0]["content"]
    assert "After unnecessary, continue tools/delivery" in prompt
    assert "still-missing material blockers may ask" in prompt
    assert "user_input=answers/new requirements" not in prompt
