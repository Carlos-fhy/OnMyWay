"""Completion checks use current artifacts, actual receipts, and durable budgets."""
import json
from types import SimpleNamespace
from uuid import uuid4

from backend.agent.decision import DecisionError, DecisionProvider
from backend.agent.runtime import AgentRuntime
from backend.agent.store import AgentStore
from backend.agent.tools import ToolCatalog, ToolDefinition, ToolError


def setup(tmp_path, actions, reviews, *, budget=None):
    store = AgentStore(tmp_path / "review.sqlite3")
    task = store.create({"request_id": str(uuid4()), "conversation_id": "review",
        "message_id": str(uuid4()), "goal": "Use verified facts to make a plan",
        **({"budget": budget} if budget else {})})
    catalog = ToolCatalog()
    catalog.register(ToolDefinition("venue", "hours", "Read venue hours", {"type": "object"}),
                     lambda _: {"notice": "Closed on Monday"})
    sequence, decisions, checks, responses = iter(actions), [], [], iter(reviews)
    def complete(messages, model, structured=False):
        decisions.append(messages)
        action = next(sequence)
        return json.dumps(action(messages) if callable(action) else action)
    def review(context):
        checks.append(context)
        value = next(responses)
        if isinstance(value, Exception):
            raise value
        return SimpleNamespace(status=value, model_dump=lambda: {"status": value, "findings": []})
    runtime = AgentRuntime(store, catalog, DecisionProvider(completion_fn=complete),
                           memory_reader=lambda _: [], artifact_reviewer=SimpleNamespace(review=review))
    return store, task, runtime, checks, decisions


CALL = {"action": "call_tool", "tool_id": "venue.hours", "arguments": {}}
PUBLISH = {"action": "publish_artifact", "kind": "text", "title": "Plan",
           "content": "Visit on Monday", "evidence_call_ids": []}
FINISH = {"action": "finish", "summary": "Plan prepared; no execution"}


def amend(messages):
    data = json.loads(messages[-1]["content"])
    artifact = data["artifacts"][-1]
    assert "contradicted" in json.dumps(data)
    return {**PUBLISH, "content": "Closed on Monday; consider Tuesday after checking availability",
            "artifact_id": artifact["artifact_id"], "expected_version": artifact["version"]}


def test_conflict_requires_new_version_and_current_review_before_completed(tmp_path):
    store, task, runtime, checks, _ = setup(tmp_path,
        [{"action": "discover_tools", "query": "venue.hours"}, CALL, PUBLISH, FINISH, amend, FINISH],
                                           ["contradicted", "supported"])
    runtime.run(task["current_run_id"])
    state = store.get(task["agent_task_id"])
    assert state["run"]["state"] == "completed"
    assert state["run"]["steps_used"] == 8
    assert len(checks) == 2 and checks[0].fingerprint != checks[1].fingerprint
    assert state["artifacts"][0]["version"] == 2
    assert "Closed on Monday" in json.dumps(checks[0].messages)
    assert "Plan prepared; no execution" in json.dumps(checks[0].messages)
    assert "Visit on Monday" in json.dumps(store.get_artifact(state["artifacts"][0]["artifact_id"], 1))


def test_same_unfixed_artifact_cannot_repeat_review_until_lucky_pass(tmp_path):
    store, task, runtime, checks, _ = setup(tmp_path, [PUBLISH, FINISH, FINISH], ["contradicted"])
    runtime.run(task["current_run_id"])
    state = store.get(task["agent_task_id"])
    assert state["run"]["state"] == "paused"
    assert state["run"]["reason"] == "artifact_review_changes_required"
    assert len(checks) == 1


def test_two_failed_reviews_pause_and_survive_store_reopen(tmp_path):
    store, task, runtime, checks, _ = setup(tmp_path, [PUBLISH, FINISH, amend, FINISH],
                                           ["contradicted", "contradicted"])
    runtime.run(task["current_run_id"])
    state = AgentStore(tmp_path / "review.sqlite3").get(task["agent_task_id"])
    assert state["run"]["reason"] == "artifact_review_limit"
    assert len(checks) == 2 and state["run"]["state"] == "paused"


def test_review_step_budget_stops_before_extra_model_call(tmp_path):
    store, task, runtime, checks, _ = setup(tmp_path, [PUBLISH, FINISH], ["supported"],
        budget={"max_steps": 2, "max_tool_calls": 2, "max_duration_seconds": 300})
    runtime.run(task["current_run_id"])
    state = store.get(task["agent_task_id"])
    assert state["run"]["state"] == "paused" and state["run"]["reason"].startswith("budget_")
    assert not checks


