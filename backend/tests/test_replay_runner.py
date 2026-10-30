"""The offline entry point must never reuse stale or incomplete evidence."""
import importlib.util
import json
import os
from pathlib import Path

import pytest


@pytest.fixture
def runner():
    path = Path(__file__).resolve().parents[2] / "tools" / "run_harness_replay.py"
    if not path.exists():
        pytest.fail("The offline replay entry point has not been implemented")
    spec = importlib.util.spec_from_file_location("replay_entry_point", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def report(tmp_path, **updates):
    data = {
        "schema_version": 1, "run_id": "this-run", "mode": "offline",
        "summary": {"total": 1, "passed": 1, "failed": 0},
        "cases": [{"id": "cancel-print", "passed": True}],
    }
    data.update(updates)
    path = tmp_path / "cases.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    return path


def test_reads_current_successful_case_report(runner, tmp_path):
    data = runner.read_case_report(report(tmp_path), "this-run", "offline")
    assert data["cases"][0]["id"] == "cancel-print"


@pytest.mark.parametrize("updates", [
    {"run_id": "previous-run"},
    {"mode": "live"},
    {"schema_version": 2},
    {"cases": [], "summary": {"total": 0, "passed": 0, "failed": 0}},
    {"summary": {"total": 1, "passed": 0, "failed": 1}},
    {"cases": [{"id": "a", "passed": "true"}]},
    {"cases": [{"id": "a", "passed": True}, {"id": "a", "passed": True}],
     "summary": {"total": 2, "passed": 2, "failed": 0}},
])
def test_rejects_stale_or_misleading_evidence(runner, tmp_path, updates):
    with pytest.raises(ValueError):
        runner.read_case_report(report(tmp_path, **updates), "this-run", "offline")


def test_preserves_failed_case_instead_of_raising_or_hiding_it(runner, tmp_path):
    path = report(tmp_path, cases=[{"id": "cancel-print", "passed": False}],
                  summary={"total": 1, "passed": 0, "failed": 1})
    assert runner.read_case_report(path, "this-run", "offline")["summary"]["failed"] == 1


def test_failed_command_cannot_be_overruled_by_green_reports(runner):
    reports = [{"summary": {"total": 1, "passed": 1, "failed": 0}}] * 2
    assert not runner.evidence_passed(reports, [{"exit_code": 1}], [])
    assert not runner.evidence_passed(reports[:1], [{"exit_code": 0}], [])
    assert not runner.evidence_passed(reports, [{"exit_code": 0}], ["missing XML"])
    assert runner.evidence_passed(reports, [{"exit_code": 0}], [])


def test_junit_counts_real_testcases_without_double_counting_suites(runner, tmp_path):
    path = tmp_path / "results.xml"
    path.write_text('<testsuites><testsuite tests="3"><testcase name="ok"/>'
                    '<testcase name="bad"><failure>mismatch</failure></testcase>'
                    '<testcase name="skip"><skipped/></testcase></testsuite></testsuites>',
                    encoding="utf-8")
    assert runner.junit_counts([path]) == {
        "total": 3, "passed": 1, "failed": 1, "errors": 0, "skipped": 1,
    }


def test_missing_junit_cannot_count_as_zero_test_success(runner):
    with pytest.raises(ValueError):
        runner.junit_counts([])


def test_junit_from_previous_run_is_rejected(runner, tmp_path):
    path = tmp_path / "old.xml"
    path.write_text('<testsuite><testcase name="old-pass"/></testsuite>', encoding="utf-8")
    os.utime(path, (10, 10))
    with pytest.raises(ValueError, match="Stale JUnit"):
        runner.junit_counts([path], not_before=20)


@pytest.mark.parametrize("skipped,exit_code", [(True, 1), (False, 0)])
def test_default_entry_point_checks_jvm_skips(runner, tmp_path, monkeypatch, skipped, exit_code):
    root = tmp_path / "repo"
    report_root = root / "app/build/reports/harness-replay"
    android_report = report_root / "android-cases.json"
    junit_dir = root / "app/build/test-results/testDebugUnitTest"
    junit_dir.mkdir(parents=True)
    monkeypatch.setattr(runner, "ROOT", root)
    monkeypatch.setattr(runner, "REPORT_ROOT", report_root)
    monkeypatch.setattr(runner, "ANDROID_REPORT", android_report)
    monkeypatch.setattr(runner, "source_revision", lambda: {})

    def fake_command(name, command, output, env):
        data = json.loads(report(tmp_path, run_id=env["ONMYWAY_REPLAY_RUN_ID"]).read_text(encoding="utf-8"))
        if name == "backend-replay":
            path = output / "backend-cases.json"
        else:
            data["mode"] = "offline_jvm"
            path = android_report
            extra = '<testcase name="skipped"><skipped/></testcase>' if skipped else ''
            (junit_dir / "TEST-com.onmyway.app.core.context.HarnessReplayTest.xml").write_text(
                f'<testsuite><testcase name="pass"/>{extra}</testsuite>', encoding="utf-8")
        path.write_text(json.dumps(data), encoding="utf-8")
        return {"name": name, "exit_code": 0, "log": "fake.log", "started_at_epoch": 0}

    monkeypatch.setattr(runner, "run_command", fake_command)
    assert runner.main([]) == exit_code
    summary_path = next(report_root.glob("*/summary.json"))
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    assert summary["regression"]["android_jvm"]["skipped"] == int(skipped)
