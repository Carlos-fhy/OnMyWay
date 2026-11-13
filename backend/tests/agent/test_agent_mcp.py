"""MCP transport tests use real HTTP servers and synthetic public-query data."""
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import socket
from threading import Thread
import time

import pytest


def api():
    assert importlib.util.find_spec("backend.agent.mcp") is not None, "MCP connector is not implemented"
    from backend.agent.mcp import McpClient, McpServerConfig
    from backend.agent.tools import ToolCatalog, ToolError
    return McpClient, McpServerConfig, ToolCatalog, ToolError


def query_tool(name="search", description="查询成都景点位置与开放时间"):
    return {"name": name, "description": description,
            "inputSchema": {"type": "object", "properties": {"city": {"type": "string"}},
                            "required": ["city"], "additionalProperties": False},
            "annotations": {"readOnlyHint": True, "idempotentHint": True}}


@contextmanager
def server(*, label="travel", tools=None):
    state = {"label": label, "tools": tools or [query_tool()], "seen": [], "calls": 0,
             "mode": "ok", "session": "session-1", "result": {"name": "成都博物馆", "open": True},
             "protocol": "2025-06-18", "content_type": "application/json"}
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def do_POST(self):
            raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            body = json.loads(raw)
            state["seen"].append((body, dict(self.headers)))
            method = body["method"]
            headers = {}
            status = 200
            if method == "initialize":
                result = {"protocolVersion": state["protocol"], "capabilities": {"tools": {"listChanged": False}},
                          "serverInfo": {"name": label, "version": "test"}}
                headers["Mcp-Session-Id"] = state["session"]
            elif method == "notifications/initialized":
                self.send_response(202)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            elif self.headers.get("Mcp-Session-Id") != state["session"]:
                self.send_response(404)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            elif method == "tools/list":
                if state["mode"] == "pagination":
                    cursor = body.get("params", {}).get("cursor")
                    result = {"tools": state["tools"][:1], "nextCursor": "page-2"} if not cursor else {"tools": state["tools"][1:]}
                elif state["mode"] == "cursor_loop":
                    result = {"tools": [], "nextCursor": "same"}
                else:
                    result = {"tools": state["tools"]}
            elif method == "tools/call":
                state["calls"] += 1
                if state["mode"] == "timeout":
                    time.sleep(state.get("delay_seconds", 0.15))
                result = {"content": [{"type": "text", "text": json.dumps(state["result"], ensure_ascii=False)}],
                          "structuredContent": state["result"], "isError": state["mode"] == "tool_error"}
            else:
                raise AssertionError(method)
            response = {"jsonrpc": "2.0", "id": body["id"], "result": result}
            if state["mode"] == "rpc_error" and method == "tools/call":
                response = {"jsonrpc": "2.0", "id": body["id"], "error": {"code": -32603, "message": "private-secret"}}
            if state["mode"] == "mismatched_id" and method == "tools/call":
                response["id"] = "wrong"
            data = json.dumps(response, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", state["content_type"])
            self.send_header("Content-Length", str(len(data)))
            for name, value in headers.items():
                self.send_header(name, value)
            self.end_headers()
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    httpd.daemon_threads = True
    thread = Thread(target=lambda: httpd.serve_forever(poll_interval=0.02), daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{httpd.server_port}/mcp", state
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=2)


def test_real_http_handshake_and_two_complementary_query_sources():
    McpClient, Config, Catalog, _ = api()
    catalog = Catalog()
    with server(label="places") as (places_url, places), server(label="weather", tools=[query_tool(description="查询城市天气预报")]) as (weather_url, weather):
        weather["result"] = {"forecast": "rain", "city": "成都"}
        with McpClient(Config("places", places_url, {"search": "read"})) as pc, McpClient(Config("weather", weather_url, {"search": "read"})) as wc:
            assert pc.register_tools(catalog) == ["places.search"]
            assert wc.register_tools(catalog) == ["weather.search"]
            assert catalog.call("places.search", {"city": "成都"})["data"]["structuredContent"]["open"] is True
            assert catalog.call("weather.search", {"city": "成都"})["data"]["structuredContent"]["forecast"] == "rain"
        methods = [body["method"] for body, _ in places["seen"]]
        assert methods == ["initialize", "notifications/initialized", "tools/list", "tools/call"]
        for body, headers in places["seen"][1:]:
            assert headers["Mcp-Session-Id"] == "session-1"
            assert headers["MCP-Protocol-Version"] == "2025-06-18"
        assert places["calls"] == weather["calls"] == 1


def test_server_annotations_do_not_override_local_effect_or_allowlist():
    McpClient, Config, Catalog, ToolError = api()
    catalog = Catalog()
    with server(tools=[query_tool(), query_tool("delete")]) as (url, state), McpClient(Config("places", url, {"search": "write"})) as client:
        client.register_tools(catalog)
        assert catalog.describe("places.search")["effect"] == "write"
        with pytest.raises(ToolError):
            catalog.describe("places.delete")
        with pytest.raises(ToolError) as caught:
            catalog.call("places.search", {"city": "成都"})
        assert caught.value.code == "authorization_required"
        assert state["calls"] == 0


def test_tools_list_pagination_and_removed_tool_revocation():
    McpClient, Config, Catalog, ToolError = api()
    catalog = Catalog()
    with server(tools=[query_tool(), query_tool("weather")]) as (url, state), McpClient(Config("places", url, {"search": "read", "weather": "read"})) as client:
        state["mode"] = "pagination"
        assert client.register_tools(catalog) == ["places.search", "places.weather"]
        state["mode"] = "ok"
        state["tools"] = [query_tool()]
        client.register_tools(catalog)
        with pytest.raises(ToolError) as caught:
            catalog.describe("places.weather")
        assert caught.value.code == "tool_unavailable"


def test_schema_refresh_fences_prepared_call():
    McpClient, Config, Catalog, ToolError = api()
    catalog = Catalog()
    with server() as (url, state), McpClient(Config("places", url, {"search": "read"})) as client:
        client.register_tools(catalog)
        version = catalog.describe("places.search")["version"]
        state["tools"][0]["inputSchema"]["required"].append("date")
        client.register_tools(catalog)
        with pytest.raises(ToolError) as caught:
            catalog.call("places.search", {"city": "成都"}, expected_version=version)
        assert caught.value.code == "tool_changed"
        assert state["calls"] == 0


@pytest.mark.parametrize("mode,code", [("rpc_error", "mcp_rpc_error"), ("tool_error", "mcp_tool_error"), ("mismatched_id", "mcp_protocol_error")])
def test_mcp_error_and_bad_response_are_safe_and_not_retried(mode, code):
    McpClient, Config, Catalog, ToolError = api()
    catalog = Catalog()
    with server() as (url, state), McpClient(Config("places", url, {"search": "write"})) as client:
        client.register_tools(catalog)
        state["mode"] = mode
        with pytest.raises(ToolError) as caught:
            catalog.call("places.search", {"city": "成都"}, authorized=True)
        assert caught.value.code == code
        assert caught.value.outcome == "unknown"
        assert "private-secret" not in str(caught.value)
        assert state["calls"] == 1


def test_lost_session_requires_explicit_rediscovery_without_replaying_call():
    McpClient, Config, Catalog, ToolError = api()
    catalog = Catalog()
    with server() as (url, state), McpClient(Config("places", url, {"search": "read"})) as client:
        client.register_tools(catalog)
        state["session"] = "session-2"
        with pytest.raises(ToolError) as caught:
            catalog.call("places.search", {"city": "成都"})
        assert caught.value.code == "mcp_session_expired"
        assert state["calls"] == 0
        client.register_tools(catalog)
        assert catalog.call("places.search", {"city": "成都"})["data"]["structuredContent"]["open"] is True
        assert state["calls"] == 1


def test_response_limit_and_timeout_preserve_unknown_write_outcome():
    McpClient, Config, Catalog, ToolError = api()
    with server() as (url, state):
        catalog = Catalog()
        with McpClient(Config("places", url, {"search": "write"}, timeout_seconds=0.04, max_response_bytes=2048)) as client:
            client.register_tools(catalog)
            state["result"] = {"body": "x" * 4000}
            with pytest.raises(ToolError) as caught:
                catalog.call("places.search", {"city": "成都"}, authorized=True)
            assert caught.value.code == "mcp_result_too_large"
            assert caught.value.outcome == "unknown"
            state["result"] = {}
            state["mode"] = "timeout"
            with pytest.raises(ToolError) as caught:
                catalog.call("places.search", {"city": "成都"}, authorized=True)
            assert caught.value.code == "mcp_transport_error"
            assert caught.value.outcome == "unknown"
            assert state["calls"] == 2


@pytest.mark.parametrize("mode", ["cursor_loop", "sse", "unsupported_version"])
def test_unsupported_protocol_or_unbounded_list_clears_namespace(mode):
    McpClient, Config, Catalog, ToolError = api()
    catalog = Catalog()
    with server() as (url, state):
        if mode == "sse":
            state["content_type"] = "text/event-stream"
        elif mode == "unsupported_version":
            state["protocol"] = "1900-01-01"
        else:
            state["mode"] = mode
        with McpClient(Config("places", url, {"search": "read"})) as client:
            with pytest.raises(ToolError):
                client.register_tools(catalog)
            assert catalog.discover("") == []


def test_config_rejects_credentials_in_url_and_never_repr_headers():
    _, Config, _, ToolError = api()
    with pytest.raises(ToolError):
        Config("bad", "https://user:password@example.test/mcp", {"search": "read"})
    config = Config("valid", "https://mcp.exa.ai/mcp", {"search": "read"}, official_service="exa", headers={"Authorization": "Bearer private-secret"})
    assert "private-secret" not in repr(config)


def test_structured_output_schema_keeps_internal_reference_scope():
    McpClient, Config, Catalog, _ = api()
    catalog = Catalog()
    tool = query_tool()
    tool["outputSchema"] = {
        "type": "object", "properties": {"name": {"$ref": "#/$defs/name"}},
        "required": ["name"], "$defs": {"name": {"type": "string"}},
    }
    with server(tools=[tool]) as (url, _state), McpClient(Config("places", url, {"search": "read"})) as client:
        client.register_tools(catalog)
        result = catalog.call("places.search", {"city": "成都"})
        assert result["data"]["structuredContent"]["name"] == "成都博物馆"


@contextmanager
def sse_server(mode="ok"):
    state = {"calls": 0}
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def handle(self):
            try:
                super().handle()
            except (ConnectionResetError, ConnectionAbortedError):
                pass

        def log_message(self, *_):
            pass

        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            method = request["method"]
            if method == "notifications/initialized":
                self.send_response(202)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if method == "initialize":
                result = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}}}
            elif method == "tools/list":
                result = {"tools": [query_tool()]}
            else:
                state["calls"] += 1
                result = {"content": [{"type": "text", "text": "成都公开测试结果"}], "isError": False}
            message = {"jsonrpc": "2.0", "id": request["id"], "result": result}
            if method == "tools/call" and mode == "wrong_id":
                message["id"] += 1
            if method == "tools/call" and mode == "server_request":
                message = {"jsonrpc": "2.0", "id": request["id"], "method": "sampling/createMessage"}
            encoded = json.dumps(message, ensure_ascii=False).encode("utf-8")
            prefix = b': keepalive\r\nevent: message\r\ndata: {"jsonrpc":"2.0","method":"notifications/progress","params":{}}\r\n\r\n'
            payload = prefix + b"event: message\r\ndata: " + encoded + b"\r\n\r\n"
            if method == "tools/call" and mode in {"missing", "trickle", "late_silent"}:
                payload = prefix
            if method == "tools/call" and mode == "oversize":
                payload = b":" + b"x" * 5000 + b"\n\n"
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            try:
                for byte in payload:
                    self.wfile.write(b"1\r\n" + bytes([byte]) + b"\r\n")
                self.wfile.flush()
                if method == "tools/call" and mode == "ok":
                    # Do not terminate the response: the client must accept a complete
                    # matching event without waiting for EOF or another event.
                    time.sleep(0.2)
                if method == "tools/call" and mode == "trickle":
                    for _ in range(30):
                        self.wfile.write(b"3\r\n:\n\n\r\n")
                        self.wfile.flush()
                        time.sleep(0.01)
                if method == "tools/call" and mode == "late_silent":
                    time.sleep(0.32)
                    self.wfile.write(b"3\r\n:\n\n\r\n")
                    self.wfile.flush()
                    time.sleep(0.8)
                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()
            except (OSError, socket.error):
                pass
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    httpd.daemon_threads = True
    thread = Thread(target=lambda: httpd.serve_forever(poll_interval=0.02), daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{httpd.server_port}/mcp", state
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=2)