def test_revision_during_review_fences_success_from_old_run(tmp_path):
    store, task, runtime, _, _ = setup(tmp_path, [PUBLISH, FINISH], [])
    def revise(context):
        store.revise(task["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1,
                      "goal": "New goal", "constraints": {}, "success_criteria": []})
        return SimpleNamespace(status="supported", model_dump=lambda: {"status": "supported", "findings": []})
    runtime.artifact_reviewer.review = revise
    runtime.run(task["current_run_id"])
    state = store.get(task["agent_task_id"])
    assert state["revision"] == 2 and state["run"]["state"] == "queued"
    assert not state["artifacts"]
    assert store.get_run(task["current_run_id"])["run"]["state"] != "completed"


def test_provider_error_in_review_is_not_completed(tmp_path):
    store, task, runtime, checks, _ = setup(tmp_path, [PUBLISH, FINISH],
        [DecisionError("provider_error", "Synthetic upstream error")])
    runtime.run(task["current_run_id"])
    state = store.get(task["agent_task_id"])
    assert state["run"]["state"] == "paused" and state["run"]["reason"] == "provider_error"
    assert len(checks) == 1


def test_invalid_quote_can_be_corrected_once_within_persisted_budget(tmp_path):
    from backend.agent.review import ReviewProvider
    store, task, runtime, _, decisions = setup(tmp_path,
        [{"action": "discover_tools", "query": "venue.hours"}, CALL, PUBLISH, FINISH], [])
    checks = []
    def review(messages, model, **kwargs):
        context = json.loads(messages[-1]["content"])
        checks.append(context)
        source = context["sources"][0]["tool_call_id"]
        if len(checks) == 1:
            return json.dumps({"status": "contradicted", "findings": [{
                "location": "artifact", "issue": "Venue is closed on Monday",
                "source_call_id": source, "quote": '/notice: Closed on Monday'}]})
        assert "review_format_feedback" in context
        # A repaired review still rejects the false deliverable; no forced pass.
        return json.dumps({"status": "contradicted", "findings": [{
            "location": "artifact", "issue": "Venue is closed on Monday",
            "source_call_id": source, "quote": "Closed on Monday"}]})
    runtime.artifact_reviewer = ReviewProvider(completion_fn=review)
    runtime.run(task["current_run_id"])
    state = store.get(task["agent_task_id"])
    assert len(checks) == 2 and len(decisions) == 4
    assert checks[0]["fingerprint"] == checks[1]["fingerprint"]
    assert state["run"]["steps_used"] == 6
    assert state["run"]["reason"] == "artifact_review_limit"
    assert state["run"]["state"] == "paused"
    results = [item["payload"] for item in store.events(task["agent_task_id"])["events"]
               if item["kind"] == "observation:artifact_review_result"]
    assert [item["status"] for item in results] == ["error", "contradicted"]


def test_repeated_invalid_review_stops_after_two_calls(tmp_path):
    store, task, runtime, checks, _ = setup(tmp_path, [PUBLISH, FINISH], [
        DecisionError("invalid_review", "Invalid quotation"),
        DecisionError("invalid_review", "Invalid quotation")])
    runtime.run(task["current_run_id"])
    state = AgentStore(tmp_path / "review.sqlite3").get(task["agent_task_id"])
    assert len(checks) == 2
    assert state["run"]["state"] == "paused" and state["run"]["reason"] == "invalid_review"
    assert state["run"]["steps_used"] == 4


def test_invalid_review_correction_cannot_exceed_step_budget(tmp_path):
    store, task, runtime, checks, _ = setup(tmp_path, [PUBLISH, FINISH], [
        DecisionError("invalid_review", "Invalid quotation")],
        budget={"max_steps": 3, "max_tool_calls": 2, "max_duration_seconds": 300})
    runtime.run(task["current_run_id"])
    state = store.get(task["agent_task_id"])
    assert len(checks) == 1 and state["run"]["steps_used"] == 3
    assert state["run"]["state"] == "paused" and state["run"]["reason"] == "budget_steps"


