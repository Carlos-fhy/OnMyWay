"""Actual localhost HTTP chain; model and MCP facts are explicitly synthetic."""
from backend.paths import PROJECT_ROOT

from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time
from uuid import uuid4

import httpx

from backend.tests.agent.test_agent_mcp import server, query_tool


TOKEN = "isolated-agent-http-token-0000000000000000"
HEADERS = {"X-Backup-Token": TOKEN}


@contextmanager
def model_server():
    state = {"calls": 0, "review_calls": 0, "requests": []}
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            state["calls"] += 1
            state["requests"].append(request)
            data = json.loads(request["messages"][-1]["content"])
            results = {item["tool_id"]: item for item in data.get("tool_results", []) if item["state"] == "succeeded"}
            visible = {tool["tool_id"] for tool in data.get("tools", [])}
            if "tool_call_facts" in data:
                # A controlled review fixture verifies the real HTTP contract,
                # not general model judgement about travel quality.
                state["review_calls"] += 1
                facts = {item["tool_id"] for item in data["tool_call_facts"] if item["state"] == "succeeded"}
                assert {"places.search", "weather.search"} <= facts
                evidence = json.dumps(data["sources"], ensure_ascii=False)
                assert "rain" in evidence and "closed" in evidence and "图书馆" in evidence
                assert len(data["sources"]) == 2 and all(item["coverage"]["complete"] for item in data["sources"])
                assert data["artifacts"][0]["content"]["content"]["day2"] == "雨天选择图书馆"
                assert "待核实" in data["completion_summary"]
                action = {"status": "supported", "findings": []}
            elif "weather.search" not in results:
                action = {"action": "call_tool", "tool_id": "weather.search", "arguments": {"city": "成都"}} if "weather.search" in visible else {"action": "discover_tools", "query": "城市天气预报"}
            elif "places.search" not in results:
                action = {"action": "call_tool", "tool_id": "places.search", "arguments": {"city": "成都"}} if "places.search" in visible else {"action": "discover_tools", "query": "景点开放位置"}
            elif not data["artifacts"]:
                encoded = json.dumps(results, ensure_ascii=False)
                # The fixture reacts to actual observations, not just the user's sentence.
                assert "rain" in encoded and "closed" in encoded
                action = {"action": "publish_artifact", "kind": "itinerary", "title": "成都四天草案",
                          "content": {"day2": "雨天选择图书馆", "budget_cny": 3000,
                                      "limitations": ["交通与住宿价格未核实"],
                                      "revision": data["task"]["revision"]},
                          "evidence_call_ids": [item["tool_call_id"] for item in results.values()]}
            else:
                action = {"action": "finish", "summary": "已根据雨天和闭馆信息生成草案，交通住宿报价待核实"}
            response = json.dumps({"choices": [{"message": {"content": json.dumps(action, ensure_ascii=False)}}]}, ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=lambda: httpd.serve_forever(poll_interval=0.02), daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{httpd.server_port}/v1", state
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=2)


