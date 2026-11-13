"""A bounded format correction preserves the question verdict and attempt ledger."""
from copy import deepcopy
import json
from uuid import uuid4

import pytest

from backend.agent.decision import ContextBudgetExceeded, DecisionError, DecisionProvider
from backend.agent.question_review import build_question_context, parse_question_review
from backend.agent.review import ReviewProvider
from backend.agent.runtime import AgentRuntime
from backend.agent.store import AgentStore
from backend.agent.tools import ToolCatalog
from backend.paths import BACKEND_ROOT


FIXTURE = BACKEND_ROOT / "tests/fixtures/agent_question_review_format_travel2.json"
ASK = {"action": "ask_user", "question": "Which dates?", "need": "missing_input",
       "missing_inputs": {"/dates": "Opening days are date dependent"}}
OTHER_ASK = {"action": "ask_user", "question": "Which origin?", "need": "missing_input",
             "missing_inputs": {"/origin": "Travel time depends on the origin"}}


def raw_review(status="necessary", length=601):
    return json.dumps({"status": status, "reason": "x" * length})


def fixture():
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def context():
    case = fixture()
    data = json.loads(case["messages"][-1]["content"])
    return build_question_context(case["snapshot"], data["question"], namespace_index=data["available_namespaces"])


def setup(tmp_path, responses, actions=None, *, steps=16):
    store = AgentStore(tmp_path / "question.sqlite3")
    task = store.create({"request_id": str(uuid4()), "conversation_id": "synthetic-review", "message_id": str(uuid4()),
        "goal": "Prepare a sourced plan; ask only missing material blockers",
        "constraints": {"delivery_contract": {"kind": "text"}}, "success_criteria": [],
        "budget": {"max_steps": steps, "max_tool_calls": 4, "max_duration_seconds": 300}})
    decisions, reviews = [], []
    action_sequence = iter(actions) if actions is not None else None
    response_sequence = iter(responses)

    def decide(messages, model, **kwargs):
        decisions.append(deepcopy(messages))
        return json.dumps(next(action_sequence) if action_sequence is not None else ASK)

    def review(messages, model, **kwargs):
        data = json.loads(messages[-1]["content"])
        if "question" not in data:
            return '{"status":"supported","findings":[]}'
        reviews.append(data)
        response = next(response_sequence)
        if callable(response):
            response = response(data)
        if isinstance(response, Exception):
            raise response
        return response

    runtime = AgentRuntime(store, ToolCatalog(), DecisionProvider(completion_fn=decide),
        artifact_reviewer=ReviewProvider(completion_fn=review), memory_reader=lambda _: [])
    return store, task, runtime, decisions, reviews


def review_events(store, task):
    return [item for item in store.events(task["agent_task_id"])["events"]
            if item["kind"].startswith("observation:question_review_")]