def test_format_correction_cannot_discard_existing_findings_to_pass(tmp_path):
    from backend.agent.review import ReviewProvider
    store, task, runtime, _, _ = setup(tmp_path,
        [{"action": "discover_tools", "query": "venue.hours"}, CALL, PUBLISH, FINISH], [])
    checks = []
    def review(messages, model, **kwargs):
        data = json.loads(messages[-1]["content"])
        checks.append(data)
        if len(checks) == 1:
            return json.dumps({"status": "contradicted", "findings": [{"location": "artifact",
                "issue": "Venue is closed Monday", "source_call_id": data["sources"][0]["tool_call_id"],
                "quote": "/notice: Closed on Monday"}]})
        return '{"status":"supported","findings":[]}'
    runtime.artifact_reviewer = ReviewProvider(completion_fn=review)
    runtime.run(task["current_run_id"])
    result = store.get(task["agent_task_id"])
    assert result["run"]["state"] == "paused"
    assert result["run"]["reason"] == "invalid_review" and len(checks) == 2
    assert checks[-1]["rejected_review"]["findings"][0]["issue"] == "Venue is closed Monday"


def test_current_summary_and_prior_success_sources_are_in_independent_review(tmp_path):
    store, task, runtime, checks, _ = setup(tmp_path,
        [{"action": "discover_tools", "query": "venue.hours"}, CALL, PUBLISH, FINISH], ["supported"])
    runtime.run(task["current_run_id"])
    initial = store.get(task["agent_task_id"])
    source_id = initial["tool_calls"][0]["tool_call_id"]
    revised = store.revise(task["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1,
        "goal": "Keep the closed day and shorten the visit", "constraints": {}, "success_criteria": []})
    actions = iter([{**PUBLISH, "content": "Closed on Monday; no visit then", "evidence_call_ids": [source_id]}, FINISH])
    runtime.provider = DecisionProvider(completion_fn=lambda *a, **k: json.dumps(next(actions)))
    runtime.artifact_reviewer = SimpleNamespace(review=lambda context: checks.append(context) or
        SimpleNamespace(status="supported", model_dump=lambda: {"status": "supported", "findings": []}))
    runtime.run(revised["current_run_id"])
    assert store.get(task["agent_task_id"])["run"]["state"] == "completed"
    assert checks[-1].source_refs[0]["tool_call_id"] == source_id
    assert "Closed on Monday" in json.dumps(checks[-1].messages)


def test_production_service_always_installs_completion_reviewer(tmp_path, monkeypatch):
    from backend.agent import builtin as agent_builtin
    from backend.agent import service as agent_service
    from backend.agent.review import ReviewProvider
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", "synthetic-service-token-" + "x" * 40)
    monkeypatch.setenv("ONMYWAY_AGENT_DB", str(tmp_path / "service.sqlite3"))
    monkeypatch.delenv("ONMYWAY_MCP_SERVERS_FILE", raising=False)
    monkeypatch.setattr(agent_builtin, "register_builtin", lambda *a: None)
    monkeypatch.setattr(agent_builtin, "build_executor", lambda *a, **k: None)
    service = agent_service.AgentService(SimpleNamespace())
    runtime = service._configured_runtime(service.app)
    assert isinstance(runtime.artifact_reviewer, ReviewProvider)


def test_inspecting_failed_receipt_does_not_poison_unrelated_successful_evidence(tmp_path):
    def inspect_failed(messages):
        context = json.loads(messages[-1]["content"])
        failed = next(item for item in context["tool_results"] if item["tool_id"] == "venue.unavailable")
        return {"action": "call_tool", "tool_id": "agent.read_result",
                "arguments": {"tool_call_id": failed["tool_call_id"]}}
    store, task, runtime, checks, _ = setup(tmp_path, [
        {"action": "discover_tools", "query": "venue.unavailable"},
        {"action": "call_tool", "tool_id": "venue.unavailable", "arguments": {}},
        {"action": "discover_tools", "query": "agent.read_result"}, inspect_failed,
        {"action": "discover_tools", "query": "venue.hours"}, CALL,
        {**PUBLISH, "content": "Closed Monday; no confirmed visit"}, FINISH], ["supported"])
    def unavailable(_):
        raise ToolError("unavailable", "No receipt available")
    runtime.catalog.register(ToolDefinition("venue", "unavailable", "Unavailable venue query", {"type": "object"}), unavailable)
    runtime.run(task["current_run_id"])
    state = store.get(task["agent_task_id"])
    assert state["run"]["state"] == "completed"
    assert [call["state"] for call in state["tool_calls"]] == ["failed", "succeeded", "succeeded"]
    assert [source["tool_id"] for source in checks[0].source_refs] == ["venue.hours"]
