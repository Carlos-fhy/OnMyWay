"""Run deterministic HTTP memory acceptance and write auditable per-case evidence.

Usage: python tools/evaluate_personal_memory.py
The suite uses production HTTP routes and temporary databases, with fixture model
outputs. It never calls a real model or device and does not measure map quality.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
SOURCES = (
    "backend/memory/personal.py", "backend/memory/assistant.py",
    "backend/assistant/api.py", "backend/execution/events.py", "backend/main.py",
    "backend/tests/memory/test_personal_memory_flow.py", "tools/evaluate_personal_memory.py",
)


def source_hashes():
    return {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in SOURCES if (ROOT / name).exists()}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "app/build/reports/personal-memory-20261107")
    args = parser.parse_args()
    started = datetime.now(timezone.utc)
    run_id = started.strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    output = args.output.resolve() / run_id
    output.mkdir(parents=True)
    xml_path = output / "results.xml"
    command = [sys.executable, "-m", "pytest", "backend/tests/memory/test_personal_memory_flow.py", "-q",
               "-o", "junit_family=legacy", f"--junitxml={xml_path}"]
    environment = dict(os.environ, PYTHONUTF8="1", PYTHONIOENCODING="utf-8")
    sources_before = source_hashes()
    process = subprocess.run(command, cwd=ROOT, env=environment, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    sources_after = source_hashes()
    (output / "pytest.log").write_text(process.stdout, encoding="utf-8")
    cases = []
    if xml_path.exists():
        for case in ET.parse(xml_path).getroot().iter("testcase"):
            properties = {p.attrib["name"]: p.attrib.get("value", "") for p in case.findall("./properties/property")}
            failure = case.find("failure")
            error = case.find("error")
            skipped = case.find("skipped")
            status = "failed" if failure is not None or error is not None else "not_run" if skipped is not None else "passed"
            row = dict(id=case.attrib.get("name"), scenario=properties.get("scenario", ""), status=status,
                       seconds=float(case.attrib.get("time", 0)), evidence_kind="production_http_with_fixture_model")
            for name in ("input", "actual"):
                if name in properties:
                    try:
                        row[name] = json.loads(properties[name])
                    except ValueError:
                        row[name] = properties[name]
            if failure is not None or error is not None:
                row["failure"] = (failure if failure is not None else error).text
            cases.append(row)
    counts = {status: sum(case["status"] == status for case in cases) for status in ("passed", "failed", "not_run")}
    report = dict(
        schema_version=1, run_id=run_id, started_at=started.isoformat(),
        finished_at=datetime.now(timezone.utc).isoformat(),
        suite="personal_memory_http_acceptance", command=command, exit_code=process.returncode,
        counts=counts, cases=cases,
        boundaries=dict(real_model="not_run", android_device="not_run", real_map="not_run",
                        provider_error_case="simulated failure-handling assertion; not a real provider benchmark",
                        retrieval_or_answer_quality="fixture checks only; no claim of real-model understanding"),
        source_sha256=sources_after, source_changed_during_run=sources_before != sources_after,
    )
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = ["# 个人记忆 HTTP 场景验收", "", f"运行：`{run_id}`", "",
             "生产 HTTP 接口、临时数据库、固定模型响应。未运行真实模型、真实地图或 Android 设备。",
             "模拟 provider_error 用例只验证失败处理；不能计作真实模型成功。未执行项单列为 not_run。", "",
             f"结果：{counts['passed']} 通过，{counts['failed']} 失败，{counts['not_run']} 未运行。", "",
             "| 场景 | 结果 |", "|---|---|"]
    lines.extend(f"| {case['scenario'] or case['id']} | {case['status']} |" for case in cases)
    lines += ["", "逐项输入、实际结果、证据编号及源码 SHA-256 见 report.json；完整输出见 pytest.log。", ""]
    if sources_before != sources_after:
        lines += ["源码在运行期间发生变化，本轮不能作为固定版本的验收证据，需要重新执行。", ""]
    (output / "summary.md").write_text("\n".join(lines), encoding="utf-8")
    print(f"Report: {output / 'summary.md'}")
    print(f"passed={counts['passed']} failed={counts['failed']} not_run={counts['not_run']} exit={process.returncode}")
    return process.returncode if process.returncode else (
        0 if cases and counts["failed"] == counts["not_run"] == 0 and sources_before == sources_after else 1)


if __name__ == "__main__":
    raise SystemExit(main())
