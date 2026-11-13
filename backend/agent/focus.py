"""Resolve explicit contract intent once, independently of catalog availability."""
from dataclasses import dataclass
import re


def current_observations(snapshot):
    revision = snapshot.get("run", {}).get("revision", snapshot.get("revision"))
    run_id = snapshot.get("run", {}).get("agent_run_id", snapshot.get("current_run_id"))
    items = [item for item in snapshot.get("observations", [])
             if (revision is None or item.get("revision", revision) == revision)
             and (run_id is None or item.get("agent_run_id", run_id) == run_id)]
    # Store snapshots carry durable cursors. Legacy snapshots already preserve
    # insertion order; wall-clock ties or corrections must not reverse intent.
    if all(type(item.get("cursor")) is int for item in items):
        items.sort(key=lambda item: item["cursor"])
    return items


@dataclass(frozen=True)
class ToolFocus:
    tool_id: str
    observation: dict

    @property
    def schema_request(self):
        return self.observation if self.observation.get("kind") == "tool_error" else None


def named_discovery_tool(payload):
    """Recognize a single declared identity, never infer one from descriptions."""
    words = set(re.findall(r"[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*", payload.get("query", "")))
    scope = payload.get("namespace")
    matches = set()
    for tool in payload.get("tools", []):
        identity = tool.get("tool_id", "")
        if "." not in identity:
            continue
        namespace, name = identity.split(".", 1)
        if identity in words or (name in words and (namespace in words or scope == namespace)):
            matches.add(identity)
    return next(iter(matches)) if len(matches) == 1 else None


def resolve_tool_focus(snapshot):
    """Newest explicit selection wins; broad discovery grants no selection."""
    focus = None
    for item in current_observations(snapshot):
        payload = item.get("payload", {})
        identity = None
        if item.get("kind") == "tool_error" and payload.get("code") == "discover_tool_schema_first":
            identity = payload.get("tool_id")
        elif item.get("kind") == "tools_discovered":
            identity = named_discovery_tool(payload)
        if isinstance(identity, str) and identity:
            focus = ToolFocus(identity, item)
    if focus is not None and any(call.get("created_at", 0) > focus.observation.get("created_at", 0)
                                 for call in snapshot.get("tool_calls", [])):
        return None
    return focus
