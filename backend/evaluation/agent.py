"""Bounded evidence for the production Agent loop; execution is never a pass judge."""
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import time
from urllib.parse import parse_qsl, quote, quote_plus, urlsplit

from backend.agent.decision import DecisionError, DecisionProvider
from backend.agent.runtime import AgentRuntime
from backend.agent.review import ReviewProvider


class EvaluationStop(Exception):
    def __init__(self, reason):
        super().__init__(reason)
        self.reason = reason


def _now():
    return datetime.now(timezone.utc).isoformat()


def _json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


_PRIVATE_KEY = re.compile(r"(?:^|[_-])(?:api[_-]?key|key|token|secret|password|authorization|credential)(?:$|[_-])", re.I)
_PRIVATE_QUERY = re.compile(r"([?&](?:api[_-]?key|key|token|access_token|secret|password|signature|authorization)=)[^&#\s\"<>]+", re.I)
_PRIVATE_JSON = re.compile(r'(\"(?:receipt_token|lease_token|api_key|access_token|password|secret|authorization)\"\s*:\s*)\"(?:\\.|[^\"\\])*\"', re.I)


class _Redactor:
    def __init__(self):
        secrets = set()
        for key, value in os.environ.items():
            if not value:
                continue
            if _PRIVATE_KEY.search(key) or re.search(r"API[_-]?KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL", key, re.I):
                secrets.add(value)
            if "URL" in key.upper():
                try:
                    parts = urlsplit(value)
                    if parts.password:
                        secrets.add(parts.password)
                    secrets.update(v for k, v in parse_qsl(parts.query) if _PRIVATE_KEY.search(k))
                except ValueError:
                    pass
        self.secrets = sorted({form for value in secrets if len(value) >= 4
                               for form in (value, json.dumps(value, ensure_ascii=False)[1:-1],
                                            quote(value, safe=""), quote_plus(value, safe=""))},
                              key=len, reverse=True)

    def __call__(self, value):
        if isinstance(value, dict):
            return {str(key): "[REDACTED]" if _PRIVATE_KEY.search(str(key)) else self(item)
                    for key, item in value.items()}
        if isinstance(value, (tuple, list)):
            return [self(item) for item in value]
        if isinstance(value, str):
            for secret in self.secrets:
                value = value.replace(secret, "[REDACTED]")
            value = _PRIVATE_QUERY.sub(r"\1[REDACTED]", value)
            return _PRIVATE_JSON.sub(r'\1"[REDACTED]"', value)
        return value


class _RecordingProvider:
    max_output_tokens = 2048

    def __init__(self, session, completion_fn):
        self.session, self.completion_fn = session, completion_fn
        self.delegate = DecisionProvider(completion_fn=self._complete, max_output_tokens=self.max_output_tokens)
        self.entry = None

    def _complete(self, messages, model, structured=False, max_tokens=None):
        session = self.session
        session.report["counts"]["model_calls"] += 1
        self.entry.update(model=model, status="calling", messages=session.redact(messages))
        session.flush()
        started = time.perf_counter()
        try:
            if self.completion_fn is None:
                from backend.assistant.api import completion
                raw = completion(messages, model, structured=structured,
                                 max_tokens=min(max_tokens or self.max_output_tokens, self.max_output_tokens))
            else:
                raw = self.completion_fn(messages, model, structured=structured)
            self.entry["raw_text"] = session.redact(raw)
            metadata = getattr(raw, "provider_metadata", None)
            if self.completion_fn is None and isinstance(metadata, dict):
                self.entry["provider_metadata"] = session.redact(metadata)
            self.entry["output_sha256"] = hashlib.sha256(raw.encode("utf-8")).hexdigest() if isinstance(raw, str) else None
            self.entry["status"] = "received"
            return raw
        except Exception as error:
            kind = getattr(error, "provider_error_kind", None)
            diagnostics = {"kind": kind if kind in {"http_status", "timeout", "transport", "invalid_response"} else "unclassified"}
            status = getattr(error, "upstream_status_code", None)
            if kind == "http_status" and type(status) is int and 100 <= status <= 599:
                diagnostics["upstream_status"] = status
            self.entry.update(status="provider_error", error_code="provider_error", provider_diagnostics=diagnostics)
            session.report["counts"]["provider_errors"] += 1
            raise
        finally:
            self.entry["latency_seconds"] = time.perf_counter() - started
            session.flush()

    def decide(self, context):
        return self._invoke(context, self.delegate.decide, "decision")

    def _invoke(self, context, decide, purpose):
        session = self.session
        reason = session._stop_reason()
        if reason:
            raise EvaluationStop(reason)
        if self.completion_fn is None and (not os.getenv("DASHSCOPE_API_KEY", "").strip()
                                          or not os.getenv("DASHSCOPE_BASE_URL", "").strip()):
            raise DecisionError("provider_not_configured", "模型未配置，未发出评测请求")
        self.entry = {"index": session.report["counts"]["model_calls"] + 1, "at": _now(),
                      "purpose": purpose,
                      "prompt_estimated_tokens": context.estimated_input_tokens,
                      "prompt_estimator": context.estimator, "omitted": deepcopy(context.omitted),
                      "prompt_sha256": hashlib.sha256(_json(context.messages).encode("utf-8")).hexdigest(),
                      "raw_text": None, "action": None, "latency_seconds": 0, "status": "prepared"}
        if session._active_run_id:
            owner = session.store.get_run(session._active_run_id)
            self.entry.update(agent_task_id=owner["agent_task_id"], agent_run_id=session._active_run_id,
                              revision=owner["revision"])
        session._phase["model_calls"].append(self.entry)
        try:
            action = decide(context)
            self.entry.update({"action" if purpose == "decision" else "review": session.redact(action.model_dump()),
                               "status": "parsed"})
            if getattr(action, "action", None) == "call_tool" and session.report["counts"]["tool_calls"] >= session.max_tool_calls:
                raise EvaluationStop("evaluation_tool_budget")
            return action
        except DecisionError as error:
            self.entry.update(status=error.code, error_code=error.code)
            raise
        finally:
            session.flush()


