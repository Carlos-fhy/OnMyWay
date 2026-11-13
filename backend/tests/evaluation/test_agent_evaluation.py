"""Evaluation evidence must retain failures and never turn execution into a pass."""
from copy import deepcopy
import importlib.util
import json
from pathlib import Path
from urllib.parse import quote, quote_plus
from uuid import uuid4

import pytest

from backend.agent.store import AgentStore
from backend.agent.tools import ToolCatalog, ToolDefinition, ToolError


@pytest.fixture
def evaluation(tmp_path):
    assert importlib.util.find_spec("backend.evaluation.agent") is not None, "A1 evaluation recorder is missing"
    from backend.evaluation.agent import EvaluationSession
    return EvaluationSession, AgentStore(tmp_path / "agent.sqlite3"), ToolCatalog(), tmp_path / "report.json"


def task(store, **extra):
    return store.create({"request_id": str(uuid4()), "conversation_id": "synthetic-evaluation",
                         "message_id": str(uuid4()), "goal": "查询旅行信息并给出有来源的方案", **extra})


def model(*actions):
    items = iter(actions)
    return lambda messages, name, structured=False: json.dumps(next(items), ensure_ascii=False)


ARTIFACT = {"action": "publish_artifact", "kind": "text", "title": "测试成果", "content": "仍需人工复核", "evidence_call_ids": []}
FINISH = {"action": "finish", "summary": "成果已生成，未执行外部写入"}
ASK = {"action": "ask_user", "question": "具体出发日期是什么？"}
CALL = {"action": "call_tool", "tool_id": "travel.query", "arguments": {}}


@pytest.mark.parametrize("ceiling,expected", [(20, "completed"), (3, "budget_exhausted")])
def test_delegated_evaluation_drives_children_and_records_one_global_budget(evaluation, ceiling, expected):
    Session, store, catalog, path = evaluation
    created = task(store, budget={"max_steps": 24})
    parent_steps = 0

    def completion(messages, name, structured=False):
        nonlocal parent_steps
        data = json.loads(messages[-1]["content"])
        if data.get("delegation"):
            action = FINISH if data["artifacts"] else ARTIFACT
        else:
            parent_steps += 1
            if parent_steps == 1:
                action = {"action": "delegate_task", "delegation_key": "summary", "goal": "Write a brief synthetic result", "allowed_tools": []}
            elif parent_steps == 2:
                action = {"action": "wait_children"}
            elif parent_steps == 3:
                action = {"action": "discover_tools", "query": "agent.read_artifact"}
            elif parent_steps == 4:
                output = data["delegations"][0]["outputs"][0]
                action = {"action": "call_tool", "tool_id": "agent.read_artifact", "arguments": {
                    "artifact_id": output["artifact_id"], "version": output["version"]}}
            elif parent_steps == 5:
                action = {"action": "review_delegation", "delegation_key": "summary", "verdict": "accept", "reason": "Read and reviewed"}
            elif parent_steps == 6:
                action = ARTIFACT
            else:
                action = FINISH
        return json.dumps(action, ensure_ascii=False)

    session = Session(store, catalog, path, completion_fn=completion, delegation_enabled=True, max_model_calls=ceiling)
    record = session.run(created["agent_task_id"], "delegated")
    assert record["execution_status"] == expected
    assert len(record["children"]) == 1
    assert record["children"][0]["events"]
    assert len({call["agent_task_id"] for call in record["model_calls"]}) == 2
    assert session.report["counts"]["model_calls"] == (9 if ceiling == 20 else ceiling)
    assert session.report["limits"]["delegation_enabled"] is True
    assert record["checks"]["semantic_review"] == "not_run"
    if expected == "completed":
        assert session.report["counts"]["tool_calls"] == 1
        assert record["snapshot"]["delegations"][0]["status"] == "accepted"
        assert record["tool_dispatches"][0]["agent_run_id"] == created["current_run_id"]
    else:
        assert record["evaluation_stop_reason"] == "evaluation_model_budget"


