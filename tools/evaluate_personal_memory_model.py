"""Three synthetic cases through production routes and temporary stores.

Run explicitly: python tools/evaluate_personal_memory_model.py --env-file backend/.env
Credentials are loaded only for the authorized provider calls and never reported.
The first provider failure stops subsequent calls; no retries are performed.
Use --replay-report report.json to replay recorded outputs with networking blocked.
"""
from __future__ import annotations

import argparse
from contextlib import ExitStack
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import time
from unittest.mock import patch
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from dotenv import load_dotenv
from fastapi import FastAPI
from fastapi.testclient import TestClient

from backend.assistant import api as assistant
from backend.execution import events
from backend.memory import personal as personal_memory
from backend.evaluation.model import _sanitize


CASES = (
    ("learn_explicit_preference", "以后出门我优先步行", "first-conversation"),
    ("recall_in_new_conversation", "我要回宿舍", "second-conversation"),
    ("bounded_personal_context", "这周希望少走路", "third-conversation"),
)


def load_replay(path):
    report = json.loads(path.read_text(encoding='utf-8'))
    rows = report.get('cases', [])
    if len(rows) != len(CASES) or [row.get('id') for row in rows] != [case[0] for case in CASES]:
        raise ValueError('Replay requires the three original cases in order')
    for row, (_, text, session) in zip(rows, CASES):
        raw = row.get('raw_output')
        request = row.get('input', {})
        if not isinstance(raw, str) or not raw.strip() or not request.get('now'):
            raise ValueError('Replay requires recorded raw output and original request time')
        if (request.get('messages') != [dict(role='user', content=text)]
                or request.get('memory_session_id') != session
                or datetime.fromisoformat(request['now']).utcoffset() is None):
            raise ValueError('Replay input must match the synthetic case and include a time zone')
    return report