class _RecordingReviewer(_RecordingProvider):
    def __init__(self, session, completion_fn):
        super().__init__(session, completion_fn)
        self.delegate = ReviewProvider(completion_fn=self._complete, max_output_tokens=self.max_output_tokens)

    def review(self, context):
        return self._invoke(context, self.delegate.review, "completion_review")

    def review_question(self, context):
        return self._invoke(context, self.delegate.review_question, "question_review")


class _EvaluationRuntime(AgentRuntime):
    def __init__(self, session, provider, max_input_tokens):
        self.session = session
        super().__init__(session.store, session.catalog, provider, memory_reader=lambda _: [],
                         max_input_tokens=max_input_tokens, call_executor=session._execute,
                         artifact_reviewer=session.reviewer, delegation_enabled=session.delegation_enabled)

    def run(self, run_id, *, worker_id=None):
        self.session._active_run_id = run_id
        try:
            return super().run(run_id, worker_id=worker_id)
        finally:
            self.session._active_run_id = None

    def _execute(self, call, run_id, token):
        # Prepared/approved calls can resume without another model decision.
        if self.session.report["counts"]["tool_calls"] >= self.session.max_tool_calls:
            raise EvaluationStop("evaluation_tool_budget")
        return super()._execute(call, run_id, token)


class EvaluationSession:
    def __init__(self, store, catalog, output_path, *, call_executor=None, completion_fn=None,
                 allow_live=False, max_model_calls=64, max_tool_calls=40, max_provider_errors=2,
                 max_input_tokens=12000, review_completion_fn=None, delegation_enabled=False):
        if completion_fn is None and not allow_live:
            raise ValueError("Live evaluation requires allow_live=True or an injected completion_fn")
        for name, value in (("max_model_calls", max_model_calls), ("max_tool_calls", max_tool_calls),
                            ("max_provider_errors", max_provider_errors)):
            if type(value) is not int or value < 1:
                raise ValueError(f"{name} must be a positive integer")
        self.store, self.catalog = store, catalog
        self.delegation_enabled = delegation_enabled
        self._active_run_id = None
        self.output_path = Path(output_path)
        if self.output_path.exists():
            raise FileExistsError("Evaluation evidence already exists; choose a new output path")
        self.output_path.parent.mkdir(parents=True, exist_ok=True)
        self.call_executor = call_executor
        self.max_model_calls, self.max_tool_calls = max_model_calls, max_tool_calls
        self.max_provider_errors = max_provider_errors
        self.redact = _Redactor()
        self._phase = None
        self.report = {"schema_version": 1, "started_at": _now(), "status": "needs_review",
                       "mode": "live" if completion_fn is None else "injected",
                       "limits": {"max_model_calls": max_model_calls, "max_tool_calls": max_tool_calls,
                                  "max_provider_errors": max_provider_errors, "max_input_tokens": max_input_tokens,
                                  "max_output_tokens": 2048, "delegation_enabled": delegation_enabled},
                       "counts": {"model_calls": 0, "tool_calls": 0, "provider_errors": 0}, "phases": []}
        self.provider = _RecordingProvider(self, completion_fn)
        # Production calls always include the independent completion check.
        # Controlled evaluation scripts can inject a separate reviewer explicitly.
        self.reviewer = (_RecordingReviewer(self, review_completion_fn)
                         if completion_fn is None or review_completion_fn is not None else None)
        self.report["limits"]["completion_review_enabled"] = self.reviewer is not None
        self.runtime = _EvaluationRuntime(self, self.provider, max_input_tokens)
        self.flush()

    def _stop_reason(self):
        if self.report["counts"]["provider_errors"] >= self.max_provider_errors:
            return "evaluation_provider_limit"
        if self.report["counts"]["model_calls"] >= self.max_model_calls:
            return "evaluation_model_budget"
        return None

    def _execute(self, sent, catalog):
        self.report["counts"]["tool_calls"] += 1
        call = {"tool_call_id": sent["tool_call_id"], "tool_id": sent["tool_id"],
                "agent_run_id": sent["agent_run_id"],
                "tool_version": sent["tool_version"], "at": _now(), "status": "dispatched"}
        self._phase["tool_dispatches"].append(call)
        self.flush()
        started = time.perf_counter()
        try:
            if self.call_executor is not None:
                result = self.call_executor(sent, catalog)
            else:
                result = catalog.call(sent["tool_id"], sent["arguments"], expected_version=sent["tool_version"],
                                      authorized=sent.get("approved", False))
            call["status"] = "returned"
            return result
        except Exception as error:
            call.update(status=getattr(error, "outcome", "unknown"), error_code=getattr(error, "code", "adapter_error"))
            raise
        finally:
            call["latency_seconds"] = time.perf_counter() - started
            self.flush()

    def _events(self, task_id):
        values, after = [], 0
        while True:
            page = self.store.events(task_id, after=after, limit=200)
            values.extend(page["events"])
            if not page["has_more"]:
                return values
            after = page["next_cursor"]

    def _advance_tree(self, run_id):
        """Drive only this phase's tree using the same durable ready queue."""
        next_run = run_id
        while next_run is not None:
            reason = self._stop_reason()
            if reason:
                raise EvaluationStop(reason)
            self.runtime.run(next_run)
            root = self.store.get_run(run_id)
            if not self.delegation_enabled or (root["run"]["state"] not in {"queued", "running", "waiting_tool"}
                    and root["run"].get("reason") != "waiting_children"):
                return
            ready = set(self.store.ready_runs(limit=100))
            members = [run_id, *(item["child_run_id"] for item in root.get("delegations", []))]
            next_run = next((identity for identity in members if identity in ready), None)

    def run(self, task_id, phase_id, expected_state="completed"):
        if self._phase is not None:
            raise RuntimeError("EvaluationSession runs phases sequentially")
        if any(item["phase_id"] == phase_id for item in self.report["phases"]):
            raise ValueError("phase_id must be unique; use a new report for a separate evaluation")
        before = self.store.get(task_id)
        run_id = before["current_run_id"]
        record = {"phase_id": phase_id, "agent_task_id": task_id, "agent_run_id": run_id,
                  "revision": before["revision"], "started_at": _now(), "expected_state": expected_state,
                  "status": "needs_review", "execution_status": "running", "evaluation_stop_reason": None,
                  "model_calls": [], "tool_dispatches": [], "events": [], "checks": {},
                  "snapshot": self.redact(before)}
        self.report["phases"].append(record)
        self._phase = record
        self.flush()
        reason = self._stop_reason()
        try:
            if reason:
                record.update(execution_status="not_run", evaluation_stop_reason=reason)
            else:
                try:
                    self._advance_tree(run_id)
                except EvaluationStop as stopped:
                    record["evaluation_stop_reason"] = stopped.reason
                except Exception as error:
                    record["error"] = {"code": "evaluation_runtime_error", "type": type(error).__name__}
                snapshot = self.store.get_run(run_id)
                record.update(snapshot=self.redact(snapshot), events=self.redact(self._events(task_id)))
                child_snapshots = [self.store.get_run(item["child_run_id"]) for item in snapshot.get("delegations", [])]
                if self.delegation_enabled or child_snapshots:
                    record["children"] = [{"snapshot": self.redact(child), "events": self.redact(self._events(child["agent_task_id"]))}
                                          for child in child_snapshots]
                all_calls = [call for member in [snapshot, *child_snapshots] for call in member["tool_calls"]]
                state, runtime_reason = snapshot["run"]["state"], snapshot["run"].get("reason")
                if record["evaluation_stop_reason"]:
                    status = "budget_exhausted"
                elif any(call["state"] == "unknown" for call in all_calls) or snapshot.get("unresolved_calls"):
                    status = "unknown"
                elif runtime_reason in {"provider_error", "provider_not_configured"}:
                    status = runtime_reason
                elif runtime_reason and (runtime_reason.startswith("budget_") or "context_budget" in runtime_reason):
                    status = "budget_exhausted"
                elif "error" in record:
                    status = "runtime_error"
                else:
                    status = state
                record["execution_status"] = status
                record["checks"] = {"expected_state": state == expected_state, "semantic_review": "not_run",
                                    "current_revision": self.store.get(task_id)["revision"] == snapshot["revision"],
                                    "unknown_calls": sum(call["state"] == "unknown" for call in all_calls),
                                    "failed_calls": sum(call["state"] == "failed" for call in all_calls)}
        finally:
            record["finished_at"] = _now()
            self._phase = None
            self.flush()
        return self.redact(record)

    def flush(self):
        self.report["updated_at"] = _now()
        encoded = json.dumps(self.redact(self.report), ensure_ascii=False, indent=2, allow_nan=False) + "\n"
        descriptor, name = tempfile.mkstemp(prefix=self.output_path.name + ".", suffix=".tmp", dir=self.output_path.parent)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as output:
                output.write(encoded)
                output.flush()
                os.fsync(output.fileno())
            os.replace(name, self.output_path)
        finally:
            Path(name).unlink(missing_ok=True)
