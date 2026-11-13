"""Official MCP Streamable HTTP client, JSON and bounded POST SSE responses.

Supports negotiated 2025-03-26 / 2025-06-18, sessions and paged tools/list.
Legacy GET SSE, stdio, OAuth discovery, server-initiated requests and automatic
tool retries are deliberately unsupported. URLs and headers never come from
the model. External write failures remain unknown until separately reconciled.
"""
import asyncio
from copy import deepcopy
from dataclasses import dataclass, field
import json
import logging
import math
import re
from threading import RLock
import time
from urllib.parse import quote, quote_plus, urlsplit

import httpx
import anyio

from backend.agent.tools import ToolCatalog, ToolDefinition, ToolError
from backend.agent.official_services import OFFICIAL_SERVICES, validate_endpoint


PROTOCOLS = ("2025-06-18", "2025-03-26")


@dataclass(frozen=True)
class McpServerConfig:
    name: str
    url: str = field(repr=False)
    allowed_tools: dict[str, str]
    headers: dict[str, str] = field(default_factory=dict, repr=False)
    timeout_seconds: float = 15.0
    max_response_bytes: int = 65536
    official_service: str | None = None
    query_params: dict[str, str] = field(default_factory=dict)
    query_secrets: dict[str, str] = field(default_factory=dict, repr=False)

    def __post_init__(self):
        try:
            url = urlsplit(self.url)
            valid_url = url.scheme in ("http", "https") and bool(url.hostname) and not url.username and not url.password and not url.fragment
            valid_url = valid_url and (url.scheme == "https" or url.hostname in ("localhost", "127.0.0.1", "::1"))
        except (TypeError, ValueError):
            valid_url = False
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", self.name) or not valid_url:
            raise ToolError("invalid_mcp_config", "MCP 名称或地址无效；远程服务须使用已核实的官方 HTTPS 地址")
        try:
            for values in (self.query_params, self.query_secrets):
                if not isinstance(values, dict) or any(not isinstance(k, str) or not isinstance(v, str) or not v or len(v) > 8192
                                                       for k, v in values.items()):
                    raise ValueError
            validate_endpoint(self.url, self.official_service, self.query_params, self.query_secrets)
            if "tools" in self.query_params and not re.fullmatch(r"[A-Za-z0-9_]+(?:,[A-Za-z0-9_]+){0,49}", self.query_params["tools"]):
                raise ValueError
        except (TypeError, ValueError):
            raise ToolError("invalid_mcp_config", "MCP 官方来源、连接地址或查询配置未通过校验") from None
        if not isinstance(self.allowed_tools, dict) or len(self.allowed_tools) > 200 or any(
            not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", name) or effect not in ("read", "write", "device")
            for name, effect in self.allowed_tools.items()
        ):
            raise ToolError("invalid_mcp_config", "MCP 允许工具及其执行影响必须显式配置")
        reserved = {"accept", "content-type", "mcp-session-id", "mcp-protocol-version", "host", "content-length"}
        if not isinstance(self.headers, dict) or any(
            not isinstance(key, str) or not isinstance(value, str) or key.lower() in reserved or
            not re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+", key) or "\r" in value or "\n" in value
            for key, value in self.headers.items()
        ):
            raise ToolError("invalid_mcp_config", "MCP 请求头配置无效")
        if isinstance(self.timeout_seconds, bool) or not isinstance(self.timeout_seconds, (int, float)) or not math.isfinite(self.timeout_seconds) or not 0 < self.timeout_seconds <= 120:
            raise ToolError("invalid_mcp_config", "MCP 超时必须介于 0 和 120 秒之间")
        if type(self.max_response_bytes) is not int or not 1024 <= self.max_response_bytes <= 8 * 1024 * 1024:
            raise ToolError("invalid_mcp_config", "MCP 返回大小预算无效")
        object.__setattr__(self, "headers", deepcopy(self.headers))
        object.__setattr__(self, "allowed_tools", deepcopy(self.allowed_tools))
        object.__setattr__(self, "query_params", deepcopy(self.query_params))
        object.__setattr__(self, "query_secrets", deepcopy(self.query_secrets))


