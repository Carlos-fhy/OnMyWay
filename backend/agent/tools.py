"""Discoverable tool contracts shared by native adapters and configured MCP servers.

The catalog validates contracts. The task executor owns authorization, durable
dispatch and recovery; an advertised MCP annotation is never an authorization.
"""
from collections import Counter
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import re
from threading import RLock
from typing import Callable, Literal

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError, ValidationError


class ToolError(Exception):
    def __init__(self, code: str, message: str, *, outcome: str = "failed"):
        super().__init__(message)
        self.code = code
        self.outcome = outcome


def _json(value, max_bytes, code="invalid_arguments"):
    try:
        encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
    except (ValueError, TypeError, RecursionError):
        raise ToolError(code, "工具数据必须为有限的 JSON 值") from None
    if len(encoded.encode("utf-8")) > max_bytes:
        raise ToolError(code, "工具数据超过大小限制")
    return encoded


def _schema(schema):
    if not isinstance(schema, dict) or schema.get("type") != "object":
        raise ToolError("invalid_schema", "工具参数和结构化结果 Schema 必须描述对象")
    _json(schema, 32768, "invalid_schema")
    def inspect(value):
        if isinstance(value, dict):
            for key, child in value.items():
                if key in ("$ref", "$dynamicRef") and (not isinstance(child, str) or not child.startswith("#")):
                    raise ToolError("invalid_schema", "工具 Schema 只支持文档内部引用")
                inspect(child)
        elif isinstance(value, list):
            for child in value:
                inspect(child)
    inspect(schema)
    try:
        Draft202012Validator.check_schema(schema)
    except (SchemaError, RecursionError):
        raise ToolError("invalid_schema", "工具 Schema 无效") from None


@dataclass(frozen=True)
class ToolDefinition:
    namespace: str
    name: str
    description: str
    input_schema: dict
    effect: Literal["read", "write", "device"] = "read"
    executor: str = "server"
    source: str | None = None
    output_schema: dict | None = None
    output_key: str | None = None

    def __post_init__(self):
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", self.namespace) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,128}", self.name):
            raise ToolError("invalid_tool", "工具命名空间或名称无效")
        if not isinstance(self.description, str) or not self.description.strip() or len(self.description) > 4000:
            raise ToolError("invalid_tool", "工具说明必须有内容且不超过 4000 字符")
        if self.effect not in ("read", "write", "device") or self.executor not in ("server", "android"):
            raise ToolError("invalid_tool", "工具执行策略无效")
        _schema(self.input_schema)
        if self.output_schema is not None:
            _schema(self.output_schema)
        if self.output_key is not None and (not isinstance(self.output_key, str) or not self.output_key or len(self.output_key) > 128):
            raise ToolError("invalid_tool", "结构化工具结果字段无效")
        object.__setattr__(self, "input_schema", deepcopy(self.input_schema))
        object.__setattr__(self, "output_schema", deepcopy(self.output_schema))
        object.__setattr__(self, "source", self.source or f"builtin:{self.namespace}")

    @property
    def tool_id(self):
        return f"{self.namespace}.{self.name}"

    @property
    def version(self):
        contract = {"description": self.description, "input_schema": self.input_schema,
                    "output_schema": self.output_schema, "effect": self.effect,
                    "executor": self.executor, "source": self.source, "output_key": self.output_key}
        return hashlib.sha256(_json(contract, 100000, "invalid_schema").encode("utf-8")).hexdigest()

    def descriptor(self, *, schema=True):
        result = {"tool_id": self.tool_id, "description": self.description, "version": self.version,
                  "effect": self.effect, "executor": self.executor, "source": self.source}
        if schema:
            result.update(input_schema=deepcopy(self.input_schema), output_schema=deepcopy(self.output_schema), output_key=self.output_key)
        return result


def _terms(text):
    # Search the words in identifiers as well as prose, without language- or
    # domain-specific aliases. Keep full identity matching in _rank.
    segmented = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1 \2", text)
    segmented = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", segmented)
    words = re.findall(r"[a-z0-9]+", segmented.lower())
    for run in re.findall(r"[\u3400-\u9fff]+", text):
        words += [run] if len(run) == 1 else [run[i:i + 2] for i in range(len(run) - 1)]
    return Counter(words)


def _rank(definitions, query):
    wanted = _terms(query)
    identifiers = set(re.findall(r"[a-z0-9_-]+(?:\.[a-z0-9_-]+)*", query.lower()))
    namespaces = {identifier.split(".", 1)[0] for identifier in identifiers}

    def score(tool):
        tokens = _terms(tool.tool_id + " " + tool.description)
        return sum(min(count, tokens[word]) for word, count in wanted.items())

    ranked = sorted(definitions, key=lambda tool: (
        -(tool.tool_id.lower() in identifiers), -(tool.namespace.lower() in namespaces),
        -score(tool), tool.tool_id,
    ))
    return [tool for tool in ranked if score(tool) > 0] if wanted else ranked