def test_completion_review_is_recorded_and_uses_shared_model_budget(evaluation):
    Session, store, catalog, path = evaluation
    session = Session(store, catalog, path, completion_fn=model(ARTIFACT, FINISH),
        review_completion_fn=lambda *a, **k: '{"status":"supported","findings":[]}', max_model_calls=3)
    record = session.run(task(store)["agent_task_id"], "reviewed")
    assert record["execution_status"] == "completed"
    assert session.report["counts"]["model_calls"] == 3
    entry = record["model_calls"][-1]
    assert entry["purpose"] == "completion_review" and entry["review"]["status"] == "supported"
    assert entry["action"] is None and entry["messages"] and entry["raw_text"]
    assert record["snapshot"]["run"]["steps_used"] == 3
    assert session.report["limits"]["completion_review_enabled"] is True


def test_completion_review_cannot_exceed_global_model_budget(evaluation):
    Session, store, catalog, path = evaluation
    session = Session(store, catalog, path, completion_fn=model(ARTIFACT, FINISH),
        review_completion_fn=lambda *a, **k: pytest.fail("Review must share the model ceiling"), max_model_calls=2)
    record = session.run(task(store)["agent_task_id"], "review-budget")
    assert record["execution_status"] == "budget_exhausted"
    assert record["evaluation_stop_reason"] == "evaluation_model_budget"
    assert session.report["counts"]["model_calls"] == 2
    assert store.get_run(record["agent_run_id"])["run"]["state"] == "paused"


def test_real_evaluation_always_enables_review_without_sending_network(evaluation):
    Session, store, catalog, path = evaluation
    session = Session(store, catalog, path, allow_live=True)
    assert session.reviewer is not None
    assert session.runtime.artifact_reviewer is session.reviewer
    assert session.report["limits"]["completion_review_enabled"] is True


@pytest.mark.parametrize("ceiling", [1, 2])
def test_question_review_is_recorded_under_global_model_budget(evaluation, ceiling):
    Session, store, catalog, path = evaluation
    created = task(store, constraints={"delivery_contract": {"kind": "text"}})
    question = {**ASK, "need": "missing_input", "missing_inputs": {"/dates": "opening dates"}}
    session = Session(store, catalog, path, completion_fn=model(question), max_model_calls=ceiling,
        review_completion_fn=lambda *a, **k: '{"status":"necessary","reason":"Dates are absent and required"}')
    record = session.run(created["agent_task_id"], "question-review", expected_state="waiting_user")
    assert session.report["counts"]["model_calls"] == ceiling
    if ceiling == 1:
        assert record["execution_status"] == "budget_exhausted"
        assert record["evaluation_stop_reason"] == "evaluation_model_budget"
    else:
        assert record["execution_status"] == "waiting_user"
        assert record["model_calls"][-1]["purpose"] == "question_review"
        assert record["model_calls"][-1]["review"]["status"] == "necessary"


def register(catalog, callback):
    catalog.register(ToolDefinition("travel", "query", "查询旅行信息", {"type": "object", "additionalProperties": False}), callback)


def test_completed_execution_requires_review_and_records_model_evidence(evaluation, monkeypatch):
    Session, store, catalog, path = evaluation
    from backend.memory import personal as personal_memory
    monkeypatch.setattr(personal_memory, "recall", lambda *a, **k: pytest.fail("Evaluation must not read personal memory"))
    created = task(store)
    session = Session(store, catalog, path, completion_fn=model(ARTIFACT, FINISH))
    record = session.run(created["agent_task_id"], "initial")
    assert record["execution_status"] == "completed"
    assert record["status"] == "needs_review"
    assert record["checks"]["expected_state"] is True
    assert len(record["model_calls"]) == 2 and record["events"]
    assert all(call["prompt_estimated_tokens"] > 0 and len(call["prompt_sha256"]) == 64 for call in record["model_calls"])
    assert record["model_calls"][0]["action"]["action"] == "publish_artifact"
    assert json.loads(record["model_calls"][0]["raw_text"])["content"] == ARTIFACT["content"]
    assert all(call["latency_seconds"] >= 0 for call in record["model_calls"])
    saved = json.loads(path.read_text(encoding="utf-8"))
    assert saved["counts"]["model_calls"] == 2
    assert saved["phases"][0]["snapshot"]["run"]["state"] == "completed"
    assert saved["status"] == "needs_review"


