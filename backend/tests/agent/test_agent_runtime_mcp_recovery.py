"""Production loop + real localhost MCP session recovery, with controlled decisions."""
import json
from uuid import uuid4

from backend.agent.decision import DecisionProvider
from backend.agent.mcp import McpClient, McpServerConfig
from backend.agent.runtime import AgentRuntime
from backend.agent.store import AgentStore
from backend.agent.tools import ToolCatalog, ToolDefinition
from backend.tests.agent.test_agent_mcp import server, query_tool


def task(store):
    return store.create({"request_id": str(uuid4()), "conversation_id": "mcp-recovery",
                         "message_id": str(uuid4()), "goal": "查询成都天气，安排旅行",
                         "constraints": {"city": "成都"}, "success_criteria": ["依据查询返回安排"],
                         "budget": {"max_steps": 8, "max_tool_calls": 4, "max_duration_seconds": 300}})


def test_expired_mcp_session_recovers_only_after_explicit_discovery(tmp_path):
    store, catalog = AgentStore(tmp_path / "agent.sqlite3"), ToolCatalog()
    created = task(store)
    refreshes, decisions = [], []
    with server(label="weather", tools=[query_tool(description="查询成都天气预报")]) as (url, state):
        with McpClient(McpServerConfig("weather", url, {"search": "read"})) as client:
            client.register_tools(catalog)
            def refresh():
                refreshes.append(1)
                return client.register_tools(catalog)

            def decide(messages, model, structured=False):
                context = json.loads(messages[-1]["content"])
                decisions.append(context)
                step = len(decisions)
                if step == 1:
                    state["session"] = "session-2"
                    return json.dumps({"action": "call_tool", "tool_id": "weather.search", "arguments": {"city": "成都"}})
                if step == 2:
                    assert state["calls"] == 0
                    assert sum(body["method"] == "initialize" for body, _ in state["seen"]) == 1
                    assert sum(body["method"] == "tools/call" for body, _ in state["seen"]) == 1
                    assert context["tool_results"][0]["error"]["code"] == "mcp_session_expired"
                    assert refreshes == []
                    return json.dumps({"action": "discover_tools", "query": "成都天气"})
                if step == 3:
                    assert refreshes == [1]
                    assert any(tool["tool_id"] == "weather.search" for tool in context["tools"])
                    return json.dumps({"action": "call_tool", "tool_id": "weather.search", "arguments": {"city": "成都"}})
                if step == 4:
                    assert state["calls"] == 1
                    return json.dumps({"action": "publish_artifact", "kind": "text", "title": "天气查询结果",
                                       "content": "已恢复连接，查询结果来自新调用", "evidence_call_ids": []})
                return json.dumps({"action": "finish", "summary": "已交付查询结果"})

            runtime = AgentRuntime(store, catalog, DecisionProvider(completion_fn=decide, model="fixture"),
                                   memory_reader=lambda _: [], discovery_refresh=refresh)
            runtime.run(created["current_run_id"])
            result = store.get(created["agent_task_id"])
            assert result["run"]["state"] == "completed"
            assert result["run"]["tool_calls_used"] == 2
            calls = result["tool_calls"]
            assert [call["state"] for call in calls] == ["failed", "succeeded"]
            assert calls[0]["tool_call_id"] != calls[1]["tool_call_id"]
            assert state["calls"] == 1
            assert sum(body["method"] == "tools/call" for body, _ in state["seen"]) == 2
            assert sum(body["method"] == "initialize" for body, _ in state["seen"]) == 2
            assert len(decisions) == 5


def test_schema_changed_during_model_response_cannot_dispatch_new_handler(tmp_path):
    store, catalog = AgentStore(tmp_path / "agent.sqlite3"), ToolCatalog()
    created = task(store)
    original_calls, changed_calls, decisions = [], [], []
    schema = {"type": "object", "properties": {}, "additionalProperties": False}
    catalog.register(ToolDefinition("weather", "search", "查询成都天气预报", schema),
                     lambda args: original_calls.append(args) or {"weather": "old"})
    original_version = catalog.describe("weather.search")["version"]

    def decide(messages, model, structured=False):
        context = json.loads(messages[-1]["content"])
        decisions.append(context)
        if len(decisions) == 1:
            visible = next(tool for tool in context["tools"] if tool["tool_id"] == "weather.search")
            assert visible["version"] == original_version
            catalog.register(ToolDefinition("weather", "search", "查询成都历史天气，与预报不同", schema),
                             lambda args: changed_calls.append(args) or {"weather": "changed"})
            return json.dumps({"action": "call_tool", "tool_id": "weather.search", "arguments": {}})
        assert "tool_changed" in json.dumps(context)
        return json.dumps({"action": "ask_user", "question": "工具说明发生变化，先确认需要预报还是历史信息"})

    runtime = AgentRuntime(store, catalog, DecisionProvider(completion_fn=decide, model="fixture"), memory_reader=lambda _: [])
    runtime.run(created["current_run_id"])
    result = store.get(created["agent_task_id"])
    assert result["run"]["state"] == "waiting_user"
    assert not original_calls and not changed_calls
    assert not result["tool_calls"]