def test_post_sse_stream_handles_chunk_boundaries_and_stops_at_matching_result():
    Client, Config, Catalog, _ = api()
    with sse_server() as (url, state), Client(Config("stream", url, {"search": "read"}, timeout_seconds=0.1)) as client:
        catalog = Catalog()
        client.register_tools(catalog)
        result = catalog.call("stream.search", {"city": "成都"})
        assert result["data"]["content"][0]["text"] == "成都公开测试结果"
        assert state["calls"] == 1


@pytest.mark.parametrize("mode,code", [("wrong_id", "mcp_protocol_error"), ("server_request", "mcp_protocol_error"),
                                      ("missing", "mcp_protocol_error"), ("oversize", "mcp_result_too_large")])
def test_invalid_post_sse_write_response_is_unknown_and_never_replayed(mode, code):
    Client, Config, Catalog, ToolError = api()
    with sse_server(mode) as (url, state), Client(Config("stream", url, {"search": "write"}, max_response_bytes=2048)) as client:
        catalog = Catalog()
        client.register_tools(catalog)
        with pytest.raises(ToolError) as caught:
            catalog.call("stream.search", {"city": "成都"}, authorized=True)
        assert caught.value.code == code
        assert caught.value.outcome == "unknown"
        assert state["calls"] == 1


