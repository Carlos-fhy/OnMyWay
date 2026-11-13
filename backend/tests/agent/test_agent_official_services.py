"""Only audited first-party endpoints may receive remote MCP traffic."""
import json
import logging

import httpx
import pytest

from backend.agent.mcp import McpClient, McpServerConfig
from backend.agent.service import load_mcp_config
from backend.agent.tools import ToolCatalog, ToolError


def write_config(tmp_path, **changes):
    server = {"name": "amap", "official_service": "amap", "url": "https://mcp.amap.com/mcp",
              "allowed_tools": {"search": "read"}, "query_env": {"key": "SYNTHETIC_MAP_KEY"}}
    server.update(changes)
    path = tmp_path / "mcp.json"
    path.write_text(json.dumps({"servers": [server]}), encoding="utf-8")
    return path


def test_official_map_key_resolved_without_storing_it_in_endpoint(tmp_path, monkeypatch, caplog):
    secret = "synthetic-key+with/special?bytes"
    monkeypatch.setenv("SYNTHETIC_MAP_KEY", secret)
    config = load_mcp_config(write_config(tmp_path))[0]
    assert config.url == "https://mcp.amap.com/mcp"
    assert config.official_service == "amap"
    assert secret not in repr(config)
    requests = []
    def handle(request):
        requests.append(request)
        assert request.url.params["key"] == secret
        message = json.loads(request.content)
        if message["method"] == "notifications/initialized":
            return httpx.Response(202)
        result = ({"protocolVersion": "2025-06-18", "capabilities": {"tools": {}}}
                  if message["method"] == "initialize" else {"tools": []})
        return httpx.Response(200, json={"jsonrpc": "2.0", "id": message["id"], "result": result})
    with McpClient(config) as client:
        client._runner.run(client._http.aclose())
        client._http = httpx.AsyncClient(transport=httpx.MockTransport(handle))
        with caplog.at_level(logging.DEBUG):
            assert client.register_tools(ToolCatalog()) == []
    assert len(requests) == 3
    assert secret not in caplog.text
    assert "synthetic-key%2Bwith%2Fspecial%3Fbytes" not in caplog.text


@pytest.mark.parametrize("changes", [
    {"url": "https://mcp.amap.com.evil.example/mcp"},
    {"url": "https://mcp.amap.com/other"},
    {"url": "https://mcp.amap.com:444/mcp"},
    {"url": "https://mcp.amap.com/mcp?key=literal-secret"},
    {"url": "http://mcp.amap.com/mcp"},
    {"url": "https://mcp.amap.com/mcp", "official_service": "unverified"},
    {"url": "http://127.0.0.1:9000/mcp", "official_service": "amap"},
    {"query_params": {"key": "literal-secret"}},
    {"query_env": {"destination": "SYNTHETIC_MAP_KEY"}},
])
def test_config_rejects_unofficial_or_modified_endpoint_before_network(tmp_path, monkeypatch, changes):
    monkeypatch.setenv("SYNTHETIC_MAP_KEY", "synthetic")
    with pytest.raises(ValueError) as caught:
        load_mcp_config(write_config(tmp_path, **changes))
    assert "literal-secret" not in str(caught.value)


def test_direct_config_cannot_bypass_official_service_registry():
    with pytest.raises(ToolError):
        McpServerConfig("unverified", "https://arbitrary.example/mcp", {})


def test_anonymous_exa_can_select_only_audited_nonsecret_query_options(tmp_path):
    config = load_mcp_config(write_config(tmp_path, name="exa", official_service="exa",
                            url="https://mcp.exa.ai/mcp", query_env={},
                            query_params={"tools": "web_search_exa,web_fetch_exa"}))[0]
    assert config.query_params == {"tools": "web_search_exa,web_fetch_exa"}
    with pytest.raises(ValueError):
        load_mcp_config(write_config(tmp_path, name="exa", official_service="exa",
                        url="https://mcp.exa.ai/mcp", query_env={}, query_params={"exaApiKey": "literal-secret"}))


def test_loopback_fixture_is_explicitly_not_official():
    config = McpServerConfig("test", "http://127.0.0.1:9300/mcp", {})
    assert config.official_service is None


@pytest.mark.parametrize("service", ["amap", "exa", None])
def test_namespace_labels_come_from_reviewed_registry_not_remote_tool_claims(service):
    endpoints = {"amap": "https://mcp.amap.com/mcp", "exa": "https://mcp.exa.ai/mcp",
                 None: "http://127.0.0.1:9300/mcp"}
    config = McpServerConfig("configured_name", endpoints[service], {"search": "read"}, official_service=service,
                             query_secrets={"key": "synthetic"} if service == "amap" else {})
    tool = {"name": "search", "description": "Remote tool data", "inputSchema": {"type": "object"},
            "display_name": "Untrusted official identity", "aliases": ["Untrusted alias"], "source": "mcp:official:amap"}

    def handle(request):
        message = json.loads(request.content)
        if message["method"] == "notifications/initialized":
            return httpx.Response(202)
        result = ({"protocolVersion": "2025-06-18", "capabilities": {"tools": {}},
                   "serverInfo": {"name": "Untrusted official identity"}}
                  if message["method"] == "initialize" else {"tools": [tool, {**tool, "name": "not_allowed"}]})
        return httpx.Response(200, json={"jsonrpc": "2.0", "id": message["id"], "result": result})

    catalog = ToolCatalog()
    with McpClient(config) as client:
        client._runner.run(client._http.aclose())
        client._http = httpx.AsyncClient(transport=httpx.MockTransport(handle))
        client.register_tools(catalog)
        item = catalog.namespace_index()["items"][0]
    assert item["namespace"] == "configured_name" and item["tool_count"] == 1
    assert "Untrusted" not in json.dumps(item)
    if service == "amap":
        assert item["display_name"] == "高德地图" and "高德" in item["aliases"]
    elif service == "exa":
        assert "Exa" in item["display_name"]
    else:
        assert "display_name" not in item and "aliases" not in item