class _CredentialFilter(logging.Filter):
    """HTTPX logs request URLs even at INFO; remove credential values there too."""
    def __init__(self, config):
        super().__init__()
        self.secrets = sorted({encoded for value in [*config.query_secrets.values(), *config.headers.values()]
                               for encoded in (value, quote(value, safe=""), quote_plus(value)) if value}, key=len, reverse=True)

    def filter(self, record):
        text = record.getMessage()
        for value in self.secrets:
            text = text.replace(value, "[REDACTED]")
        record.msg, record.args = text, ()
        return True


class McpClient:
    def __init__(self, config: McpServerConfig):
        self.config = deepcopy(config)
        # Keep a private loop and pool behind the existing synchronous API. An
        # cancellation deadline bounds blocked DNS/connect/write/read operations;
        # an HTTPX read timeout alone would restart after every arriving chunk.
        self._runner = asyncio.Runner()
        self._http = httpx.AsyncClient(timeout=config.timeout_seconds, follow_redirects=False, trust_env=False)
        self._lock = RLock()
        self._request_id = 0
        self._session = None
        self._protocol = None
        self._initialized = False
        self._closed = False
        self._definitions = {}
        self._catalogs = []
        self._credential_filter = _CredentialFilter(config)
        self._loggers = [logging.getLogger(name) for name in
                         ("httpx", "httpcore.connection", "httpcore.http11", "httpcore.http2", "httpcore.proxy")]
        for logger in self._loggers:
            logger.addFilter(self._credential_filter)

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.close()

    def close(self):
        with self._lock:
            if self._closed:
                return
            try:
                self._runner.run(self._http.aclose())
            finally:
                self._runner.close()
                self._closed = True
                self._invalidate()
                for logger in self._loggers:
                    logger.removeFilter(self._credential_filter)

    def _invalidate(self):
        self._session = None
        self._protocol = None
        self._initialized = False
        self._definitions = {}
        for catalog in self._catalogs:
            catalog.replace_namespace(self.config.name, [])

    def _request(self, method, params=None, *, notification=False, outcome="failed"):
        with self._lock:
            if self._closed:
                raise ToolError("tool_unavailable", "MCP 连接已关闭")
            try:
                return self._runner.run(self._request_with_deadline(method, params, notification=notification, outcome=outcome))
            except TimeoutError:
                raise ToolError("mcp_transport_error", "MCP 请求超时，本次调用未自动重试", outcome=outcome) from None

    async def _request_with_deadline(self, method, params, *, notification, outcome):
        # HTTPX/httpcore use AnyIO cancellation scopes internally. Use the same
        # mechanism so cleanup cannot leak CancelledError past the sync API.
        with anyio.fail_after(self.config.timeout_seconds):
            return await self._request_async(method, params, notification=notification, outcome=outcome)

    async def _request_async(self, method, params, *, notification, outcome):
        if self._closed:
            raise ToolError("tool_unavailable", "MCP 连接已关闭")
        self._request_id += 1
        message = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            message["params"] = params
        if not notification:
            message["id"] = self._request_id
        headers = {**self.config.headers, "Accept": "application/json, text/event-stream", "Content-Type": "application/json"}
        if self._session:
            headers["Mcp-Session-Id"] = self._session
        if self._protocol:
            headers["MCP-Protocol-Version"] = self._protocol
        started = time.monotonic()
        try:
            async with self._http.stream("POST", self.config.url, params={**self.config.query_params, **self.config.query_secrets},
                                         json=message, headers=headers) as response:
                if response.status_code == 404 and self._initialized:
                    self._invalidate()
                    raise ToolError("mcp_session_expired", "MCP 会话失效，请重新发现工具；本次调用未自动重试", outcome=outcome)
                if response.status_code not in (200, 202):
                    raise ToolError("mcp_http_error", "MCP 服务未接受请求", outcome=outcome)
                if notification and response.status_code == 202:
                    return None
                if notification:
                    raise ToolError("mcp_protocol_error", "MCP 通知应返回已接受状态", outcome=outcome)
                content_type = response.headers.get("content-type", "").split(";", 1)[0].strip().lower()
                if content_type == "text/event-stream":
                    parsed = await self._read_sse(response, message["id"], started, outcome)
                elif content_type == "application/json":
                    parsed = json.loads(b"".join([chunk async for chunk in self._bounded_chunks(response, started, outcome)]))
                else:
                    raise ToolError("mcp_unsupported_transport", "MCP 响应格式不支持", outcome=outcome)
                self._validate_response_id(parsed, message["id"], outcome)
                if "error" in parsed:
                    # Remote exception text can contain URLs, credentials or private input.
                    raise ToolError("mcp_rpc_error", "MCP 服务返回协议错误", outcome=outcome)
                if not isinstance(parsed.get("result"), dict):
                    raise ToolError("mcp_protocol_error", "MCP 返回缺少有效结果", outcome=outcome)
                if method == "initialize":
                    session = response.headers.get("Mcp-Session-Id")
                    if session is not None and (len(session) > 256 or not re.fullmatch(r"[\x21-\x7E]+", session)):
                        raise ToolError("mcp_protocol_error", "MCP 会话标识无效")
                    self._session = session
                return parsed["result"]
        except ToolError:
            raise
        except (httpx.HTTPError, ValueError, TypeError, UnicodeError, RecursionError):
            raise ToolError("mcp_transport_error", "MCP 通信失败，本次调用未自动重试", outcome=outcome) from None

    async def _bounded_chunks(self, response, started, outcome):
        size = 0
        # Do not request buffered 8 KiB blocks: an SSE stream can remain open
        # after its response, and partial network chunks must be processed now.
        async for chunk in response.aiter_bytes():
            if time.monotonic() - started > self.config.timeout_seconds:
                raise ToolError("mcp_transport_error", "MCP 请求超时，本次调用未自动重试", outcome=outcome)
            size += len(chunk)
            if size > self.config.max_response_bytes:
                raise ToolError("mcp_result_too_large", "MCP 返回超过配置的大小限制", outcome=outcome)
            yield chunk

    @staticmethod
    def _validate_response_id(parsed, expected_id, outcome):
        if (not isinstance(parsed, dict) or parsed.get("jsonrpc") != "2.0" or
            type(parsed.get("id")) is not type(expected_id) or parsed.get("id") != expected_id or "method" in parsed):
            raise ToolError("mcp_protocol_error", "MCP 返回的协议或请求身份不匹配", outcome=outcome)

    async def _read_sse(self, response, expected_id, started, outcome):
        buffer, data_lines = bytearray(), []
        async for chunk in self._bounded_chunks(response, started, outcome):
            buffer.extend(chunk)
            while b"\n" in buffer:
                raw, _, remaining = buffer.partition(b"\n")
                buffer = bytearray(remaining)
                line = raw.rstrip(b"\r").decode("utf-8")
                if not line:
                    if not data_lines:
                        continue
                    parsed = json.loads("\n".join(data_lines))
                    data_lines = []
                    if isinstance(parsed, dict) and parsed.get("jsonrpc") == "2.0" and "id" not in parsed and isinstance(parsed.get("method"), str):
                        # Progress/log notifications are not a result or permission.
                        continue
                    self._validate_response_id(parsed, expected_id, outcome)
                    return parsed
                if line.startswith("data:"):
                    data_lines.append(line[5:].removeprefix(" "))
                # SSE comments and event/id/retry metadata cannot execute code.
        raise ToolError("mcp_protocol_error", "MCP 流结束前未收到完整的匹配结果", outcome=outcome)

    def _initialize(self):
        if self._initialized:
            return
        result = self._request("initialize", {"protocolVersion": PROTOCOLS[0], "capabilities": {},
                                              "clientInfo": {"name": "OnMyWay", "version": "agent-a1"}})
        if result.get("protocolVersion") not in PROTOCOLS or not isinstance(result.get("capabilities"), dict) or "tools" not in result["capabilities"]:
            self._invalidate()
            raise ToolError("mcp_unsupported_protocol", "MCP 服务未提供支持的协议版本和工具能力")
        self._protocol = result["protocolVersion"]
        self._request("notifications/initialized", notification=True)
        self._initialized = True

    def register_tools(self, catalog: ToolCatalog):
        with self._lock:
            if catalog not in self._catalogs:
                self._catalogs.append(catalog)
            try:
                self._initialize()
                raw_tools, cursors, cursor = [], set(), None
                for _ in range(20):
                    result = self._request("tools/list", {"cursor": cursor} if cursor else {})
                    page = result.get("tools")
                    if not isinstance(page, list) or len(page) > 200 or len(raw_tools) + len(page) > 200:
                        raise ToolError("mcp_protocol_error", "MCP 工具目录无效或超过数量限制")
                    raw_tools.extend(page)
                    cursor = result.get("nextCursor")
                    if cursor is None:
                        break
                    if not isinstance(cursor, str) or not cursor or len(cursor) > 2048 or cursor in cursors:
                        raise ToolError("mcp_protocol_error", "MCP 工具分页游标无效或重复")
                    cursors.add(cursor)
                else:
                    raise ToolError("mcp_protocol_error", "MCP 工具分页超过限制")
                entries, definitions, names = [], {}, set()
                for item in raw_tools:
                    if not isinstance(item, dict) or not isinstance(item.get("name"), str) or item["name"] in names:
                        raise ToolError("mcp_protocol_error", "MCP 工具目录含无效或重复名称")
                    name = item["name"]
                    names.add(name)
                    if name not in self.config.allowed_tools:
                        continue
                    output = item.get("outputSchema")
                    tool = ToolDefinition(namespace=self.config.name, name=name,
                                          description=item.get("description") or name,
                                          input_schema=item.get("inputSchema"),
                                          output_schema=output, output_key="structuredContent" if output is not None else None,
                                          effect=self.config.allowed_tools[name],
                                          source=f"mcp:official:{self.config.official_service}" if self.config.official_service else f"mcp:fixture:{self.config.name}")
                    definitions[name] = tool
                    entries.append((tool, lambda args, name=name, version=tool.version:
                                    self.call(name, args, expected_version=version, authorized=True)))
                self._definitions = definitions
                official = OFFICIAL_SERVICES.get(self.config.official_service)
                metadata = ({"display_name": official.display_name, "aliases": list(official.aliases)}
                            if official is not None else None)
                catalog.replace_namespace(self.config.name, entries, metadata=metadata)
                return [tool.tool_id for tool, _ in entries]
            except (ToolError, TypeError, ValueError):
                self._invalidate()
                raise

    def call(self, name, arguments, *, expected_version=None, authorized=False):
        with self._lock:
            definition = self._definitions.get(name)
            if not self._initialized or definition is None:
                raise ToolError("tool_unavailable", "MCP 工具未发现或已停用，请先刷新能力目录")
            ToolCatalog._validate(definition, arguments, expected_version)
            if definition.effect != "read" and authorized is not True:
                raise ToolError("authorization_required", "此 MCP 操作需要有效执行授权")
            outcome = "failed" if definition.effect == "read" else "unknown"
            result = self._request("tools/call", {"name": name, "arguments": deepcopy(arguments)}, outcome=outcome)
            if result.get("isError", False) is not False:
                raise ToolError("mcp_tool_error", "MCP 工具报告执行错误", outcome=outcome)
            if not isinstance(result.get("content"), list):
                raise ToolError("mcp_protocol_error", "MCP 工具返回缺少内容列表", outcome=outcome)
            return result