def test_post_sse_trickle_cannot_extend_total_request_time_or_repeat_write():
    Client, Config, Catalog, ToolError = api()
    with sse_server("trickle") as (url, state), Client(Config("stream", url, {"search": "write"}, timeout_seconds=0.1)) as client:
        catalog = Catalog()
        client.register_tools(catalog)
        started = time.monotonic()
        with pytest.raises(ToolError) as caught:
            catalog.call("stream.search", {"city": "成都"}, authorized=True)
        assert caught.value.code == "mcp_transport_error"
        assert caught.value.outcome == "unknown"
        assert time.monotonic() - started < 0.3
        assert state["calls"] == 1


def test_post_sse_last_chunk_before_deadline_does_not_restart_read_timeout():
    Client, Config, Catalog, ToolError = api()
    with sse_server("late_silent") as (url, state), Client(Config("stream", url, {"search": "write"}, timeout_seconds=0.5)) as client:
        catalog = Catalog()
        client.register_tools(catalog)
        started = time.monotonic()
        with pytest.raises(ToolError) as caught:
            catalog.call("stream.search", {"city": "成都"}, authorized=True)
        elapsed = time.monotonic() - started
        assert elapsed < 0.65, f"total deadline exceeded: {elapsed:.3f}s"
        assert caught.value.code == "mcp_transport_error"
        assert caught.value.outcome == "unknown"
        assert state["calls"] == 1


def test_client_can_initialize_call_and_close_on_different_threads():
    Client, Config, Catalog, _ = api()
    catalog = Catalog()
    with server() as (url, state):
        client = Client(Config("threaded", url, {"search": "read"}))
        with ThreadPoolExecutor(max_workers=1) as first_worker:
            assert first_worker.submit(client.register_tools, catalog).result(timeout=3) == ["threaded.search"]
        with ThreadPoolExecutor(max_workers=1) as second_worker:
            result = second_worker.submit(catalog.call, "threaded.search", {"city": "成都"}).result(timeout=3)
        assert result["data"]["structuredContent"]["open"] is True
        event_loop = client._runner.get_loop()
        client.close()
        assert event_loop.is_closed()
        assert client._http.is_closed
        client.close()
        assert state["calls"] == 1
