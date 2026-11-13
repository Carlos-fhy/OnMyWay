"""Worker lifecycle and operator-only connection configuration."""
import importlib.util
import json
import threading
from types import SimpleNamespace
from uuid import uuid4

import pytest


@pytest.fixture
def service_module():
    assert importlib.util.find_spec("backend.agent.service") is not None, "Durable agent worker service is missing"
    from backend.agent import service as agent_service
    return agent_service


def test_config_uses_named_secret_reference_without_returning_secret(tmp_path, monkeypatch, service_module):
    file = tmp_path / "servers.json"
    file.write_text(json.dumps({"servers": [{"name": "travel", "url": "http://127.0.0.1:39001/mcp",
                                            "allowed_tools": {"search": "read"},
                                            "headers_env": {"Authorization": "ONMYWAY_TEST_MCP_AUTH"}}]}))
    monkeypatch.setenv("ONMYWAY_TEST_MCP_AUTH", "Bearer synthetic-secret")
    configs = service_module.load_mcp_config(file)
    assert len(configs) == 1 and configs[0].headers["Authorization"] == "Bearer synthetic-secret"
    assert "synthetic-secret" not in repr(configs[0])


@pytest.mark.parametrize("servers", [
    [{"name": "bad", "url": "http://127.0.0.1:39001/mcp", "allowed_tools": {}, "command": "shell"}],
    [{"name": "bad", "url": "http://127.0.0.1:39001/mcp", "allowed_tools": {}, "headers_env": {"Authorization": "MISSING_TEST_MCP_SECRET"}}],
    [{"name": "same", "url": "http://127.0.0.1:39001/mcp", "allowed_tools": {}}] * 2,
])
def test_invalid_config_cannot_install_tools_or_invoke_commands(tmp_path, service_module, servers):
    path = tmp_path / "servers.json"
    path.write_text(json.dumps({"servers": servers}))
    with pytest.raises(ValueError):
        service_module.load_mcp_config(path)


def test_worker_advances_durable_queued_task_and_stops(tmp_path, service_module):
    from backend.agent.store import AgentStore
    from backend.agent.tools import ToolCatalog
    from backend.agent.decision import DecisionProvider
    from backend.agent.runtime import AgentRuntime
    store = AgentStore(tmp_path / "agent.sqlite3")
    task = store.create({"request_id": str(uuid4()), "conversation_id": "trip", "message_id": str(uuid4()),
                         "goal": "成都四天", "constraints": {}, "success_criteria": []})
    observed = threading.Event()
    def completion(messages, model, structured=False):
        observed.set()
        return '{"action":"ask_user","question":"从哪里出发？"}'
    runtime = AgentRuntime(store, ToolCatalog(), DecisionProvider(completion_fn=completion), memory_reader=lambda _: [])
    service = service_module.AgentService(SimpleNamespace(), runtime_factory=lambda _: runtime, poll_seconds=0.01)
    service.start()
    try:
        assert observed.wait(timeout=5)
        for _ in range(200):
            if store.get(task["agent_task_id"])["run"]["state"] == "waiting_user":
                break
            threading.Event().wait(0.01)
        assert store.get(task["agent_task_id"])["run"]["state"] == "waiting_user"
    finally:
        service.stop()
    assert not service.thread.is_alive()


def test_missing_provider_is_reported_without_repeated_work(tmp_path, monkeypatch, service_module):
    from backend.agent.store import AgentStore
    from backend.agent.tools import ToolCatalog
    from backend.agent.decision import DecisionProvider
    from backend.agent.runtime import AgentRuntime
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)
    monkeypatch.delenv("DASHSCOPE_BASE_URL", raising=False)
    store = AgentStore(tmp_path / "agent.sqlite3")
    task = store.create({"request_id": str(uuid4()), "conversation_id": "x", "message_id": str(uuid4()), "goal": "旅行"})
    runtime = AgentRuntime(store, ToolCatalog(), DecisionProvider(), memory_reader=lambda _: [])
    service = service_module.AgentService(SimpleNamespace(), runtime_factory=lambda _: runtime, poll_seconds=0.01)
    service.start()
    try:
        for _ in range(200):
            state = store.get(task["agent_task_id"])
            if state["run"]["state"] == "paused":
                break
            threading.Event().wait(0.01)
        assert state["run"]["state"] == "paused"
        assert state["run"]["reason"] == "provider_not_configured"
        assert state["run"]["steps_used"] == 1
    finally:
        service.stop()
