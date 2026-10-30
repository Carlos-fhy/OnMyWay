"""Live evaluation orchestration is tested with a synthetic provider, never a paid call."""
import copy
import importlib
import importlib.util
import json
import os
from pathlib import Path

import pytest
import httpx
from fastapi import HTTPException

from backend import assistant


def evaluation():
    assert importlib.util.find_spec("backend.model_eval") is not None, "live model evaluation entry point is missing"
    return importlib.import_module("backend.model_eval")


def suite(*cases):
    default = {
        "id": "cancel-print", "source_case": "cancel_print_keep_parcel_dorm",
        "request_override": {"text": "不打印了，取快递后回宿舍"},
        "expected": {"action": "adjust", "keep_task_ids": ["parcel"], "effective_destination": "宿舍"},
        "requires_reply": True,
    }
    return {"schema_version": 1, "suite_id": "synthetic-live-test", "cases": list(cases) or [default]}


def output(**updates):
    return json.dumps({"action": "adjust", "keep_task_ids": ["parcel"],
                       "destination": "宿舍", "reply": "保留快递，回宿舍，请确认。", **updates}, ensure_ascii=False)


@pytest.fixture(autouse=True)
def synthetic_configuration(monkeypatch):
    monkeypatch.setenv("DASHSCOPE_API_KEY", "test-secret-key")
    monkeypatch.setenv("DASHSCOPE_BASE_URL", "https://provider.invalid/compatible-mode/v1")
    monkeypatch.setenv("AGENT_MODEL", "synthetic-model")
    monkeypatch.setattr(assistant, "completion", lambda *_args, **_kwargs: pytest.fail("unexpected model call"))


def test_live_path_uses_real_validator_and_preserves_raw_output(monkeypatch):
    evaluator = evaluation()
    calls = []
    def complete(messages, model, structured=False):
        calls.append((messages, model, structured))
        return output()
    monkeypatch.setattr(assistant, "completion", complete)
    manifest = suite()
    before = copy.deepcopy(manifest)
    report = evaluator.run_suite(manifest)
    assert report["mode"] == "live_model"
    assert report["summary"]["all_passed"]
    assert report["summary"]["executed"] == 1
    case = report["cases"][0]
    assert case["status"] == "passed" and case["passed"]
    assert case["raw_output"] == output()
    assert case["actual"]["keep_task_ids"] == ["parcel"]
    assert len(case["prompt_hash"]) == 64
    assert case["model_calls"] == 1
    assert case["synthetic_time_rebased"] is True
    assert report["configuration"]["model"] == "synthetic-model"
    assert report["configuration"]["provider_host"] == "provider.invalid"
    assert "device_execution" in report["not_verified"]
    assert calls[0][1:] == ("synthetic-model", True)
    assert manifest == before


def test_repeats_use_fresh_request_identity_and_isolated_database(tmp_path, monkeypatch):
    evaluator = evaluation()
    database = tmp_path / "user.sqlite3"
    database.write_bytes(b"do-not-touch")
    monkeypatch.setenv("ONMYWAY_HARNESS_DB", str(database))
    original = lambda *_args, **_kwargs: output()
    monkeypatch.setattr(assistant, "completion", original)
    report = evaluator.run_suite(suite(), repeat=2, max_calls=2)
    assert report["summary"]["semantic_passed"] == 2
    assert len({case["request_id"] for case in report["cases"]}) == 2
    assert len({case["snapshot_id"] for case in report["cases"]}) == 2
    assert database.read_bytes() == b"do-not-touch"
    assert os.environ["ONMYWAY_HARNESS_DB"] == str(database)
    assert assistant.completion is original


@pytest.mark.parametrize("destination,proposed,expected,status", [
    ("", "", "宿舍", "passed"),
    ("", "图书馆", "图书馆", "passed"),
    ("", "图书馆", "宿舍", "semantic_failed"),
])
def test_effective_destination_follows_production_inheritance(monkeypatch, destination, proposed, expected, status):
    evaluator = evaluation()
    manifest = suite()
    manifest["cases"][0]["request_override"]["proposed_destination"] = proposed
    manifest["cases"][0]["expected"]["effective_destination"] = expected
    monkeypatch.setattr(assistant, "completion", lambda *_args, **_kwargs: output(destination=destination))
    report = evaluator.run_suite(manifest)
    assert report["cases"][0]["status"] == status


def test_wrong_task_is_semantic_failure_not_provider_failure(monkeypatch):
    evaluator = evaluation()
    monkeypatch.setattr(assistant, "completion", lambda *_args, **_kwargs: output(keep_task_ids=["print"]))
    report = evaluator.run_suite(suite())
    assert report["summary"]["semantic_failed"] == 1
    assert report["summary"]["provider_errors"] == 0
    assert not report["summary"]["all_passed"]
    assert any(not check["passed"] and check["path"] == "keep_task_ids" for check in report["cases"][0]["checks"])


def test_invalid_model_ids_are_rejected_by_production_validation(monkeypatch):
    evaluator = evaluation()
    monkeypatch.setattr(assistant, "completion", lambda *_args, **_kwargs: output(keep_task_ids=["invented"]))
    report = evaluator.run_suite(suite())
    assert report["summary"]["validation_failed"] == 1
    assert report["cases"][0]["http_status"] == 502
    assert report["cases"][0]["raw_output"] == output(keep_task_ids=["invented"])
    assert not report["summary"]["all_passed"]