def test_prompt_evidence_matches_sent_context_and_redacts_nested_excerpts(evaluation, monkeypatch):
    Session, store, catalog, path = evaluation
    secret = "synthetic-prompt /+credential?=12345"
    monkeypatch.setenv("AMAP_WEB_KEY", secret)
    private_forms = {secret, quote(secret, safe=""), quote_plus(secret, safe="")}
    marker = "public-evidence-marker"
    evidence = marker + "; " + "; ".join(sorted(private_forms))
    register(catalog, lambda args: {"evidence": evidence, "z_tail": "long reference text " * 700})
    actions = iter([CALL, ARTIFACT, FINISH])
    sent_messages, contexts = [], []

    def completion(messages, name, structured=False):
        sent_messages.append(deepcopy(messages))
        return json.dumps(next(actions), ensure_ascii=False)

    session = Session(store, catalog, path, completion_fn=completion)
    original_decide = session.provider.delegate.decide

    def capture_context(context):
        contexts.append({"messages": deepcopy(context.messages), "omitted": deepcopy(context.omitted),
                         "tokens": context.estimated_input_tokens, "estimator": context.estimator})
        return original_decide(context)

    monkeypatch.setattr(session.provider.delegate, "decide", capture_context)
    record = session.run(task(store)["agent_task_id"], "prompt-evidence")
    persisted = json.loads(path.read_text(encoding="utf-8"))["phases"][0]
    assert record["execution_status"] == "completed"
    assert len(sent_messages) == len(contexts) == len(persisted["model_calls"]) == 3
    for sent, context, entry in zip(sent_messages, contexts, persisted["model_calls"], strict=True):
        assert sent == context["messages"]
        assert entry["messages"] == session.redact(sent)
        assert entry["omitted"] == context["omitted"]
        assert entry["prompt_estimated_tokens"] == context["tokens"]
        assert entry["prompt_estimator"] == context["estimator"]
        assert len(entry["prompt_sha256"]) == 64
    excerpts = json.dumps(persisted["model_calls"][1]["messages"], ensure_ascii=False)
    assert marker in excerpts and "[REDACTED]" in excerpts
    assert "long reference text " * 700 not in excerpts
    assert persisted["model_calls"][1]["omitted"]
    for private in private_forms:
        assert private not in path.read_text(encoding="utf-8")
        assert private not in json.dumps(record, ensure_ascii=False)
    assert marker in json.dumps(sent_messages[1], ensure_ascii=False)


def test_live_model_requires_explicit_opt_in_before_any_call(evaluation):
    Session, store, catalog, path = evaluation
    with pytest.raises(ValueError, match="allow_live"):
        Session(store, catalog, path)


def test_production_adapter_keeps_output_limit_and_configured_model(evaluation, monkeypatch):
    Session, store, catalog, path = evaluation
    from backend.assistant import api as assistant
    monkeypatch.setenv("DASHSCOPE_API_KEY", "synthetic-key-not-used-over-network")
    monkeypatch.setenv("DASHSCOPE_BASE_URL", "https://synthetic.invalid")
    monkeypatch.setenv("AGENT_MODEL", "evaluation-synthetic")
    calls = []
    def completion(messages, name, **kwargs):
        calls.append((name, kwargs))
        return json.dumps(ASK)
    monkeypatch.setattr(assistant, "completion", completion)
    session = Session(store, catalog, path, allow_live=True)
    record = session.run(task(store)["agent_task_id"], "clarify", expected_state="waiting_user")
    assert calls == [("evaluation-synthetic", {"structured": True, "max_tokens": 2048})]
    assert record["checks"]["expected_state"] is True
    assert record["status"] == "needs_review"


@pytest.mark.parametrize("failure", ["http_status", "timeout", "invalid_response"])
def test_production_failure_records_safe_diagnostics_without_response_body(evaluation, monkeypatch, failure):
    import httpx
    from backend.assistant import api as assistant
    Session, store, catalog, path = evaluation
    monkeypatch.setenv("DASHSCOPE_API_KEY", "synthetic-key")
    monkeypatch.setenv("DASHSCOPE_BASE_URL", "https://synthetic.invalid")
    marker = "private-response-and-url-must-not-appear"

    def post(*args, **kwargs):
        if failure == "timeout":
            raise httpx.ReadTimeout(marker)
        if failure == "invalid_response":
            return httpx.Response(200, text=marker)
        return httpx.Response(400, text=marker)

    monkeypatch.setattr(assistant.httpx, "post", post)
    session = Session(store, catalog, path, allow_live=True)
    record = session.run(task(store)["agent_task_id"], "provider_failure")
    assert record["execution_status"] == "provider_error"
    expected = {"kind": failure}
    if failure == "http_status":
        expected["upstream_status"] = 400
    assert record["model_calls"][0]["provider_diagnostics"] == expected
    assert session.report["counts"]["provider_errors"] == 1
    assert marker not in path.read_text(encoding="utf-8")


