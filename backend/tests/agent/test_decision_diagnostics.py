"""Safe actionable diagnostics preserve strict decision rejection and budget use."""
import json

import pytest

from backend.agent.decision import DecisionError, DecisionProvider, SYSTEM_PROMPT, parse_decision
from backend.tests.agent.test_agent_runtime import create_task, parts


def rejected(raw):
    with pytest.raises(DecisionError) as raised:
        parse_decision(raw)
    error = raised.value
    assert error.code == "invalid_decision"
    assert len(json.dumps(error.diagnostic, ensure_ascii=False, separators=(",", ":")).encode()) <= 900
    assert len(str(error).encode()) <= 400
    assert "agent.read_result" not in str(error)
    return error


@pytest.mark.parametrize("tail", ['{"action":"finish","summary":"second"}', 'private-tail-key=SECRET'])
def test_multiple_actions_and_trailing_text_are_rejected_without_extracting_first(tail):
    error = rejected('{"action":"finish","summary":"first"}\n' + tail)
    assert error.diagnostic["rule"] == "json_extra_data"
    assert "private-tail" not in str(error) and "SECRET" not in str(error)


@pytest.mark.parametrize("raw,rule", [
    ('```json\n{"action":"finish","summary":"done"}\n```', "invalid_json"),
    ('[{"action":"finish","summary":"done"}]', "object_required"),
    ('{"action":"finish","action":"finish","summary":"done"}', "invalid_json_value"),
    ('{"action":"call_tool","tool_id":"demo.query","arguments":{"x":NaN}}', "invalid_json_value"),
    ('{"action":"call_tool","tool_id":"demo.query","arguments":{"x":1e999}}', "invalid_json_value"),
])
def test_other_invalid_json_is_still_rejected(raw, rule):
    assert rejected(raw).diagnostic["rule"] == rule


@pytest.mark.parametrize("field", ["type", "secret-key-MUST-NOT-LEAK"])
def test_extra_fields_expose_only_the_program_defined_contract(field):
    error = rejected(json.dumps({"action": "finish", "summary": "done", field: "secret-value-MUST-NOT-LEAK"}))
    assert error.diagnostic["rule"] == "extra_fields"
    assert set(error.diagnostic["allowed_fields"]) == {"action", "summary", "evidence_notes"}
    assert "MUST-NOT-LEAK" not in json.dumps(error.diagnostic) + str(error)


@pytest.mark.parametrize("query", ["", "   "])
def test_empty_discovery_requires_a_namespace_without_changing_the_contract(query):
    error = rejected(json.dumps({"action": "discover_tools", "query": query}))
    assert error.diagnostic["rule"] == "discovery_scope_required"
    assert error.diagnostic["fields"] == ["query", "namespace"]
    valid = parse_decision(json.dumps({"action": "discover_tools", "query": query, "namespace": "demo"}))
    assert valid.query == "" and valid.namespace == "demo"


@pytest.mark.parametrize("value,field,expected", [
    ({"action": "call_tool", "tool_id": "demo.query", "arguments": "SECRET"}, "arguments", "object"),
    ({"action": "ask_user", "question": False}, "question", "string"),
    ({"action": "delegate_task", "delegation_key": "child", "goal": "Read", "allowed_tools": "SECRET"}, "allowed_tools", "array"),
])
def test_wrong_types_identify_known_fields_and_expected_type(value, field, expected):
    error = rejected(json.dumps(value))
    assert error.diagnostic["rule"] == "wrong_type"
    assert error.diagnostic["field_path"] == [field]
    assert error.diagnostic["expected_type"] == expected
    assert "SECRET" not in json.dumps(error.diagnostic) + str(error)


def test_missing_field_and_unknown_action_are_distinct_and_safe():
    error = rejected('{"action":"call_tool","tool_id":"demo.query"}')
    assert error.diagnostic["rule"] == "missing_field"
    assert error.diagnostic["field_path"] == ["arguments"]
    error = rejected('{"action":"sensitive-invalid-output","secret-field":"PRIVATE"}')
    assert error.diagnostic["rule"] == "unsupported_action"
    assert "call_tool" in error.diagnostic["allowed_actions"]
    assert "sensitive-invalid-output" not in json.dumps(error.diagnostic) + str(error)
    assert "PRIVATE" not in json.dumps(error.diagnostic) + str(error)


def test_dynamic_dict_keys_and_values_cannot_leak_through_validation_paths():
    error = rejected(json.dumps({"action": "ask_user", "question": "Which date?",
        "missing_inputs": {"PRIVATE_DYNAMIC_KEY": {"token": "PRIVATE_VALUE"}}}))
    assert error.diagnostic["field_path"] == ["missing_inputs"]
    assert "PRIVATE" not in json.dumps(error.diagnostic) + str(error)


