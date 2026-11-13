"""Compose real local MCP transport and existing H2 authority with Agent state."""
import json
import sqlite3
from uuid import uuid4

from fastapi import HTTPException
import pytest

from backend.agent.decision import DecisionProvider
from backend.agent.mcp import McpClient, McpServerConfig
from backend.agent.runtime import AgentRuntime
from backend.agent.store import AgentStore
from backend.agent.tools import ToolCatalog
from backend.tests.agent.test_agent_acceptance import create, loop, successful
from backend.tests.agent.test_agent_mcp import query_tool, server
from backend.tests.execution.test_execution_runtime import prepare_navigation, submit, runtime as execution_runtime
from backend.tests.data_services.test_tasks import TOKEN


def test_delivered_mcp_write_lost_receipt_stays_unknown_after_reopen_and_revision(tmp_path):
    database = tmp_path / "agent.sqlite3"
    store, catalog = AgentStore(database), ToolCatalog()
    actions = iter([
        {"action": "discover_tools", "query": "writes.save"},
        {"action": "call_tool", "tool_id": "writes.save", "arguments": {"city": "成都"}},
    ])
    decisions = []
    def completion(*args, **kwargs):
        decisions.append(1)
        return json.dumps(next(actions), ensure_ascii=False)
    provider = DecisionProvider(completion_fn=completion)
    with server(tools=[query_tool("save", "保存合成记录")]) as (url, remote):
        with McpClient(McpServerConfig("writes", url, {"save": "write"}, timeout_seconds=0.3)) as connection:
            connection.register_tools(catalog)
            task = create(store, "保存合成记录，未知结果必须先核对原调用")
            runtime = AgentRuntime(store, catalog, provider, memory_reader=lambda _: [])
            runtime.run(task["current_run_id"])
            pending = store.get(task["agent_task_id"])
            call_id = pending["tool_calls"][0]["tool_call_id"]
            assert pending["run"]["state"] == "waiting_approval" and remote["calls"] == 0
            approval = {"request_id": str(uuid4()), "expected_revision": 1}
            store.approve_call(task["agent_task_id"], call_id, approval)
            store.approve_call(task["agent_task_id"], call_id, approval)
            # The server counts the accepted write before withholding its receipt.
            remote.update(mode="timeout", delay_seconds=0.8, result={"saved": True})
            runtime.run(task["current_run_id"])
            current = store.get(task["agent_task_id"])
            assert remote["calls"] == 1
            assert current["run"]["state"] == "paused"
            assert current["tool_calls"][0]["state"] == "unknown"
            assert current["tool_calls"][0]["tool_call_id"] == call_id
            requests = [body for body, _ in remote["seen"] if body["method"] == "tools/call"]
            assert len(requests) == 1 and requests[0]["params"]["arguments"] == {"city": "成都"}

            reopened = AgentStore(database)
            restored = AgentRuntime(reopened, catalog, provider, memory_reader=lambda _: [])
            restored.run(task["current_run_id"])
            with pytest.raises(HTTPException) as rejected:
                reopened.resume(task["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1})
            assert rejected.value.status_code == 409
            revised = reopened.revise(task["agent_task_id"], {
                "request_id": str(uuid4()), "expected_revision": 1,
                "goal": "修改后仍先核对原保存结果", "constraints": {},
            })
            restored.run(revised["current_run_id"])
            final = reopened.get(task["agent_task_id"])
            assert final["run"]["state"] == "paused"
            assert final["unresolved_calls"][0]["tool_call_id"] == call_id
            assert reopened.get_call(call_id)["state"] == "unknown"
            assert remote["calls"] == 1 and len(decisions) == 2
            assert final["artifacts"] == []


def test_expired_h2_confirmation_cannot_be_promoted_by_agent_read(tmp_path, execution_runtime):
    client, head, event_path = execution_runtime
    body, _ = prepare_navigation(client, head)
    assert submit(client, body).status_code == 200
    with sqlite3.connect(event_path) as db:
        db.execute("UPDATE confirmation_receipts SET valid_until_epoch=0")
    path = f'/executions/{body["run_id"]}'
    assert client.post(path + "/confirm", headers=TOKEN,
                       json={"confirmation_id": str(uuid4())}).status_code == 409
    store = AgentStore(tmp_path / "agent.sqlite3")
    task = create(store, "核对原导航执行；读取记录不能代替有效授权或设备回执")
    observed = []
    def decide(data):
        readings = successful(data, "onmyway.read_execution")
        if not readings:
            return {"action": "call_tool", "tool_id": "onmyway.read_execution", "arguments": {"run_id": body["run_id"]}}
        result = readings[-1]["result"]["data"]
        observed.append(result)
        assert result["recorded_is_not_executed"] is True
        assert result["dispatch_allowed"] is False and result["device_execution_observed"] is False
        return {"action": "ask_user", "question": "尚未收到设备执行回执，请先核对原确认状态"}
    loop(store, decide, builtin=True).run(task["current_run_id"])
    final = store.get(task["agent_task_id"])
    assert observed and final["run"]["state"] == "waiting_user"
    assert final["artifacts"] == []
    persisted = client.get(path, headers=TOKEN).json()
    assert not any(step["step"] == "dispatch_claimed" for step in persisted["steps"])
    assert persisted["device_execution_observed"] is False
