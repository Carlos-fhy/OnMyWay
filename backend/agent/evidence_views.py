"""Bounded display projections of MCP JSON text; source data stays untrusted."""
import hashlib
import json
import math


_MAX_BYTES = 65536
_MAX_DEPTH = 12
_MAX_BLOCKS = 8


def _check_depth(text):
    depth, quoted, escaped = 0, False, False
    for char in text:
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in "[{":
            depth += 1
            if depth > _MAX_DEPTH:
                raise ValueError("JSON nesting exceeds the display limit")
        elif char in "]}":
            depth -= 1


def _unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("Duplicate JSON keys are ambiguous")
        value[key] = item
    return value


def _reject_constant(_value):
    raise ValueError("Nonfinite JSON values are not supported")


def _finite_float(value):
    number = float(value)
    if not math.isfinite(number):
        raise ValueError("Nonfinite JSON values are not supported")
    return number


def _check_unicode(value):
    if isinstance(value, str):
        value.encode("utf-8")
    elif isinstance(value, dict):
        for key, item in value.items():
            key.encode("utf-8")
            _check_unicode(item)
    elif isinstance(value, list):
        for item in value:
            _check_unicode(item)


def mcp_json_text_views(result, *, project):
    """Map original MCP text paths to marked views of strictly decoded JSON.

    ``project`` bounds the decoded structure and labels generated paths with
    ``decoded_path``. Neither this helper nor the caller may replace source
    text, promote its contents to instructions, or reinterpret its authority.
    """
    if not isinstance(result, dict) or not isinstance(result.get("data"), dict):
        return {}
    blocks = result["data"].get("content")
    if not isinstance(blocks, list):
        return {}
    views, remaining = {}, _MAX_BYTES
    for index, block in enumerate(blocks[:_MAX_BLOCKS]):
        if not isinstance(block, dict) or block.get("type") != "text" or not isinstance(block.get("text"), str):
            continue
        text = block["text"]
        if len(text) > remaining or not text.lstrip().startswith(("{", "[")):
            continue
        try:
            encoded = text.encode("utf-8")
            if len(encoded) > remaining:
                continue
            remaining -= len(encoded)
            _check_depth(text)
            decoded = json.loads(text, object_pairs_hook=_unique_object,
                                 parse_constant=_reject_constant, parse_float=_finite_float)
            if not isinstance(decoded, (dict, list)):
                continue
            _check_unicode(decoded)
        except (ValueError, RecursionError, UnicodeError):
            continue
        path = f"/data/content/{index}/text"
        views[path] = {"representation": "decoded_json", "source_path": path,
                       "source_sha256": hashlib.sha256(encoded).hexdigest(),
                       "decoded_view": project(decoded)}
    return views
