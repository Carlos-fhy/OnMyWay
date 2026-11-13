"""Isolated loopback backend for App process-death acceptance, with no paid providers.

The production chat/status routes and durable request ledger remain in use. Only
the model completion and map provider are synthetic. The host controls the model
barrier through /__test endpoints and records counters separately from App state.
"""
from __future__ import annotations

import argparse
import asyncio
import copy
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import sys
import threading
import time
from unittest.mock import patch
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
REPORTS = ROOT / "app" / "build" / "reports"
CHAT_PATHS = {"/assistant/chat", "/assistant/chat/status"}
PLAN_SUBMIT_PATHS = {"/plans", "/recommendations"}
PLAN_STATUS_PATHS = {"/plans/status", "/recommendations/status"}
CAPTURE_PATHS = CHAT_PATHS | PLAN_SUBMIT_PATHS | PLAN_STATUS_PATHS


def create_app(data_dir: Path, gate_timeout: float = 180):
    data_dir = data_dir.resolve()
    if not data_dir.is_relative_to(REPORTS.resolve()):
        raise ValueError("Test data directory must be inside app/build/reports")
    if gate_timeout <= 0 or gate_timeout > 600:
        raise ValueError("Gate timeout must be in (0, 600] seconds")
    data_dir.mkdir(parents=True, exist_ok=True)
    for name in ("MEMORY", "HARNESS", "EVENT", "BACKUP"):
        os.environ[f"ONMYWAY_{name}_DB"] = str(data_dir / f"{name.lower()}.sqlite3")
    os.environ["ONMYWAY_BACKUP_TOKEN"] = "isolated-process-recovery-" + uuid4().hex
    for key in ("DASHSCOPE_API_KEY", "DASHSCOPE_BASE_URL", "AMAP_WEB_KEY", "AMAP_KEY",
                "AMAP_WEB_SERVICE_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY"):
        os.environ[key] = ""
    sys.path.insert(0, str(ROOT))
    with patch("dotenv.load_dotenv", return_value=False):
        from backend.main import app
    from backend.assistant import api as assistant
    from backend.tests.planning.test_planner import Maps
    from fastapi import HTTPException
    from pydantic import BaseModel
    from typing import Literal

    app.state.provider = Maps()
    lock = threading.Lock()
    gate = threading.Event()
    state = dict(scenario="print", calls=[], model_calls=0, gate_entered=False,
                 active_calls=0, gate_timeout=False)

    def completion(*_args, **_kwargs):
        with lock:
            state["model_calls"] += 1
            state["gate_entered"] = True
            scenario = state["scenario"]
        if not gate.wait(gate_timeout):
            with lock:
                state["gate_timeout"] = True
            raise HTTPException(504, "Isolated test model barrier timed out")
        if scenario == "reply":
            value = {"action": "reply", "reply": "打印草稿仍待确认，快递和宿舍安排保留。"}
        else:
            value = {"action": "create_task", "reply": "请核对打印论文草稿。",
                     "title": "打印论文", "place_type": "PRINT_SHOP"}
        return json.dumps(value, ensure_ascii=False)

    assistant.completion = completion

    class CaptureCalls:
        def __init__(self, app):
            self.app = app

        async def __call__(self, scope, receive, send):
            if (scope["type"] != "http" or scope.get("method") != "POST" or
                    scope.get("path") not in CAPTURE_PATHS):
                return await self.app(scope, receive, send)
            messages = []
            body_bytes = bytearray()
            while True:
                message = await receive()
                messages.append(message)
                if message["type"] == "http.disconnect":
                    break
                body_bytes.extend(message.get("body", b""))
                if not message.get("more_body", False):
                    break
            try:
                body = json.loads(body_bytes)
            except (ValueError, UnicodeDecodeError):
                body = None
            headers = dict(scope.get("headers", []))
            execution_id = headers.get(b"x-execution-id", b"").decode("ascii", errors="replace")
            call = dict(path=scope["path"], request_id=body.get("request_id") if isinstance(body, dict) else None,
                        execution_id=execution_id or None,
                        body=body, body_sha256=hashlib.sha256(body_bytes).hexdigest(),
                        status_code=None, response=None, started_at=time.time())
            with lock:
                call["sequence"] = len(state["calls"]) + 1
                state["calls"].append(call)
                state["active_calls"] += 1
                gate_response = state["scenario"] == "planning" and scope["path"] in PLAN_SUBMIT_PATHS
            response_bytes = bytearray()
            response_messages = []

            async def replay_receive():
                return messages.pop(0) if messages else await receive()

            async def capture_send(message):
                if message["type"] == "http.response.start":
                    with lock:
                        call["status_code"] = message["status"]
                elif message["type"] == "http.response.body":
                    response_bytes.extend(message.get("body", b""))
                if gate_response:
                    response_messages.append(message)
                else:
                    await send(message)

            try:
                await self.app(scope, replay_receive, capture_send)
                if gate_response:
                    # The production endpoint has finished, including its durable receipt.
                    # Hold delivery, not execution, so the App dies with an uncertain result.
                    if call["status_code"] == 200:
                        with lock:
                            call["response"] = json.loads(response_bytes)
                            state["gate_entered"] = True
                        if not await asyncio.to_thread(gate.wait, gate_timeout):
                            with lock:
                                state["gate_timeout"] = True
                            raise RuntimeError("Isolated test planning response barrier timed out")
                    for message in response_messages:
                        await send(message)
            finally:
                try:
                    response = json.loads(response_bytes)
                except (ValueError, UnicodeDecodeError):
                    response = None
                with lock:
                    call["response"] = response
                    call["finished_at"] = time.time()
                    state["active_calls"] -= 1

    app.add_middleware(CaptureCalls)

    class ResetBody(BaseModel):
        scenario: Literal["print", "reply", "planning"] = "print"

    # The model is local to this factory; explicitly bind it for FastAPI's annotation lookup.
    def reset(body):
        with lock:
            if state["active_calls"]:
                raise HTTPException(409, "Cannot reset while a captured request is active")
            gate.clear()
            app.state.provider.modes.clear()
            state.update(scenario=body.scenario, calls=[], model_calls=0,
                         gate_entered=False, gate_timeout=False)
        return {"status": "reset", "scenario": body.scenario}

    reset.__annotations__["body"] = ResetBody
    app.post("/__test/reset")(reset)

    @app.post("/__test/release")
    def release():
        gate.set()
        return {"status": "released"}

    @app.get("/__test/state")
    def observation():
        with lock:
            result = copy.deepcopy(state)
        result["gate_released"] = gate.is_set()
        result["submit_count"] = sum(c["path"] == "/assistant/chat" for c in result["calls"])
        result["status_count"] = sum(c["path"] == "/assistant/chat/status" for c in result["calls"])
        result["plan_submit_count"] = sum(c["path"] in PLAN_SUBMIT_PATHS for c in result["calls"])
        result["plan_status_count"] = sum(c["path"] in PLAN_STATUS_PATHS for c in result["calls"])
        result["map_calls"] = len(app.state.provider.modes)
        result["ledger"] = []
        ledger = data_dir / "harness.sqlite3"
        if ledger.exists():
            with sqlite3.connect(f"{ledger.as_uri()}?mode=ro", uri=True, timeout=5) as db:
                exists = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='intent_runs'").fetchone()
                if exists:
                    result["ledger"] = [dict(zip(("request_id", "operation", "input_hash", "state", "error_status"), row))
                        for row in db.execute("SELECT request_id,operation,input_hash,state,error_status FROM intent_runs ORDER BY created_at,request_id")]
        result["planning_ledger"] = []
        events = data_dir / "event.sqlite3"
        if events.exists():
            with sqlite3.connect(f"{events.as_uri()}?mode=ro", uri=True, timeout=5) as db:
                exists = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='capability_runs'").fetchone()
                if exists:
                    result["planning_ledger"] = [dict(zip(("run_id", "state", "error_status"), row))
                        for row in db.execute("SELECT run_id,state,error_status FROM capability_runs ORDER BY created_at,run_id")]
        return result

    return app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--gate-timeout", type=float, default=180)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("Port must be between 1024 and 65535")
    app = create_app(args.data_dir, args.gate_timeout)
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=args.port, log_level="warning")


if __name__ == "__main__":
    main()
