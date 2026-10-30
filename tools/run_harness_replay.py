"""Run deterministic backend/JVM replay without a device, providers, or credentials."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from uuid import uuid4
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
REPORT_ROOT = ROOT / "app/build/reports/harness-replay"
ANDROID_REPORT = REPORT_ROOT / "android-cases.json"


def read_case_report(path, run_id, mode):
    data = json.loads(path.read_text(encoding="utf-8"))
    if (not isinstance(data, dict) or data.get("schema_version") != 1
            or data.get("run_id") != run_id or data.get("mode") != mode):
        raise ValueError(f"Invalid or stale replay report: {path.name}")
    cases = data.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError(f"Replay has no cases: {path.name}")
    ids = set()
    for case in cases:
        if (not isinstance(case, dict) or not isinstance(case.get("id"), str)
                or not case["id"] or case["id"] in ids or type(case.get("passed")) is not bool):
            raise ValueError(f"Invalid or duplicate case: {path.name}")
        ids.add(case["id"])
    passed = sum(case["passed"] for case in cases)
    expected = {"total": len(cases), "passed": passed, "failed": len(cases) - passed}
    if data.get("summary") != expected:
        raise ValueError(f"Summary does not match actual cases: {path.name}")
    return data


def evidence_passed(reports, commands, errors):
    return (len(reports) == 2 and bool(commands) and not errors
            and all(command["exit_code"] == 0 for command in commands)
            and all(report["summary"]["failed"] == 0 for report in reports))


def junit_counts(paths, not_before=None):
    counts = {"total": 0, "passed": 0, "failed": 0, "errors": 0, "skipped": 0}
    for path in paths:
        if not_before is not None and path.stat().st_mtime < not_before:
            raise ValueError(f"Stale JUnit evidence: {path.name}")
        for case in ET.parse(path).getroot().iter("testcase"):
            counts["total"] += 1
            if case.find("failure") is not None:
                counts["failed"] += 1
            elif case.find("error") is not None:
                counts["errors"] += 1
            elif case.find("skipped") is not None:
                counts["skipped"] += 1
            else:
                counts["passed"] += 1
    if not counts["total"]:
        raise ValueError("JUnit evidence is missing or contains no test cases")
    return counts


def run_command(name, command, output, env):
    log_path = output / f"{name}.log"
    print(f"Running {name}; log: {log_path}", flush=True)
    start = time.monotonic()
    started_at_epoch = time.time()
    with log_path.open("w", encoding="utf-8") as log:
        try:
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=log,
                                    stderr=subprocess.STDOUT, timeout=900)
            code = result.returncode
        except (OSError, subprocess.TimeoutExpired) as error:
            log.write(f"\nRunner error: {error}\n")
            code = 2
    print(f"{name}: exit {code}", flush=True)
    return {"name": name, "exit_code": code, "log": str(log_path.relative_to(ROOT)),
            "started_at_epoch": started_at_epoch,
            "seconds": round(time.monotonic() - start, 3)}


def source_revision():
    try:
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT,
                                         stderr=subprocess.DEVNULL, text=True).strip()
        status = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT,
                                         stderr=subprocess.DEVNULL, text=True).splitlines()
        return {"commit": commit, "working_tree_dirty": bool(status), "changes": status}
    except (OSError, subprocess.CalledProcessError):
        return {"commit": None, "working_tree_dirty": None, "changes": []}


def write_summary(output, summary):
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2),
                                          encoding="utf-8")
    lines = ["# Harness offline replay", "", f"Run: `{summary['run_id']}`",
             f"Result: **{'PASS' if summary['passed'] else 'FAIL'}**", "",
             "Evidence: fixed model responses and production backend/JVM validation.",
             "No live model, actual map routing, Room transaction, SDK, or device execution verified.",
             "", "| Suite | Total | Passed | Failed |", "|---|---:|---:|---:|"]
    for name, report in summary["reports"].items():
        counts = report["summary"]
        lines.append(f"| {name} | {counts['total']} | {counts['passed']} | {counts['failed']} |")
    lines.extend(["", "## Cases", "", "| Suite | Case | Result |", "|---|---|---|"])
    for name, report in summary["reports"].items():
        for case in report["cases"]:
            identity = case["id"].replace("|", "\\|").replace("\n", " ")
            lines.append(f"| {name} | {identity} | {'PASS' if case['passed'] else 'FAIL'} |")
    if summary["regression"]:
        lines.extend(["", "## Regression", "", "| Suite | Total | Passed | Failed | Errors | Skipped |",
                      "|---|---:|---:|---:|---:|---:|"])
        for name, counts in summary["regression"].items():
            values = " | ".join(str(counts[key]) for key in ("total", "passed", "failed", "errors", "skipped"))
            lines.append(f"| {name} | {values} |")
    lines.extend(["", "## Commands", ""])
    for command in summary["commands"]:
        lines.append(f"- {command['name']}: exit {command['exit_code']}; `{command['log']}`")
    if summary["errors"]:
        lines.extend(["", "## Evidence errors", ""])
        lines.extend(f"- {error}" for error in summary["errors"])
    (output / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--full-regression", action="store_true",
                        help="Also run all backend and Android JVM tests")
    parser.add_argument("--java-home", type=Path, help="JDK 17 directory; otherwise use JAVA_HOME")
    args = parser.parse_args(argv)
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid4().hex[:8]
    output = REPORT_ROOT / run_id
    output.mkdir(parents=True)
    env = dict(os.environ, PYTHON_DOTENV_DISABLED="1", PYTHONUTF8="1", ONMYWAY_REPLAY_RUN_ID=run_id)
    if args.java_home:
        env["JAVA_HOME"] = str(args.java_home.resolve())
    if env.get("JAVA_HOME"):
        env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + env.get("PATH", "")
    summary = {"schema_version": 1, "run_id": run_id, "generated_at": datetime.now(timezone.utc).isoformat(),
               "mode": "offline", "source": source_revision(), "full_regression": args.full_regression,
               "live_model_verified": False, "device_execution_verified": False,
               "reports": {}, "regression": {}, "commands": [], "errors": []}
    commands = summary["commands"]
    commands.append(run_command("backend-replay", [sys.executable, "-m", "backend.replay", "--output",
                                                   str(output / "backend-cases.json")], output, env))
    if args.full_regression:
        commands.append(run_command("backend-tests", [sys.executable, "-m", "pytest", "backend/tests", "-q",
                        f"--junitxml={output / 'backend-tests.xml'}"], output, env))
    gradle = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    command = [gradle, ":app:testDebugUnitTest", "--offline", "--rerun-tasks", "--console=plain"]
    if not args.full_regression:
        command.extend(["--tests", "com.onmyway.app.core.context.HarnessReplayTest"])
    commands.append(run_command("android-jvm", command, output, env))
    for name, path, mode in [("backend", output / "backend-cases.json", "offline"),
                             ("android", ANDROID_REPORT, "offline_jvm")]:
        try:
            data = read_case_report(path, run_id, mode)
            summary["reports"][name] = data
            if name == "android":
                shutil.copy2(path, output / "android-cases.json")
        except (OSError, ValueError, TypeError) as error:
            summary["errors"].append(f"{name}: {error}")
    junit_dir = ROOT / "app/build/test-results/testDebugUnitTest"
    suites = [("android_jvm", list(junit_dir.glob("TEST-*.xml")) if args.full_regression else
               [junit_dir / "TEST-com.onmyway.app.core.context.HarnessReplayTest.xml"])]
    if args.full_regression:
        suites.insert(0, ("backend", [output / "backend-tests.xml"]))
    for name, paths in suites:
        try:
            command_name = "backend-tests" if name == "backend" else "android-jvm"
            started_at = next(item["started_at_epoch"] for item in commands if item["name"] == command_name)
            counts = junit_counts(paths, not_before=started_at)
            summary["regression"][name] = counts
            if counts["failed"] or counts["errors"] or counts["skipped"]:
                summary["errors"].append(f"{name}: regression includes failed, errored or skipped tests")
            if name == "android_jvm":
                archive = output / "android-junit"
                archive.mkdir()
                for path in paths:
                    shutil.copy2(path, archive / path.name)
        except (OSError, ValueError, ET.ParseError) as error:
            summary["errors"].append(f"{name}: {error}")
    summary["passed"] = evidence_passed(list(summary["reports"].values()), commands, summary["errors"])
    write_summary(output, summary)
    print(f"{'PASS' if summary['passed'] else 'FAIL'}: {output / 'summary.md'}", flush=True)
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
