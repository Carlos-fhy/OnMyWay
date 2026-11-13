"""A1 scenario evidence through the production loop and business authorities.

Model responses, map geometry and device observations are controlled fixtures.
These tests do not assert real-model quality or actual Android execution.
"""
from copy import deepcopy
from datetime import datetime, timezone
import json
from uuid import uuid4

from fastapi import HTTPException
import pytest

from backend import main
from backend.agent.builtin import build_executor, register_builtin
from backend.agent.decision import DecisionProvider
from backend.agent.runtime import AgentRuntime
from backend.agent.store import AgentStore
from backend.agent.tools import ToolCatalog
from backend.assistant.api import DraftItem
from backend.tests.agent.test_agent_builtin import runtime as business_runtime
from backend.tests.execution.test_execution_runtime import device_event, prepare_navigation, submit
from backend.tests.planning.test_planner import A, START, END
from backend.tests.data_services.test_tasks import TOKEN, command, commit, task


def create(store, goal, constraints=None):
    return store.create({"request_id": str(uuid4()), "conversation_id": str(uuid4()),
                         "message_id": str(uuid4()), "goal": goal, "constraints": constraints or {}})


def revise(store, current, goal, constraints):
    return store.revise(current["agent_task_id"], {"request_id": str(uuid4()),
                        "expected_revision": current["revision"], "goal": goal, "constraints": constraints})


def loop(store, decide, *, builtin=False):
    catalog = ToolCatalog()
    if builtin:
        register_builtin(catalog, main.app, TOKEN["X-Backup-Token"])
    def completion(messages, model, structured=False):
        return json.dumps(decide(json.loads(messages[-1]["content"])), ensure_ascii=False)
    return AgentRuntime(store, catalog, DecisionProvider(completion_fn=completion), memory_reader=lambda _: [],
                        call_executor=build_executor(main.app, TOKEN["X-Backup-Token"], store=store) if builtin else None)


def successful(data, name):
    return [item for item in data["tool_results"] if item["tool_id"] == name and item["state"] == "succeeded"]


def test_four_day_revision_reads_original_artifact_and_changes_only_day_two(tmp_path):
    store = AgentStore(tmp_path / "agent.sqlite3")
    original = {
        "days": {"day1": {"date": "2026-11-20", "stops": ["人民公园"], "pace": "轻松"},
                 "day2": {"date": "2026-11-21", "stops": ["成都博物馆", "宽窄巷子"]},
                 "day3": {"date": "2026-11-22", "stops": ["杜甫草堂"], "pace": "轻松"},
                 "day4": {"date": "2026-11-23", "stops": ["市区休息与返程"]}},
        "budget_cny": 3000, "scope": "1人，市内交通和住宿餐饮；往返大交通另计",
        "limitations": ["这是固定场景草案；没有实时价格、开馆与预订证据"],
    }
    created = create(store, "成都四天旅行，预算三千，别太累", {"days": 4, "budget_cny": 3000})
    reads = []
    def decide(data):
        if data["artifacts"]:
            return {"action": "finish", "summary": "四天草案已保存，价格与开放信息仍待核实"}
        if data["task"]["revision"] == 1:
            content, evidence = original, []
        else:
            prior = data["prior_artifacts"][0]
            pages = sorted((page for page in data["evidence_pages"]
                            if page["source_kind"] == "artifact" and page["source_ref"] == prior["artifact_id"]
                            and page["version"] == prior["version"]), key=lambda page: page["offset"])
            if not pages or pages[-1]["has_more"]:
                if "agent.read_artifact" not in {t["tool_id"] for t in data["tools"]}:
                    return {"action": "discover_tools", "query": "agent.read_artifact"}
                offset = pages[-1]["next_offset"] if pages else 0
                reads.append((prior["artifact_id"], prior["version"], offset))
                return {"action": "call_tool", "tool_id": "agent.read_artifact",
                        "arguments": {"artifact_id": prior["artifact_id"], "version": prior["version"],
                                      "offset": offset, "max_chars": 3000}}
            assert pages[0]["offset"] == 0
            assert all(left["next_offset"] == right["offset"] for left, right in zip(pages, pages[1:]))
            persisted = json.loads("".join(page["text"] for page in pages))
            assert persisted["revision"] == 1
            content = deepcopy(persisted["content"]["content"])
            content["days"]["day2"]["stops"] = ["成都博物馆"]
            evidence = data["evidence_sources"][0]["evidence_call_ids"]
        return {"action": "publish_artifact", "kind": "itinerary", "title": "成都四天草案",
                "content": content, "evidence_call_ids": evidence}
    runtime = loop(store, decide)
    runtime.run(created["current_run_id"])
    first = store.get(created["agent_task_id"])
    assert first["run"]["state"] == "completed"
    changed = revise(store, first, "只调整第二天，只去一个室内地点；其余三天与预算口径不变", first["constraints"])
    runtime.run(changed["current_run_id"])
    final = store.get(created["agent_task_id"])
    assert final["run"]["state"] == "completed", final["run"]
    artifact = final["artifacts"][0]
    content = artifact["content"]["content"]
    assert set(content["days"]) == {"day1", "day2", "day3", "day4"}
    assert content["days"]["day2"]["stops"] == ["成都博物馆"]
    assert all(content["days"][day] == original["days"][day] for day in ("day1", "day3", "day4"))
    assert {k: v for k, v in content.items() if k != "days"} == {k: v for k, v in original.items() if k != "days"}
    assert reads[0] == (first["artifacts"][0]["artifact_id"], 1, 0)
    assert all(identity == reads[0][0] and version == 1 for identity, version, _ in reads)
    assert len({offset for _, _, offset in reads}) == len(reads)
    assert len(artifact["sources"]) == 1
    assert artifact["sources"][0]["artifact_id"] == first["artifacts"][0]["artifact_id"]
    assert artifact["sources"][0]["version"] == 1
    assert store.get_artifact(first["artifacts"][0]["artifact_id"], 1)["content"]["content"] == original