@contextmanager
def backend_process(tmp_path, env):
    with socket.socket() as available:
        available.bind(("127.0.0.1", 0))
        port = available.getsockname()[1]
    root = PROJECT_ROOT
    log = (tmp_path / f"backend-{port}.log").open("w", encoding="utf-8")
    process = subprocess.Popen([sys.executable, "-X", "utf8", "-m", "uvicorn", "backend.main:app", "--host", "127.0.0.1", "--port", str(port)],
                               cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT,
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    client = httpx.Client(base_url=f"http://127.0.0.1:{port}", headers=HEADERS, timeout=3, trust_env=False)
    try:
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            assert process.poll() is None, "isolated backend exited during startup"
            try:
                if client.get("/health").status_code == 200:
                    break
            except httpx.HTTPError:
                pass
            time.sleep(0.05)
        else:
            raise AssertionError("isolated backend did not become healthy")
        yield process, client
    finally:
        client.close()
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        log.close()


def wait_task(client, task_id, state="completed"):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        task = client.get(f"/agent/tasks/{task_id}").json()
        if task["run"]["state"] == state:
            return task
        if task["run"]["state"] in {"failed", "paused", "waiting_user"}:
            raise AssertionError(f"unexpected run state: {task['run']}")
        time.sleep(0.03)
    raise AssertionError(f"task did not reach {state}; worker={client.get('/agent/status').json()}")


@contextmanager
def blocked_mcp():
    entered, release = threading.Event(), threading.Event()
    state = {"calls": 0}
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            method = request["method"]
            if method == "initialize":
                result = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}}, "serverInfo": {"name": "blocking", "version": "test"}}
            elif method == "notifications/initialized":
                self.send_response(202)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            elif method == "tools/list":
                result = {"tools": [query_tool(description="城市天气预报")]}
            else:
                assert method == "tools/call"
                state["calls"] += 1
                entered.set()
                release.wait(timeout=10)
                result = {"content": [{"type": "text", "text": "rain"}], "structuredContent": {"forecast": "rain"}, "isError": False}
            data = json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}).encode()
            try:
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    httpd.daemon_threads = True
    thread = threading.Thread(target=lambda: httpd.serve_forever(poll_interval=0.02), daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{httpd.server_port}/mcp", state, entered, release
    finally:
        release.set()
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=2)


def test_actual_http_task_model_two_mcp_services_and_restart(tmp_path, record_property):
    with server(label="places") as (places_url, places), server(label="weather", tools=[query_tool(description="城市天气预报")]) as (weather_url, weather), model_server() as (model_url, model):
        places["result"] = {"museum": "closed", "alternative": "图书馆"}
        weather["result"] = {"forecast": "rain"}
        config = tmp_path / "mcp.json"
        config.write_text(json.dumps({"servers": [
            {"name": "places", "url": places_url, "allowed_tools": {"search": "read"}},
            {"name": "weather", "url": weather_url, "allowed_tools": {"search": "read"}},
        ]}), encoding="utf-8")
        env = {**os.environ, "ONMYWAY_AGENT_WORKER_ENABLED": "true", "ONMYWAY_MCP_SERVERS_FILE": str(config),
               "ONMYWAY_AGENT_DB": str(tmp_path / "agent.sqlite3"), "ONMYWAY_BACKUP_TOKEN": TOKEN,
               "ONMYWAY_BACKUP_DB": str(tmp_path / "business.sqlite3"), "ONMYWAY_MEMORY_DB": str(tmp_path / "memory.sqlite3"),
               "ONMYWAY_EVENT_DB": str(tmp_path / "events.sqlite3"),
               "DASHSCOPE_API_KEY": "synthetic-local-only", "DASHSCOPE_BASE_URL": model_url,
               "AGENT_MODEL": "synthetic-observation-model", "AMAP_WEB_KEY": "", "AMAP_KEY": ""}
        payload = {"request_id": str(uuid4()), "conversation_id": "trip-e2e", "message_id": str(uuid4()),
                   "goal": "成都四天旅行，预算三千，别太累", "constraints": {"days": 4, "budget_cny": 3000}}
        with backend_process(tmp_path, env) as (process, client):
            first = client.post("/agent/tasks", json=payload)
            assert first.status_code == 201, first.text
            task_id = first.json()["agent_task_id"]
            result = wait_task(client, task_id)
            assert places["calls"] == weather["calls"] == 1
            assert "图书馆" in json.dumps(result["artifacts"], ensure_ascii=False)
            assert len(result["artifacts"][0]["sources"]) == 2
            assert model["review_calls"] == 1
            assert any(item["kind"] == "artifact_review_result" and item["payload"]["status"] == "supported"
                       for item in result["observations"])
            assert all("max_tokens" in request for request in model["requests"])
            original_pid, model_count = process.pid, model["calls"]
            record_property("task_id", task_id)
            record_property("original_pid", original_pid)
            # Abrupt process exit after persisted completion; original request must not replay work.
            process.kill()
            process.wait(timeout=5)
        with backend_process(tmp_path, env) as (new_process, client):
            assert new_process.pid != original_pid
            record_property("restarted_pid", new_process.pid)
            replay = client.post("/agent/tasks", json=payload)
            assert replay.status_code == 201 and replay.json()["agent_task_id"] == task_id
            restored = wait_task(client, task_id)
            assert restored["current_run_id"] == result["current_run_id"]
            assert places["calls"] == weather["calls"] == 1 and model["calls"] == model_count
            changed = client.post(f"/agent/tasks/{task_id}/revisions", json={
                "request_id": str(uuid4()), "expected_revision": 1,
                "goal": "成都四天旅行，第二天只安排一个室内地点", "constraints": {"days": 4, "day2_max_stops": 1}})
            assert changed.status_code == 200
            revised = wait_task(client, task_id)
            assert revised["revision"] == 2
            assert all(artifact["agent_run_id"] == revised["current_run_id"] for artifact in revised["artifacts"])
            assert places["calls"] == weather["calls"] == 2
            assert model["review_calls"] == 2


