"""Offline, isolated API replay; fixed responses do not measure model understanding.

Run from the repository root: python -m backend.replay --output report.json
This module deliberately does not import backend.main or load a .env file.
"""
import argparse
from contextlib import ExitStack, closing
import copy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sqlite3
import sys
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from typing import Literal
from unittest.mock import patch
from uuid import uuid4

from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
import httpx
from pydantic import BaseModel, ConfigDict, Field, model_validator

from . import assistant, context, harness


DEFAULT_CASES = Path(__file__).with_name("evals") / "harness_cases.json"
NOT_VERIFIED = ["live_model_semantics", "android_confirmation_transactions",
                "device_navigation", "real_map_routing"]


class FixtureModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, protected_namespaces=())


class Expected(FixtureModel):
    status_code: int = Field(ge=100, le=599)
    body: dict
    model_calls: int = Field(ge=0)
    ledger_states: list[str]


class Step(FixtureModel):
    name: str = Field(min_length=1)
    endpoint: Literal["submit", "status"]
    at_ms: int = Field(ge=0)
    request_override: dict = Field(default_factory=dict)
    expected: Expected


class ModelResponse(FixtureModel):
    value: dict | None = None
    error_status: int | None = Field(default=None, ge=400, le=599)

    @model_validator(mode="after")
    def one_result(self):
        if (self.value is None) == (self.error_status is None):
            raise ValueError("Provide exactly one fixed response or failure status")
        return self


class Case(FixtureModel):
    id: str = Field(min_length=1)
    description: str = Field(min_length=1)
    operation: Literal["chat", "navigation"]
    request: dict
    model_responses: list[ModelResponse]
    steps: list[Step] = Field(min_length=1)


class Suite(FixtureModel):
    schema_version: Literal[1]
    suite_id: str = Field(min_length=1)
    cases: list[Case] = Field(min_length=1)

    @model_validator(mode="after")
    def unique_cases(self):
        if len({case.id for case in self.cases}) != len(self.cases):
            raise ValueError("Case ids must be unique")
        return self


def load_cases(path=DEFAULT_CASES):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    Suite.model_validate(data)
    return data


def _checks(expected, actual, prefix=""):
    """Compare dictionaries as subsets, but lists/scalars exactly, with visible paths."""
    checks = []
    for key, wanted in expected.items():
        name = f"{prefix}.{key}" if prefix else key
        present = isinstance(actual, dict) and key in actual
        observed = actual.get(key) if present else None
        if isinstance(wanted, dict) and isinstance(observed, dict):
            checks.extend(_checks(wanted, observed, name))
        else:
            checks.append(dict(path=name, expected=wanted, actual=observed,
                               passed=present and observed == wanted))
    return checks


def _ledger(path):
    if not path.exists():
        return []
    with closing(sqlite3.connect(path)) as db:
        rows = db.execute(
            "SELECT request_id,operation,state,error_status FROM intent_runs ORDER BY request_id"
        ).fetchall()
    return [dict(zip(("request_id", "operation", "state", "error_status"), row)) for row in rows]


def _run_case(case):
    app = FastAPI()
    app.include_router(assistant.router)
    calls = 0
    clock = SimpleNamespace(now=case.steps[0].at_ms)
    frozen_time = SimpleNamespace(time=lambda: clock.now / 1000)

    def completion(*_args, **_kwargs):
        nonlocal calls
        index = calls
        calls += 1
        if index >= len(case.model_responses):
            raise RuntimeError("Unexpected additional model call in offline replay")
        response = case.model_responses[index]
        if response.error_status is not None:
            raise HTTPException(response.error_status, "Fixed offline provider failure")
        return json.dumps(response.value, ensure_ascii=False)

    def no_network(*_args, **_kwargs):
        raise RuntimeError("Network access is disabled during offline replay")

    steps = []
    with TemporaryDirectory(prefix="onmyway-replay-") as directory, ExitStack() as stack:
        database = Path(directory) / "harness.sqlite3"
        stack.enter_context(patch.dict(os.environ, {"ONMYWAY_HARNESS_DB": str(database)}))
        stack.enter_context(patch.object(assistant, "completion", completion))
        stack.enter_context(patch.object(context, "time", frozen_time))
        stack.enter_context(patch.object(harness, "time", frozen_time))
        # The in-process TestClient has its own transport; real HTTP transports fail closed.
        stack.enter_context(patch.object(httpx.HTTPTransport, "handle_request", no_network))
        stack.enter_context(patch.object(httpx.AsyncHTTPTransport, "handle_async_request", no_network))
        client = stack.enter_context(TestClient(app, raise_server_exceptions=False))
        for step in case.steps:
            clock.now = step.at_ms
            body = copy.deepcopy(case.request)
            body.update(copy.deepcopy(step.request_override))
            endpoint = f"/assistant/{case.operation}" + ("/status" if step.endpoint == "status" else "")
            response = client.post(endpoint, json=body)
            try:
                response_body = response.json()
            except ValueError:
                response_body = {"detail": "Non-JSON server response"}
            ledger = _ledger(database)
            actual = dict(status_code=response.status_code, body=response_body, model_calls=calls,
                          ledger=ledger, ledger_states=[row["state"] for row in ledger])
            expected = step.expected.model_dump()
            checks = _checks(expected, actual)
            snapshot = body.get("context_snapshot")
            steps.append(dict(name=step.name, endpoint=endpoint, at_ms=step.at_ms,
                              request_id=body.get("request_id"),
                              snapshot_id=snapshot.get("snapshot_id") if isinstance(snapshot, dict) else None,
                              input=body, expected=expected, actual=actual, checks=checks,
                              passed=all(check["passed"] for check in checks)))
    return dict(id=case.id, description=case.description,
                evidence_scope="in_process_api_contract_with_fixed_model_responses",
                model_responses=[item.model_dump(exclude_none=True) for item in case.model_responses],
                passed=all(step["passed"] for step in steps), steps=steps)


def run_suite(suite=None):
    """Run serially: isolated database per case and restored process-local patches."""
    parsed = Suite.model_validate(load_cases() if suite is None else suite)
    cases = [_run_case(case) for case in parsed.cases]
    passed = sum(case["passed"] for case in cases)
    return dict(schema_version=1, suite_id=parsed.suite_id, mode="offline",
                run_id=os.environ.get("ONMYWAY_REPLAY_RUN_ID") or str(uuid4()),
                generated_at=datetime.now(timezone.utc).isoformat(),
                evidence_scope="fixed model responses, production API validation and SQLite request ledger",
                not_verified=NOT_VERIFIED,
                summary=dict(total=len(cases), passed=passed, failed=len(cases) - passed), cases=cases)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        report = run_suite(load_cases(args.cases))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (ValueError, OSError) as error:
        print(f"Replay configuration/output error: {error}", file=sys.stderr)
        return 2
    summary = report["summary"]
    print(f"Offline replay: {summary['passed']}/{summary['total']} passed; {summary['failed']} failed")
    return 1 if summary["failed"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