def seed_attempts(store, task, runtime, sequence):
    run_id = task["current_run_id"]
    lease = store.claim(run_id, "seed-worker")
    token = lease["lease_token"]
    state = store.get_run(run_id)
    base = build_question_context(state, {key: ASK[key] for key in ("question", "missing_inputs")},
                                  namespace_index=runtime._catalog_for(state).namespace_index())
    for kind in sequence:
        if kind == "request":
            store.reserve_step(run_id, token)
            store.record_observation(run_id, token, "question_review_requested",
                {"fingerprint": base.fingerprint, "input_cursor": base.input_cursor})
        else:
            store.record_observation(run_id, token, "question_review_result",
                {"fingerprint": base.fingerprint, "status": "error", "code": "invalid_question_review",
                 "rejected_status": "necessary"})
    store.transition(run_id, token, "paused", reason="synthetic_interruption")
    store.resume(task["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1})
    return base


def test_exact_travel_output_remains_invalid_but_exposes_only_safe_repair_status():
    case = fixture()
    base = context()
    assert base.fingerprint == case["fingerprint"]
    assert len(json.loads(case["raw_response"])["reason"]) == case["reason_characters"] == 612
    with pytest.raises(DecisionError) as error:
        parse_question_review(case["raw_response"], base)
    assert error.value.code == "invalid_question_review"
    assert error.value.rejected_status == "necessary"
    assert not hasattr(error.value, "rejected_review")
    assert json.loads(case["raw_response"])["reason"] not in str(error.value)


def test_correction_keeps_requirements_fingerprint_cursor_and_budget():
    case = fixture()
    data = json.loads(case["messages"][-1]["content"])
    base = context()
    repaired = build_question_context(case["snapshot"], data["question"],
        namespace_index=data["available_namespaces"], rejected_status="necessary")
    actual = json.loads(repaired.messages[-1]["content"])
    assert repaired.fingerprint == base.fingerprint == case["fingerprint"]
    assert repaired.input_cursor == base.input_cursor == 0
    assert repaired.rejected_status == actual["rejected_status"] == "necessary"
    assert actual["review_format_feedback"]
    assert {key: actual[key] for key in data} == data
    assert repaired.estimated_input_tokens <= 12000
    with pytest.raises(ContextBudgetExceeded):
        build_question_context(case["snapshot"], data["question"], namespace_index=data["available_namespaces"],
            rejected_status="necessary", max_input_tokens=base.estimated_input_tokens)


@pytest.mark.parametrize("status", ["necessary", "unnecessary"])
def test_reason_boundary_is_strict_and_correction_cannot_flip_status(status):
    base = context()
    assert parse_question_review(raw_review(status, 600), base).status == status
    with pytest.raises(DecisionError) as error:
        parse_question_review(raw_review(status, 601), base)
    assert error.value.rejected_status == status
    case = fixture()
    data = json.loads(case["messages"][-1]["content"])
    corrected = build_question_context(case["snapshot"], data["question"], rejected_status=status)
    other = "unnecessary" if status == "necessary" else "necessary"
    with pytest.raises(DecisionError) as flipped:
        parse_question_review(raw_review(other, 40), corrected)
    assert flipped.value.code == "invalid_question_review"
    assert not hasattr(flipped.value, "rejected_status")


@pytest.mark.parametrize("raw", [
    '{"status":"necessary","reason":"unfinished',
    json.dumps({"status": "necessary", "reason": "x" * 601, "authorized": True}),
    json.dumps({"status": "unknown", "reason": "x" * 601}),
    json.dumps({"status": "necessary", "reason": 1000}),
    json.dumps({"status": "necessary", "reason": "   "}),
    '{"status":"unnecessary","status":"necessary","reason":"' + "x" * 601 + '"}',
    raw_review(length=5000),
])
def test_other_invalid_reviews_are_not_eligible_for_format_retry(raw):
    with pytest.raises(DecisionError) as error:
        parse_question_review(raw, context())
    assert error.value.code == "invalid_question_review"
    assert not hasattr(error.value, "rejected_status")


def test_runtime_corrects_overlong_reason_once_before_sending_necessary_question(tmp_path):
    store, task, runtime, decisions, reviews = setup(tmp_path, [raw_review(), raw_review(length=40)])
    runtime.run(task["current_run_id"])
    state = store.get(task["agent_task_id"])
    assert state["run"]["state"] == "waiting_user"
    assert state["run"]["steps_used"] == 3 and len(decisions) == 1 and len(reviews) == 2
    assert reviews[1]["rejected_status"] == "necessary" and reviews[1]["review_format_feedback"]
    history = review_events(store, task)
    assert [item["payload"].get("status") for item in history] == [None, "error", None, "necessary"]
    assert len({item["payload"]["fingerprint"] for item in history}) == 1
    assert [item["payload"]["input_cursor"] for item in history if item["kind"].endswith("requested")] == [0, 0]
    assert history[1]["payload"]["rejected_status"] == "necessary"
    assert "x" * 601 not in json.dumps(history)
    assert sum(item["kind"] == "question" for item in state["observations"]) == 1


def test_corrected_unnecessary_stays_rejected_and_runtime_can_deliver(tmp_path):
    actions = [ASK, {"action": "publish_artifact", "kind": "text", "title": "Plan", "content": "Draft based on supplied facts",
                     "evidence_call_ids": []}, {"action": "finish", "summary": "Draft delivered"}]
    store, task, runtime, _, reviews = setup(tmp_path,
        [raw_review("unnecessary"), raw_review("unnecessary", 40)], actions)
    runtime.run(task["current_run_id"])
    state = store.get(task["agent_task_id"])
    assert state["run"]["state"] == "completed" and state["run"]["steps_used"] == 6
    assert len(reviews) == 2 and len(state["artifacts"]) == 1
    assert not any(item["kind"] == "question" for item in state["observations"])


def test_second_invalid_review_and_same_input_resume_cannot_trigger_third_attempt(tmp_path):
    store, task, runtime, _, reviews = setup(tmp_path, [raw_review(), raw_review(), raw_review(length=40)])
    runtime.run(task["current_run_id"])
    state = AgentStore(tmp_path / "question.sqlite3").get(task["agent_task_id"])
    assert state["run"]["reason"] == "invalid_question_review" and len(reviews) == 2
    assert state["run"]["steps_used"] == 3
    runtime.store = AgentStore(tmp_path / "question.sqlite3")
    runtime.store.resume(task["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1})
    runtime.run(task["current_run_id"])
    assert len(reviews) == 2
    assert runtime.store.get(task["agent_task_id"])["run"]["state"] == "paused"
    assert len([item for item in review_events(runtime.store, task) if item["kind"].endswith("requested")]) == 2


@pytest.mark.parametrize("status", ["necessary", "unnecessary"])
def test_runtime_rejects_semantic_status_flip_during_format_repair(tmp_path, status):
    other = "unnecessary" if status == "necessary" else "necessary"
    store, task, runtime, _, reviews = setup(tmp_path, [raw_review(status), raw_review(other, 40)])
    runtime.run(task["current_run_id"])
    state = store.get(task["agent_task_id"])
    assert len(reviews) == 2 and state["run"]["reason"] == "invalid_question_review"
    assert not any(item["kind"] == "question" for item in state["observations"])


@pytest.mark.parametrize("after_format_error", [False, True])
def test_provider_error_never_gets_a_format_retry(tmp_path, after_format_error):
    responses = ([raw_review()] if after_format_error else []) + [RuntimeError("synthetic provider failure")]
    store, task, runtime, _, reviews = setup(tmp_path, responses)
    runtime.run(task["current_run_id"])
    state = store.get(task["agent_task_id"])
    assert len(reviews) == 1 + int(after_format_error)
    assert state["run"]["reason"] == "provider_error"
    assert not any(item["kind"] == "question" for item in state["observations"])


def test_correction_reserves_a_step_before_a_second_model_call(tmp_path):
    store, task, runtime, _, reviews = setup(tmp_path, [raw_review(), raw_review(length=40)], steps=2)
    runtime.run(task["current_run_id"])
    state = store.get(task["agent_task_id"])
    assert len(reviews) == 1 and state["run"]["steps_used"] == 2
    assert state["run"]["reason"] == "budget_steps"


@pytest.mark.parametrize("sequence", [("request",), ("request", "error", "request")],
                         ids=["initial-request-interrupted", "correction-request-interrupted"])
def test_interrupted_request_without_result_is_never_replayed(tmp_path, sequence):
    store, task, runtime, _, reviews = setup(tmp_path, [raw_review(length=40)])
    seed_attempts(store, task, runtime, sequence)
    runtime.store = AgentStore(tmp_path / "question.sqlite3")
    runtime.run(task["current_run_id"])
    state = runtime.store.get(task["agent_task_id"])
    assert not reviews and state["run"]["state"] == "paused"
    assert len([item for item in review_events(runtime.store, task) if item["kind"].endswith("requested")]) == sequence.count("request")
    assert not any(item["kind"] == "question" for item in state["observations"])


def test_persisted_format_error_allows_only_remaining_correction_after_reopen(tmp_path):
    store, task, runtime, _, reviews = setup(tmp_path, [raw_review(length=40)])
    base = seed_attempts(store, task, runtime, ("request", "error"))
    runtime.store = AgentStore(tmp_path / "question.sqlite3")
    runtime.run(task["current_run_id"])
    state = runtime.store.get(task["agent_task_id"])
    assert len(reviews) == 1 and reviews[0]["rejected_status"] == "necessary"
    assert state["run"]["state"] == "waiting_user"
    requests = [item for item in review_events(runtime.store, task) if item["kind"].endswith("requested")]
    assert len(requests) == 2 and all(item["payload"]["fingerprint"] == base.fingerprint for item in requests)


def test_different_questions_share_the_same_two_attempt_limit_with_corrections(tmp_path):
    store, task, runtime, _, reviews = setup(tmp_path, [raw_review("unnecessary", 40), raw_review()], [ASK, OTHER_ASK])
    runtime.run(task["current_run_id"])
    state = store.get(task["agent_task_id"])
    assert len(reviews) == 2 and state["run"]["steps_used"] == 4
    assert state["run"]["reason"] == "invalid_question_review"
    assert len({item["payload"]["fingerprint"] for item in review_events(store, task)}) == 2


@pytest.mark.parametrize("change", ["cancel", "revise"])
def test_cancellation_or_revision_during_correction_fences_old_question(tmp_path, change):
    store, task, runtime, _, reviews = setup(tmp_path, [])

    def mutate(_data):
        if change == "cancel":
            store.cancel(task["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1})
        else:
            store.revise(task["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1,
                "goal": "New requirement", "constraints": {}, "success_criteria": []})
        return raw_review(length=40)

    responses = iter([raw_review(), mutate])

    def review(messages, model, **kwargs):
        data = json.loads(messages[-1]["content"])
        reviews.append(data)
        value = next(responses)
        return value(data) if callable(value) else value

    runtime.artifact_reviewer = ReviewProvider(completion_fn=review)
    runtime.run(task["current_run_id"])
    assert len(reviews) == 2
    old = store.get_run(task["current_run_id"])
    assert old["run"]["state"] != "waiting_user"
    assert not any(item["kind"] == "question" for item in old["observations"])
    if change == "revise":
        assert store.get(task["agent_task_id"])["revision"] == 2
        assert store.get(task["agent_task_id"])["run"]["state"] == "queued"