def grade(case_id, request, result, state):
    active = [item for item in state["items"] if item["status"] == "active"]
    checks = []

    def check(name, expected, actual):
        checks.append(dict(name=name, expected=expected, actual=actual, passed=expected == actual))

    context = result.get('memory_context', {})
    check('no_uncommitted_memory_candidates', [], result.get('memory_actions', []))
    if case_id != 'recall_in_new_conversation':
        feedback = context.get('feedback', '')
        check('confirmed_write_status', 'applied', context.get('write_status'))
        check('one_confirmed_write', 1, len(context.get('applied_ids', [])))
        check('feedback_version', 1, context.get('feedback_version'))
        check('receipt_feedback_present', True, bool(feedback))
        check('reply_is_receipt_feedback', feedback, result.get('reply'))
    if case_id == "learn_explicit_preference":
        items = [item for item in active if item["kind"] == "preference" and item["quote"] == CASES[0][1]]
        check("one_source_bound_preference", 1, len(items))
        check("source_is_current_request", True, bool(items) and items[0]["source_id"] == "chat:" + request["request_id"])
    elif case_id == "recall_in_new_conversation":
        items = [item for item in active if item["kind"] == "preference" and item["quote"] == CASES[0][1]]
        check("action", "plan_route", result.get("action"))
        check("mode", "walking", result.get("mode"))
        check('mode_selected_by_program_from_memory', 'memory', context.get('mode_source'))
        check("evidence_contains_learned_preference", True, bool(items) and items[0]["id"] in result.get("memory_context", {}).get("evidence_ids", []))
    else:
        items = [item for item in active if item["kind"] == "context" and item["quote"] == CASES[2][1]]
        check("one_temporary_context", 1, len(items))
        check("cross_session_personal_scope", "personal", items[0]["scope"] if items else None)
        expiry = datetime.fromisoformat(items[0]["valid_until"].replace("Z", "+00:00")) if items and items[0]["valid_until"] else None
        now = datetime.fromisoformat(request["now"])
        check("bounded_future_expiry", True, expiry is not None and now < expiry <= now + timedelta(days=7))
        check("not_promoted_to_long_term", False, any(item["kind"] == "preference" and item["quote"] == CASES[2][1] for item in active))
        check('no_invented_mode', '', result.get('mode', ''))
        check('explicit_choice_required', 'choice_required', context.get('mode_constraint'))
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--env-file", type=Path)
    source.add_argument("--replay-report", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "app/build/reports/personal-memory-20261107")
    args = parser.parse_args()
    replay = load_replay(args.replay_report) if args.replay_report else None
    if args.env_file:
        if not args.env_file.is_file():
            raise SystemExit("Explicit configuration file is unavailable; no model calls made.")
        load_dotenv(args.env_file, override=False)
    configured = replay is not None or bool(os.getenv("DASHSCOPE_API_KEY", "").strip() and os.getenv("DASHSCOPE_BASE_URL", "").strip())
    started = datetime.now(timezone.utc)
    run_id = ("replay-" if replay else "model-") + started.strftime("%Y%m%dT%H%M%SZ") + "-" + uuid4().hex[:8]
    output = args.output.resolve() / run_id
    output.mkdir(parents=True)
    model_name = replay.get('model', 'recorded') if replay else os.getenv("AGENT_MODEL", "qwen3.8-max")
    original_completion = assistant.completion
    stopped = not configured
    records = []
    application = FastAPI()
    application.include_router(assistant.router)
    application.include_router(personal_memory.router)
    application.include_router(events.router)
    token = "synthetic-live-memory-eval-" + uuid4().hex
    headers = {"X-Backup-Token": token}
    sources = ("backend/assistant/api.py", "backend/memory/assistant.py", "backend/memory/personal.py", "backend/execution/events.py", "tools/evaluate_personal_memory_model.py")
    source_hashes = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in sources}
    with TemporaryDirectory(prefix="onmyway-memory-model-") as directory, ExitStack() as stack:
        values = {f"ONMYWAY_{name}_DB": str(Path(directory) / (name.lower() + ".sqlite3")) for name in ("MEMORY", "HARNESS", "EVENT", "BACKUP")}
        values["ONMYWAY_BACKUP_TOKEN"] = token
        stack.enter_context(patch.dict(os.environ, values))
        client = stack.enter_context(TestClient(application, raise_server_exceptions=False))
        if replay:
            # Windows asyncio first creates a loopback socket pair. Block network
            # after that initialization, before running any production route.
            for target in ("socket.create_connection", "socket.socket.connect", "socket.socket.connect_ex"):
                stack.enter_context(patch(target, side_effect=RuntimeError('Network is disabled during offline replay')))
        head = client.get("/memory", headers=headers).json()
        configured_learning = client.post("/memory/commands", headers=headers, json=dict(
            operation_id=str(uuid4()), store_id=head["store_id"], expected_revision=head["revision"],
            operation="settings", learning_enabled=True))
        if configured_learning.status_code != 200:
            raise RuntimeError("Synthetic memory initialization failed before any model call")
        for index, (case_id, text, session) in enumerate(CASES):
            recorded = replay['cases'][index] if replay else None
            now = datetime.fromisoformat(recorded['input']['now']) if recorded else datetime.now(timezone(timedelta(hours=8)))
            head = client.get("/memory", headers=headers).json()
            request = dict(request_id=str(uuid4()), messages=[dict(role="user", content=text)], now=now.isoformat(),
                           memory_contract=2, memory_enabled=True, memory_auto_learn=True,
                           memory_store_id=head["store_id"], memory_snapshot_revision=head["revision"],
                           memory_session_id=session, memory_trip_id="")
            assistant.ChatRequest.model_validate(request)
            record = dict(id=case_id, input=request, status="not_run", model_calls=0, replay_calls=0, passed=False,
                          raw_output=None, actual=None, checks=[], http_status=None, latency_ms=0)
            if stopped:
                record["reason"] = "missing_provider_configuration" if not configured else "stopped_after_first_provider_error"
            else:
                provider_failed = False

                def observe(messages, model, structured=False):
                    nonlocal provider_failed
                    if recorded:
                        record['replay_calls'] += 1
                        if record['replay_calls'] != 1:
                            raise RuntimeError('Evaluation forbids replay retries')
                        record['raw_output'] = recorded['raw_output']
                        return record['raw_output']
                    record["model_calls"] += 1
                    if record["model_calls"] != 1:
                        raise RuntimeError("Evaluation forbids retries")
                    record["prompt_sha256"] = hashlib.sha256(json.dumps(messages, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
                    call_started = time.monotonic()
                    try:
                        record["raw_output"] = original_completion(messages, model, structured)
                        return record["raw_output"]
                    except Exception as error:
                        provider_failed = True
                        record["provider_error_type"] = type(error.__context__ or error).__name__
                        raise
                    finally:
                        record["latency_ms"] = round((time.monotonic() - call_started) * 1000, 2)

                with patch.object(assistant, "completion", observe):
                    response = client.post("/assistant/chat", headers=headers, json=request)
                record["http_status"] = response.status_code
                record["memory_after"] = client.get("/memory", headers=headers).json()
                if provider_failed:
                    record["status"] = "provider_error"
                    stopped = True
                elif response.status_code != 200 or record["replay_calls" if replay else "model_calls"] != 1:
                    record["status"] = "validation_failed"
                else:
                    record["actual"] = response.json()
                    record["checks"] = grade(case_id, request, record["actual"], record["memory_after"])
                    record["passed"] = all(check["passed"] for check in record["checks"])
                    record["status"] = "passed" if record["passed"] else "semantic_failed"
            records.append(record)
            print(f"{case_id}: {record['status']}", flush=True)
    counts = {status: sum(record["status"] == status for record in records)
              for status in ("passed", "semantic_failed", "validation_failed", "provider_error", "not_run")}
    changed = source_hashes != {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in sources}
    report = _sanitize(dict(schema_version=2, run_id=run_id, mode="offline_recorded_output" if replay else "live_model", model=model_name,
        started_at=started.isoformat(), finished_at=datetime.now(timezone.utc).isoformat(), max_calls=0 if replay else 3,
        model_calls=sum(record["model_calls"] for record in records), timeout_seconds=45, retry_count=0,
        replay_calls=sum(record['replay_calls'] for record in records),
        replay_source=dict(run_id=replay.get('run_id'), sha256=hashlib.sha256(args.replay_report.read_bytes()).hexdigest()) if replay else None,
        counts=counts, cases=records, source_sha256=source_hashes, source_changed_during_run=changed,
        boundaries=dict(android_device="not_run", real_map="not_run", broad_accuracy="not_established",
                        assistant_prose="receipt_contract_checked_not_broad_semantics", input_data="synthetic_only")))
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = ["# 个人记忆历史模型输出离线重放" if replay else "# 个人记忆真实模型小基线", "", f"运行：`{run_id}`；模型：`{report['model']}`。", "",
             "使用原始请求时间与已保存模型输出，经过当前生产接口和临时数据库；网络连接已阻止，无新增模型调用。" if replay else "3 条合成输入，临时数据库，经生产助手与记忆接口。首个上游错误后停止，未进行重试。",
             "未进行 Android、实际地图或真实出行验收；这不是广泛准确率评价。", "",
             f"实际模型调用：{report['model_calls']}；结果：{json.dumps(counts, ensure_ascii=False)}。", "",
             "| 案例 | 状态 |", "|---|---|"]
    lines.extend(f"| {record['id']} | {record['status']} |" for record in records)
    lines += ["", "原始模型输出、生产校验结果、实际记忆状态、断言及源码 SHA-256 见 report.json。", ""]
    if changed:
        lines += ["运行中源码发生变化，本轮不能作为固定版本验收证据。", ""]
    (output / "summary.md").write_text("\n".join(lines), encoding="utf-8")
    print(f"Report: {output / 'summary.md'}", flush=True)
    return 0 if counts["passed"] == len(CASES) and not changed else 2 if counts["provider_error"] or counts["not_run"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
