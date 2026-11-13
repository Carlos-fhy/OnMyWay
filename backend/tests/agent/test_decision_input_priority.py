"""Bounded context keeps the facts required by a selected cross-domain tool."""
from copy import deepcopy
import json

from backend.agent.decision import build_context
from backend.tests.agent.test_agent_decision import snapshot
from backend.tests.agent.test_agent_tool_focus import discovery


def test_selected_invoice_contract_keeps_older_input_over_recent_unrelated_news():
    task = snapshot()
    task.update(goal="Prepare a draft invoice", constraints={"authorization": "draft only", "terms": "t" * 600})
    contract = {"tool_id": "billing.prepare_invoice", "description": "Invoice preparation details. " * 30,
                "version": "v1", "effect": "read", "executor": "server", "source": "builtin:billing",
                "input_schema": {"type": "object", "properties": {"account_id": {"type": "string"},
                    "raw_records": {"type": "array", "items": {"type": "string"}}},
                    "required": ["account_id", "raw_records"], "additionalProperties": False}}
    source = {"tool_call_id": "original-records", "tool_id": "ledger.read_records", "state": "succeeded",
              "effect": "read", "created_at": 1, "updated_at": 2,
              "result": {"account_id": "account-7", "raw_records": ["exact original value"], "notes": "r" * 950}}
    task["tool_calls"] = [source, {"tool_call_id": "newer-news", "tool_id": "news.headlines", "state": "succeeded",
        "effect": "read", "created_at": 3, "updated_at": 4, "result": {"headlines": "n" * 1100}}]
    task["observations"] = [discovery(contract["tool_id"], [contract], created_at=5)]
    before = deepcopy(task)
    context = build_context(task, [contract], max_input_tokens=5900)
    data = json.loads(context.messages[-1]["content"])
    assert data["tools"][0] == contract
    visible = next(item for item in data["tool_results"] if item["tool_call_id"] == source["tool_call_id"])
    assert visible.get("result") == source["result"]
    assert visible["tool_id"] == source["tool_id"] and visible["truncated"] is False
    assert data["task"]["constraints"] == task["constraints"]
    assert context.estimated_input_tokens <= 5900 and task == before


def test_valid_action_reaching_argument_validation_resolves_only_old_format_error():
    task = snapshot()
    task["observations"] = [
        {"kind": "decision_error", "created_at": 1, "payload": {"code": "invalid_decision", "diagnostic": {"rule": "invalid_json"}}},
        {"kind": "decision_error", "created_at": 2, "payload": {"code": "artifact_source_mismatch", "message": "Source differs"}},
        {"kind": "tool_error", "created_at": 3, "payload": {"code": "invalid_arguments", "tool_id": "billing.prepare_invoice"}},
    ]
    before = deepcopy(task)
    context = build_context(task)
    data = json.loads(context.messages[-1]["content"])
    assert [item["payload"]["code"] for item in data["observations"]] == ["artifact_source_mismatch", "invalid_arguments"]
    assert task == before
