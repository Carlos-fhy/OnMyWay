"""Bounded evidence context and generic model decisions for the Agent runtime.

No model output is an authorization or an execution receipt. The runtime owns
those decisions and validates each proposed action against its current catalog.
"""
import json
import os
import re
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Annotated, Any, Literal
from urllib.parse import parse_qsl, urlsplit

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter, ValidationError, field_validator, model_serializer, model_validator

from backend.agent.evidence_views import mcp_json_text_views


class EvidenceNote(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    id: Annotated[str, Field(min_length=1, max_length=60, pattern=r"^[A-Za-z0-9_-]+$")]
    source_call_id: Annotated[str, Field(min_length=1, max_length=200)]
    quote: Annotated[str, Field(min_length=1, max_length=2400)] | None = None
    summary: Annotated[str, Field(min_length=1, max_length=2400)] | None = None

    @model_validator(mode="before")
    @classmethod
    def ignore_echoed_server_metadata(cls, value):
        if not isinstance(value, dict):
            return value
        readonly = {"observed_at", "source_revision", "revision", "reference_only", "model_derived", "verification",
                    "source_path", "source_offset", "quote_format"}
        return {key: item for key, item in value.items() if key not in readonly}

    @model_validator(mode="after")
    def exactly_one_kind(self):
        if (self.quote is None) == (self.summary is None):
            raise ValueError("Choose a verbatim quote or a derived summary")
        if not (self.quote if self.quote is not None else self.summary).strip():
            raise ValueError("A note cannot be whitespace")
        return self

    @model_serializer(mode="wrap")
    def serialize_note(self, handler):
        return {key: value for key, value in handler(self).items() if value is not None}


class EvidenceNotes(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    upsert: Annotated[list[EvidenceNote], Field(max_length=8)] = Field(default_factory=list)
    delete: Annotated[list[Annotated[str, Field(min_length=1, max_length=60)]], Field(max_length=8)] = Field(default_factory=list)


class _Action(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, str_strip_whitespace=True, allow_inf_nan=False)
    evidence_notes: EvidenceNotes | None = None

    @model_serializer(mode="wrap")
    def serialize_action(self, handler):
        value = handler(self)
        if self.evidence_notes is None:
            value.pop("evidence_notes", None)
        for key in ("artifact_id", "expected_version", "namespace", "directory_version", "need"):
            if value.get(key) is None:
                value.pop(key, None)
        if "offset" not in self.model_fields_set:
            value.pop("offset", None)
        if "missing_inputs" not in self.model_fields_set:
            value.pop("missing_inputs", None)
        return value


Text = Annotated[str, Field(min_length=1, max_length=4000)]
ToolId = Annotated[str, Field(min_length=3, max_length=200, pattern=r"^[A-Za-z0-9_-]+\.[A-Za-z0-9_.-]+$")]


class DiscoverTools(_Action):
    action: Literal["discover_tools"]
    query: Annotated[str, Field(max_length=1000)] = ""
    namespace: Annotated[str, Field(min_length=1, max_length=80, pattern=r"^[A-Za-z0-9_-]+$")] | None = None
    offset: Annotated[int, Field(ge=0, le=2147483647)] = 0
    directory_version: Annotated[str, Field(min_length=1, max_length=200)] | None = None

    @model_validator(mode="after")
    def query_or_namespace(self):
        if not self.query and self.namespace is None:
            raise ValueError("Discovery requires a query or namespace")
        return self


class CallTool(_Action):
    action: Literal["call_tool"]
    tool_id: ToolId
    arguments: dict[str, Any]


class AskUser(_Action):
    action: Literal["ask_user"]
    question: Text
    need: Literal["missing_input", "user_decision"] | None = None
    missing_inputs: dict[str, str] = Field(default_factory=dict, max_length=6)


class PublishArtifact(_Action):
    action: Literal["publish_artifact"]
    kind: Annotated[str, Field(min_length=1, max_length=60, pattern=r"^[a-z][a-z0-9_]*$")]
    title: Annotated[str, Field(min_length=1, max_length=200)]
    content: Any
    evidence_call_ids: Annotated[list[Annotated[str, Field(min_length=1, max_length=200)]], Field(max_length=100)]
    artifact_id: Annotated[str, Field(min_length=1, max_length=200)] | None = None
    expected_version: Annotated[int, Field(ge=1, le=2147483647)] | None = None

    @model_validator(mode="after")
    def paired_update_identity(self):
        if (self.artifact_id is None) != (self.expected_version is None):
            raise ValueError("Updating an artifact requires its identity and expected version")
        return self

    @field_validator("content")
    @classmethod
    def nonempty_content(cls, value):
        if value is None or (isinstance(value, str) and not value.strip()) or value == {} or value == []:
            raise ValueError("成果内容不能为空")
        return value

    @field_validator("evidence_call_ids")
    @classmethod
    def unique_evidence(cls, value):
        if len(set(value)) != len(value):
            raise ValueError("来源调用编号不能重复")
        return value


class Finish(_Action):
    action: Literal["finish"]
    summary: Text


class SharedInput(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    kind: Literal["result", "artifact"]
    ref: Annotated[str, Field(min_length=1, max_length=200)]
    version: Annotated[int, Field(ge=0)] = 0

    @model_validator(mode="after")
    def pinned_version(self):
        if (self.kind == "artifact" and self.version < 1) or (self.kind == "result" and self.version != 0):
            raise ValueError("Artifacts need a positive version; results use version zero")
        return self


class ChildBudget(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False)
    max_steps: Annotated[int, Field(ge=1, le=1000)] = 8
    max_tool_calls: Annotated[int, Field(ge=1, le=1000)] = 4
    max_duration_seconds: Annotated[float, Field(gt=0, le=86400)] = 90


class DelegateTask(_Action):
    action: Literal["delegate_task"]
    delegation_key: Annotated[str, Field(min_length=1, max_length=80)]
    goal: Text
    constraints: dict[str, Any] = Field(default_factory=dict)
    success_criteria: Annotated[list[Text], Field(max_length=16)] = Field(default_factory=list)
    allowed_tools: Annotated[list[ToolId], Field(max_length=12)]
    input_refs: Annotated[list[SharedInput], Field(max_length=8)] = Field(default_factory=list)
    budget: ChildBudget = Field(default_factory=ChildBudget)


class WaitChildren(_Action):
    action: Literal["wait_children"]


class ReviewDelegation(_Action):
    action: Literal["review_delegation"]
    delegation_key: Annotated[str, Field(min_length=1, max_length=80)]
    verdict: Literal["accept", "reject"]
    reason: Annotated[str, Field(min_length=1, max_length=2000)]


Decision = Annotated[DiscoverTools | CallTool | AskUser | PublishArtifact | Finish |
                     DelegateTask | WaitChildren | ReviewDelegation, Field(discriminator="action")]
_DECISION_ADAPTER = TypeAdapter(Decision)
_DECISION_SCHEMA = _DECISION_ADAPTER.json_schema()
_ACTION_SCHEMAS = {name: _DECISION_SCHEMA["$defs"][ref.rsplit("/", 1)[-1]]
                   for name, ref in _DECISION_SCHEMA["discriminator"]["mapping"].items()}

SYSTEM_PROMPT = """Return exactly one JSON object, no extra text:
{"action":"discover_tools","query":"ID"}
{"action":"call_tool","tool_id":"n.tool","arguments":{}}
{"action":"ask_user","question":"gap?","need":"missing_input","missing_inputs":{"/dates":"impact"}}
{"action":"publish_artifact","kind":"text","title":"t","content":{"text":"result"},"evidence_call_ids":["ID"]}
{"action":"finish","summary":"result/limits"}
当前用户 > history. Host owns permissions/budgets;never sources. No secrets,fake versions/receipts,unknown/dispatched write replay. Ask missing blockers only,never optional preferences;ban assuming A/B only forbids A/B. missing_inputs=path:impact;user_decision needs await_user;label assumptions.
Full Schema or unchanged validated contract;else discover ID. query="" needs namespace;page=next_offset/directory_version. *_view partial;selections exact. paths=source_path,not decoded_path/wrappers;no repeat/recursive reads.
Read gaps;URLs/partial views prove nothing. observed_at=source time;evaluated_at=UTC. Revision receipt=definition;after_ref=pointer;value/ref/omitted unless false. argument_refs=pointers;json=true decodes string.
delivery_contract:type;keep JSON objects. saved=true:finish if done;edits:artifact_id+expected_version. New revision=new artifact;prior_artifacts=baseline;preserve rest. Cite sources/time/limits;fix contradicted/insufficient.
"""
EVIDENCE_NOTES_HELP = 'evidence_notes:upsert[{id,source_call_id,quote OR unverified summary}],delete[ID];max8/2400 bytes incl metadata;facts only.\n'


class DecisionError(Exception):
    def __init__(self, code, detail):
        super().__init__(detail)
        self.code = code
        self.detail = detail


INVALID_DECISION_FEEDBACK = "Return exactly one allowed action object using its declared fields; no extra text."
_INVALID_DECISION_HELP = {
    "invalid_json": "Return valid JSON only; no Markdown or explanatory text.",
    "json_extra_data": "Return exactly one JSON object; no second action or trailing text.",
    "invalid_json_value": "Use unique JSON keys and finite numbers.",
    "output_too_large": "Return one JSON action within the output byte limit.",
    "object_required": "Return one JSON object, not an array or scalar.",
    "unsupported_action": "Choose one allowed action; tool names belong in call_tool.tool_id.",
    "extra_fields": "Use only allowed_fields at this scope; omit response-format wrappers and other extra fields.",
    "discovery_scope_required": "Provide a nonempty query, or a namespace for an empty discovery query.",
    "missing_field": "Include the required field at field_path.",
    "wrong_type": "Use expected_type at field_path; do not stringify objects or arrays.",
    "invalid_value": "Use an allowed value at field_path.",
    "invalid_field": "Correct field_path to satisfy the declared action contract.",
}


class InvalidDecision(DecisionError):
    def __init__(self, rule, **diagnostic):
        super().__init__("invalid_decision", _INVALID_DECISION_HELP[rule])
        self.diagnostic = {"rule": rule, **diagnostic}
        if len(json.dumps(self.diagnostic, separators=(",", ":")).encode("utf-8")) > 900:
            self.diagnostic = {"rule": rule}


def _decision_shape(schema):
    if "$ref" in schema:
        return _DECISION_SCHEMA["$defs"][schema["$ref"].rsplit("/", 1)[-1]]
    if "anyOf" in schema:
        return _decision_shape(next((item for item in schema["anyOf"] if item.get("type") != "null"), {}))
    return schema


def _invalid_action(value, error):
    if not isinstance(value, dict):
        return InvalidDecision("object_required")
    action = value.get("action")
    if not isinstance(action, str) or action not in _ACTION_SCHEMAS:
        if "action" not in value:
            return InvalidDecision("missing_field", field_path=["action"], allowed_actions=list(_ACTION_SCHEMAS))
        return InvalidDecision("unsupported_action", allowed_actions=list(_ACTION_SCHEMAS))
    issue = error.errors(include_url=False, include_input=False, include_context=False)[0]
    schema, path = _ACTION_SCHEMAS[action], []
    # Validation locations can contain private dynamic keys. Walk only our
    # declared schema properties; never copy messages, input, ctx or raw loc.
    for segment in issue["loc"][1:9]:
        schema = _decision_shape(schema)
        if isinstance(segment, str) and segment in schema.get("properties", {}):
            path.append(segment)
            schema = schema["properties"][segment]
        elif type(segment) is int and "items" in schema:
            path.append("items")
            schema = schema["items"]
        else:
            break
    schema = _decision_shape(schema)
    kind = issue["type"]
    if kind == "extra_forbidden":
        return InvalidDecision("extra_fields", field_path=path, allowed_fields=list(schema.get("properties", {}))[:12])
    if kind == "value_error" and action == "discover_tools" and not path:
        return InvalidDecision("discovery_scope_required", fields=["query", "namespace"])
    if kind == "missing":
        return InvalidDecision("missing_field", field_path=path)
    expected = {"string_type": "string", "dict_type": "object", "list_type": "array",
                "int_type": "integer", "float_type": "number", "bool_type": "boolean"}.get(kind)
    if expected:
        return InvalidDecision("wrong_type", field_path=path, expected_type=expected)
    if kind == "literal_error":
        return InvalidDecision("invalid_value", field_path=path, allowed_values=schema.get("enum", []))
    return InvalidDecision("invalid_field", field_path=path)


class ContextBudgetExceeded(DecisionError):
    def __init__(self, detail="当前目标、约束或未决操作超过上下文预算，请缩小范围后继续。"):
        super().__init__("context_budget_exceeded", detail)


def _json(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate key")
        result[key] = value
    return result


def _invalid_constant(_value):
    raise ValueError("non-finite JSON number")


def parse_decision(content, *, max_output_bytes=32768):
    if not isinstance(content, str):
        raise InvalidDecision("invalid_json")
    if len(content.encode("utf-8")) > max_output_bytes:
        raise InvalidDecision("output_too_large")
    try:
        value = json.loads(content, parse_constant=_invalid_constant, object_pairs_hook=_unique_object)
        _json(value)  # JSON's finite exponent syntax can still overflow to inf.
    except json.JSONDecodeError as error:
        raise InvalidDecision("json_extra_data" if error.msg == "Extra data" else "invalid_json") from None
    except (ValueError, TypeError, RecursionError):
        raise InvalidDecision("invalid_json_value") from None
    try:
        return _DECISION_ADAPTER.validate_python(value)
    except ValidationError as error:
        raise _invalid_action(value, error) from None
    except (ValueError, TypeError, RecursionError):
        raise InvalidDecision("invalid_field") from None


@dataclass(frozen=True)
class DecisionContext:
    messages: list[dict[str, str]]
    estimated_input_tokens: int
    omitted: dict[str, int]
    estimator: str = "utf8_bytes_with_message_allowance"


def estimate_input_tokens(messages):
    """Conservative byte-token estimate, not provider-reported token usage.

    The exact UTF-8 serialized payload size bounds byte-level tokenization of
    these messages. The per-message allowance reserves framing overhead; a
    provider-specific tokenizer/hidden prompt may differ, so callers must also
    retain output space and configure a margin below the model's actual window.
    """
    return len(_json(messages).encode("utf-8")) + 32 * len(messages)


def _select(value, keys):
    return {key: value[key] for key in keys if key in value}


def observed_at(item):
    """Use the persisted receipt clock, never a date supplied in tool content."""
    stamp = next((item[key] for key in ("updated_at", "completed_at", "created_at")
                  if type(item.get(key)) in (int, float)), None)
    return datetime.fromtimestamp(stamp, timezone.utc).isoformat() if stamp is not None else None


def source_links(value):
    """Retain bounded public links, without promoting them to verified claims."""
    def texts(node):
        if isinstance(node, str):
            yield node
        elif isinstance(node, dict):
            for child in node.values():
                yield from texts(child)
        elif isinstance(node, list):
            for child in node:
                yield from texts(child)
    seen, urls, omitted = set(), [], 0
    sensitive = {"key", "apikey", "token", "accesstoken", "refreshtoken", "authorization", "password",
                 "secret", "signature", "sig", "credential", "xamzcredential", "xamzsignature"}
    for text in texts(value):
        for match in re.finditer(r"https?://[^\s<>\"'\\]+", text):
            url = match.group().rstrip(".,;!?，。；！？）】》")
            while url.endswith((")", "]", "}")) and url.count(url[-1]) > url.count({")": "(", "]": "[", "}": "{"}[url[-1]]):
                url = url[:-1]
            if url in seen:
                continue
            seen.add(url)
            try:
                parsed = urlsplit(url)
                params = parse_qsl(parsed.query, keep_blank_values=True) + parse_qsl(parsed.fragment, keep_blank_values=True)
                secret = any(re.sub(r"[^a-z]", "", key.lower()) in sensitive for key, _ in params)
                safe = bool(parsed.hostname) and not parsed.username and not parsed.password and not secret
            except ValueError:
                safe = False
            if (not safe or len(urls) >= 3 or
                    len(_json({"urls": [*urls, url], "omitted": 999999}).encode("utf-8")) > 512):
                omitted += 1
            else:
                urls.append(url)
    return {"urls": urls, "omitted": omitted}


_READERS = {"agent.read_result", "agent.read_artifact"}


def reader_source(call):
    """Resolve durable page identity before defaults that may now point elsewhere."""
    if call.get("tool_id") not in _READERS:
        return None
    result = call.get("result")
    page = result.get("data") if isinstance(result, dict) else None
    if isinstance(page, dict) and "source_ref" in page:
        if page.get("source_kind") not in {"result", "artifact"} or not isinstance(page["source_ref"], str):
            return None
        return _select(page, ("source_kind", "source_ref", "version"))
    args = call.get("arguments", {})
    if call["tool_id"] == "agent.read_result":
        return {"source_kind": "result", "source_ref": args.get("tool_call_id")}
    version = args.get("version")
    if version is None and isinstance(page, dict) and page.get("offset") == 0:
        # The old serializer emitted artifact_id/version before content. Recover
        # only this exact metadata prefix, never a version mentioned in content.
        prefix = '{"artifact_id":' + _json(args.get("artifact_id")) + ',"version":'
        text = page.get("text", "")
        if isinstance(text, str) and text.startswith(prefix):
            try:
                value, end = json.JSONDecoder().raw_decode(text[len(prefix):])
                if type(value) is int and value > 0 and text[len(prefix) + end:len(prefix) + end + 1] in {",", "}"}:
                    version = value
            except ValueError:
                pass
    return {"source_kind": "artifact", "source_ref": args.get("artifact_id"), "version": version}


def _reader_evidence(calls):
    """Fold page receipts by original source and range, not reader call identity."""
    sources, pages = {}, {}
    by_id = {call["tool_call_id"]: call for call in calls}
    for call in calls:
        if call.get("tool_id") not in _READERS or call.get("state") != "succeeded":
            continue
        result = call.get("result")
        page = result.get("data") if isinstance(result, dict) else None
        if not isinstance(page, dict) or not isinstance(page.get("text"), str):
            continue
        page = deepcopy(page)
        if not page.get("source_ref"):
            # Existing direct pages remain usable; old nested reader envelopes
            # are not source text and must never become recursively readable IDs.
            reference = reader_source(call)
            if reference is None:
                continue
            target = by_id.get(reference.get("source_ref"), {})
            if reference["source_kind"] == "result" and target.get("tool_id") not in _READERS:
                page.update(**reference,
                            source_tool_id=target.get("tool_id"), observed_at=observed_at(target))
            elif reference["source_kind"] == "artifact" and type(reference.get("version")) is int:
                page.update(reference)
            else:
                continue
        if not page.get("source_ref") or page.get("source_kind") not in {"result", "artifact"}:
            continue
        if any(type(page.get(key)) is not int for key in ("offset", "next_offset", "total_chars")):
            continue
        start, end, total = page["offset"], page["next_offset"], page["total_chars"]
        if not 0 <= start <= end <= total or len(page["text"]) != end - start:
            continue
        key = (page["source_kind"], page["source_ref"], page.get("version"))
        if page["source_kind"] == "artifact":
            page["evidence_call_ids"] = [call["tool_call_id"]]
        else:
            source_state = page.get("source_state", by_id.get(page["source_ref"], {}).get("state", "succeeded"))
            page["evidence_call_ids"] = [page["source_ref"]] if source_state == "succeeded" else []
        source = sources.setdefault(key, {**_select(page, ("source_kind", "source_ref", "source_tool_id", "version", "observed_at")),
                                          "read_ranges": []})
        links = page.get("source_links") or source_links(by_id.get(page["source_ref"], {}).get("result"))
        if links["urls"] or links["omitted"]:
            source["source_links"] = links
        source.update(total_chars=total, next_offset=end, has_more=page.get("has_more", end < total),
                      evidence_call_ids=page["evidence_call_ids"])
        source["read_ranges"].append([start, end])
        bounded = page["text"].encode("utf-8")[:2400].decode("utf-8", errors="ignore")
        if bounded != page["text"]:
            page.update(text=bounded, next_offset=start + len(bounded), has_more=start + len(bounded) < total,
                        compacted=True, retrieved_next_offset=end)
        page_key = (*key, start, end)
        pages.pop(page_key, None)
        pages[page_key] = _select(page, ("source_kind", "source_ref", "version", "observed_at", "text", "offset",
                                        "next_offset", "has_more", "total_chars", "compacted", "retrieved_next_offset",
                                        "evidence_call_ids"))
    for source in sources.values():
        merged = []
        for start, end in sorted(source["read_ranges"]):
            if merged and start <= merged[-1][1]:
                merged[-1][1] = max(merged[-1][1], end)
            else:
                merged.append([start, end])
        source["read_ranges"] = merged
    return list(sources.values()), list(reversed(list(pages.values())))


def _reader_selections(calls):
    """Keep original-source field selections distinct from character pages."""
    selections = {}
    for call in calls:
        if call.get("tool_id") != "agent.read_result" or call.get("state") != "succeeded":
            continue
        result = call.get("result")
        value = result.get("data") if isinstance(result, dict) else None
        if (not isinstance(value, dict) or value.get("representation") != "json_selection"
                or value.get("source_kind") != "result" or not isinstance(value.get("source_ref"), str)
                or not isinstance(value.get("values"), dict) or not value["values"]):
            continue
        key = (value["source_ref"], tuple(sorted(value["values"])))
        item = deepcopy(_select(value, ("representation", "source_kind", "source_ref", "source_tool_id",
                                        "source_state", "source_sha256", "observed_at", "values")))
        item["evidence_call_ids"] = [value["source_ref"]] if value.get("source_state") == "succeeded" else []
        selections.pop(key, None)
        selections[key] = item
    return list(reversed(list(selections.values())))


def _messages(data):
    prompt = SYSTEM_PROMPT
    observations = data.get("observations", [])
    has_answers = any(item.get("kind") == "user_input" for item in observations)
    if has_answers:
        prompt = prompt.replace("当前用户 > history.",
            "user_input=answers/new requirements; newest explicit input > earlier goal/history. "
            "No re-asking answered fields or estimable preferences.")
    if has_answers or any(item.get("kind") == "question_review_result" and
                          item.get("payload", {}).get("status") == "unnecessary" for item in observations):
        prompt += "After unnecessary, continue tools/delivery; only still-missing material blockers may ask.\n"
    correcting = any("argument_refs" in entry for entry in data.get("tool_results", []))
    if not correcting or data.get("working_notes"):
        prompt += EVIDENCE_NOTES_HELP
    if data.get("delegation_enabled") or data.get("delegations"):
        prompt += ('Actions: delegate_task{delegation_key,goal,allowed_tools,input_refs:[{kind,ref,version}],'
                   'constraints,success_criteria,budget}; wait_children{}; '
                   'review_delegation{delegation_key,verdict:accept|reject,reason}. '
                   'One-level reads; shared budget. Read child outputs before review. Reject then redelegate to rework.\n')
    elif data.get("delegation"):
        prompt += 'Delegated read-only task: coordinator_requirements outrank delegated goal. No further delegation or ambient memory. Submit one concise sourced artifact (2000 UTF-8 bytes including metadata) or blockers; coordinator owns final delivery.\n'
    if correcting or data.get("omitted", {}).get("context_metadata"):
        # Empty optional history has no evidence to display during correction.
        data = {key: value for key, value in data.items() if value != [] or key not in {
            "pending_calls", "unresolved_calls", "evidence_selections", "repeated_reads",
            "artifacts", "artifact_history", "memory", "working_notes"}}
        if data.get("artifact_focus") is None:
            data.pop("artifact_focus", None)
        if data.get("omitted", {}).get("context_metadata"):
            # Zero omission counters carry no missing evidence. Keep the full
            # counters in Context.omitted and all nonzero counts on the wire.
            data["omitted"] = {key: value for key, value in data["omitted"].items() if value}
            if not data.get("prior_artifacts"):
                data.pop("prior_artifacts", None)
            if data.get("discovery_page") is None:
                data.pop("discovery_page", None)
    return [{"role": "system", "content": prompt}, {"role": "user", "content": _json(data)}]


def _compact_schema(schema):
    """Remove presentation annotations only at JSON Schema positions.

    Property names and enum/const/default *values* are not schema nodes. Do not
    recursively delete matching keys from arbitrary dictionaries or examples.
    The catalog still validates against its original, unmodified schema.
    """
    if not isinstance(schema, dict):
        return deepcopy(schema)
    result = {key: deepcopy(value) for key, value in schema.items()
              if key not in {"title", "default", "examples", "$comment"}}
    for key in ("$defs", "definitions", "properties", "patternProperties", "dependentSchemas"):
        if isinstance(result.get(key), dict):
            result[key] = {name: _compact_schema(value) for name, value in result[key].items()}
    for key in ("additionalItems", "items", "contains", "additionalProperties", "unevaluatedProperties",
                "propertyNames", "not", "if", "then", "else", "unevaluatedItems", "contentSchema"):
        if isinstance(result.get(key), (dict, bool)):
            result[key] = _compact_schema(result[key])
    for key in ("prefixItems", "allOf", "anyOf", "oneOf"):
        if isinstance(result.get(key), list):
            result[key] = [_compact_schema(value) for value in result[key]]
    return result


def _input_fields(schema):
    """Read declared schema fields only, with bounded structural traversal."""
    pending, names = [(schema, 0)], set()
    for _ in range(256):
        if not pending:
            break
        node, depth = pending.pop()
        if not isinstance(node, dict) or depth >= 16:
            continue
        properties = node.get("properties")
        if isinstance(properties, dict):
            names.update(properties)
        children = []
        for key in ("$defs", "definitions", "properties", "patternProperties", "dependentSchemas"):
            if isinstance(node.get(key), dict):
                children.extend(node[key].values())
        for key in ("additionalItems", "items", "contains", "additionalProperties", "unevaluatedProperties",
                    "propertyNames", "not", "if", "then", "else", "unevaluatedItems", "contentSchema"):
            if isinstance(node.get(key), dict):
                children.append(node[key])
        for key in ("prefixItems", "allOf", "anyOf", "oneOf"):
            if isinstance(node.get(key), list):
                children.extend(node[key])
        pending.extend((child, depth + 1) for child in reversed(children[:256]))
    return names


def _result_fields(value):
    """Rank original structural keys, never instructions or decoded strings."""
    pending, names = [(value, 0)], set()
    for _ in range(256):
        if not pending:
            break
        node, depth = pending.pop()
        if depth >= 16:
            continue
        if isinstance(node, dict):
            names.update(node)
            children = list(node.values())[:256]
        elif isinstance(node, list):
            children = node[:256]
        else:
            continue
        pending.extend((child, depth + 1) for child in reversed(children))
    return names


def _result_view(value, *, string_bytes=192, array_items=8, object_items=64, max_nodes=256,
                 path_key="source_path", decode_mcp=False):
    """Project data structurally without changing the durable source or its types.

    Omitted nodes are explicit metadata, not invented source values. Paths are
    JSON Pointers into the original result; they do not imply a path-read API.
    """
    remaining = max_nodes
    decoded = mcp_json_text_views(value, project=lambda node: _result_view(
        node, string_bytes=string_bytes, array_items=array_items, object_items=object_items,
        max_nodes=max_nodes, path_key="decoded_path", decode_mcp=False)) if decode_mcp else {}

    def visit(node, path, depth):
        nonlocal remaining
        remaining -= 1
        kind = "object" if isinstance(node, dict) else "array" if isinstance(node, list) else "string"
        if isinstance(node, (dict, list, str)):
            marker = {"truncated": True, "type": kind, "length": len(node), path_key: path}
            if remaining < 0 or depth >= 12:
                return marker
        if isinstance(node, str):
            encoded = node.encode("utf-8")
            if len(encoded) <= string_bytes:
                return node
            if path in decoded:
                return decoded[path]
            marker["bytes"] = len(encoded)
            marker["preview"] = encoded[:min(64, string_bytes)].decode("utf-8", errors="ignore")
            return marker
        if isinstance(node, list):
            items = [visit(item, f"{path}/{index}", depth + 1)
                     for index, item in enumerate(node[:array_items])]
            if len(node) > array_items:
                return {**marker, "items": items} if items else marker
            return items
        if isinstance(node, dict):
            items = {key: visit(item, path + "/" + key.replace("~", "~0").replace("/", "~1"), depth + 1)
                     for key, item in list(node.items())[:object_items]}
            return {**marker, "items": items} if len(node) > object_items else items
        return node

    return visit(value, "", 0)


def pending_schema_request(snapshot):
    """Compatibility view of the shared, current explicit contract target."""
    from backend.agent.focus import resolve_tool_focus
    focus = resolve_tool_focus(snapshot)
    return focus.schema_request if focus is not None else None


def _revision_context(receipt, task, *, reference_root="/task"):
    """Reference current values once; keep previous values and receipt identity.

    The durable receipt is unchanged. A reference is used only after comparing
    the value, so old/incomplete snapshots cannot silently change its meaning.
    """
    result = deepcopy(receipt)
    for change in result.get("changes", []):
        for side in ("before", "after"):
            if change.get(side + "_present") is True and (side in change or side + "_omitted" in change):
                change.pop(side + "_present")
        if "after" not in change:
            continue
        try:
            node = task
            for part in change["path"].split("/")[1:]:
                key = part.replace("~1", "/").replace("~0", "~")
                node = node[int(key)] if isinstance(node, list) else node[key]
            reference = reference_root + change["path"]
            if (_json(node) == _json(change["after"])
                    and len(_json(reference).encode("utf-8")) + 4 < len(_json(node).encode("utf-8"))):
                change.pop("after")
                change["after_ref"] = reference
        except (KeyError, TypeError, ValueError, IndexError):
            pass
    return result


def _argument_context(arguments, data):
    """Reference equal visible values; keep mismatched arguments verbatim."""
    candidates = {}
    def key_for(value):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False, sort_keys=True)

    def collect(value, path, *, decoded=False):
        if isinstance(value, (dict, list)):
            reference = {"path": path, **({"json": True} if decoded else {})}
            key = key_for(value)
            if key not in candidates or len(_json(reference)) < len(_json(candidates[key])):
                candidates[key] = reference
            if not decoded:
                items = value.items() if isinstance(value, dict) else enumerate(value)
                for name, child in items:
                    collect(child, path + "/" + str(name).replace("~", "~0").replace("/", "~1"))
        elif isinstance(value, str) and 64 < len(value) <= 4096:
            try:
                parsed = json.loads(value, object_pairs_hook=_unique_object, parse_constant=_invalid_constant)
                if isinstance(parsed, (dict, list)):
                    collect(parsed, path, decoded=True)
            except (ValueError, RecursionError):
                pass
    collect(data["task"], "/task")
    for index, entry in enumerate(data["tool_results"]):
        if entry.get("truncated") is False and "result" in entry:
            collect(entry["result"], f"/tool_results/{index}/result")
    references = {}
    def project(value, path=""):
        if isinstance(value, (dict, list)):
            reference = candidates.get(key_for(value))
            if path and reference is not None and len(_json(reference)) + len(path) + 12 < len(_json(value).encode("utf-8")):
                references[path] = reference
                return None
        if isinstance(value, dict):
            result = {}
            for name, child in value.items():
                child_path = path + "/" + name.replace("~", "~0").replace("/", "~1")
                projected = project(child, child_path)
                if child_path not in references:
                    result[name] = projected
            return result
        if isinstance(value, list):
            return [project(child, f"{path}/{index}") for index, child in enumerate(value)]
        return value
    return project(arguments), references


def build_context(snapshot, tool_descriptors=(), memory=(), *, namespace_index=None, active_tool_ids=(), max_input_tokens=12000,
                  output_reserve=2048, context_window=16384):
    """Keep task invariants intact, then fill remaining space with evidence.

    Only whitelisted state fields enter the model context: leases, receipt
    secrets and arbitrary chat history are never copied from a store snapshot.
    All current-run user replies are essential, as are unresolved call inputs.
    """
    limits = (max_input_tokens, output_reserve, context_window)
    if any(type(limit) is not int or limit <= 0 for limit in limits):
        raise ValueError("Context budgets must be positive integers")
    ceiling = min(max_input_tokens, context_window - output_reserve)
    run = snapshot.get("run", {})
    if run.get("revision") != snapshot.get("revision") or run.get("agent_run_id") != snapshot.get("current_run_id"):
        raise DecisionError("invalid_context", "运行与任务当前修订不匹配，不能继续生成决策。")
    from backend.agent.focus import current_observations, named_discovery_tool, resolve_tool_focus
    observations = current_observations(snapshot)
    tool_focus = resolve_tool_focus(snapshot)
    calls = snapshot.get("tool_calls", [])
    pending_states = {"prepared", "waiting_approval", "dispatched", "unknown"}
    pending = [call for call in calls if call.get("state") in pending_states]
    all_completed = [call for call in calls if call.get("state") not in pending_states]
    reader_failures = [call for call in all_completed if call.get("tool_id") in _READERS and call.get("state") == "failed"]
    latest_reader_failure = reader_failures[-1] if reader_failures else None
    if latest_reader_failure is not None:
        later = all_completed[all_completed.index(latest_reader_failure) + 1:]
        if any(item.get("state") == "succeeded" and reader_source(item)
               for item in later if item.get("tool_id") in _READERS) or any(
                   item.get("kind") == "tools_discovered" and item.get("created_at", 0) >
                   latest_reader_failure.get("updated_at", latest_reader_failure.get("created_at", 0))
                   for item in observations):
            # Old read failures remain in the durable ledger and omission count.
            # A corrected read or a new discovery moves past their diagnostic.
            latest_reader_failure = None
    completed = [call for call in all_completed
                 if call.get("tool_id") not in _READERS or call is latest_reader_failure]
    # Repeated read-only observations of identical business data need one
    # payload, while each receipt keeps its own identity and completion time.
    # This is display compaction, never call deduplication or write replay.
    latest_read = reader_source(calls[-1]) if calls and calls[-1].get("state") == "succeeded" else None
    pinned_source = latest_read.get("source_ref") if latest_read else None
    seen_reads, repeated_reads, retained = {}, {}, []
    for call in reversed(completed):
        result = call.get("result")
        is_read = (call.get("effect") == "read" and call.get("state") == "succeeded" and isinstance(result, dict)
                   and set(result) == {"data", "observed_at", "source", "tool_id", "version"})
        key = _json([call.get("tool_id"), call.get("tool_version"),
                     {k: v for k, v in result.items() if k != "observed_at"}]) if is_read else None
        if key is not None and key in seen_reads and call["tool_call_id"] != pinned_source:
            identity = seen_reads[key]
            repeated_reads.setdefault(identity, []).append({"result_ref": call["tool_call_id"],
                "observed_at": observed_at(call), "source_time": result["observed_at"]})
        else:
            retained.append(call)
            if key is not None:
                seen_reads.setdefault(key, call["tool_call_id"])
    completed = list(reversed(retained))
    correction_call = next((call for call in reversed(completed)
                            if call.get("state") == "failed" and call.get("tool_id") not in _READERS
                            and isinstance(call.get("error"), dict) and call["error"].get("code") == "invalid_arguments"
                            and call.get("agent_run_id", run.get("agent_run_id")) == run.get("agent_run_id")
                            and not any(later.get("tool_id") == call.get("tool_id") and later.get("state") == "succeeded"
                                        for later in completed[completed.index(call) + 1:])), None)
    if tool_focus is not None and correction_call is not None and tool_focus.tool_id != correction_call["tool_id"]:
        correction_call = None
    evidence_sources, evidence_pages = _reader_evidence(calls)
    evidence_selections = _reader_selections(calls)
    user_inputs = [item for item in observations if item.get("kind") == "user_input"]
    latest_reply = max((item.get("created_at", 0) for item in user_inputs), default=0)
    latest_reply_index = max((index for index, item in enumerate(observations)
                              if item.get("kind") == "user_input"), default=0)
    # current_observations is cursor-ordered (legacy snapshots use insertion
    # order). Retire only matched reviews completed before the latest answer;
    # leave the durable ledger, unfinished requests and current feedback intact.
    review_requests, answered_reviews = {}, set()
    for index, item in enumerate(observations[:latest_reply_index]):
        fingerprint = item.get("payload", {}).get("fingerprint")
        if not isinstance(fingerprint, str) or not fingerprint:
            continue
        if item.get("kind") == "question_review_requested":
            review_requests[fingerprint] = index
        elif item.get("kind") == "question_review_result" and fingerprint in review_requests:
            answered_reviews.update((review_requests.pop(fingerprint), index))
    decision_progress = max(
        [item.get("created_at", 0) for item in observations if item.get("kind") in {"tools_discovered", "user_input"}]
        + [call.get("created_at", 0) for call in calls]
        + [item.get("created_at", 0) for item in snapshot.get("artifacts", [])], default=0)
    parsed_action_at = max((item.get("created_at", 0) for item in observations
                           if item.get("kind") == "tool_error"), default=0)
    def resolved_tool_error(item):
        return item.get("kind") == "tool_error" and any(
            call.get("tool_id") == item.get("payload", {}).get("tool_id") and call.get("state") == "succeeded"
            and call.get("created_at", 0) > item.get("created_at", 0) for call in calls)
    recent = [item for index, item in enumerate(observations) if index not in answered_reviews
              and item.get("kind") not in {"user_input", "tools_discovered"}
              and not (item.get("kind") == "question" and item.get("created_at", 0) < latest_reply)
              and not (item.get("kind") == "decision_error" and item.get("created_at", 0) < decision_progress)
              and not (item.get("kind") == "decision_error" and item.get("payload", {}).get("code") == "invalid_decision"
                       and item.get("created_at", 0) < parsed_action_at)
              and not (item.get("kind") == "tool_error"
                       and item.get("payload", {}).get("code") == "discover_tool_schema_first"
                       and (tool_focus is None or item != tool_focus.schema_request))
              and not resolved_tool_error(item)][-3:]
    core_observations = [item for item in observations if item in user_inputs or item in recent]
    if correction_call is not None:
        # Repeated validation failures are still in the ledger. Show the newest
        # instance once; do not replace a tool's actionable business diagnostic.
        unique, seen_errors = [], set()
        for item in reversed(core_observations):
            key = _json([item.get("kind"), item.get("payload")])
            if item.get("kind") in {"tool_error", "decision_error"}:
                if key in seen_errors:
                    continue
                seen_errors.add(key)
            if (item.get("kind") == "decision_error" and
                    item.get("payload", {}).get("code") == "invalid_decision" and
                    "diagnostic" not in item.get("payload", {})):
                item = {**item, "payload": {**item["payload"], "message":
                        'Use one valid JSON action; no extra text.',
                        "message_compacted": True}}
            unique.append(item)
        core_observations = list(reversed(unique))
    all_artifacts = snapshot.get("artifacts", [])
    all_prior_artifacts = snapshot.get("prior_artifacts", [])

    def newest(items):
        return max(enumerate(items), key=lambda pair: (pair[1].get("revision", 0),
                   pair[1].get("created_at", 0), pair[1].get("version", 0), pair[0]))[1] if items else None

    current_artifact, baseline_artifact = newest(all_artifacts), newest(all_prior_artifacts)
    artifacts = [current_artifact] if current_artifact else []
    prior_artifacts = [baseline_artifact] if baseline_artifact else []
    historical_artifacts = [item for item in all_artifacts if item is not current_artifact]
    historical_priors = [item for item in all_prior_artifacts if item is not baseline_artifact]

    def stamp(item, *keys):
        return max((value for key in keys if type(value := item.get(key)) in (int, float)), default=0)

    discoveries = [item for item in observations if item.get("kind") == "tools_discovered"]
    directory_payload = discoveries[-1].get("payload", {}) if discoveries else {}
    directory = directory_payload.get("tools", [])
    paged_directory = "directory_version" in directory_payload
    last_discovery = max((stamp(item, "created_at") for item in discoveries), default=0)
    last_evidence = max([stamp(item, "updated_at", "completed_at") for item in all_completed]
                        + [stamp(item, "created_at") for item in artifacts], default=0)
    schema_request = tool_focus.schema_request if tool_focus is not None else None
    directory_consumed = paged_directory and last_evidence > last_discovery
    notes = [_select(item, ("id", "source_call_id", "quote", "summary", "model_derived", "verification",
                            "observed_at", "source_revision", "revision", "reference_only", "source_path",
                            "source_offset", "quote_format")) for item in snapshot.get("working_notes", [])]
    derived_notes = [item for item in notes if item.get("model_derived") or "summary" in item]
    omitted = {"tools": 0, "discovered_tools": 0, "memory": 0, "result_bytes": 0,
               "artifact_bytes": sum(len(_json(item.get("content")).encode("utf-8"))
                                     for item in historical_artifacts + historical_priors),
               "evidence_pages": 0, "evidence_page_bytes": 0,
               "observations": len(observations) - len(core_observations), "working_notes": 0}
    data = {
        "evaluated_at": datetime.now(timezone.utc).isoformat(),
        "task": _select(snapshot, ("agent_task_id", "revision", "goal", "constraints", "success_criteria")),
        "run": _select(snapshot.get("run", {}), ("agent_run_id", "revision", "state", "reason", "steps_used",
                                                 "tool_calls_used", "budget")),
        "pending_calls": [_select(call, ("tool_call_id", "tool_id", "tool_version", "state", "arguments",
                                         "effect", "executor", "error")) for call in pending],
        "unresolved_calls": [_select(call, ("tool_call_id", "tool_id", "agent_run_id", "revision", "state",
                                            "arguments", "effect", "executor", "error"))
                             for call in snapshot.get("unresolved_calls", [])],
        "observations": [_select(item, ("kind", "payload", "created_at")) for item in core_observations],
        "working_notes": [item for item in notes if item not in derived_notes],
        "tool_results": [dict(_select(call, ("tool_call_id", "tool_id", "state")),
                              observed_at=observed_at(call),
                              result_ref=call["tool_call_id"] if call.get("tool_id") not in _READERS else None,
                              **({"arguments_meta": {"bytes": len(_json(call["arguments"]).encode("utf-8"))}}
                                 if call.get("arguments") else {}),
                              **({"error": call["error"]} if call.get("error") is not None else {}),
                              truncated=True) for call in completed],
        "evidence_sources": evidence_sources, "evidence_pages": [], "evidence_selections": [],
        "repeated_reads": [],
        # The small identifier index is the recovery path for unsuccessful
        # searches. Optional display names must not make that path disappear.
        "available_namespaces": {"items": [_select(item, ("namespace", "tool_count"))
                                             for item in (namespace_index or {}).get("items", [])],
                                 "omitted": (namespace_index or {}).get("omitted", 0)},
        "discovered_tools": [_select(item, ("tool_id", "description", "description_truncated", "version", "effect", "executor", "source"))
                             for item in directory] if paged_directory and not directory_consumed else [],
        "discovery_page": ({**_select(directory_payload, ("query", "namespace", "offset", "next_offset", "total", "directory_version")),
                            **({"consumed": True} if directory_consumed else {})}
                           if paged_directory else None),
        "artifacts": [dict(_select(item, ("artifact_id", "version", "revision", "agent_run_id", "kind", "title")),
                           content_ref=item.get("artifact_id"), saved=True, truncated=True) for item in artifacts],
        "prior_artifacts": [dict(_select(item, ("artifact_id", "version", "revision", "agent_run_id", "kind", "title")),
                                 content_ref=item.get("artifact_id"), reference_only=True, truncated=True) for item in prior_artifacts],
        "artifact_focus": ({**_select(current_artifact or baseline_artifact, ("artifact_id", "version")),
                            "role": "current" if current_artifact else "revision_baseline"}
                           if current_artifact or baseline_artifact else None),
        "artifact_history": [dict(_select(item, ("artifact_id", "version", "revision", "kind")), reference_only=prior)
                             for items, prior in ((historical_artifacts, False), (historical_priors, True)) for item in items],
        "tools": [], "memory": [], "omitted": omitted,
    }
    if snapshot.get("delegation_enabled"):
        data["delegation_enabled"] = True
    if snapshot.get("delegation"):
        data["delegation"] = _select(snapshot["delegation"], ("delegation_key", "allowed_tools", "inputs"))
        data["coordinator_requirements"] = snapshot["coordinator_requirements"]
    if snapshot.get("delegations"):
        data["delegations"] = [_select(item, ("delegation_key", "child_task_id", "goal", "constraints", "success_criteria", "status", "child_state",
                                               "child_reason", "outputs", "review_reason"))
                               for item in snapshot["delegations"]]
    if snapshot.get("shared_budget"):
        data["shared_budget"] = snapshot["shared_budget"]
    if snapshot.get("task_revision_receipt") is not None:
        data["task_revision_receipt"] = _revision_context(snapshot["task_revision_receipt"], data["task"])
    if reader_failures:
        omitted["reader_failures"] = len(reader_failures) - int(latest_reader_failure is not None)
        for call, entry in zip(completed, data["tool_results"]):
            if call is latest_reader_failure:
                entry["arguments"] = _select(call.get("arguments", {}), ("tool_call_id", "artifact_id", "paths", "offset", "max_chars", "version"))
    if type(run.get("budget", {}).get("max_steps")) is int:
        data["run"]["remaining_steps"] = max(0, run["budget"]["max_steps"] - run.get("steps_used", 0))
    for call, entry in zip(completed, data["tool_results"]):
        if any(item["source_kind"] == "result" and item["source_ref"] == call["tool_call_id"] for item in evidence_sources):
            continue
        links = source_links(call.get("result"))
        if links["urls"] or links["omitted"]:
            entry["source_links"] = links

    def fits():
        return estimate_input_tokens(_messages(data)) <= ceiling

    if not fits():
        raise ContextBudgetExceeded()

    # The counters and final metadata may grow by a few bytes as evidence is
    # compacted. Reserve a small fixed margin rather than shaving core fields.
    evidence_ceiling = ceiling - 128

    def append_if_fits(bucket, item):
        bucket.append(item)
        if estimate_input_tokens(_messages(data)) > evidence_ceiling:
            bucket.pop()
            return False
        return True

    discovery_sequence = [item for item in discoveries if stamp(item, "created_at") > max(last_evidence, latest_reply)]
    if len(discovery_sequence) >= 2:
        seen_contracts, history, unchanged = set(), [], 0
        for item in discovery_sequence:
            payload = item.get("payload", {})
            contracts = {(tool.get("tool_id"), tool.get("version")) for tool in payload.get("tools", [])}
            new_count = len(contracts - seen_contracts)
            seen_contracts.update(contracts)
            unchanged = 0 if new_count else unchanged + 1
            query = payload.get("query", "")
            bounded_query = query.encode("utf-8")[:160].decode("utf-8", errors="ignore")
            history.append({"query": bounded_query, "query_truncated": query != bounded_query,
                            "namespace": payload.get("namespace"), "offset": payload.get("offset", 0),
                            "new_contracts": new_count})
        data["discovery_progress"] = {"requests_since_last_progress": len(discovery_sequence),
                                      "consecutive_no_new_tools": unchanged, "recent_queries": history[-3:]}
        if estimate_input_tokens(_messages(data)) > evidence_ceiling:
            data["discovery_progress"]["recent_queries"] = history[-1:]
        if estimate_input_tokens(_messages(data)) > evidence_ceiling:
            data.pop("discovery_progress")
            omitted["discovery_progress"] = 1

    def append_visible_page(page):
        if append_if_fits(data["evidence_pages"], page):
            return True
        # Durable notes and the reader schema take priority over page size.
        # A smaller visible prefix retains the original retrieved range and an
        # exact continuation offset; it is never labelled a complete page.
        low, high, best = 1, len(page["text"]), None
        while low <= high:
            size = (low + high) // 2
            candidate = {**page, "text": page["text"][:size], "compacted": True,
                         "next_offset": page["offset"] + size,
                         "has_more": page["offset"] + size < page["total_chars"],
                         "retrieved_next_offset": page.get("retrieved_next_offset", page["next_offset"])}
            if append_if_fits(data["evidence_pages"], candidate):
                data["evidence_pages"].pop()
                best, low = candidate, size + 1
            else:
                high = size - 1
        if best is None:
            return False
        data["evidence_pages"].append(best)
        omitted["evidence_page_bytes"] += len(page["text"].encode("utf-8")) - len(best["text"].encode("utf-8"))
        return True

    descriptors = []
    for tool in tool_descriptors:
        descriptor = _select(tool, ("tool_id", "description", "version", "effect", "executor", "source",
                                    "input_schema", "output_schema"))
        for key in ("input_schema", "output_schema"):
            if descriptor.get(key) is not None:
                descriptor[key] = _compact_schema(descriptor[key])
            elif key in descriptor:
                descriptor.pop(key)
        descriptors.append(descriptor)

    # A discovery is an explicit request to load schemas for the next decision.
    # After a tool returns, its observations instead take precedence over the
    # same schemas left over from the preceding decision.
    schema_first = not all_completed or last_discovery > last_evidence or tool_focus is not None
    candidate_ids = [item["tool_id"] for item in directory]
    selected_discovery = named_discovery_tool(directory_payload)
    candidate_ids.sort(key=lambda identity: identity != selected_discovery)
    candidates = [item for identity in candidate_ids for item in descriptors if item["tool_id"] == identity]
    deferred_directory = False
    # A requested contract is needed to correct the next action. Directory
    # descriptions are optional navigation metadata, even for a large source.
    preferred_ids = [tool_focus.tool_id] if tool_focus is not None else []
    preferred = [item for identity in preferred_ids for item in descriptors if item["tool_id"] == identity]
    attempted_tools = set()
    def add_descriptors(values):
        for descriptor in values:
            if descriptor["tool_id"] in attempted_tools:
                continue
            attempted_tools.add(descriptor["tool_id"])
            if not append_if_fits(data["tools"], descriptor):
                omitted["tools"] += 1

    handled_results, handled_artifacts, complete_results = set(), set(), set()
    def mark_complete(identity):
        identities = {identity, *(item["result_ref"] for item in repeated_reads.get(identity, []))}
        complete_results.update(identities)
        kept = [item for item in data["evidence_sources"]
                if not (item["source_kind"] == "result" and item["source_ref"] in identities)]
        removed = len(data["evidence_sources"]) - len(kept)
        if removed:
            omitted["evidence_sources"] = omitted.get("evidence_sources", 0) + removed
            data["evidence_sources"] = kept

    def add_full_result(call, entry):
        if call["tool_call_id"] in complete_results:
            return True
        entry.update(result=call.get("result"), truncated=False)
        if estimate_input_tokens(_messages(data)) <= evidence_ceiling:
            mark_complete(call["tool_call_id"])
            handled_results.add(call["tool_call_id"])
            return True
        entry.pop("result")
        entry["truncated"] = True
        return False

    def add_result(call, entry):
        identity = call["tool_call_id"]
        if identity in handled_results:
            return
        handled_results.add(identity)
        original = call.get("result")
        byte_count = len(_json(original).encode("utf-8"))
        # Errors are independent of result representation. In particular a
        # failed/unknown source never becomes a successful partial result.
        entry["error"] = call.get("error")
        if add_full_result(call, entry):
            return
        for attempt, (string_bytes, array_items, max_nodes) in enumerate(
                ((192, 8, 256), (96, 4, 128), (48, 2, 64), (96, 0, 128), (0, 0, 32))):
            view = original if byte_count <= 1024 or (deferred_directory and byte_count <= 2400 and attempt == 0) else _result_view(
                original, string_bytes=string_bytes, array_items=array_items, max_nodes=max_nodes, decode_mcp=True)
            complete = view == original
            key = "result" if complete else "result_view"
            entry[key] = view
            entry["truncated"] = not complete
            if estimate_input_tokens(_messages(data)) <= evidence_ceiling:
                if complete:
                    mark_complete(identity)
                omitted["result_bytes"] += max(0, byte_count - len(_json(view).encode("utf-8")))
                return
            entry.pop(key)
        entry["truncated"] = True
        omitted["result_bytes"] += byte_count
        if call.get("error") is None and estimate_input_tokens(_messages(data)) > evidence_ceiling:
            entry.pop("error", None)

    def add_artifact(original, entry):
        identity = (original["artifact_id"], original.get("version"))
        if identity in handled_artifacts:
            return
        handled_artifacts.add(identity)
        reference = dict(entry)
        body = original.get("content")
        if isinstance(body, dict) and set(body) == {"title", "content"}:
            entry.update(title=body["title"], content_envelope_unwrapped=True)
            body = body["content"]
        entry.update(content=body, truncated=False)
        if estimate_input_tokens(_messages(data)) > evidence_ceiling:
            entry.pop("content")
            entry["truncated"] = True
            raw_bytes = len(_json(body).encode("utf-8"))
            for string_bytes, array_items in ((384, 8), (192, 8), (96, 4), (48, 2), (96, 0)):
                view = _result_view(body, string_bytes=string_bytes, array_items=array_items)
                entry["content_view"] = view
                if estimate_input_tokens(_messages(data)) <= evidence_ceiling:
                    omitted["artifact_bytes"] += max(0, raw_bytes - len(_json(view).encode("utf-8")))
                    return
                entry.pop("content_view")
            omitted["artifact_bytes"] += raw_bytes
            # Optional envelope metadata belongs to the same budgeted attempt.
            # Keeping its title after every body projection failed can overflow
            # a context whose requested source page already filled the workset.
            entry.clear()
            entry.update(reference)

    latest_call_at = max((stamp(item, "updated_at", "completed_at") for item in all_completed), default=0)
    latest_artifact = max(artifacts, key=lambda item: stamp(item, "created_at"), default=None)
    artifact_first = latest_artifact is not None and stamp(latest_artifact, "created_at") >= max(latest_call_at, last_discovery)
    latest_call = max(all_completed, key=lambda item: stamp(item, "updated_at", "completed_at"), default=None)
    read_target = (reader_source(latest_call) if latest_call and latest_call.get("state") in {"succeeded", "failed"}
                   and latest_call.get("tool_id") == "agent.read_result" else None)
    focused_result = next((item for item in completed if read_target
                           and read_target.get("source_kind") == "result"
                           and item["tool_call_id"] == read_target.get("source_ref")), None)
    # The latest read expresses which receipt the model is inspecting. A newer
    # unrelated receipt must not displace that source or pin its old schema.
    newest_result = focused_result or (completed[-1] if completed else None)
    reading_result_page = (latest_call is not None and latest_call.get("state") == "succeeded"
                           and latest_call.get("tool_id") == "agent.read_result"
                           and isinstance((latest_call.get("result") or {}).get("data", {}).get("text"), str))
    reading_artifact_page = (latest_call is not None and latest_call.get("state") == "succeeded"
                             and latest_call.get("tool_id") == "agent.read_artifact"
                             and isinstance((latest_call.get("result") or {}).get("data", {}).get("text"), str))
    result_first = (newest_result is not None and not schema_first
                    and (stamp(newest_result, "updated_at", "completed_at") >= latest_call_at
                         or latest_call.get("tool_id") == "agent.read_result"))
    # Original receipts remain readable while browsing new capabilities. Do
    # not spend the final schema bytes on a previously used external tool and
    # then reject the reader advertised by the evidence references.
    primary_reader = ("agent.read_artifact" if evidence_pages and evidence_pages[0]["source_kind"] == "artifact"
                      else "agent.read_result")
    # Allocate a complete candidate contract and recent original inputs as one
    # workset. A broad candidate is still only visible capability, not selected
    # or authorized. Optional directory prose cannot split this usable pair.
    prior_producers = {source.get("tool_id") for item in prior_artifacts for source in item.get("sources", [])}
    completed_tools = {item.get("tool_id") for item in completed if item.get("state") == "succeeded"}
    continuation_contracts = [item for item in descriptors if item["tool_id"] in prior_producers - completed_tools]
    correction_contracts = [item for item in descriptors if correction_call is not None
                            and item["tool_id"] == correction_call["tool_id"]
                            and item.get("version") == correction_call.get("tool_version")]
    continue_producer = (not artifacts and not discoveries and not reading_result_page
                         and (latest_call is None or latest_call.get("tool_id") not in _READERS))
    if schema_first or correction_contracts or (continue_producer and continuation_contracts):
        next_contracts = preferred or correction_contracts or (continuation_contracts[:1] if continue_producer else []) or candidates[:1]
        source_candidates, source_tools = [], set()
        for call in reversed(completed):
            size = len(_json(call.get("result")).encode("utf-8"))
            if (call.get("state") == "succeeded" and call.get("tool_id") not in _READERS
                    and call.get("tool_id") not in source_tools and size <= 2400):
                source_candidates.append((call, size))
                source_tools.add(call.get("tool_id"))
        fields = set().union(*(_input_fields(item.get("input_schema", {})) for item in next_contracts))
        # Stable ties retain recency. Matching fields only prioritize display;
        # they never prove input validity or authorize a tool action.
        source_candidates.sort(key=lambda item: -len(fields & _result_fields(item[0].get("result"))))
        sources, source_bytes = [], 0
        for call, size in source_candidates:
            if source_bytes + size <= 4800 and len(sources) < 4:
                sources.append(call)
                source_bytes += size
        progress = data.get("discovery_progress")
        directory_options = [(data["discovered_tools"], data["discovery_page"], progress)]
        if paged_directory and (sources or preferred or correction_contracts or continuation_contracts):
            directory_options.extend([
                ([{"tool_id": item["tool_id"]} for item in directory],
                 {**data["discovery_page"], "descriptions_deferred": True}, progress),
                ([], None, progress)])
            if progress and progress.get("recent_queries"):
                directory_options.append(([], None, {key: value for key, value in progress.items()
                                                     if key != "recent_queries"}))
        chosen = None
        if next_contracts:
            recovery = [item for item in descriptors if item["tool_id"] == primary_reader
                        and item["tool_id"] not in {tool["tool_id"] for tool in next_contracts}]
            contracts_options = ([next_contracts + recovery] if recovery else []) + [next_contracts]
            compact_contracts = []
            if correction_contracts and next_contracts == correction_contracts:
                # Only a dispatched, unchanged contract being corrected may
                # defer repeated description prose. First-use semantics stay.
                compact_contracts = [{**item, "description": item.get("description", "")[:16],
                                      "description_omitted": True} for item in next_contracts]
                contracts_options.append(compact_contracts)
            for count in range(len(sources), -1, -1):
                inputs = {call["tool_call_id"]: call for call in sources[:count]}
                complete_ids = set(inputs) | {item["result_ref"] for identity in inputs
                                               for item in repeated_reads.get(identity, [])}
                result_probe = [{**entry, "result": inputs[entry["tool_call_id"]].get("result"), "truncated": False}
                                if entry["tool_call_id"] in inputs else dict(entry) for entry in data["tool_results"]]
                source_probe = [item for item in data["evidence_sources"]
                                if not (item["source_kind"] == "result" and item["source_ref"] in complete_ids)]
                for contracts in contracts_options:
                    for option, (visible_directory, page, visible_progress) in enumerate(directory_options):
                        probe = {**data, "tools": contracts, "tool_results": result_probe,
                                 "evidence_sources": source_probe, "discovered_tools": visible_directory,
                                 "discovery_page": page}
                        if correction_call is not None and correction_contracts:
                            entry = result_probe[completed.index(correction_call)]
                            arguments, references = _argument_context(correction_call.get("arguments", {}), probe)
                            entry.update(arguments_view=arguments, argument_refs=references)
                            entry.pop("arguments_meta", None)
                            for source_entry in result_probe:
                                if source_entry.get("truncated") is False:
                                    source_entry.pop("arguments_meta", None)
                            if contracts == compact_contracts:
                                probe["prior_artifacts"] = [_select(item, ("artifact_id", "version", "revision", "reference_only"))
                                                            for item in data["prior_artifacts"]]
                                if not artifacts:
                                    # The sole prior artifact already identifies
                                    # the revision baseline, including version.
                                    probe["artifact_focus"] = None
                        if visible_progress is not None:
                            probe["discovery_progress"] = visible_progress
                        # Optional capability help may yield to the current
                        # contract. Existing child state/obligations never yield.
                        defer_delegation = False
                        if (estimate_input_tokens(_messages(probe)) > evidence_ceiling and
                                probe.get("delegation_enabled") and not probe.get("delegations")):
                            probe.pop("delegation_enabled")
                            probe["omitted"] = {**omitted, "delegation_help": 1}
                            defer_delegation = True
                        if (estimate_input_tokens(_messages(probe)) > evidence_ceiling
                                and option == len(directory_options) - 1):
                            # Recoverable display metadata yields before raw
                            # inputs or a first-use contract. Keep source IDs,
                            # errors, clocks and baseline version/revision.
                            probe["omitted"] = {**probe["omitted"], "context_metadata": 1}
                            probe["tool_results"] = [{key: value for key, value in entry.items()
                                                      if key != "arguments_meta" or entry.get("state") != "succeeded"}
                                                     for entry in probe["tool_results"]]
                            if not artifacts:
                                probe["prior_artifacts"] = [_select(item, ("artifact_id", "version", "revision", "reference_only"))
                                                            for item in data["prior_artifacts"]]
                                probe["artifact_focus"] = None
                        if estimate_input_tokens(_messages(probe)) <= evidence_ceiling:
                            chosen = (count, option, visible_directory, page, visible_progress, contracts, defer_delegation, probe)
                            break
                    if chosen is not None:
                        break
                if chosen is not None:
                    break
        if chosen is not None:
            count, option, visible_directory, page, visible_progress, contracts, defer_delegation, probe = chosen
            for key in ("tool_results", "prior_artifacts", "available_namespaces", "artifact_focus"):
                data[key] = probe[key]
            if probe["omitted"].get("context_metadata"):
                omitted["context_metadata"] = 1
            if defer_delegation:
                data.pop("delegation_enabled", None)
                omitted["delegation_help"] = 1
            deferred_directory = option > 0
            omitted["discovered_tools"] += max(0, len(data["discovered_tools"]) - len(visible_directory))
            data["discovered_tools"], data["discovery_page"] = visible_directory, page
            if visible_progress is not None:
                omitted["discovery_queries"] = len(progress.get("recent_queries", [])) - len(visible_progress.get("recent_queries", []))
                data["discovery_progress"] = visible_progress
            # Commit together after the combined budget probe succeeded.
            data["tools"].extend(contracts)
            attempted_tools.update(item["tool_id"] for item in contracts)
            for source in sources[:count]:
                entry = data["tool_results"][completed.index(source)]
                entry.update(result=source.get("result"), truncated=False)
                mark_complete(source["tool_call_id"])
                handled_results.add(source["tool_call_id"])
        else:
            add_descriptors(next_contracts)
    if completed or evidence_pages:
        add_descriptors([item for item in descriptors if item["tool_id"] == primary_reader])
    elif artifacts or prior_artifacts:
        add_descriptors([item for item in descriptors if item["tool_id"] == "agent.read_artifact"])
    if artifact_first:
        add_artifact(latest_artifact, data["artifacts"][artifacts.index(latest_artifact)])
    prior_first = False
    prior_source = (reader_source(latest_call) if latest_call and latest_call.get("state") == "succeeded"
                    and latest_call.get("tool_id") == "agent.read_artifact" else None)
    prior_target = next((item for item in all_prior_artifacts if prior_source
                         and item.get("artifact_id") == prior_source.get("source_ref")
                         and item.get("version") == prior_source.get("version")), None)
    last_schema_request = max((stamp(item, "created_at") for item in observations
                               if item.get("kind") == "tool_error"
                               and item.get("payload", {}).get("code") == "discover_tool_schema_first"), default=0)
    if (not artifacts and prior_artifacts and (latest_call is None or prior_target is not None)
            and max(last_discovery, last_schema_request) <= latest_call_at):
        latest_prior = baseline_artifact
        prior_entry = data["prior_artifacts"][prior_artifacts.index(latest_prior)]
        add_artifact(latest_prior, prior_entry)
        prior_first = "content" in prior_entry or "content_view" in prior_entry
    # Default capability matches do not express a new intent while the model
    # is checking its saved deliverable. Explicit discovery/schema requests
    # still take precedence as before.
    artifact_reading = (latest_call is not None and latest_call.get("tool_id") == "agent.read_artifact"
                        and latest_call.get("state") == "succeeded")
    artifact_focused = ((artifact_first or prior_first) and
                        max(last_discovery, last_schema_request) <= max(latest_call_at, stamp(latest_artifact or {}, "created_at")))
    if artifact_focused:
        add_descriptors([item for item in descriptors if item["tool_id"] == "agent.read_artifact"])
        if artifact_reading or not discoveries:
            active_tool_ids = ()
    if schema_first and paged_directory:
        # Broad discovery candidates follow an explicit request. Otherwise the
        # same directory can repeatedly evict the very schema being corrected.
        add_descriptors(candidates[:1] if deferred_directory else candidates)
    if active_tool_ids:
        add_descriptors([item for identity in list(active_tool_ids)[:1] for item in descriptors
                         if item["tool_id"] == identity and not (result_first and not schema_request and identity == newest_result["tool_id"]
                                                                 and newest_result.get("state") == "succeeded")])
    elif schema_first and not paged_directory:
        add_descriptors(descriptors)
    focused_page_added = False
    if (reading_result_page or reading_artifact_page) and evidence_pages and not schema_first and not artifact_first and not prior_first:
        # Serve the latest read before older selections, previews and drafts.
        # A complete original is preferable when it fits; otherwise expose an
        # exact prefix with a continuation offset, not a misleading overview.
        # Artifact pages retain the requested version and receipt identity;
        # a current draft cannot substitute for a read of another version.
        # Preserve useful working notes only when a real source excerpt can
        # coexist. Notes must never consume the final bytes of a requested read.
        page = evidence_pages[0]
        excerpt = {**page, "text": page["text"][:256], "compacted": True,
                   "next_offset": page["offset"] + min(256, len(page["text"])),
                   "retrieved_next_offset": page["next_offset"], "has_more": True}
        data["evidence_pages"].append(excerpt)
        remaining_notes = []
        for note in derived_notes:
            if not append_if_fits(data["working_notes"], note):
                remaining_notes.append(note)
        data["evidence_pages"].pop()
        derived_notes = remaining_notes
        entry = data["tool_results"][completed.index(focused_result)] if focused_result is not None else None
        if entry is None or not add_full_result(focused_result, entry):
            focused_page_added = append_visible_page(evidence_pages[0])
    # Fulfil the requested read before filling a workset with previews. Prefer
    # the complete original when it fits: the selection envelope is sometimes
    # larger than the source, and the model needs the actual values next turn.
    for selection in evidence_selections:
        if selection["source_ref"] in complete_results:
            continue
        source = next((call for call in completed if call["tool_call_id"] == selection["source_ref"]), None)
        if source is not None and add_full_result(source, data["tool_results"][completed.index(source)]):
            continue
        if not append_if_fits(data["evidence_selections"], selection):
            omitted["evidence_selections"] = omitted.get("evidence_selections", 0) + 1
    if artifact_focused and not schema_first:
        # A saved deliverable does not replace the original inputs used to
        # verify it. Retain a bounded set of recent small receipts before
        # unused contracts; smaller complete sources beat larger previews.
        # Requested schemas, reader contracts and requested pages precede this.
        small_candidates = []
        for call, entry in reversed(list(zip(completed, data["tool_results"]))):
            size = len(_json(call.get("result")).encode("utf-8"))
            if (call.get("state") == "succeeded" and call.get("tool_id") not in _READERS
                    and call["tool_call_id"] not in handled_results and size <= 2400):
                small_candidates.append((size, call, entry))
                if len(small_candidates) == 4:
                    break
        small_bytes = 0
        for size, call, entry in sorted(small_candidates, key=lambda item: item[0]):
            if small_bytes + size <= 4800 and add_full_result(call, entry):
                small_bytes += size
    # A large newest receipt must not displace the small state that supplied
    # its inputs. This is optional history, subordinate to the requested read.
    if not schema_first and not artifact_first and not prior_first and not reading_result_page:
        small_bytes = 0
        for call, entry in reversed(list(zip(completed, data["tool_results"]))):
            size = len(_json(call.get("result")).encode("utf-8"))
            if (call.get("state") == "succeeded" and call.get("tool_id") not in _READERS
                    and small_bytes + size <= 2400):
                add_result(call, entry)
                small_bytes += size
    if result_first and not artifact_first and not prior_first:
        add_result(newest_result, data["tool_results"][completed.index(newest_result)])
    # A rejected call does not supersede the factual source used to prepare it.
    # Keep that recent source alongside the correction schema, ahead of model
    # summaries, optional readers and older results.
    if not artifact_first and not prior_first:
        latest_source = focused_result if reading_result_page else next((call for call in reversed(completed)
                              if call.get("state") == "succeeded" and call.get("tool_id") not in _READERS
                              and call["tool_call_id"] not in handled_results), None)
        if latest_source is not None:
            add_result(latest_source, data["tool_results"][completed.index(latest_source)])
        if current_artifact is not None and not artifact_first:
            # New evidence may require an amendment, so keep the saved draft
            # visible before unrelated optional schemas consume the remainder.
            add_artifact(current_artifact, data["artifacts"][0])
    if deferred_directory:
        add_descriptors(candidates[1:])
    if not schema_first or active_tool_ids:
        add_descriptors([item for item in descriptors if item["tool_id"] in _READERS
                         and (not evidence_pages or item["tool_id"] == primary_reader)])

    # Bounded summaries are a recoverable workset, still subordinate to raw
    # business state and requested schemas. Keep their unverified provenance.
    for identity, receipts in repeated_reads.items():
        if not append_if_fits(data["repeated_reads"], {"same_data_as": identity, "receipts": list(reversed(receipts))}):
            omitted["repeated_reads"] = omitted.get("repeated_reads", 0) + len(receipts)
    for note in derived_notes:
        if not append_if_fits(data["working_notes"], note):
            omitted["working_notes"] += 1

    if namespace_index is not None:
        previous = data["available_namespaces"]
        data["available_namespaces"] = deepcopy(namespace_index)
        if estimate_input_tokens(_messages(data)) > evidence_ceiling:
            data["available_namespaces"] = previous

    # A reader still exposes its latest page after the structural overview.
    # Otherwise a compact overview could make a valid continuation invisible.
    latest_selection = (latest_call is not None and latest_call.get("state") == "succeeded"
                        and latest_call.get("tool_id") == "agent.read_result"
                        and (latest_call.get("result") or {}).get("data", {}).get("representation") == "json_selection")
    first_page_count = int(bool(evidence_pages) and not latest_selection and not artifact_first and not prior_first
                           and (not result_first or latest_call.get("tool_id") == "agent.read_result"))
    for index, page in enumerate(evidence_pages[:first_page_count]):
        if index == 0 and focused_page_added:
            continue
        if page["source_kind"] == "result" and page["source_ref"] in complete_results:
            continue
        if not append_visible_page(page):
            has_source_view = any(item.get("result_ref") == page.get("source_ref")
                                  and ("result" in item or "result_view" in item) for item in data["tool_results"])
            if index == 0 and not schema_first and not has_source_view:
                raise ContextBudgetExceeded("当前约束、摘录与证据读取说明占满上下文，请缩小任务范围。")
            omitted["evidence_pages"] += 1

    if (not schema_first or active_tool_ids) and evidence_pages:
        add_descriptors([item for item in descriptors if item["tool_id"] in _READERS and item["tool_id"] != primary_reader])

    add_descriptors(descriptors)
    for item in ([] if paged_directory else directory):
        if not append_if_fits(data["discovered_tools"], _select(item, ("tool_id",))):
            omitted["discovered_tools"] += 1

    # Recent results get first access to optional bytes. A result's identity is
    # always retained, even when only a bounded preview fits in this invocation.
    for call, entry in reversed(list(zip(completed, data["tool_results"]))):
        add_result(call, entry)

    artifact_entries = list(zip(prior_artifacts, data["prior_artifacts"])) + list(zip(artifacts, data["artifacts"]))
    for original, artifact in reversed(artifact_entries):
        add_artifact(original, artifact)

    for page in evidence_pages[first_page_count:]:
        if page["source_kind"] == "result" and page["source_ref"] in complete_results:
            continue
        if not append_if_fits(data["evidence_pages"], page):
            omitted["evidence_pages"] += 1

    # Once used, directory descriptions cannot crowd out the evidence they
    # helped obtain. The versioned pointer remains in core for rediscovery.
    if directory_consumed:
        for item in directory:
            value = _select(item, ("tool_id", "description", "description_truncated", "version", "effect", "executor", "source"))
            if not append_if_fits(data["discovered_tools"], value):
                omitted["discovered_tools"] += 1

    for item in memory:
        if isinstance(item, dict):
            value = _select(item, ("id", "memory_id", "kind", "text", "source", "sources", "revision", "expires_at",
                                   "source_kind", "source_id", "quote", "valid_from", "valid_until", "scope", "scope_id"))
        elif isinstance(item, str):
            value = {"text": item}
        else:
            omitted["memory"] += 1
            continue
        if not append_if_fits(data["memory"], value):
            omitted["memory"] += 1

    messages = _messages(data)
    estimated = estimate_input_tokens(messages)
    if estimated > ceiling:
        raise ContextBudgetExceeded()
    return DecisionContext(messages, estimated, dict(omitted))


class DecisionProvider:
    def __init__(self, completion_fn=None, model=None, max_output_bytes=32768, max_output_tokens=2048):
        if type(max_output_tokens) is not int or not 1 <= max_output_tokens <= 32768:
            raise ValueError("max_output_tokens must be an integer between 1 and 32768")
        if type(max_output_bytes) is not int or max_output_bytes <= 0:
            raise ValueError("max_output_bytes must be a positive integer")
        self.completion_fn = completion_fn
        self.model = model
        self.max_output_bytes = max_output_bytes
        self.max_output_tokens = max_output_tokens

    def decide(self, messages):
        if isinstance(messages, DecisionContext):
            messages = messages.messages
        completion = self.completion_fn
        if completion is None:
            if not os.getenv("DASHSCOPE_API_KEY", "").strip() or not os.getenv("DASHSCOPE_BASE_URL", "").strip():
                raise DecisionError("provider_not_configured", "模型服务尚未配置，未发出模型请求。")
            from backend.assistant.api import completion
        try:
            kwargs = {"structured": True}
            if self.completion_fn is None:
                kwargs["max_tokens"] = self.max_output_tokens
            content = completion(messages, self.model or os.getenv("AGENT_MODEL", "qwen3.8-max"), **kwargs)
        except Exception:
            raise DecisionError("provider_error", "模型服务调用失败，本轮没有可执行决策。") from None
        return parse_decision(content, max_output_bytes=self.max_output_bytes)
