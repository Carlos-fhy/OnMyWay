"""Offline replay must exercise production boundaries and expose failed expectations."""
import copy
import importlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest


def replay_module():
    assert importlib.util.find_spec("backend.replay") is not None, "offline replay entrypoint is missing"
    return importlib.import_module("backend.replay")


def test_reference_cases_have_actual_results_and_explicit_evidence_scope():
    replay = replay_module()
    report = replay.run_suite(replay.load_cases())
    assert report["mode"] == "offline"
    assert report["summary"]["total"] >= 7
    assert report["summary"]["passed"] == report["summary"]["total"]
    assert report["summary"]["failed"] == 0
    assert "live_model_semantics" in report["not_verified"]
    assert "android_confirmation_transactions" in report["not_verified"]
    for case in report["cases"]:
        assert case["passed"]
        assert case["steps"]
        for step in case["steps"]:
            assert step["input"]["context_snapshot"]["snapshot_id"]
            assert step["checks"]
            assert all(check["passed"] for check in step["checks"])
            assert "status_code" in step["actual"]
            assert "ledger" in step["actual"]


def test_wrong_expected_kept_task_fails_and_other_cases_still_run():
    replay = replay_module()
    suite = replay.load_cases()
    suite["cases"][0]["steps"][0]["expected"]["body"]["keep_task_ids"] = ["print"]
    report = replay.run_suite(suite)
    assert report["summary"]["failed"] == 1
    assert len(report["cases"]) == len(suite["cases"])
    failed = [check for check in report["cases"][0]["steps"][0]["checks"] if not check["passed"]]
    assert any(check["path"] == "body.keep_task_ids" and check["actual"] == ["parcel"] for check in failed)


def test_invalid_model_output_is_rejected_by_real_validator():
    replay = replay_module()
    suite = replay.load_cases()
    case = copy.deepcopy(suite["cases"][0])
    case["model_responses"][0]["value"]["keep_task_ids"] = ["invented-task"]
    case["steps"] = case["steps"][:1]
    suite["cases"] = [case]
    result = replay.run_suite(suite)["cases"][0]
    assert not result["passed"]
    assert result["steps"][0]["actual"]["status_code"] == 502
    assert result["steps"][0]["actual"]["ledger"][0]["state"] == "failed"


@pytest.mark.parametrize("snapshot,status_code", [(None, 200), ([], 422), (7, 422)])
def test_null_or_malformed_snapshot_is_reported_without_aborting_suite(snapshot, status_code):
    replay = replay_module()
    suite = replay.load_cases()
    case = suite["cases"][0]
    case["request"]["context_snapshot"] = snapshot
    case["steps"] = case["steps"][:1]
    if status_code == 422:
        case["steps"][0]["expected"] = {
            "status_code": 422, "body": {}, "model_calls": 0, "ledger_states": [],
        }
    result = replay.run_suite(suite)
    assert len(result["cases"]) == len(suite["cases"])
    step = result["cases"][0]["steps"][0]
    assert step["snapshot_id"] is None
    assert step["actual"]["status_code"] == status_code
    assert result["summary"]["failed"] == 0


def test_run_is_isolated_and_restores_patched_dependencies(tmp_path, monkeypatch):
    replay = replay_module()
    from backend import assistant, context, harness
    database = tmp_path / "existing.sqlite3"
    database.write_bytes(b"untouched-user-database")
    monkeypatch.setenv("ONMYWAY_HARNESS_DB", str(database))
    before = (assistant.completion, context.time, harness.time)
    first = replay.run_suite(replay.load_cases())
    second = replay.run_suite(replay.load_cases())
    assert first["cases"] == second["cases"]
    assert database.read_bytes() == b"untouched-user-database"
    assert os.environ["ONMYWAY_HARNESS_DB"] == str(database)
    assert (assistant.completion, context.time, harness.time) == before


@pytest.mark.parametrize("change", ["empty", "duplicate_id", "missing_expected", "unknown_endpoint"])
def test_invalid_case_configuration_is_not_reported_as_passing(change):
    replay = replay_module()
    suite = replay.load_cases()
    if change == "empty":
        suite["cases"] = []
    elif change == "duplicate_id":
        suite["cases"].append(copy.deepcopy(suite["cases"][0]))
    elif change == "missing_expected":
        del suite["cases"][0]["steps"][0]["expected"]
    else:
        suite["cases"][0]["steps"][0]["endpoint"] = "https://example.invalid"
    with pytest.raises(ValueError):
        replay.run_suite(suite)


@pytest.mark.parametrize("outcome,exit_code", [("pass", 0), ("fail", 1), ("invalid", 2)])
def test_cli_writes_report_and_returns_meaningful_exit_code(tmp_path, outcome, exit_code):
    replay = replay_module()
    suite = replay.load_cases()
    if outcome == "fail":
        suite["cases"][0]["steps"][0]["expected"]["status_code"] = 418
    elif outcome == "invalid":
        suite["cases"] = []
    source, output = tmp_path / "cases.json", tmp_path / "nested" / "report.json"
    source.write_text(json.dumps(suite, ensure_ascii=False), encoding="utf-8")
    process = subprocess.run(
        [sys.executable, "-m", "backend.replay", "--cases", str(source), "--output", str(output)],
        cwd=Path(__file__).resolve().parents[2], capture_output=True, text=True, encoding="utf-8",
        env={**os.environ, "PYTHONUTF8": "1"}, timeout=30,
    )
    assert process.returncode == exit_code, process.stderr
    if outcome != "invalid":
        report = json.loads(output.read_text(encoding="utf-8"))
        assert report["summary"]["failed"] == (1 if outcome == "fail" else 0)
    else:
        assert not output.exists()