def test_global_model_budget_pauses_then_leaves_later_phases_not_run(evaluation):
    Session, store, catalog, path = evaluation
    session = Session(store, catalog, path, completion_fn=model(ARTIFACT, FINISH), max_model_calls=1)
    first = task(store)
    stopped = session.run(first["agent_task_id"], "first")
    assert stopped["execution_status"] == "budget_exhausted"
    assert stopped["evaluation_stop_reason"] == "evaluation_model_budget"
    assert stopped["snapshot"]["run"]["state"] == "paused"
    skipped_task = task(store)
    skipped = session.run(skipped_task["agent_task_id"], "second")
    assert skipped["execution_status"] == "not_run"
    assert skipped["model_calls"] == [] and skipped["events"] == []
    assert store.get(skipped_task["agent_task_id"])["run"]["state"] == "queued"
    assert session.report["counts"]["model_calls"] == 1


def test_global_tool_budget_stops_before_preparing_an_extra_call(evaluation):
    Session, store, catalog, path = evaluation
    sends = []
    register(catalog, lambda args: sends.append(args) or {"fact": "open"})
    session = Session(store, catalog, path, completion_fn=model(CALL, CALL), max_tool_calls=1)
    record = session.run(task(store)["agent_task_id"], "limited")
    assert sends == [{}]
    assert record["execution_status"] == "budget_exhausted"
    assert record["evaluation_stop_reason"] == "evaluation_tool_budget"
    assert len(record["snapshot"]["tool_calls"]) == 1
    assert record["snapshot"]["tool_calls"][0]["state"] == "succeeded"
    assert session.report["counts"]["tool_calls"] == 1


def test_provider_error_threshold_preserves_errors_and_skips_following_phase(evaluation):
    Session, store, catalog, path = evaluation
    attempts = []
    def broken(*args, **kwargs):
        attempts.append(1)
        raise OSError("raw provider credential should never be recorded")
    session = Session(store, catalog, path, completion_fn=broken, max_provider_errors=2)
    records = [session.run(task(store)["agent_task_id"], f"phase-{i}") for i in range(3)]
    assert [record["execution_status"] for record in records] == ["provider_error", "provider_error", "not_run"]
    assert len(attempts) == 2
    assert session.report["counts"]["provider_errors"] == 2
    assert all(record["status"] != "passed" for record in records)
    assert "raw provider credential" not in path.read_text(encoding="utf-8")


def test_unknown_tool_result_is_retained_without_retry_or_pass(evaluation):
    Session, store, catalog, path = evaluation
    attempts = []
    def unknown(args):
        attempts.append(1)
        raise ToolError("response_lost", "结果待核实", outcome="unknown")
    register(catalog, unknown)
    session = Session(store, catalog, path, completion_fn=model(CALL))
    record = session.run(task(store)["agent_task_id"], "unknown")
    assert len(attempts) == 1 and record["execution_status"] == "unknown"
    assert record["snapshot"]["tool_calls"][0]["state"] == "unknown"
    assert record["status"] == "needs_review"


def test_secret_redaction_covers_output_snapshot_events_and_url_query(evaluation, monkeypatch):
    Session, store, catalog, path = evaluation
    secret = "synthetic-private-credential-12345"
    monkeypatch.setenv("SYNTHETIC_API_KEY", secret)
    monkeypatch.setenv("SYNTHETIC_BASE_URL", "https://example.invalid/api?key=url-secret-value")
    value = f"{secret}; https://example.invalid/api?token=not-an-env-secret&city=成都"
    action = {**ARTIFACT, "content": {"text": value, "receipt_token": "runtime-private-value", "lease_token": "lease-private-value"}}
    session = Session(store, catalog, path, completion_fn=model(action, FINISH))
    record = session.run(task(store)["agent_task_id"], "redaction")
    session.report["metadata"] = {"base_url": "https://example.invalid/api?key=url-secret-value"}
    session.flush()
    saved = path.read_text(encoding="utf-8")
    returned = json.dumps(record, ensure_ascii=False)
    for private in [secret, "not-an-env-secret", "url-secret-value", "runtime-private-value", "lease-private-value"]:
        assert private not in saved and private not in returned
    assert "[REDACTED]" in saved
    assert "成都" in saved


