"""Bounded live-model evaluation using synthetic inputs and an isolated request ledger.

Explicitly load credentials only with --env-file. Offline replay never enables this mode.
"""
import argparse
from contextlib import ExitStack
import copy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import time
from typing import Literal
from unittest.mock import patch
from urllib.parse import parse_qsl, urlsplit
from uuid import uuid4

from dotenv import load_dotenv
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import BaseModel, ConfigDict, Field, model_validator

from . import assistant
from .replay import load_cases as load_replay_cases


DEFAULT_CASES = Path(__file__).with_name("evals") / "model_cases.json"


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Expected(StrictModel):
    action: Literal["adjust", "clarify", "add_stop"]
    keep_task_ids: list[str] | None = None
    effective_destination: str | None = None
    new_place_type: str | None = None

    @model_validator(mode="after")
    def unique_ids(self):
        if self.keep_task_ids is not None and len(set(self.keep_task_ids)) != len(self.keep_task_ids):
            raise ValueError("Duplicate expected task ids")
        return self


class Case(StrictModel):
    id: str = Field(min_length=1)
    source_case: str = Field(min_length=1)
    request_override: dict = Field(default_factory=dict)
    expected: Expected
    requires_reply: bool = False


class Suite(StrictModel):
    schema_version: Literal[1]
    suite_id: str = Field(min_length=1)
    cases: list[Case] = Field(min_length=1)

    @model_validator(mode="after")
    def unique_cases(self):
        if len({case.id for case in self.cases}) != len(self.cases):
            raise ValueError("Duplicate case ids")
        return self


def load_cases(path=DEFAULT_CASES):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    Suite.model_validate(data)
    return data


def _prepare(case, sources):
    source = sources.get(case.source_case)
    if source is None or source["operation"] != "navigation":
        raise ValueError("Unknown or unsupported source case")
    body = copy.deepcopy(source["request"])
    body.update(copy.deepcopy(case.request_override))
    snapshot = body.get("context_snapshot")
    if not isinstance(snapshot, dict):
        raise ValueError("Live evaluation requires a synthetic context snapshot")
    body["request_id"] = str(uuid4())
    snapshot["snapshot_id"] = str(uuid4())
    snapshot["captured_at"] = int(time.time() * 1000)
    # Validate all fixtures before any paid call. This creates new test requests;
    # it does not refresh the snapshot of an existing or recovered real request.
    assistant.NavigationRequest.model_validate(body)
    if case.expected.keep_task_ids is not None:
        if not set(case.expected.keep_task_ids) <= {task["id"] for task in body["tasks"]}:
            raise ValueError("Expected task is absent from the synthetic request")
    return body


def _grade(case, body, actual):
    checks = []

    def check(path, expected, observed):
        checks.append(dict(path=path, expected=expected, actual=observed, passed=expected == observed))

    check("action", case.expected.action, actual.get("action"))
    if case.expected.action != "clarify":
        if case.expected.keep_task_ids is not None:
            check("keep_task_ids", sorted(case.expected.keep_task_ids), sorted(actual.get("keep_task_ids", [])))
        if case.expected.effective_destination is not None:
            destination = (actual.get("destination", "").strip() or body.get("proposed_destination", "").strip()
                           or body["destination"].strip())
            check("effective_destination", case.expected.effective_destination, destination)
    if case.expected.action in ("adjust", "clarify"):
        check("new_task_title", "", actual.get("new_task_title", ""))
        check("new_place_type", "", actual.get("new_place_type", ""))
    elif case.expected.new_place_type is not None:
        check("new_place_type", case.expected.new_place_type, actual.get("new_place_type"))
    if case.requires_reply:
        check("reply_nonempty", True, bool(actual.get("reply", "").strip()))
    return checks


def _trial(case, body, repeat_index, original_completion):
    record = dict(id=case.id, trial=repeat_index, input=body,
                  request_id=body["request_id"], snapshot_id=body["context_snapshot"]["snapshot_id"],
                  synthetic_time_rebased=True, expected=case.expected.model_dump(exclude_none=True),
                  human_review_required=case.expected.action == "clarify", model_calls=0,
                  raw_output=None, actual=None, checks=[], prompt_hash=None, system_prompt_hash=None,
                  latency_ms=0, http_status=None, passed=False, status="not_run")
    provider_error = False

    def observe(messages, model, structured=False):
        nonlocal provider_error
        record["model_calls"] += 1
        record["prompt_hash"] = hashlib.sha256(json.dumps(messages, ensure_ascii=False,
                                                          sort_keys=True).encode()).hexdigest()
        system = [message for message in messages if message.get("role") == "system"]
        record["system_prompt_hash"] = hashlib.sha256(json.dumps(system, ensure_ascii=False,
                                                                 sort_keys=True).encode()).hexdigest()
        started = time.monotonic()
        try:
            record["raw_output"] = original_completion(messages, model, structured)
            return record["raw_output"]
        except Exception as error:
            provider_error = True
            cause = error.__context__ or error
            error_type = type(cause).__name__
            record["provider_error_type"] = error_type if error_type in {
                "ReadTimeout", "ConnectTimeout", "WriteTimeout", "PoolTimeout", "ConnectError",
                "ReadError", "RemoteProtocolError", "HTTPException", "HTTPStatusError",
                "JSONDecodeError", "ValueError", "KeyError", "IndexError", "TypeError",
            } else "provider_exception"
            raise
        finally:
            record["latency_ms"] = round((time.monotonic() - started) * 1000, 2)

    app = FastAPI()
    app.include_router(assistant.router)
    with TemporaryDirectory(prefix="onmyway-live-eval-") as directory, ExitStack() as stack:
        stack.enter_context(patch.dict(os.environ, {"ONMYWAY_HARNESS_DB": str(Path(directory) / "harness.db")}))
        stack.enter_context(patch.object(assistant, "completion", observe))
        client = stack.enter_context(TestClient(app, raise_server_exceptions=False))
        response = client.post("/assistant/navigation", json=body)
        record["http_status"] = response.status_code
        if provider_error:
            record.update(status="provider_error", reason="model_provider_unavailable")
        elif response.status_code != 200 or record["model_calls"] != 1:
            record.update(status="validation_failed", reason="request_or_model_output_rejected")
        else:
            record["actual"] = response.json()
            record["checks"] = _grade(case, body, record["actual"])
            record["passed"] = all(check["passed"] for check in record["checks"])
            record["status"] = "passed" if record["passed"] else "semantic_failed"
    return record


