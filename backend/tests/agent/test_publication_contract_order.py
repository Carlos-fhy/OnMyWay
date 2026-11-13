"""Content shape errors remain distinguishable from source authority errors."""
from copy import deepcopy
import json

import pytest

from backend.agent.delivery import DeliveryError, validate_artifact
from backend.agent.tools import ToolError
from backend.tests.agent.test_agent_runtime import parts, create_task, run


def test_structured_content_is_checked_before_source_adapter_and_never_coerced(parts):
    checked, feedback = [], []
    def source_validator(content, snapshot, sources):
        checked.append(deepcopy(content))
        if content != {"count": 7}:
            raise ToolError("artifact_source_mismatch", "Source does not support the record")
    parts.catalog.register_artifact_validator("record", source_validator)
    task = create_task(parts.store, constraints={"delivery_contract": {"kind": "record", "max_artifacts": 1,
        "content_schema": {"type": "object", "properties": {"count": {"type": "integer"}}, "required": ["count"]}}})
    bad = {"action": "publish_artifact", "kind": "record", "title": "Count",
           "content": '{"count":7,"private_probe":"do-not-echo"}', "evidence_call_ids": []}
    def correct(messages):
        data = json.loads(messages[-1]["content"])
        feedback.append((data["observations"][-1]["payload"], deepcopy(checked), data["artifacts"]))
        return {**bad, "content": {"count": 7}}
    result = run(parts, task, [bad, correct, {"action": "finish", "summary": "Count delivered"}])
    error, before_checks, before_artifacts = feedback[0]
    assert error["code"] == "artifact_contract_mismatch"
    assert "object" in error["message"] and "string" in error["message"]
    assert "private_probe" not in error["message"] and "do-not-echo" not in error["message"]
    assert not before_checks and not before_artifacts
    assert result["run"]["state"] == "completed"
    assert checked == [{"count": 7}, {"count": 7}]
    assert len(result["artifacts"]) == 1 and result["artifacts"][0]["version"] == 1
    assert result["artifacts"][0]["content"]["content"] == {"count": 7}


def test_valid_shape_still_requires_source_authority(parts):
    def source_validator(content, snapshot, sources):
        raise ToolError("artifact_source_mismatch", "No authoritative source")
    parts.catalog.register_artifact_validator("record", source_validator)
    task = create_task(parts.store, budget={"max_steps": 1, "max_tool_calls": 1, "max_duration_seconds": 30},
        constraints={"delivery_contract": {"kind": "record", "content_schema": {"type": "object"}}})
    result = run(parts, task, [{"action": "publish_artifact", "kind": "record", "title": "Count",
                              "content": {"count": 7}, "evidence_call_ids": []}])
    assert not result["artifacts"]
    assert result["observations"][-1]["payload"]["code"] == "artifact_source_mismatch"


@pytest.mark.parametrize("schema,value,expected,actual", [
    ({"type": "object"}, '{"value":"do-not-echo"}', "object", "string"),
    ({"type": "object", "properties": {"count": {"type": "integer"}}}, {"count": True}, "integer", "boolean"),
    ({"type": "object", "properties": {"rows": {"type": "array"}}}, {"rows": "do-not-echo"}, "array", "string"),
])
def test_delivery_type_feedback_states_expected_and_actual_without_echoing_values(schema, value, expected, actual):
    original = deepcopy(value)
    with pytest.raises(DeliveryError) as error:
        validate_artifact({"delivery_contract": {"content_schema": schema}}, "record", {"content": value})
    message = str(error.value)
    assert error.value.code == "artifact_contract_mismatch"
    assert expected in message and actual in message and "do-not-echo" not in message
    assert value == original
