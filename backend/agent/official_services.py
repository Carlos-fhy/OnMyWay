"""Reviewed first-party MCP endpoints; updating this list requires source evidence."""
from dataclasses import dataclass
from types import MappingProxyType
from urllib.parse import urlsplit


@dataclass(frozen=True)
class OfficialService:
    host: str
    path: str
    source_url: str
    reviewed_on: str
    secret_parameters: frozenset[str] = frozenset()
    required_secrets: frozenset[str] = frozenset()
    public_parameters: frozenset[str] = frozenset()
    display_name: str = ""
    aliases: tuple[str, ...] = ()


OFFICIAL_SERVICES = MappingProxyType({
    "amap": OfficialService("mcp.amap.com", "/mcp",
        "https://lbs.amap.com/api/mcp-server/gettingstarted", "2026-11-08",
        secret_parameters=frozenset({"key"}), required_secrets=frozenset({"key"}),
        display_name="高德地图", aliases=("高德", "Amap")),
    "exa": OfficialService("mcp.exa.ai", "/mcp",
        "https://docs.exa.ai/reference/exa-mcp", "2026-11-08",
        public_parameters=frozenset({"tools"}), display_name="Exa 搜索", aliases=("Exa",)),
})


def validate_endpoint(url, official_service, query_params, query_secrets):
    """Local fixtures have no official identity; all remote traffic is reviewed."""
    parsed = urlsplit(url)
    if parsed.query or parsed.fragment or parsed.username or parsed.password:
        raise ValueError("MCP endpoint must not contain embedded credentials or query parameters")
    if parsed.hostname in {"localhost", "127.0.0.1", "::1"}:
        if official_service is not None or query_params or query_secrets:
            raise ValueError("Loopback fixture cannot claim official provenance or receive query credentials")
        return
    if not isinstance(official_service, str) or official_service not in OFFICIAL_SERVICES:
        raise ValueError("Remote MCP service has not been reviewed")
    service = OFFICIAL_SERVICES[official_service]
    if parsed.scheme != "https" or parsed.hostname != service.host or parsed.path != service.path or parsed.port not in (None, 443):
        raise ValueError("MCP endpoint does not match its official source")
    if not set(query_params) <= service.public_parameters or not set(query_secrets) <= service.secret_parameters:
        raise ValueError("MCP query parameter has not been reviewed")
    if not service.required_secrets <= set(query_secrets):
        raise ValueError("MCP service credential is missing")
