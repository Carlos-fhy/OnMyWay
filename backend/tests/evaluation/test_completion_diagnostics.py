"""Retain safe provider facts without changing the completion string contract."""
import json
from copy import deepcopy
import pickle

import pytest

from backend.assistant import api as assistant
from backend.tests.evaluation.test_agent_evaluation import ARTIFACT, FINISH, evaluation, task


def configure(monkeypatch, payload):
    monkeypatch.setenv("DASHSCOPE_BASE_URL", "https://api.deepseek.com")
    monkeypatch.setenv("DASHSCOPE_API_KEY", "test-only-provider-key")
    monkeypatch.setenv("AGENT_MODEL", "deepseek-flash")
    sent = []

    class Response:
        status_code = 200

        def json(self):
            return payload

    def post(url, **kwargs):
        sent.append(kwargs["json"])
        return Response()

    monkeypatch.setattr(assistant.httpx, "post", post)
    return sent


@pytest.mark.parametrize("reason", ["stop", "length"])
def test_completion_preserves_content_and_safe_finish_facts(monkeypatch, reason):
    content = '{"action":"finish","summary":"visible content"}'
    sent = configure(monkeypatch, {
        "model": "deepseek-flash", "id": "private-response-id",
        "choices": [{"finish_reason": reason, "message": {"content": content}}],
        "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120,
                  "private_usage_field": "SECRET"}})
    result = assistant.completion([], "deepseek-flash", structured=True, max_tokens=2048)
    assert isinstance(result, str) and result == content and result.encode() == content.encode()
    assert result.provider_metadata == {
        "finish_reason": reason, "response_model_matches_request": True,
        "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120},
        "requested_response_format": "json_object", "requested_max_tokens": 2048}
    assert sent[0]["response_format"] == {"type": "json_object"}
    assert sent[0]["thinking"] == {"type": "disabled"}
    assert "SECRET" not in json.dumps(result.provider_metadata)
    assert "private-response-id" not in json.dumps(result.provider_metadata)


def test_untrusted_metadata_is_not_echoed_or_coerced(monkeypatch):
    configure(monkeypatch, {"model": "PRIVATE-MODEL",
        "choices": [{"finish_reason": "SECRET", "message": {"content": "result"}}],
        "usage": {"prompt_tokens": True, "completion_tokens": "SECRET", "total_tokens": -1,
                  "prompt_cache_hit_tokens": 10, "prompt_cache_miss_tokens": 10**30}})
    result = assistant.completion([], "deepseek-flash")
    assert result.provider_metadata == {"finish_reason": "unknown", "response_model_matches_request": False,
        "usage": {"prompt_cache_hit_tokens": 10}, "requested_response_format": "text"}
    assert "PRIVATE" not in json.dumps(result.provider_metadata)
    assert "SECRET" not in json.dumps(result.provider_metadata)


@pytest.mark.parametrize("copy_result", [deepcopy, lambda value: pickle.loads(pickle.dumps(value))])
def test_completion_remains_copyable_as_a_string(monkeypatch, copy_result):
    configure(monkeypatch, {"model": "deepseek-flash",
        "choices": [{"finish_reason": "stop", "message": {"content": "result"}}]})
    result = assistant.completion([], "deepseek-flash")
    copied = copy_result(result)
    assert copied == result and isinstance(copied, str)
    assert copied.provider_metadata == result.provider_metadata
    copied.provider_metadata["finish_reason"] = "changed"
    assert result.provider_metadata["finish_reason"] == "stop"


def test_evaluation_keeps_truncation_metadata_and_rejects_partial_action(evaluation, monkeypatch):
    Session, store, catalog, path = evaluation
    raw = '{"action":"finish","summary":"cut'
    configure(monkeypatch, {"model": "deepseek-flash",
        "choices": [{"finish_reason": "length", "message": {"content": raw}}],
        "usage": {"prompt_tokens": 100, "completion_tokens": 2048, "total_tokens": 2148}})
    session = Session(store, catalog, path, allow_live=True, max_model_calls=1)
    record = session.run(task(store)["agent_task_id"], "truncated-protocol")
    entry = record["model_calls"][0]
    assert entry["status"] == "invalid_decision" and entry["action"] is None
    assert entry["raw_text"] == raw
    assert entry["provider_metadata"]["finish_reason"] == "length"
    assert entry["provider_metadata"]["requested_response_format"] == "json_object"
    assert entry["provider_metadata"]["requested_max_tokens"] == 2048
    assert not record["snapshot"]["artifacts"] and not record["snapshot"]["tool_calls"]
    assert session.report["counts"]["model_calls"] == 1
    saved = json.loads(path.read_text("utf-8"))
    assert saved["phases"][0]["model_calls"][0]["provider_metadata"] == entry["provider_metadata"]


def test_custom_completion_cannot_claim_live_provider_metadata(evaluation):
    Session, store, catalog, path = evaluation
    actions = iter([ARTIFACT, FINISH])

    def complete(*_args, **_kwargs):
        return assistant.CompletionText(json.dumps(next(actions)), {"debug": "PRIVATE-EXTERNAL-DATA"})

    session = Session(store, catalog, path, completion_fn=complete)
    record = session.run(task(store)["agent_task_id"], "custom-provider")
    assert record["execution_status"] == "completed"
    assert all("provider_metadata" not in entry for entry in record["model_calls"])
    assert "PRIVATE-EXTERNAL-DATA" not in path.read_text("utf-8")
