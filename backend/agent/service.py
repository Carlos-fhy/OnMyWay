"""Opt-in durable worker and operator-owned MCP configuration."""
from contextlib import asynccontextmanager
import json
import os
from pathlib import Path
import re
import threading

from backend.agent.decision import DecisionProvider
from backend.agent.mcp import McpClient, McpServerConfig
from backend.agent.runtime import AgentRuntime
from backend.agent.review import ReviewProvider
from backend.agent.store import AgentStore
from backend.agent.tools import ToolCatalog, ToolError


def load_mcp_config(path):
    path = Path(path)
    if path.stat().st_size > 65536:
        raise ValueError("MCP 配置文件超过大小限制")
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
        if not isinstance(data, dict) or set(data) != {"servers"} or not isinstance(data["servers"], list) or len(data["servers"]) > 10:
            raise ValueError
        configs, names = [], set()
        for server in data["servers"]:
            allowed = {"name", "url", "allowed_tools", "headers_env", "timeout_seconds", "max_response_bytes",
                       "official_service", "query_env", "query_params"}
            if not isinstance(server, dict) or set(server) - allowed:
                raise ValueError
            fields = dict(server)
            def resolve(references):
                if not isinstance(references, dict):
                    raise ValueError
                values = {}
                for key, variable in references.items():
                    if not isinstance(variable, str) or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,100}", variable):
                        raise ValueError
                    value = os.getenv(variable)
                    if not value:
                        raise ValueError
                    values[key] = value
                return values
            headers = resolve(fields.pop("headers_env", {}))
            query_secrets = resolve(fields.pop("query_env", {}))
            config = McpServerConfig(**fields, headers=headers, query_secrets=query_secrets)
            if config.name in names or config.name in {"onmyway", "agent"}:
                raise ValueError
            names.add(config.name)
            configs.append(config)
        return configs
    except (TypeError, ValueError, KeyError, ToolError):
        # Never echo a credential-bearing config or a raw parsing exception.
        raise ValueError("MCP 配置无效或所引用的凭据尚未设置") from None


class AgentService:
    def __init__(self, app, *, runtime_factory=None, poll_seconds=0.25):
        self.app = app
        self.runtime_factory = runtime_factory or self._configured_runtime
        self.poll_seconds = poll_seconds
        self.stopped = threading.Event()
        self.thread = threading.Thread(target=self._work, name="onmyway-agent-worker", daemon=True)
        self.clients = []
        self.state = "starting"
        self.errors = []

    def _configured_runtime(self, _app):
        from backend.agent.builtin import build_executor, register_builtin
        token = os.getenv("ONMYWAY_BACKUP_TOKEN", "")
        if len(token) < 32:
            raise ValueError("数据服务身份尚未配置")
        catalog = ToolCatalog()
        register_builtin(catalog, self.app, token)
        path = os.getenv("ONMYWAY_MCP_SERVERS_FILE", "").strip()
        for config in load_mcp_config(path) if path else []:
            if self.stopped.is_set():
                break
            client = McpClient(config)
            self.clients.append(client)
            try:
                client.register_tools(catalog)
            except ToolError as error:
                self.errors.append({"source": config.name, "code": error.code})
        def refresh():
            for client in self.clients:
                if self.stopped.is_set():
                    return
                try:
                    client.register_tools(catalog)
                except ToolError as error:
                    self.errors.append({"source": client.config.name, "code": error.code})
                    self.errors = self.errors[-20:]
        lease_seconds = int(os.getenv("ONMYWAY_AGENT_LEASE_SECONDS", "90"))
        max_input_tokens = int(os.getenv("ONMYWAY_AGENT_INPUT_TOKENS", "12000"))
        if not 1 <= lease_seconds <= 300 or not 1024 <= max_input_tokens <= 14336:
            raise ValueError("Agent 预算配置超出范围")
        store = AgentStore()
        return AgentRuntime(store, catalog, DecisionProvider(), lease_seconds=lease_seconds,
                            max_input_tokens=max_input_tokens, discovery_refresh=refresh,
                            delegation_enabled=os.getenv("ONMYWAY_AGENT_DELEGATION_ENABLED", "true").lower() == "true",
                            artifact_reviewer=ReviewProvider(),
                            call_executor=build_executor(self.app, token, store=store), stop_event=self.stopped)

    def start(self):
        self.thread.start()

    def stop(self):
        self.stopped.set()
        self.thread.join(timeout=5)

    def status(self):
        return {"enabled": True, "state": self.state, "errors": self.errors[-20:]}

    def _work(self):
        try:
            runtime = self.runtime_factory(self.app)
            self.state = "running"
            while not self.stopped.is_set():
                ready = runtime.store.ready_runs(limit=10)
                for run_id in ready:
                    if self.stopped.is_set():
                        break
                    try:
                        runtime.run(run_id)
                    except Exception:
                        # Runtime pauses its owned run; retain a safe diagnostic only.
                        self.errors.append({"source": "runtime", "code": "run_failed"})
                        self.errors = self.errors[-20:]
                self.stopped.wait(self.poll_seconds)
        except Exception:
            self.state = "unavailable"
            self.errors.append({"source": "worker", "code": "initialization_or_storage_error"})
        finally:
            for client in self.clients:
                client.close()
            if self.stopped.is_set():
                self.state = "stopped"


@asynccontextmanager
async def agent_lifespan(app):
    enabled = os.getenv("ONMYWAY_AGENT_WORKER_ENABLED", "false").lower() == "true"
    service = AgentService(app) if enabled else None
    app.state.agent_worker = service
    if service:
        service.start()
    try:
        yield
    finally:
        if service:
            service.stop()