def test_cancel_unconfirmed_print_draft_keeps_confirmed_parcel_and_dorm_route(tmp_path, business_runtime):
    client, head, maps = business_runtime
    parcel = task("parcel", title="取快递", placeType="PARCEL", placeJson=json.dumps(A.model_dump()))
    assert commit(client, head, command(parcel)).status_code == 200
    before = client.get("/tasks", headers=TOKEN).json()
    draft = DraftItem(item_id="print-draft", version=1, title="打印论文", place_type="PRINT_SHOP").model_dump()
    parameters = {"store_id": head["store_id"], "task_versions": {"parcel": 1}, "request": {
        "origin": START.model_dump(mode="json"), "destination": {**END.model_dump(mode="json"), "name": "宿舍"},
        "departure": datetime.now(timezone.utc).isoformat(),
        "tasks": [{"id": parcel["id"], "title": parcel["title"], "place": A.model_dump(mode="json"),
                   "priority": parcel["priority"], "service_minutes": parcel["serviceMinutes"]}]}}
    store = AgentStore(tmp_path / "agent.sqlite3")
    created = create(store, "取快递 + 打印论文 + 回宿舍；打印仍是未确认草稿",
                     {"drafts": [draft], "destination_id": "dorm", "planning_parameters": parameters})
    def decide(data):
        if data["task"]["revision"] == 1:
            if not data["artifacts"]:
                return {"action": "publish_artifact", "kind": "task_drafts", "title": "打印待确认",
                        "content": {"drafts": data["task"]["constraints"]["drafts"], "destination_id": "dorm"},
                        "evidence_call_ids": []}
            return {"action": "ask_user", "question": "打印尚未保存，请确认或取消该草稿"}
        assert data["task"]["constraints"]["drafts"] == []
        if not successful(data, "onmyway.read_tasks"):
            return {"action": "call_tool", "tool_id": "onmyway.read_tasks", "arguments": {}}
        plans = successful(data, "onmyway.plan_route")
        if not plans:
            if "onmyway.plan_route" not in {t["tool_id"] for t in data["tools"]}:
                return {"action": "discover_tools", "query": "onmyway.plan_route"}
            return {"action": "call_tool", "tool_id": "onmyway.plan_route",
                    "arguments": data["task"]["constraints"]["planning_parameters"]}
        if not data["artifacts"]:
            assert not plans[-1]["truncated"]
            receipt = plans[-1]["result"]["data"]
            return {"action": "publish_artifact", "kind": "route", "title": "取快递后回宿舍",
                    "content": {**receipt["plan"], "plan_id": receipt["plan_id"],
                                "plan_version": receipt["plan_version"], "navigation_started": False},
                    "evidence_call_ids": [plans[-1]["tool_call_id"]]}
        return {"action": "finish", "summary": "打印草稿已取消，本次仅规划快递和宿舍，未启动导航"}
    runtime = loop(store, decide, builtin=True)
    runtime.run(created["current_run_id"])
    waiting = store.get(created["agent_task_id"])
    assert waiting["run"]["state"] == "waiting_user"
    assert client.get("/tasks", headers=TOKEN).json() == before
    changed = revise(store, waiting, "取消未确认打印草稿，取快递后回宿舍",
                     {"drafts": [], "destination_id": "dorm", "planning_parameters": parameters})
    runtime.run(changed["current_run_id"])
    final = store.get(created["agent_task_id"])
    assert final["run"]["state"] == "completed", final["run"]
    plan = final["artifacts"][0]["content"]["content"]
    assert {identity for stop in plan["stops"] for identity in stop["task_ids"]} == {"parcel"}
    assert plan["destination"]["name"] == "宿舍"
    assert client.get("/tasks", headers=TOKEN).json() == before
    assert before["task_versions"] == {"parcel": 1}
    assert all(a["kind"] != "task_drafts" for a in final["artifacts"])
    assert final["prior_artifacts"][0]["reference_only"] is True
    with pytest.raises(HTTPException) as caught:
        store.claim(created["current_run_id"], "stale-worker")
    assert caught.value.status_code == 409
    calculation_count = len(maps.modes)
    runtime.run(changed["current_run_id"])
    assert len(maps.modes) == calculation_count
    assert client.get("/tasks", headers=TOKEN).json() == before