class ToolCatalog:
    def __init__(self, *, max_result_bytes=65536):
        if not isinstance(max_result_bytes, int) or not 64 <= max_result_bytes <= 8 * 1024 * 1024:
            raise ValueError("Invalid tool result budget")
        self.max_result_bytes = max_result_bytes
        self._tools: dict[str, tuple[ToolDefinition, Callable]] = {}
        self._artifact_validators: dict[str, Callable] = {}
        self._namespace_metadata = {}
        self._lock = RLock()

    def register(self, definition: ToolDefinition, handler: Callable):
        if not isinstance(definition, ToolDefinition) or not callable(handler):
            raise ToolError("invalid_tool", "工具必须具有定义和可调用适配器")
        with self._lock:
            self._tools[definition.tool_id] = (deepcopy(definition), handler)
        return definition.tool_id

    def register_artifact_validator(self, kind, validator):
        """Host adapters own factual contracts; MCP content cannot install one."""
        if not isinstance(kind, str) or not kind or not callable(validator):
            raise ValueError("Invalid artifact validator")
        with self._lock:
            self._artifact_validators[kind] = validator

    def has_artifact_validator(self, kind):
        with self._lock:
            return kind in self._artifact_validators

    def validate_artifact(self, kind, content, snapshot, sources):
        with self._lock:
            validator = self._artifact_validators.get(kind)
        if validator is not None:
            validator(content, snapshot, sources)

    def restricted(self, allowed_ids):
        """An independent directory view; handlers remain bound to one runtime.

        Missing/revoked IDs are not resurrected. Calls still validate the live
        base catalog at dispatch, so this snapshot never grants authority.
        """
        selected = set(allowed_ids)
        view = ToolCatalog(max_result_bytes=self.max_result_bytes)
        with self._lock:
            view._artifact_validators = dict(self._artifact_validators)
            view._tools = {identity: (deepcopy(definition), handler)
                           for identity, (definition, handler) in self._tools.items() if identity in selected}
            namespaces = {identity.split(".", 1)[0] for identity in view._tools}
            view._namespace_metadata = {key: deepcopy(value) for key, value in self._namespace_metadata.items() if key in namespaces}
        return view

    def replace_namespace(self, namespace: str, tools: list[tuple[ToolDefinition, Callable]], *, metadata=None):
        """Publish a complete successful discovery snapshot; removed tools disappear."""
        if any(tool.namespace != namespace or not callable(handler) for tool, handler in tools):
            raise ToolError("invalid_tool", "工具发现结果不属于配置的命名空间")
        if len({tool.tool_id for tool, _ in tools}) != len(tools):
            raise ToolError("invalid_tool", "工具发现包含重复名称")
        if metadata is not None and (
            not isinstance(metadata, dict) or set(metadata) != {"display_name", "aliases"}
            or not isinstance(metadata["display_name"], str) or not 1 <= len(metadata["display_name"]) <= 120
            or not isinstance(metadata["aliases"], list) or len(metadata["aliases"]) > 8
            or any(not isinstance(alias, str) or not 1 <= len(alias) <= 80 for alias in metadata["aliases"])
        ):
            raise ToolError("invalid_tool", "命名空间展示元数据无效")
        with self._lock:
            self._tools = {key: value for key, value in self._tools.items() if value[0].namespace != namespace}
            self._tools.update({tool.tool_id: (deepcopy(tool), handler) for tool, handler in tools})
            if metadata is not None and tools:
                self._namespace_metadata[namespace] = deepcopy(metadata)
            else:
                self._namespace_metadata.pop(namespace, None)

    def revoke(self, tool_id: str):
        with self._lock:
            self._tools.pop(tool_id, None)

    def _entry(self, tool_id):
        with self._lock:
            entry = self._tools.get(tool_id)
            if entry is None:
                raise ToolError("tool_unavailable", "工具未连接或已停用")
            return deepcopy(entry[0]), entry[1]

    def namespace_index(self, limit=20):
        if type(limit) is not int or not 1 <= limit <= 20:
            raise ToolError("invalid_query", "工具命名空间索引数量无效")
        with self._lock:
            counts = Counter(definition.namespace for definition, _ in self._tools.values())
            metadata = deepcopy(self._namespace_metadata)
        namespaces = sorted(counts)
        return {"items": [{"namespace": namespace, "tool_count": counts[namespace], **metadata.get(namespace, {})}
                          for namespace in namespaces[:limit]],
                "omitted": max(0, len(namespaces) - limit)}

    def discover(self, query: str, *, limit=5):
        if not isinstance(query, str) or len(query) > 4000 or type(limit) is not int or not 1 <= limit <= 20:
            raise ToolError("invalid_query", "工具检索条件或数量无效")
        with self._lock:
            definitions = [deepcopy(value[0]) for value in self._tools.values()]
        return [tool.descriptor(schema=False) for tool in _rank(definitions, query)[:limit]]

    def discover_page(self, query="", *, namespace=None, offset=0, directory_version=None, limit=5):
        """Browse bounded summaries; continuations are tied to one directory snapshot."""
        if (not isinstance(query, str) or len(query) > 4000 or type(offset) is not int or offset < 0
                or type(limit) is not int or not 1 <= limit <= 20
                or namespace is not None and (not isinstance(namespace, str)
                                              or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", namespace))):
            raise ToolError("invalid_query", "工具目录检索或分页条件无效")
        query = query.strip()
        with self._lock:
            definitions = [deepcopy(value[0]) for value in self._tools.values()
                           if namespace is None or value[0].namespace == namespace]
            exact_identity = query in self._tools
        changed = "工具目录或检索条件已变化，请从当前命名空间目录首页重新读取。"
        if namespace is not None and not definitions:
            if offset > 0 and directory_version is not None:
                raise ToolError("directory_changed", changed)
            raise ToolError("invalid_query", "命名空间未登记或当前没有可发现工具，请查看可用命名空间。")
        fingerprint = {"query": query, "namespace": namespace,
                       "tools": sorted((tool.tool_id, tool.version) for tool in definitions)}
        version = hashlib.sha256(json.dumps(fingerprint, ensure_ascii=False, sort_keys=True,
                                             separators=(",", ":")).encode("utf-8")).hexdigest()
        if (offset > 0 or directory_version is not None) and directory_version != version:
            raise ToolError("directory_changed", changed)
        ranked = [tool for tool in definitions if tool.tool_id == query] if exact_identity else _rank(definitions, query)
        if offset > len(ranked):
            raise ToolError("invalid_query", "工具目录偏移超出结果范围，请从目录首页重新读取。")
        summaries = []
        for tool in ranked[offset:offset + limit]:
            description = tool.description.encode("utf-8")[:160].decode("utf-8", errors="ignore")
            item = {"tool_id": tool.tool_id, "description": description,
                    "description_truncated": description != tool.description,
                    "version": tool.version, "effect": tool.effect, "executor": tool.executor}
            if re.fullmatch(r"(?:builtin|mcp:official|mcp:fixture):[A-Za-z0-9_-]{1,80}", tool.source or ""):
                item["source"] = tool.source
            summaries.append(item)
        next_offset = offset + len(summaries)
        return {"tools": summaries, "query": query, "namespace": namespace, "offset": offset,
                "next_offset": next_offset if next_offset < len(ranked) else None,
                "total": len(ranked), "directory_version": version}

    def describe(self, tool_id):
        return self._entry(tool_id)[0].descriptor()

    @staticmethod
    def _validate(definition, arguments, expected_version):
        if expected_version is not None and expected_version != definition.version:
            raise ToolError("tool_changed", "工具契约已变化，请重新加载说明和参数")
        _json(arguments, 65536)
        try:
            Draft202012Validator(definition.input_schema).validate(arguments)
        except ValidationError as error:
            # Schema-derived diagnostics only: jsonschema's message/path may
            # echo private argument values or dynamic keys.
            shape = error.schema if isinstance(error.schema, dict) else {}
            bounded = lambda values: [str(value)[:40] for value in list(values)[:8]]
            diagnostic = {"rule": str(error.validator)[:40],
                          "schema_path": bounded(error.absolute_schema_path)}
            if isinstance(shape.get("properties"), dict):
                diagnostic["allowed_fields"] = bounded(shape["properties"])
            if isinstance(shape.get("required"), list):
                diagnostic["required_fields"] = bounded(shape["required"])
            raise ToolError("invalid_arguments", "工具参数不符合 Schema，请按契约修正：" +
                            json.dumps(diagnostic, ensure_ascii=False, separators=(",", ":"))) from None
        except RecursionError:
            raise ToolError("invalid_arguments", "工具参数不符合已登记的 Schema") from None
        except Exception:
            raise ToolError("invalid_schema", "工具 Schema 引用无法解析") from None

    def validate(self, tool_id, arguments, *, expected_version=None):
        definition, _ = self._entry(tool_id)
        self._validate(definition, arguments, expected_version)
        return definition

    def call(self, tool_id, arguments, *, expected_version=None, authorized=False):
        definition, handler = self._entry(tool_id)
        self._validate(definition, arguments, expected_version)
        if definition.effect != "read" and authorized is not True:
            raise ToolError("authorization_required", "此工具操作需要有效的执行授权")
        try:
            data = handler(deepcopy(arguments))
            _json(data, self.max_result_bytes, "result_too_large")
        except ToolError as error:
            if definition.effect != "read" and error.code == "result_too_large":
                error.outcome = "unknown"
            raise
        except Exception:
            raise ToolError("tool_execution_error", "工具执行未获得有效结果",
                            outcome="failed" if definition.effect == "read" else "unknown") from None
        if definition.output_schema is not None:
            try:
                value = data[definition.output_key] if definition.output_key is not None else data
                Draft202012Validator(definition.output_schema).validate(value)
            except Exception:
                raise ToolError("invalid_result", "工具返回不符合已登记的 Schema",
                                outcome="failed" if definition.effect == "read" else "unknown") from None
        return {"tool_id": definition.tool_id, "version": definition.version,
                "source": definition.source, "observed_at": datetime.now(timezone.utc).isoformat(),
                "data": deepcopy(data)}