def test_two_provider_errors_stop_without_retry_and_keep_unrun_trials(monkeypatch):
    evaluator = evaluation()
    calls = []
    def fail(*_args, **_kwargs):
        calls.append(1)
        raise HTTPException(502, "must not retain test-secret-key or provider credentials")
    monkeypatch.setattr(assistant, "completion", fail)
    report = evaluator.run_suite(suite(), repeat=3, max_calls=3)
    assert len(calls) == 2
    assert report["summary"]["planned"] == 3
    assert report["summary"]["executed"] == 2
    assert report["summary"]["provider_errors"] == 2
    assert report["summary"]["not_run"] == 1
    assert report["cases"][-1]["status"] == "not_run"
    assert report["cases"][-1]["input"]["text"]
    assert "test-secret-key" not in json.dumps(report)


def test_timeout_diagnostic_records_type_without_exception_text(monkeypatch):
    def fail(*_args, **_kwargs):
        try:
            raise httpx.ReadTimeout('test-secret-key in private transport details')
        except httpx.ReadTimeout:
            raise HTTPException(502, 'provider temporarily unavailable') from None
    monkeypatch.setattr(assistant, 'completion', fail)
    result = evaluation().run_suite(suite())["cases"][0]
    assert result["status"] == 'provider_error'
    assert result["provider_error_type"] == 'ReadTimeout'
    assert 'private transport details' not in json.dumps(result)


def test_missing_config_is_blocked_before_call_and_reports_no_success(monkeypatch):
    evaluator = evaluation()
    monkeypatch.delenv("DASHSCOPE_API_KEY")
    report = evaluator.run_suite(suite())
    assert report["summary"]["executed"] == 0
    assert report["summary"]["not_run"] == 1
    assert report["blocked_reason"] == "missing_provider_configuration"
    assert not report["summary"]["all_passed"]


@pytest.mark.parametrize("repeat,budget", [(2, 1), (4, 8), (1, 33), (1, 0)])
def test_invalid_budget_cannot_make_calls(repeat, budget):
    with pytest.raises(ValueError):
        evaluation().run_suite(suite(), repeat=repeat, max_calls=budget)


def test_clarify_does_not_score_arbitrary_task_list_but_requires_reply(monkeypatch):
    evaluator = evaluation()
    manifest = suite()
    manifest["cases"][0]["source_case"] = "clarify_missing_pickup_place"
    manifest["cases"][0]["expected"] = {"action": "clarify"}
    monkeypatch.setattr(assistant, "completion", lambda *_args, **_kwargs: output(
        action="clarify", destination="", keep_task_ids=[], reply="请问在哪个快递站领取？"))
    report = evaluator.run_suite(manifest)
    assert report["summary"]["all_passed"]
    assert report["cases"][0]["human_review_required"] is True
    monkeypatch.setattr(assistant, "completion", lambda *_args, **_kwargs: output(action="clarify", reply=""))
    assert evaluation().run_suite(manifest)["summary"]["semantic_failed"] == 1


@pytest.mark.parametrize("change", ["empty", "duplicate", "unknown_source", "invalid_expectation"])
def test_invalid_manifest_fails_before_provider(change):
    manifest = suite()
    if change == "empty":
        manifest["cases"] = []
    elif change == "duplicate":
        manifest["cases"].append(copy.deepcopy(manifest["cases"][0]))
    elif change == "unknown_source":
        manifest["cases"][0]["source_case"] = "unknown"
    else:
        manifest["cases"][0]["expected"]["keep_task_ids"] = ["parcel", "parcel"]
    with pytest.raises(ValueError):
        evaluation().run_suite(manifest)


def test_secrets_in_raw_output_or_endpoint_are_not_recorded(monkeypatch):
    evaluator = evaluation()
    monkeypatch.setenv("DASHSCOPE_BASE_URL", "https://login:password@provider.invalid/v1?token=private-query")
    monkeypatch.setattr(assistant, "completion", lambda *_args, **_kwargs: output(
        reply="test-secret-key password private-query"))
    report = evaluator.run_suite(suite())
    serialized = json.dumps(report)
    for secret in ("test-secret-key", "password", "private-query", "login:"):
        assert secret not in serialized
    assert report["configuration"]["provider_host"] == "provider.invalid"


def test_cli_writes_blocked_report_without_implicitly_loading_env(tmp_path, monkeypatch):
    evaluator = evaluation()
    monkeypatch.delenv("DASHSCOPE_API_KEY")
    source = tmp_path / "cases.json"
    output_path = tmp_path / "report.json"
    source.write_text(json.dumps(suite()), encoding="utf-8")
    monkeypatch.setattr(evaluator, "load_dotenv", lambda *_args, **_kwargs: pytest.fail("implicit .env read"))
    assert evaluator.main(["--cases", str(source), "--output", str(output_path)]) == 2
    report = json.loads(output_path.read_text(encoding="utf-8"))
    assert report["blocked_reason"] == "missing_provider_configuration"