def test_agent_observes_recorded_navigation_without_claiming_device_execution(tmp_path, business_runtime):
    client, head, maps = business_runtime
    body, _ = prepare_navigation(client, head)
    assert submit(client, body).json()["state"] == "waiting_confirmation"
    path = f'/executions/{body["run_id"]}'
    store = AgentStore(tmp_path / "agent.sqlite3")
    created = create(store, "核对原导航执行记录；只有匹配设备回执才说明已执行",
                     {"execution_id": body["run_id"]})
    observed = []
    def decide(data):
        if data["artifacts"]:
            return {"action": "finish", "summary": "已收到匹配的设备到达回执"}
        readings = successful(data, "onmyway.read_execution")
        # Context can fold identical reads. Freshness depends on the receipt
        # clock after the user's reply, not the count of displayed payloads.
        latest_reply = max((o["created_at"] for o in data["observations"] if o["kind"] == "user_input"), default=0)
        if not readings or datetime.fromisoformat(readings[-1]["observed_at"]).timestamp() < latest_reply:
            if "onmyway.read_execution" not in {t["tool_id"] for t in data["tools"]}:
                return {"action": "discover_tools", "query": "onmyway.read_execution"}
            return {"action": "call_tool", "tool_id": "onmyway.read_execution",
                    "arguments": {"run_id": data["task"]["constraints"]["execution_id"]}}
        current = readings[-1]["result"]["data"]
        observed.append((current["state"], current["device_execution_observed"]))
        assert current["recorded_is_not_executed"] is True
        assert current["dispatch_allowed"] is False
        if not current["device_execution_observed"]:
            return {"action": "ask_user", "question": "原请求已记录，尚无设备执行回执；等待手机操作后再核对"}
        if not data["artifacts"]:
            return {"action": "publish_artifact", "kind": "receipt", "title": "设备执行证据",
                    "content": current["result"], "evidence_call_ids": [readings[-1]["tool_call_id"]]}
        return {"action": "finish", "summary": "已收到匹配的设备到达回执"}
    runtime = loop(store, decide, builtin=True)
    runtime.run(created["current_run_id"])
    current = store.get(created["agent_task_id"])
    assert current["run"]["state"] == "waiting_user", current["run"]
    assert current["artifacts"] == []
    def resume():
        store.resume(created["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1,
                     "reason": "请继续核对原执行的最新设备回执"})
        runtime.run(created["current_run_id"])
        return store.get(created["agent_task_id"])
    confirmed = client.post(path + "/confirm", headers=TOKEN, json={"confirmation_id": str(uuid4())}).json()
    assert confirmed["dispatch_allowed"] is True and confirmed["device_execution_observed"] is False
    assert resume()["run"]["state"] == "waiting_user"
    wrong = device_event(body, "NAVIGATION_ARRIVED")
    wrong["payload"]["execution_id"] = str(uuid4())
    assert client.post("/events", headers=TOKEN, json=wrong).status_code == 200
    assert client.post(path + "/reconcile", headers=TOKEN).json()["device_execution_observed"] is False
    assert resume()["run"]["state"] == "waiting_user"
    arrived = device_event(body, "NAVIGATION_ARRIVED")
    assert client.post("/events", headers=TOKEN, json=arrived).status_code == 200
    assert client.post(path + "/reconcile", headers=TOKEN).json()["state"] == "completed"
    final = resume()
    assert final["run"]["state"] == "completed", final["run"]
    assert final["artifacts"][0]["content"]["content"]["event_id"] == arrived["event_id"]
    assert observed[:3] == [("waiting_confirmation", False), ("running", False), ("running", False)]
    assert observed[-1] == ("completed", True)
    assert sum(call["tool_id"] == "onmyway.read_execution" for call in final["tool_calls"]) == 4
    persisted = client.get(path, headers=TOKEN).json()
    assert sum(step["step"] == "dispatch_claimed" for step in persisted["steps"]) == 1
    assert client.post(path + "/confirm", headers=TOKEN, json={"confirmation_id": str(uuid4())}).json()["dispatch_allowed"] is False
    assert maps.modes == []
