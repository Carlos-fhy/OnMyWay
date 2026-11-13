"""One durable goal-driven loop; domain logic remains behind tools."""
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import datetime, timezone
import hashlib
import json
import re
import sqlite3
import threading
from uuid import uuid4

from fastapi import HTTPException

from backend.agent.decision import (ContextBudgetExceeded, DecisionError, build_context, observed_at,
                             reader_source, source_links)
from backend.agent.focus import current_observations, named_discovery_tool, resolve_tool_focus
from backend.agent.tools import ToolDefinition, ToolError
from backend.agent.delivery import DeliveryError, validate_artifact, validate_completion, validate_question
from backend.agent.delegation_runtime import DelegationRuntime


def personal_context(snapshot):
    from backend.memory.personal import recall
    return recall(snapshot["goal"], at=datetime.now(timezone.utc).isoformat(),
                  session_id=snapshot["conversation_id"], limit=6, budget=1800)["items"]


class AgentRuntime(DelegationRuntime):
    def __init__(self, store, catalog, provider, *, memory_reader=personal_context,
                 max_input_tokens=12000, lease_seconds=90, call_executor=None, stop_event=None,
                 discovery_refresh=None, artifact_reviewer=None, delegation_enabled=False):
        self.store = store
        self.catalog = catalog
        self.provider = provider
        self.memory_reader = memory_reader
        self.max_input_tokens = max_input_tokens
        self.lease_seconds = lease_seconds
        self.call_executor = call_executor
        self.stop_event = stop_event
        self.discovery_refresh = discovery_refresh
        self.artifact_reviewer = artifact_reviewer
        self.delegation_enabled = delegation_enabled
        self._active_task = ContextVar("agent_evidence_task", default=None)
        page_fields = {"offset": {"type": "integer", "minimum": 0},
                       "max_chars": {"type": "integer", "minimum": 1, "maximum": 3000}}
        self.catalog.register(ToolDefinition("agent", "read_result", "按result_ref读缺失事实：结构字段优先用paths（原result的JSON Pointer，非decoded_path）；否则offset/max_chars字符分页，按next_offset续读。最多2400 UTF-8字节，勿重读。",
            {"type": "object", "properties": {"tool_call_id": {"type": "string"}, **page_fields,
                "paths": {"type": "array", "minItems": 1, "maxItems": 8, "uniqueItems": True,
                          "items": {"type": "string", "maxLength": 512}}},
             "required": ["tool_call_id"], "additionalProperties": False,
             "allOf": [{"if": {"required": ["paths"]}, "then": {
                 "not": {"anyOf": [{"required": ["offset"]}, {"required": ["max_chars"]}]}}}]}), self._read_result)
        self.catalog.register(ToolDefinition("agent", "read_artifact", "按 content_ref/version 读取成果缺失段；旧成果仅供参考。offset 为字符，页最多 2400 UTF-8 字节，用 next_offset 续读。",
            {"type": "object", "properties": {"artifact_id": {"type": "string"}, "version": {"type": "integer", "minimum": 1}, **page_fields},
             "required": ["artifact_id"], "additionalProperties": False}), self._read_artifact)

    @staticmethod
    def _page(value, args):
        text = json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
        offset, size = args.get("offset", 0), args.get("max_chars", 2000)
        offset = min(offset, len(text))
        page = text[offset:offset + size].encode("utf-8")[:2400].decode("utf-8", errors="ignore")
        end = offset + len(page)
        return {"text": page, "offset": offset, "next_offset": end,
                "has_more": end < len(text), "total_chars": len(text)}

    @staticmethod
    def _selection(value, args):
        from backend.agent.result_reader import SelectionError
        paths = args.get("paths")
        if (not isinstance(paths, list) or not 1 <= len(paths) <= 8
                or any(not isinstance(path, str) or len(path) > 512 for path in paths)
                or len(set(paths)) != len(paths) or "offset" in args or "max_chars" in args):
            raise ToolError("invalid_selection", "paths须为1至8个不同JSON Pointer，不能同时使用分页参数。")
        values = {}
        for path in paths:
            if (path and not path.startswith("/")) or re.search(r"~(?![01])", path):
                raise ToolError("invalid_selection", "路径须为JSON Pointer；斜杠和波浪号分别转义为~1和~0。")
            node, parent_path = value, ""
            for segment in path.split("/")[1:] if path else []:
                key = segment.replace("~1", "/").replace("~0", "~")
                if isinstance(node, dict) and key in node:
                    node = node[key]
                elif isinstance(node, list) and re.fullmatch(r"0|[1-9][0-9]*", key) and int(key) < len(node):
                    node = node[int(key)]
                else:
                    raise SelectionError("selection_path_not_found", "路径不在原始result中；details给出最近有效父路径及真实子路径，不使用decoded_path或分页包装路径。",
                                         paths, [(parent_path, node)], original=value,
                                         failed_path=path, failed_segment=key)
                parent_path += "/" + segment
            values[path] = node
        encoded = json.dumps(values, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
        if len(encoded.encode("utf-8")) > 2400:
            raise SelectionError("selection_too_large", "所选字段超过2400 UTF-8字节；按details中的子路径/大小缩小选择。标量长文请使用字符分页。",
                                 paths, list(values.items()))
        original = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
        return {"representation": "json_selection", "values": values,
                "source_sha256": hashlib.sha256(original.encode("utf-8")).hexdigest()}

    def _owned_call(self, call_id):
        try:
            call = self.store.get_call(call_id)
            owner = self.store.get_run(call["agent_run_id"])["agent_task_id"]
            receiver = self._active_task.get()
            if not receiver or (owner != receiver and not self.store.evidence_allowed(receiver, "result", call_id)):
                raise HTTPException(404, "source unavailable")
            return call
        except HTTPException:
            raise ToolError("evidence_not_found", "该来源不属于当前任务或不存在") from None

    def _owned_artifact(self, artifact_id, version=None):
        try:
            artifact = self.store.get_artifact(artifact_id, version)
            receiver = self._active_task.get()
            if not receiver or (artifact["agent_task_id"] != receiver and not self.store.evidence_allowed(
                    receiver, "artifact", artifact_id, artifact["version"])):
                raise HTTPException(404, "source unavailable")
            return artifact
        except HTTPException:
            raise ToolError("evidence_not_found", "该成果不属于当前任务或不存在") from None

    def _call_source(self, target):
        visited = set()
        while target not in visited and len(visited) < 32:
            visited.add(target)
            call = self._owned_call(target)
            if call["tool_id"] not in {"agent.read_result", "agent.read_artifact"}:
                return "result", call
            reference = reader_source(call)
            if reference is None or not isinstance(reference.get("source_ref"), str):
                raise ToolError("invalid_evidence_reference", "读取记录缺少有效的原始来源")
            if reference["source_kind"] == "artifact":
                version = reference.get("version")
                if type(version) is not int or version <= 0:
                    raise ToolError("ambiguous_evidence_reference", "旧成果读取没有可核实版本，请按 artifact_id 和明确 version 读取")
                return "artifact", self._owned_artifact(reference["source_ref"], version)
            target = reference["source_ref"]
        raise ToolError("invalid_evidence_reference", "读取来源形成循环，请使用原始 result_ref")

    def _artifact_page(self, artifact, args):
        return {**self._page(artifact, args), "source_kind": "artifact", "source_ref": artifact["artifact_id"],
                "version": artifact["version"], "observed_at": observed_at(artifact)}

    def _read_result(self, args):
        kind, source = self._call_source(args["tool_call_id"])
        if kind == "artifact":
            if "paths" in args:
                raise ToolError("selection_result_required", "paths只读取原始工具result；成果请使用agent.read_artifact。")
            return self._artifact_page(source, args)
        if "paths" in args:
            return {**self._selection(source.get("result"), args),
                    "source_kind": "result", "source_ref": source["tool_call_id"], "source_state": source["state"],
                    "source_tool_id": source["tool_id"], "observed_at": observed_at(source)}
        return {**self._page({"tool_call_id": source["tool_call_id"], "state": source["state"],
                             "result": source.get("result"), "error": source.get("error")}, args),
                "source_kind": "result", "source_ref": source["tool_call_id"], "source_state": source["state"],
                "source_tool_id": source["tool_id"], "observed_at": observed_at(source),
                "source_links": source_links(source.get("result"))}

    def _read_artifact(self, args):
        return self._artifact_page(self._owned_artifact(args["artifact_id"], args.get("version")), args)

    def _publication_sources(self, snapshot, references):
        """Validate same-task provenance, including successful earlier revisions."""
        selected = references or [call["tool_call_id"] for call in snapshot["tool_calls"] if call["state"] == "succeeded"]
        sources = {}
        scope = self._active_task.set(snapshot["agent_task_id"])
        try:
            for reference in selected:
                try:
                    receipt = self._owned_call(reference)
                    if receipt["state"] != "succeeded":
                        raise ToolError("invalid_evidence_reference", "未成功的调用不能作为已核实来源")
                    kind, source = self._call_source(reference)
                    if kind == "result":
                        if source["state"] != "succeeded":
                            raise ToolError("invalid_evidence_reference", "读取回执不能将未知或失败结果变成成功来源")
                        key = (kind, source["tool_call_id"])
                        sources[key] = {"tool_call_id": source["tool_call_id"], "tool_id": source["tool_id"],
                                        "tool_version": source["tool_version"], "observed_at": observed_at(source),
                                        "source_links": source_links(source.get("result"))}
                    else:
                        key = (kind, source["artifact_id"], source["version"])
                        sources[key] = {"tool_call_id": receipt["tool_call_id"], "tool_id": receipt["tool_id"],
                                        "source_kind": kind, "artifact_id": source["artifact_id"], "version": source["version"],
                                        "observed_at": observed_at(source), "read_at": observed_at(receipt)}
                except ToolError:
                    if references:
                        raise
                    # Automatic provenance includes usable sources only; a
                    # successful read of an unknown result is not proof of it.
                    continue
        finally:
            self._active_task.reset(scope)
        return list(sources.values())

    @staticmethod
    def _quote_location(value, quote):
        def strings(node, path=""):
            if isinstance(node, str):
                yield path, node
            elif isinstance(node, dict):
                for key, child in node.items():
                    escaped = str(key).replace("~", "~0").replace("/", "~1")
                    yield from strings(child, path + "/" + escaped)
            elif isinstance(node, list):
                for index, child in enumerate(node):
                    yield from strings(child, path + "/" + str(index))
        if not quote.strip():
            return None
        for path, text in strings(value):
            offset = text.find(quote)
            if offset >= 0:
                return {"source_path": path, "source_offset": offset, "quote_format": "text"}
        # Numeric/boolean facts can be retained as exact JSON fragments, too.
        encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
        offset = encoded.find(quote)
        if offset >= 0:
            return {"source_path": "", "source_offset": offset, "quote_format": "json"}
        return None

    def _validated_notes(self, snapshot, update):
        validated = []
        scope = self._active_task.set(snapshot["agent_task_id"])
        try:
            for note in update.upsert:
                try:
                    receipt = self._owned_call(note.source_call_id)
                    kind, source = self._call_source(note.source_call_id)
                    if receipt["state"] != "succeeded" or (kind == "result" and source["state"] != "succeeded"):
                        raise ToolError("invalid_evidence_reference", "来源调用未成功")
                except ToolError:
                    raise ToolError("invalid_notes_source", "摘录只能引用本任务已成功的来源；读取回执不能证明未知结果。") from None
                value = source.get("result") if kind == "result" else source["content"]
                if note.quote is not None:
                    location = self._quote_location(value, note.quote)
                    if location is None:
                        raise ToolError("invalid_evidence_quote", "quote须逐字原文或精确JSON；改写/拼接请使用summary，程序会标为未核实工作摘要。")
                    content = {"quote": note.quote, "model_derived": False, "verification": "verbatim", **location}
                else:
                    content = {"summary": note.summary, "model_derived": True, "verification": "unverified"}
                revision = self.store.get_run(source["agent_run_id"])["revision"]
                validated.append({"id": note.id, "observed_at": observed_at(source),
                                  "source_revision": revision, **content,
                                  "source_call_id": source["tool_call_id"] if kind == "result" else receipt["tool_call_id"]})
        finally:
            self._active_task.reset(scope)
        return validated

    @contextmanager
    def _keep_lease(self, run_id, token):
        stopped = threading.Event()
        def heartbeat():
            while not stopped.wait(max(0.1, self.lease_seconds / 3)):
                try:
                    self.store.heartbeat(run_id, token, lease_seconds=self.lease_seconds)
                except (HTTPException, sqlite3.Error):
                    return
        thread = threading.Thread(target=heartbeat, name="agent-lease", daemon=True)
        thread.start()
        try:
            yield
        finally:
            stopped.set()
            thread.join(timeout=1)

    def _observation(self, run_id, token, kind, **payload):
        return self.store.record_observation(run_id, token, kind, payload)

    def _pause(self, run_id, token, reason):
        try:
            self.store.transition(run_id, token, "paused", reason=reason)
        except HTTPException as error:
            if error.status_code != 409:
                raise

    def _review_sources(self, snapshot):
        """Resolve successful original sources, including prior-version references."""
        queue = [call["tool_call_id"] for call in snapshot["tool_calls"] if call["state"] == "succeeded"
                 and call["tool_id"] not in {"agent.read_result", "agent.read_artifact"}]
        for artifact in snapshot["artifacts"]:
            queue.extend(item["tool_call_id"] for item in artifact.get("sources", []) if item.get("tool_call_id"))
        visited, sources, artifacts = set(), {}, set()
        scope = self._active_task.set(snapshot["agent_task_id"])
        try:
            while queue:
                identity = queue.pop(0)
                if identity in visited:
                    continue
                visited.add(identity)
                receipt = self._owned_call(identity)
                if receipt["state"] != "succeeded":
                    raise ToolError("invalid_review_source", "审查来源必须为本任务已成功的原始回执")
                kind, source = self._call_source(identity)
                if kind == "result":
                    if source["state"] != "succeeded":
                        raise ToolError("invalid_review_source", "读取回执不能证明原始结果成功")
                    sources[source["tool_call_id"]] = source
                else:
                    key = (source["artifact_id"], source["version"])
                    if key not in artifacts:
                        artifacts.add(key)
                        queue.extend(item["tool_call_id"] for item in source.get("sources", []) if item.get("tool_call_id"))
        finally:
            self._active_task.reset(scope)
        return list(sources.values())

    def _review_events(self, snapshot, group="artifact"):
        """Read durable attempts beyond the bounded model observation window."""
        values, cursor = [], 0
        while True:
            page = self.store.events(snapshot["agent_task_id"], after=cursor, limit=200)
            values.extend(item for item in page["events"]
                          if item.get("agent_run_id") == snapshot["current_run_id"]
                           and item["kind"] in {f"observation:{group}_review_requested", f"observation:{group}_review_result"})
            if not page["has_more"]:
                return values
            cursor = page["next_cursor"]

    def _validate_publication(self, snapshot, kind, content, sources):
        if self.catalog.has_artifact_validator(kind):
            # Resolve only this artifact's explicitly attached, authorized
            # provenance. Unrelated successful calls cannot justify a result.
            original = self._review_sources({**snapshot, "tool_calls": [], "artifacts": [{"sources": sources}]})
            self.catalog.validate_artifact(kind, content, snapshot, original)

    def _review_question(self, snapshot, token, action):
        from backend.agent.delivery import delivery_contract
        from backend.agent.question_review import build_question_context
        contract = delivery_contract(snapshot["constraints"])
        if (not contract or contract.get("completion_mode") == "await_user"
                or not hasattr(self.artifact_reviewer, "review_question")):
            return True
        run_id = snapshot["current_run_id"]
        try:
            context = build_question_context(snapshot, {"question": action.question, "missing_inputs": action.missing_inputs},
                                             namespace_index=self._catalog_for(snapshot).namespace_index(), max_input_tokens=self.max_input_tokens,
                                             output_reserve=getattr(self.artifact_reviewer, "max_output_tokens", 2048))
        except ContextBudgetExceeded:
            self._pause(run_id, token, "question_review_context_budget")
            return None
        history = self._review_events(snapshot, "question")
        requests = [o for o in history if o["kind"].endswith("requested") and o["payload"].get("input_cursor") == context.input_cursor]
        matching = [o for o in history if o["payload"].get("fingerprint") == context.fingerprint]
        previous = [o["payload"] for o in matching if o["kind"].endswith("result")]
        # An interrupted initial/correction request has no result to repair.
        # Never use an earlier error receipt to replay that pending attempt.
        if matching and matching[-1]["kind"].endswith("requested"):
            self._pause(run_id, token, "question_review_limit")
            return None
        if previous:
            if previous[-1].get("status") == "error":
                rejected_status = previous[-1].get("rejected_status")
                if (previous[-1].get("code") != "invalid_question_review"
                        or rejected_status not in ("necessary", "unnecessary") or len(requests) >= 2):
                    self._pause(run_id, token, previous[-1].get("code", "invalid_question_review"))
                    return None
                try:
                    context = build_question_context(snapshot, {"question": action.question, "missing_inputs": action.missing_inputs},
                        namespace_index=self._catalog_for(snapshot).namespace_index(), max_input_tokens=self.max_input_tokens,
                        output_reserve=getattr(self.artifact_reviewer, "max_output_tokens", 2048), rejected_status=rejected_status)
                except ContextBudgetExceeded:
                    self._pause(run_id, token, "question_review_context_budget")
                    return None
            else:
                return previous[-1].get("status") == "necessary"
        if len(requests) >= 2:
            self._pause(run_id, token, "question_review_limit")
            return None
        self.store.reserve_step(run_id, token)
        self._observation(run_id, token, "question_review_requested", fingerprint=context.fingerprint, input_cursor=context.input_cursor)
        try:
            result = self.artifact_reviewer.review_question(context)
        except DecisionError as error:
            rejected_status = getattr(error, "rejected_status", None)
            repairable = error.code == "invalid_question_review" and rejected_status in ("necessary", "unnecessary")
            self._observation(run_id, token, "question_review_result", fingerprint=context.fingerprint, status="error", code=error.code,
                              **({"rejected_status": rejected_status} if repairable else {}))
            if repairable and len(requests) + 1 < 2:
                self.store.heartbeat(run_id, token, lease_seconds=self.lease_seconds)
                return self._review_question(self.store.get_run(run_id), token, action)
            self._pause(run_id, token, error.code)
            return None
        self.store.heartbeat(run_id, token, lease_seconds=self.lease_seconds)
        self._observation(run_id, token, "question_review_result", fingerprint=context.fingerprint, **result.model_dump())
        return result.status == "necessary"

    def _review_completion(self, snapshot, token, summary):
        """Return True to finish, False to revise, or None after a safe pause."""
        from backend.agent.review import build_review_context
        run_id = snapshot["current_run_id"]
        try:
            context = build_review_context({**snapshot, "completion_summary": summary}, self._review_sources(snapshot),
                                           max_input_tokens=self.max_input_tokens,
                                           output_reserve=getattr(self.artifact_reviewer, "max_output_tokens", 2048))
        except ContextBudgetExceeded:
            self._pause(run_id, token, "artifact_review_context_budget")
            return None
        except (ToolError, DecisionError):
            self._pause(run_id, token, "artifact_review_invalid_source")
            return None
        history = self._review_events(snapshot)
        requests = [item for item in history if item["kind"].endswith("artifact_review_requested")]
        previous = [item["payload"] for item in history if item["kind"].endswith("artifact_review_result")
                    and item["payload"].get("fingerprint") == context.fingerprint]
        if previous and previous[-1].get("status") == "supported":
            return True
        if len(requests) >= 2:
            self._pause(run_id, token, "artifact_review_limit")
            return None
        if any(item["payload"].get("fingerprint") == context.fingerprint for item in requests):
            # An invalid format is not a negative factual verdict. Permit one
            # correction, sharing the same persisted two-review ceiling. An
            # interrupted request without an error receipt is not replayed.
            if not previous or previous[-1].get("code") != "invalid_review":
                self._pause(run_id, token, "artifact_review_changes_required")
                return None
            try:
                context = build_review_context(
                    {**snapshot, "completion_summary": summary}, self._review_sources(snapshot),
                    max_input_tokens=self.max_input_tokens,
                    output_reserve=getattr(self.artifact_reviewer, "max_output_tokens", 2048),
                    correct_format=True, rejected_review=previous[-1].get("rejected_review"))
            except ContextBudgetExceeded:
                self._pause(run_id, token, "artifact_review_context_budget")
                return None
        # The independent model call consumes the same persisted decision and
        # activity-time budgets. Its request survives a worker interruption.
        self.store.reserve_step(run_id, token)
        self._observation(run_id, token, "artifact_review_requested", fingerprint=context.fingerprint,
                          artifact_refs=list(context.artifact_refs), source_refs=list(context.source_refs))
        try:
            result = self.artifact_reviewer.review(context)
        except DecisionError as error:
            self._observation(run_id, token, "artifact_review_result", fingerprint=context.fingerprint,
                              status="error", code=error.code,
                              **({"rejected_review": error.rejected_review} if hasattr(error, "rejected_review") else {}))
            if error.code == "invalid_review" and len(requests) + 1 < 2:
                self.store.heartbeat(run_id, token, lease_seconds=self.lease_seconds)
                return self._review_completion(self.store.get_run(run_id), token, summary)
            self._pause(run_id, token, error.code)
            return None
        self.store.heartbeat(run_id, token, lease_seconds=self.lease_seconds)
        current = self.store.get_run(run_id)
        fresh = build_review_context({**current, "completion_summary": summary}, self._review_sources(current),
                                     max_input_tokens=self.max_input_tokens,
                                     output_reserve=getattr(self.artifact_reviewer, "max_output_tokens", 2048))
        if fresh.fingerprint != context.fingerprint:
            self._pause(run_id, token, "artifact_review_stale")
            return None
        self._observation(run_id, token, "artifact_review_result", fingerprint=context.fingerprint,
                          artifact_refs=list(context.artifact_refs), **result.model_dump())
        if result.status == "supported":
            return True
        if len(requests) + 1 >= 2:
            self._pause(run_id, token, "artifact_review_limit")
            return None
        return False

    def _execute(self, call, run_id, token):
        if self.stop_event is not None and self.stop_event.is_set():
            self._pause(run_id, token, "worker_stopping")
            return False
        # Validate again after an approval wait: a tool may have changed or been revoked.
        try:
            self._catalog_for(self.store.get_run(run_id)).validate(call["tool_id"], call["arguments"], expected_version=call["tool_version"])
        except ToolError as error:
            self.store.reject_prepared(call["tool_call_id"], token, {"code": error.code, "message": str(error)})
            self._observation(run_id, token, "tool_error", code=error.code, tool_id=call["tool_id"])
            return True
        sent = self.store.dispatch_call(call["tool_call_id"], token)
        evidence_context = self._active_task.set(self.store.get_run(run_id)["agent_task_id"])
        try:
            if self.call_executor is not None:
                result = self.call_executor(sent, self.catalog)
            else:
                result = self.catalog.call(sent["tool_id"], sent["arguments"],
                                           expected_version=sent["tool_version"],
                                           authorized=sent.get("approved", False))
        except ToolError as error:
            from backend.agent.result_reader import SelectionError
            self.store.record_result(sent["tool_call_id"], sent["receipt_token"], error.outcome,
                                     error={"code": error.code, "message": str(error),
                                            **({"details": error.details} if isinstance(error, SelectionError) else {})})
            return error.outcome != "unknown"
        except Exception:
            # An adapter that violated its error contract may have performed its write.
            self.store.record_result(sent["tool_call_id"], sent["receipt_token"], "unknown",
                                     error={"code": "adapter_error", "message": "工具执行结果待核实"})
            return False
        finally:
            self._active_task.reset(evidence_context)
        self.store.record_result(sent["tool_call_id"], sent["receipt_token"], "succeeded", result=result)
        return True

    def run(self, run_id, *, worker_id=None):
        """Advance until completion/wait/pause; receipt callbacks survive a revision."""
        try:
            lease = self.store.claim(run_id, worker_id or str(uuid4()), lease_seconds=self.lease_seconds)
        except HTTPException as error:
            if error.status_code == 409:
                return
            raise
        token = lease["lease_token"]
        with self._keep_lease(run_id, token):
            try:
                self._advance(run_id, token)
            except HTTPException as error:
                if error.status_code != 409:
                    raise
                # A user revision/cancellation or another lease fences this worker.
                # Budget transitions are already committed by the store.
            except Exception:
                self._pause(run_id, token, "runtime_error")
                raise

    def _context_tools(self, snapshot):
        """Keep a small discovery cache; changing namespaces does not erase it."""
        catalog = self._catalog_for(snapshot)
        discoveries = [item for item in current_observations(snapshot) if item.get("kind") == "tools_discovered"]
        groups = [item["payload"].get("tools", []) for item in reversed(discoveries)]
        if not groups:
            groups = [catalog.discover(snapshot["goal"][:4000], limit=5)]
        latest = [item["tool_id"] for item in groups[0]]
        latest_page = discoveries[-1]["payload"] if discoveries else {}
        paged = "directory_version" in latest_page
        selected = named_discovery_tool(latest_page)
        tool_focus = resolve_tool_focus(snapshot)
        requested = tool_focus.tool_id if tool_focus is not None else None
        recent_used = next((item["tool_id"] for item in reversed(snapshot.get("tool_calls", []))
                            if item.get("state") == "succeeded" and not item["tool_id"].startswith("agent.")), None)
        last_progress = max([item.get("updated_at", item.get("created_at", 0)) for item in snapshot.get("tool_calls", [])]
                            + [item.get("created_at", 0) for item in snapshot.get("artifacts", [])], default=0)
        fresh_directory = paged and discoveries[-1].get("created_at", 0) > last_progress
        latest_call = max(snapshot.get("tool_calls", []),
                          key=lambda item: item.get("updated_at", item.get("created_at", 0)), default={})
        latest_call_at = latest_call.get("updated_at", latest_call.get("created_at", 0))
        reader_focused = (latest_call.get("state") == "succeeded" or
                          (latest_call.get("state") == "failed" and latest_call.get("tool_id") == "agent.read_result"))
        reader_focused = (reader_focused and latest_call.get("tool_id") in {"agent.read_result", "agent.read_artifact"}
                          and bool((reader_source(latest_call) or {}).get("source_ref"))
                          and latest_call_at > max((item.get("created_at", 0) for item in discoveries), default=0))
        candidates, active, namespaces = [], [], set()
        def add(identity, *, preferred=False):
            if not identity or identity in {item["tool_id"] for item in candidates} or len(candidates) >= 6:
                return
            try:
                candidate = catalog.describe(identity)
            except ToolError:
                return
            candidates.append(candidate)
            namespaces.add(identity.split(".", 1)[0])
            if preferred and len(active) < 2 and (identity == requested or not identity.startswith("agent.")):
                active.append(identity)
        add(requested, preferred=True)
        # A revision continues an existing deliverable. Its real producer is a
        # useful capability candidate even when the new goal only says "change
        # this part". A fresh discovery or explicit reader still takes priority.
        if not discoveries and not snapshot.get("artifacts"):
            for artifact in snapshot.get("prior_artifacts", [])[-1:]:
                for source in artifact.get("sources", []):
                    add(source.get("tool_id"), preferred=not reader_focused)
        if latest:
            add(selected if paged else latest[0], preferred=not reader_focused)
        add(recent_used, preferred=not fresh_directory and not reader_focused)
        for group in groups:
            for item in group:
                if item["tool_id"].split(".", 1)[0] not in namespaces:
                    add(item["tool_id"], preferred=not paged and not reader_focused and len(active) < 2)
        for group in groups:
            for item in group:
                add(item["tool_id"])
        return candidates, active

    def _advance(self, run_id, token):
        while True:
            if self.stop_event is not None and self.stop_event.is_set():
                self._pause(run_id, token, "worker_stopping")
                return
            snapshot = self.store.get_run(run_id)
            if snapshot["run"]["state"] != "running":
                return
            prepared = next((call for call in snapshot["tool_calls"] if call["state"] == "prepared"), None)
            if prepared:
                if not self._execute(prepared, run_id, token):
                    return
                continue

            self.store.reserve_step(run_id, token)
            snapshot = self.store.get_run(run_id)
            try:
                memory = [] if snapshot.get("delegation") else self.memory_reader(snapshot)
            except (OSError, sqlite3.Error, HTTPException):
                memory = []
                self._observation(run_id, token, "memory_unavailable", message="本轮未参考个人记忆")
                snapshot = self.store.get_run(run_id)
            if self.delegation_enabled and not snapshot.get("delegation"):
                snapshot["delegation_enabled"] = True
            catalog = self._catalog_for(snapshot)
            discovered = [o for o in current_observations(snapshot) if o.get("kind") == "tools_discovered"]
            descriptors, active_tools = self._context_tools(snapshot)
            try:
                context = build_context(snapshot, descriptors, memory, max_input_tokens=self.max_input_tokens,
                                        namespace_index=catalog.namespace_index(),
                                        active_tool_ids=active_tools,
                                        output_reserve=getattr(self.provider, "max_output_tokens", 2048))
                if any(context.omitted.get(key, 0) for key in ("result_bytes", "artifact_bytes", "evidence_pages", "evidence_page_bytes")):
                    # Preserve explicit discovery order. The context builder gives
                    # readers priority on observation rounds, after a tool returns.
                    described = {item["tool_id"] for item in descriptors}
                    descriptors.extend(catalog.describe(tool_id)
                                       for tool_id in ("agent.read_result", "agent.read_artifact")
                                       if tool_id not in described)
                    context = build_context(snapshot, descriptors, memory, max_input_tokens=self.max_input_tokens,
                                            namespace_index=catalog.namespace_index(),
                                            active_tool_ids=active_tools,
                                            output_reserve=getattr(self.provider, "max_output_tokens", 2048))
                context_data = json.loads(context.messages[-1]["content"])
                shown_tools = {tool["tool_id"]: tool["version"] for tool in context_data["tools"]}
                latest_discovery = max((o.get("created_at", 0) for o in discovered), default=-1)
                latest_evidence = max([c.get("updated_at", 0) for c in snapshot["tool_calls"]
                                       if c["state"] in {"succeeded", "failed"}]
                                      + [a.get("created_at", 0) for a in snapshot["artifacts"]], default=-1)
                tool_focus = resolve_tool_focus(snapshot)
                exact_requested = tool_focus.tool_id if tool_focus is not None else None
                missing_requested = any(item["tool_id"] == exact_requested and item["tool_id"] not in shown_tools
                                        for item in descriptors)
                needs_schema = missing_requested or (not shown_tools and not context_data.get("discovery_page"))
                if descriptors and needs_schema and (tool_focus is not None or latest_evidence < 0 or latest_discovery > latest_evidence):
                    self._pause(run_id, token, "tool_schema_exceeds_context_budget")
                    return
                action = self.provider.decide(context)
            except ContextBudgetExceeded:
                self._pause(run_id, token, "context_budget_exceeded")
                return
            except DecisionError as error:
                from backend.agent.decision import INVALID_DECISION_FEEDBACK, InvalidDecision
                feedback = ({"message": str(error), "diagnostic": error.diagnostic} if isinstance(error, InvalidDecision)
                            else {"message": INVALID_DECISION_FEEDBACK} if error.code == "invalid_decision" else {})
                self._observation(run_id, token, "decision_error", code=error.code, **feedback)
                if error.code in {"provider_error", "provider_not_configured"}:
                    self._pause(run_id, token, error.code)
                    return
                continue

            # The provider can take time; recheck the fencing token before effects.
            self.store.heartbeat(run_id, token, lease_seconds=self.lease_seconds)
            if self.stop_event is not None and self.stop_event.is_set():
                self._pause(run_id, token, "worker_stopping")
                return
            if action.evidence_notes is not None:
                try:
                    notes = self._validated_notes(snapshot, action.evidence_notes)
                    self.store.update_evidence_notes(run_id, token, notes, action.evidence_notes.delete)
                except ToolError as error:
                    self._observation(run_id, token, "decision_error", code=error.code, message=str(error))
                    continue
                except HTTPException as error:
                    if error.status_code != 422:
                        raise
                    self._observation(run_id, token, "decision_error", code="evidence_notes_rejected",
                                      message="摘录更新被拒绝：最多8项、含元数据2400 UTF-8字节；检查id重复/冲突，缩短或删除无用项后重试。")
                    continue
            if action.action in {"delegate_task", "wait_children", "review_delegation"}:
                if self._delegation_action(snapshot, token, action):
                    return
            elif action.action == "discover_tools":
                if self.discovery_refresh is not None:
                    try:
                        self.discovery_refresh()
                    except ToolError as error:
                        self._observation(run_id, token, "tool_error", code=error.code)
                try:
                    page = self._catalog_for(snapshot).discover_page(action.query, namespace=action.namespace, offset=action.offset,
                                                      directory_version=action.directory_version, limit=5)
                except ToolError as error:
                    self._observation(run_id, token, "tool_error", code=error.code, message=str(error))
                    continue
                self._observation(run_id, token, "tools_discovered", **page)
            elif action.action == "call_tool":
                known_version = shown_tools.get(action.tool_id) or next((call.get("tool_version")
                    for call in reversed(snapshot["tool_calls"])
                    if call["tool_id"] == action.tool_id and call["state"] in {"succeeded", "failed"}), None)
                if known_version is None:
                    self._observation(run_id, token, "tool_error", tool_id=action.tool_id, code="discover_tool_schema_first")
                    continue
                try:
                    definition = self._catalog_for(snapshot).validate(action.tool_id, action.arguments,
                                                       expected_version=known_version)
                    if snapshot.get("delegation") and (definition.effect != "read" or definition.executor != "server"):
                        raise ToolError("delegation_scope_rejected", "Child tools must remain server read tools.")
                except ToolError as error:
                    self._observation(run_id, token, "tool_error", tool_id=action.tool_id, code=error.code,
                                      message=str(error))
                    continue
                call_id = str(uuid4())
                call = self.store.prepare_call(run_id, token, action.tool_id, definition.version,
                                               action.arguments, effect=definition.effect,
                                               requires_approval=definition.effect != "read",
                                               tool_call_id=call_id,
                                               legacy_run_id=call_id if action.tool_id.startswith("onmyway.") else None)
                if call["state"] == "waiting_approval":
                    return
                if not self._execute(call, run_id, token):
                    return
            elif action.action == "ask_user":
                try:
                    validate_question(snapshot.get("constraints", {}), action.need, action.missing_inputs)
                except DeliveryError as error:
                    self._observation(run_id, token, "decision_error", code=error.code, message=str(error))
                    continue
                necessary = self._review_question(snapshot, token, action)
                if necessary is None:
                    return
                if not necessary:
                    continue
                self._observation(run_id, token, "question", question=action.question,
                                  need=action.need, missing_inputs=action.missing_inputs)
                self.store.transition(run_id, token, "waiting_user", reason=action.question)
                return
            elif action.action == "publish_artifact":
                if action.artifact_id is not None:
                    target = next((item for item in snapshot["artifacts"]
                                   if item["artifact_id"] == action.artifact_id), None)
                    if target is None:
                        self._observation(run_id, token, "decision_error", code="artifact_not_current",
                                          message="更新仅限当前运行已有成果；跨修订或独立成果请省略更新编号和版本，创建新成果。")
                        continue
                    if target["version"] != action.expected_version:
                        self._observation(run_id, token, "decision_error", code="artifact_version_conflict",
                                          message="expected_version 必须等于当前成果版本，请读取最新内容后再修改。")
                        continue
                try:
                    validate_artifact(snapshot["constraints"], action.kind, {"content": action.content})
                    sources = self._publication_sources(snapshot, action.evidence_call_ids)
                    self._validate_publication(snapshot, action.kind, action.content, sources)
                except (ToolError, DeliveryError) as error:
                    self._observation(run_id, token, "decision_error", code=error.code, message=str(error))
                    continue
                try:
                    self.store.publish_artifact(run_id, token, action.kind,
                                                {"title": action.title, "content": action.content}, sources=sources,
                                                artifact_id=action.artifact_id, expected_version=action.expected_version)
                except HTTPException as error:
                    if (error.status_code == 422 and isinstance(error.detail, dict)
                            and error.detail.get("code") == "artifact_contract_mismatch"):
                        self._observation(run_id, token, "decision_error", **error.detail)
                        continue
                    if error.status_code != 409 or error.detail not in {
                            "成果版本已变化，请读取最新版本后重新判断", "成果不属于当前运行"}:
                        raise
                    self._observation(run_id, token, "decision_error", code="artifact_version_conflict",
                                      message="成果身份或版本已变化，请按当前运行的成果编号和版本重新判断。")
            elif action.action == "finish":
                if self._unreviewed_children(snapshot):
                    self._observation(run_id, token, "decision_error", code="children_require_review",
                                      message="Wait for children, read submitted artifact versions, then accept or reject before finishing.")
                    continue
                try:
                    validate_completion(snapshot["constraints"])
                except DeliveryError as error:
                    self._observation(run_id, token, "decision_error", code=error.code, message=str(error))
                    continue
                if not snapshot["artifacts"]:
                    self._observation(run_id, token, "decision_error", code="artifact_required")
                    continue
                try:
                    for artifact in snapshot["artifacts"]:
                        content = artifact.get("content", {})
                        self._validate_publication(snapshot, artifact["kind"], content.get("content") if isinstance(content, dict) else content,
                                                   artifact.get("sources", []))
                except ToolError as error:
                    self._observation(run_id, token, "decision_error", code=error.code, message=str(error))
                    continue
                if self.artifact_reviewer is not None:
                    checked = self._review_completion(snapshot, token, action.summary)
                    if checked is None:
                        return
                    if not checked:
                        continue
                self.store.transition(run_id, token, "completed", reason=action.summary)
                return