def test_amap_web_key_is_redacted_as_bare_text_and_url_encoded_evidence(evaluation, monkeypatch):
    Session, store, catalog, path = evaluation
    secret = "synthetic-amap /+key?=12345"
    monkeypatch.setenv("AMAP_WEB_KEY", secret)
    private_forms = {secret, quote(secret, safe=""), quote_plus(secret, safe="")}
    evidence = "成都；" + "; ".join(sorted(private_forms))
    register(catalog, lambda args: {"evidence": evidence})
    action = {**ARTIFACT, "content": {"evidence": evidence}}
    session = Session(store, catalog, path, completion_fn=model(CALL, action, FINISH))
    record = session.run(task(store)["agent_task_id"], "amap-redaction")
    session.report["metadata"] = {"provider_evidence": evidence}
    session.flush()

    saved = path.read_text(encoding="utf-8")
    returned = json.dumps(record, ensure_ascii=False)
    for private in private_forms:
        assert private not in saved and private not in returned
    assert record["execution_status"] == "completed"
    assert "成都" in saved and "[REDACTED]" in saved
    assert record["snapshot"]["tool_calls"][0]["result"]["data"]["evidence"] == "成都；[REDACTED]; [REDACTED]; [REDACTED]"


def test_report_checkpoint_exists_before_tool_executes(evaluation):
    Session, store, catalog, path = evaluation
    observed = []
    def tool(args):
        persisted = json.loads(path.read_text(encoding="utf-8"))
        observed.append(persisted)
        assert persisted["counts"]["model_calls"] == 1
        assert persisted["counts"]["tool_calls"] == 1
        assert persisted["phases"][0]["model_calls"][0]["action"]["tool_id"] == "travel.query"
        return {"fact": "checked"}
    register(catalog, tool)
    session = Session(store, catalog, path, completion_fn=model(CALL, ARTIFACT, FINISH))
    assert session.run(task(store)["agent_task_id"], "checkpoint")["execution_status"] == "completed"
    assert len(observed) == 1
    assert not list(path.parent.glob(path.name + ".*.tmp"))


def test_existing_report_cannot_be_overwritten_or_reset_budget(evaluation):
    Session, store, catalog, path = evaluation
    original = '{"previous_attempt":"preserve evidence"}'
    path.write_text(original, encoding="utf-8")
    with pytest.raises(FileExistsError):
        Session(store, catalog, path, completion_fn=model(ASK))
    assert path.read_text(encoding="utf-8") == original


def test_prepared_approved_call_cannot_bypass_tool_budget(evaluation):
    Session, store, catalog, path = evaluation
    sends = []
    register(catalog, lambda args: sends.append("query") or {"fact": "open"})
    catalog.register(ToolDefinition("travel", "save", "保存合成旅行结果", {"type": "object"}, effect="write"),
                     lambda args: sends.append("write") or {"receipt": "saved"})
    waiting = task(store)
    run_id = waiting["current_run_id"]
    lease = store.claim(run_id, "preparation")
    definition = catalog.describe("travel.save")
    pending = store.prepare_call(run_id, lease["lease_token"], "travel.save", definition["version"], {},
                                 effect="write", requires_approval=True)
    store.approve_call(waiting["agent_task_id"], pending["tool_call_id"],
                       {"request_id": str(uuid4()), "expected_revision": 1})
    session = Session(store, catalog, path, completion_fn=model(CALL, ARTIFACT, FINISH), max_tool_calls=1)
    first = session.run(task(store)["agent_task_id"], "consume-limit")
    assert first["execution_status"] == "completed"
    blocked = session.run(waiting["agent_task_id"], "approved-resume")
    assert blocked["execution_status"] == "budget_exhausted"
    assert blocked["evaluation_stop_reason"] == "evaluation_tool_budget"
    assert blocked["model_calls"] == [] and sends == ["query"]
    assert blocked["snapshot"]["tool_calls"][0]["state"] == "prepared"
