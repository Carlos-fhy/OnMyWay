"""Recoverable JSON selection errors with bounded, source-derived navigation."""
from collections import deque
from itertools import islice
import json

from backend.agent.tools import ToolError


def _bytes(value):
    return len(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))


def _shape(value, path):
    kind = "object" if isinstance(value, dict) else "array" if isinstance(value, list) else "scalar"
    return {"path": path, "type": kind, "bytes": _bytes(value)}


def _candidate_paths(value, segment):
    pending = deque([(value, "", 0)])
    candidates, inspected, truncated = [], 0, False
    while pending:
        node, path, depth = pending.popleft()
        children = node.items() if isinstance(node, dict) else enumerate(node) if isinstance(node, list) else ()
        for key, child in children:
            if inspected >= 256:
                return candidates, True
            inspected += 1
            child_path = path + "/" + str(key).replace("~", "~0").replace("/", "~1")
            if len(child_path) > 512:
                truncated = True
                continue
            if isinstance(node, dict) and key == segment:
                if len(candidates) < 8:
                    candidates.append(child_path)
                else:
                    truncated = True
            if isinstance(child, (dict, list)) and child:
                if depth + 1 >= 16:
                    truncated = True
                else:
                    pending.append((child, child_path, depth + 1))
    return candidates, truncated


class SelectionError(ToolError):
    def __init__(self, code, message, paths, nodes, *, original=None, failed_path=None, failed_segment=None):
        super().__init__(code, message)
        self.details = {"requested_paths_omitted": len(paths), "selections": []}
        if failed_path is not None:
            candidates, truncated = _candidate_paths(original, failed_segment)
            self.details.update(failed_path=failed_path, failed_segment=failed_segment,
                                candidate_paths=[], candidates_truncated=truncated)
            # Never turn an over-budget pointer into a different, apparently valid one.
            for field in sorted(("failed_path", "failed_segment"), key=lambda name: _bytes(self.details[name]), reverse=True):
                if _bytes(self.details) > 1800:
                    self.details[field + "_omitted_bytes"] = len(self.details[field].encode("utf-8"))
                    self.details[field] = None
            for candidate in candidates:
                self.details["candidate_paths"].append(candidate)
                if _bytes(self.details) > 1800:
                    self.details["candidate_paths"].pop()
                    self.details["candidates_truncated"] = True
        del self.details["requested_paths_omitted"]
        self.details["requested_paths"] = paths
        if _bytes(self.details) > 1800:
            del self.details["requested_paths"]
            self.details["requested_paths_omitted"] = len(paths)
        # Paths and keys are untrusted source data; no source values enter errors.
        for path, node in nodes:
            hint = {**_shape(node, path), "children": [], "omitted_children": 0}
            children = node.items() if isinstance(node, dict) else enumerate(node) if isinstance(node, list) else ()
            hint["omitted_children"] = len(node) if isinstance(node, (dict, list)) else 0
            self.details["selections"].append(hint)
            if _bytes(self.details) > 1800:
                self.details["selections"].pop()
                continue
            for key, child in islice(children, 8):
                child_path = path + "/" + str(key).replace("~", "~0").replace("/", "~1")
                hint["children"].append(_shape(child, child_path))
                hint["omitted_children"] -= 1
                if _bytes(self.details) > 1600:
                    hint["children"].pop()
                    hint["omitted_children"] += 1
            if _bytes(self.details) > 1800:
                self.details["selections"].pop()