def _sanitize(report):
    base = os.getenv("DASHSCOPE_BASE_URL", "").strip()
    key = os.getenv("DASHSCOPE_API_KEY", "").strip()
    parts = urlsplit(base)
    secrets = {key, base, parts.username, parts.password, *[value for _, value in parse_qsl(parts.query)]}
    secrets = sorted((value for value in secrets if value), key=len, reverse=True)

    def clean(value):
        if isinstance(value, str):
            for secret in secrets:
                value = value.replace(secret, "[REDACTED]")
            return value
        if isinstance(value, list):
            return [clean(item) for item in value]
        if isinstance(value, dict):
            return {name: clean(item) for name, item in value.items()}
        return value

    return clean(report)


def run_suite(suite=None, repeat=1, max_calls=8, progress=False):
    parsed = Suite.model_validate(load_cases() if suite is None else suite)
    if (type(repeat) is not int or not 1 <= repeat <= 3 or type(max_calls) is not int
            or not 1 <= max_calls <= 32 or len(parsed.cases) * repeat > max_calls):
        raise ValueError("Invalid evaluation call budget")
    sources = {case["id"]: case for case in load_replay_cases()["cases"]}
    # A bad later fixture must not cause partially paid runs.
    for case in parsed.cases:
        _prepare(case, sources)
    base = os.getenv("DASHSCOPE_BASE_URL", "").strip()
    configured = bool(base and os.getenv("DASHSCOPE_API_KEY", "").strip())
    report = dict(schema_version=1, run_id=str(uuid4()), suite_id=parsed.suite_id, mode="live_model",
                  generated_at=datetime.now(timezone.utc).isoformat(), blocked_reason=None,
                  configuration=dict(model=os.getenv("AGENT_MODEL", "qwen3.8-max"),
                                     provider_host=urlsplit(base).hostname, structured=True, stream=False,
                                     enable_thinking=False, timeout_seconds=45, repeat=repeat, max_calls=max_calls),
                  not_verified=["device_execution", "room_confirmation_transaction", "real_map_routing",
                                "broad_model_accuracy"], cases=[])
    if not configured:
        report["blocked_reason"] = "missing_provider_configuration"
    original_completion = assistant.completion
    consecutive_errors = 0
    for repetition in range(1, repeat + 1):
        for case in parsed.cases:
            body = _prepare(case, sources)
            if not configured or consecutive_errors >= 2:
                record = dict(id=case.id, trial=repetition, input=body, expected=case.expected.model_dump(exclude_none=True),
                              request_id=body["request_id"], snapshot_id=body["context_snapshot"]["snapshot_id"],
                              status="not_run", passed=False, model_calls=0, raw_output=None, actual=None, checks=[],
                              reason="missing_provider_configuration" if not configured else "provider_failure_limit")
            else:
                record = _trial(case, body, repetition, original_completion)
                consecutive_errors = consecutive_errors + 1 if record["status"] == "provider_error" else 0
            report["cases"].append(record)
            if progress:
                print(f"{case.id} [{repetition}]: {record['status']}", flush=True)
    cases = report["cases"]
    counts = {status: sum(case["status"] == status for case in cases)
              for status in ("passed", "semantic_failed", "validation_failed", "provider_error", "not_run")}
    report["summary"] = dict(planned=len(cases), executed=sum(case["model_calls"] > 0 for case in cases),
                             model_calls=sum(case["model_calls"] for case in cases),
                             semantic_passed=counts["passed"], semantic_failed=counts["semantic_failed"],
                             validation_failed=counts["validation_failed"], provider_errors=counts["provider_error"],
                             not_run=counts["not_run"], all_passed=counts["passed"] == len(cases))
    return _sanitize(report)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--max-calls", type=int, default=8)
    parser.add_argument("--env-file", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.env_file:
            if not args.env_file.is_file():
                raise ValueError("Missing explicit environment file")
            load_dotenv(args.env_file, override=False)
        report = run_suite(load_cases(args.cases), args.repeat, args.max_calls, progress=True)
    except (ValueError, OSError):
        report = dict(schema_version=1, run_id=str(uuid4()), mode="live_model",
                      blocked_reason="invalid_evaluation_configuration", summary={"all_passed": False}, cases=[])
    try:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except OSError:
        print("Unable to write live evaluation report", file=sys.stderr)
        return 2
    summary = report["summary"]
    if report["blocked_reason"] or summary.get("not_run") or summary.get("provider_errors"):
        return 2
    print(f"Live model evaluation: {summary['semantic_passed']}/{summary['planned']} passed", flush=True)
    return 0 if summary["all_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
