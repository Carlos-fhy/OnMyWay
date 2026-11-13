"""Errands run through the same decision loop and actual business contracts."""
from copy import deepcopy
import json
from uuid import uuid4

import pytest

from backend import main
from backend.agent.builtin import build_executor, register_builtin
from backend.agent.decision import DecisionProvider
from backend.agent.runtime import AgentRuntime
from backend.agent.store import AgentStore
from backend.agent.tools import ToolCatalog
from backend.tests.agent.test_agent_builtin import runtime as business_runtime
from backend.tests.execution.test_execution_runtime import prepare_route
from backend.tests.data_services.test_tasks import TOKEN


@pytest.mark.parametrize("correct_bad_versions", [False, True])
def test_errands_loop_revises_route_without_deleting_printing_todo(tmp_path, business_runtime, correct_bad_versions):
    client, head, maps = business_runtime
    body = prepare_route(client, head)
    original_records = client.get("/tasks", headers=TOKEN).json()
    store = AgentStore(tmp_path / "agent.sqlite3")
    catalog = ToolCatalog()
    register_builtin(catalog, main.app, TOKEN["X-Backup-Token"])
    created = store.create({"request_id": str(uuid4()), "conversation_id": "errands-loop",
                            "message_id": str(uuid4()), "goal": "取快递 + 打印论文 + 回宿舍",
                            "constraints": {"planning_parameters": body["parameters"]}})
    def completion(messages, model, structured=False):
        data = json.loads(messages[-1]["content"])
        results = {result["tool_id"]: result for result in data["tool_results"] if result["state"] == "succeeded"}
        visible = {tool["tool_id"]: tool for tool in data["tools"]}
        if "onmyway.read_tasks" not in results:
            action = {"action": "call_tool", "tool_id": "onmyway.read_tasks", "arguments": {}}
        elif "onmyway.plan_route" not in results:
            if "onmyway.plan_route" not in visible:
                action = {"action": "discover_tools", "query": "onmyway.plan_route"}
            else:
                assert visible["onmyway.plan_route"]["input_schema"]["properties"]["request"]
                parameters = deepcopy(data["task"]["constraints"]["planning_parameters"])
                failures = [row for row in data["tool_results"]
                            if row["tool_id"] == "onmyway.plan_route" and row["state"] == "failed"]
                if correct_bad_versions and data["task"]["revision"] == 2:
                    if not failures:
                        parameters["task_versions"]["print"] = 1
                    else:
                        assert failures[-1]["error"]["code"] == "invalid_arguments"
                        assert "task_versions" in failures[-1]["error"]["message"]
                        assert results["onmyway.read_tasks"]["truncated"] is False
                action = {"action": "call_tool", "tool_id": "onmyway.plan_route", "arguments": parameters}
        elif not data["artifacts"]:
            planned = results["onmyway.plan_route"]
            assert planned["truncated"] is False
            receipt = planned["result"]["data"]
            action = {"action": "publish_artifact", "kind": "route", "title": "本次办事路线",
                      "content": {**receipt["plan"], "plan_id": receipt["plan_id"],
                                  "plan_version": receipt["plan_version"], "navigation_started": False},
                      "evidence_call_ids": [planned["tool_call_id"]]}
        else:
            action = {"action": "finish", "summary": "路线已生成，未启动导航"}
        return json.dumps(action, ensure_ascii=False)
    runtime = AgentRuntime(store, catalog, DecisionProvider(completion_fn=completion), memory_reader=lambda _: [],
                           call_executor=build_executor(main.app, TOKEN["X-Backup-Token"], store=store))
    runtime.run(created["current_run_id"])
    first = store.get(created["agent_task_id"])
    assert first["run"]["state"] == "completed", json.dumps({"run": first["run"], "observations": first["observations"], "calls": first["tool_calls"]}, ensure_ascii=False)
    first_plan = first["artifacts"][0]["content"]["content"]
    assert {identity for stop in first_plan["stops"] for identity in stop["task_ids"]} == {"parcel", "print"}
    reduced = deepcopy(body["parameters"])
    reduced["task_versions"].pop("print")
    reduced["request"]["tasks"] = [item for item in reduced["request"]["tasks"] if item["id"] != "print"]
    revised = store.revise(created["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1,
                           "goal": "本次不去打印，只取快递后回宿舍，保留正式打印待办",
                           "constraints": {"planning_parameters": reduced}})
    runtime.run(revised["current_run_id"])
    result = store.get(created["agent_task_id"])
    assert result["run"]["state"] == "completed", result["run"]
    final_plan = result["artifacts"][0]["content"]["content"]
    assert {identity for stop in final_plan["stops"] for identity in stop["task_ids"]} == {"parcel"}
    assert final_plan["destination"]["name"] == "宿舍"
    assert client.get("/tasks", headers=TOKEN).json() == original_records
    assert len(result["prior_artifacts"]) == 1
    assert result["tool_calls"][-1]["legacy_run_id"] == result["tool_calls"][-1]["tool_call_id"]
    native_calls = [call for call in result["tool_calls"] if call["tool_id"] == "onmyway.plan_route"]
    assert [call["state"] for call in native_calls] == (["failed", "succeeded"] if correct_bad_versions else ["succeeded"])
    map_calls = len(maps.modes)
    runtime.run(revised["current_run_id"])
    assert len(maps.modes) == map_calls