def test_nested_budget_and_ref_contracts_use_known_paths_and_allowed_values():
    base = {"action": "delegate_task", "delegation_key": "child", "goal": "Read", "allowed_tools": ["demo.query"]}
    error = rejected(json.dumps({**base, "budget": {"max_steps": "PRIVATE"}}))
    assert error.diagnostic["field_path"] == ["budget", "max_steps"]
    assert error.diagnostic["expected_type"] == "integer"
    error = rejected(json.dumps({**base, "input_refs": [{"kind": "PRIVATE", "ref": "PRIVATE"}]}))
    assert error.diagnostic["field_path"] == ["input_refs", "items", "kind"]
    assert error.diagnostic["allowed_values"] == ["result", "artifact"]
    assert "PRIVATE" not in json.dumps(error.diagnostic) + str(error)


def test_diagnostic_size_and_prompt_match_the_current_contract():
    value = {"action": "delegate_task", "delegation_key": "child", "goal": "Read", "allowed_tools": []}
    value.update({"PRIVATE" + str(index): "SECRET" * 100 for index in range(30)})
    error = rejected(json.dumps(value))
    assert error.diagnostic["rule"] == "extra_fields"
    assert "namespace" in SYSTEM_PROMPT.split('query=""', 1)[1].split(".", 1)[0]
    assert "exactly one JSON object" in SYSTEM_PROMPT
    assert "no extra text" in SYSTEM_PROMPT


def test_every_action_example_in_the_system_prompt_is_valid():
    examples = [line for line in SYSTEM_PROMPT.splitlines() if line.startswith("{")]
    assert len(examples) == 5
    for example in examples:
        assert parse_decision(example).action


def test_compacted_legacy_feedback_does_not_steer_into_a_reader():
    from backend.agent.decision import INVALID_DECISION_FEEDBACK, build_context
    from backend.tests.agent.test_agent_decision import snapshot
    task = snapshot()
    task["tool_calls"] = [{"tool_call_id": "failed-call", "tool_id": "demo.query", "state": "failed",
        "arguments": {}, "error": {"code": "invalid_arguments"}, "created_at": 1, "updated_at": 1}]
    task["observations"] = [{"kind": "decision_error", "created_at": 2,
        "payload": {"code": "invalid_decision", "message": INVALID_DECISION_FEEDBACK}}]
    data = json.loads(build_context(task).messages[-1]["content"])
    error = next(o["payload"] for o in data["observations"] if o["kind"] == "decision_error")
    assert "read via" not in error["message"] and "tool_call_id/paths" not in error["message"]
    assert "one" in error["message"]


@pytest.mark.parametrize("raw,rule", [
    ('{"action":"finish","summary":"invalid"}\n{"action":"finish","summary":"second"}', "json_extra_data"),
    ('{"action":"discover_tools","query":"","type":"json_object"}', "extra_fields"),
    ('{"action":"discover_tools","query":""}', "discovery_scope_required"),
    ('{"action":"call_tool","tool_id":"demo.query","arguments":"SECRET"}', "wrong_type"),
])
def test_specific_diagnostics_are_durable_visible_and_consume_a_step(parts, raw, rule):
    task = create_task(parts.store, goal="Produce a short test record", constraints={})
    seen = []
    def completion(messages, model, structured=False):
        seen.append(messages)
        if len(seen) == 1:
            return raw
        data = json.loads(messages[-1]["content"])
        assert data["run"]["steps_used"] == len(seen)
        if len(seen) == 2:
            feedback = next(x["payload"] for x in data["observations"] if x["kind"] == "decision_error")
            assert feedback["diagnostic"]["rule"] == rule
            assert "SECRET" not in json.dumps(feedback)
            assert "agent.read_result" not in feedback["message"]
            return json.dumps({"action": "publish_artifact", "kind": "text", "title": "Record",
                               "content": "Controlled correction succeeded", "evidence_call_ids": []})
        return '{"action":"finish","summary":"Delivered the controlled record"}'
    worker = parts.Runtime(parts.store, parts.catalog, DecisionProvider(completion_fn=completion), memory_reader=lambda _: [])
    worker.run(task["current_run_id"])
    result = parts.store.get(task["agent_task_id"])
    assert result["run"]["state"] == "completed"
    assert result["run"]["steps_used"] == 3
    assert not result["tool_calls"] and len(result["artifacts"]) == 1
    errors = [o["payload"] for o in result["observations"] if o["kind"] == "decision_error"]
    assert len(errors) == 1 and errors[0]["diagnostic"]["rule"] == rule