def test_killed_backend_preserves_inflight_call_and_does_not_replay(tmp_path, record_property):
    with blocked_mcp() as (weather_url, weather, entered, release), model_server() as (model_url, model):
        config = tmp_path / "mcp.json"
        config.write_text(json.dumps({"servers": [{"name": "weather", "url": weather_url, "allowed_tools": {"search": "read"}}]}), encoding="utf-8")
        env = {**os.environ, "ONMYWAY_AGENT_WORKER_ENABLED": "true", "ONMYWAY_MCP_SERVERS_FILE": str(config),
               "ONMYWAY_AGENT_DB": str(tmp_path / "agent.sqlite3"), "ONMYWAY_BACKUP_TOKEN": TOKEN,
               "ONMYWAY_BACKUP_DB": str(tmp_path / "business.sqlite3"), "ONMYWAY_MEMORY_DB": str(tmp_path / "memory.sqlite3"),
               "ONMYWAY_EVENT_DB": str(tmp_path / "events.sqlite3"), "ONMYWAY_AGENT_LEASE_SECONDS": "1",
               "DASHSCOPE_API_KEY": "synthetic-local-only", "DASHSCOPE_BASE_URL": model_url, "AGENT_MODEL": "synthetic-observation-model",
               "AMAP_WEB_KEY": ""}
        payload = {"request_id": str(uuid4()), "conversation_id": "recover", "message_id": str(uuid4()), "goal": "查询成都天气，准备四天旅行"}
        with backend_process(tmp_path, env) as (process, client):
            first = client.post("/agent/tasks", json=payload)
            assert first.status_code == 201
            task_id = first.json()["agent_task_id"]
            assert entered.wait(timeout=10)
            active = client.get(f"/agent/tasks/{task_id}").json()
            assert active["run"]["state"] == "waiting_tool"
            original_call = active["tool_calls"][0]["tool_call_id"]
            original_pid, model_count = process.pid, model["calls"]
            process.kill()
            process.wait(timeout=5)
        release.set()
        with backend_process(tmp_path, env) as (process, client):
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                recovered = client.get(f"/agent/tasks/{task_id}").json()
                if recovered["run"]["state"] == "paused":
                    break
                time.sleep(0.03)
            assert recovered["run"]["state"] == "paused"
            assert recovered["tool_calls"][0]["state"] == "unknown"
            assert recovered["tool_calls"][0]["tool_call_id"] == original_call
            assert recovered["current_run_id"] == active["current_run_id"]
            assert weather["calls"] == 1 and model["calls"] == model_count
            assert client.post(f"/agent/tasks/{task_id}/resume", json={"request_id": str(uuid4()), "expected_revision": 1}).status_code == 409
            record_property("original_pid", original_pid)
            record_property("restarted_pid", process.pid)
            record_property("tool_call_id", original_call)
            record_property("tool_dispatch_count", weather["calls"])
