"""Existing task authority and planner run behind the general tool interface."""
from copy import deepcopy
import importlib.util
import json
from uuid import uuid4

from fastapi.testclient import TestClient
import pytest

from backend import main
from backend.tests.execution.test_execution_runtime import prepare_route
from backend.tests.planning.test_planner import Maps, A
from backend.tests.data_services.test_tasks import TOKEN, task, command, commit


def api():
    assert importlib.util.find_spec("backend.agent.builtin") is not None, "Agent built-in adapters are not implemented"
    from backend.agent.builtin import register_builtin, build_executor
    from backend.agent.tools import ToolCatalog, ToolError
    return register_builtin, build_executor, ToolCatalog, ToolError


@pytest.fixture
def runtime(tmp_path, monkeypatch):
    monkeypatch.setenv("ONMYWAY_BACKUP_DB", str(tmp_path / "tasks.db"))
    monkeypatch.setenv("ONMYWAY_EVENT_DB", str(tmp_path / "events.db"))
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", TOKEN["X-Backup-Token"])
    class SearchMaps(Maps):
        queries = None
        def search(self, query, city):
            self.queries = (query, city)
            return [A]
    provider = SearchMaps()
    monkeypatch.setattr(main.app.state, "provider", provider)
    with TestClient(main.app) as client:
        head = client.get("/tasks", headers=TOKEN).json()
        yield client, head, provider


def sent(catalog, tool_id, arguments, **changes):
    call_id = str(uuid4())
    return {"tool_call_id": call_id, "legacy_run_id": call_id, "agent_run_id": str(uuid4()),
            "tool_id": tool_id, "tool_version": catalog.describe(tool_id)["version"],
            "arguments": arguments, "approved": False, **changes}


def setup_catalog():
    register, build, Catalog, _ = api()
    catalog = Catalog()
    register(catalog, main.app, TOKEN["X-Backup-Token"])
    return catalog, build(main.app, TOKEN["X-Backup-Token"])


def test_registered_queries_use_existing_task_service_and_map_provider(runtime):
    client, head, provider = runtime
    catalog, execute = setup_catalog()
    assert {item["tool_id"] for item in catalog.discover("")} == {
        "onmyway.read_tasks", "onmyway.plan_route", "onmyway.search_places", "onmyway.read_execution"}
    assert commit(client, head, command(task("parcel", title="取快递"))).status_code == 200
    result = execute(sent(catalog, "onmyway.read_tasks", {}), catalog)
    assert result["data"]["task_versions"] == {"parcel": 1}
    assert result["data"]["tasks"][0]["task"]["title"] == "取快递"
    places = execute(sent(catalog, "onmyway.search_places", {"query": "打印店", "city": "成都"}), catalog)
    assert places["data"]["places"][0] == A.model_dump(mode="json")
    assert provider.queries == ("打印店", "成都")


def test_planning_uses_persisted_call_identity_and_removing_print_keeps_formal_todo(runtime):
    client, head, provider = runtime
    catalog, execute = setup_catalog()
    body = prepare_route(client, head)
    original = client.get("/tasks", headers=TOKEN).json()
    first = sent(catalog, "onmyway.plan_route", body["parameters"])
    planned = execute(first, catalog)["data"]
    assert planned["legacy_run_id"] == first["tool_call_id"]
    assert {task_id for stop in planned["plan"]["stops"] for task_id in stop["task_ids"]} == {"parcel", "print"}
    assert planned["plan"]["destination"]["name"] == "宿舍"
    calls = len(provider.modes)
    assert calls > 0
    assert execute(first, catalog)["data"] == planned
    assert len(provider.modes) == calls
    reduced = deepcopy(body["parameters"])
    reduced["task_versions"].pop("print")
    reduced["request"]["tasks"] = [t for t in reduced["request"]["tasks"] if t["id"] != "print"]
    updated = execute(sent(catalog, "onmyway.plan_route", reduced), catalog)["data"]["plan"]
    assert {task_id for stop in updated["stops"] for task_id in stop["task_ids"]} == {"parcel"}
    assert updated["destination"]["name"] == "宿舍"
    assert client.get("/tasks", headers=TOKEN).json() == original
    persisted = client.get(f'/executions/{first["tool_call_id"]}', headers=TOKEN).json()
    assert persisted["state"] == "completed"


def test_agent_plan_retains_facts_and_defers_geometry_to_original_receipt(runtime, monkeypatch):
    client, head, provider = runtime
    catalog, execute = setup_catalog()
    body = prepare_route(client, head)
    original_route = provider.route
    geometry = ";".join(f"{110 + index / 100000},30.0" for index in range(400))

    def detailed_route(*args):
        return original_route(*args).model_copy(update={"polyline": geometry})

    monkeypatch.setattr(provider, "route", detailed_route)
    call = sent(catalog, "onmyway.plan_route", body["parameters"])
    result = execute(call, catalog)["data"]
    assert all("polyline" not in leg for leg in result["plan"]["legs"])
    assert len(json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) < 2400
    reference = result["geometry_ref"]
    assert reference == {"tool_id": "onmyway.read_execution", "arguments": {"run_id": call["tool_call_id"]}}
    calls_before_read = len(provider.modes)
    full = execute(sent(catalog, reference["tool_id"], reference["arguments"]), catalog)["data"]
    assert full["state"] == "completed"
    original = deepcopy(full["result"])
    assert all(leg["polyline"] == geometry for leg in original["legs"])
    for leg in original["legs"]:
        leg.pop("polyline")
    assert result["plan"] == original
    assert len(provider.modes) == calls_before_read
    assert execute(call, catalog)["data"] == result
    assert len(provider.modes) == calls_before_read


def test_stale_business_version_fails_before_map_and_keeps_other_tasks(runtime):
    client, head, provider = runtime
    catalog, execute = setup_catalog()
    _, _, _, ToolError = api()
    body = prepare_route(client, head)
    current = client.get("/tasks", headers=TOKEN).json()
    printing = next(row["task"] for row in current["tasks"] if row["task_id"] == "print")
    printing["title"] = "打印新版论文"
    assert commit(client, head, command(printing, version=1)).status_code == 200
    with pytest.raises(ToolError) as caught:
        execute(sent(catalog, "onmyway.plan_route", body["parameters"]), catalog)
    assert caught.value.code == "stale_business_version"
    assert provider.modes == []
    records = client.get("/tasks", headers=TOKEN).json()
    assert records["task_versions"] == {"parcel": 1, "print": 2}


def test_plan_without_durable_call_context_is_not_dispatched(runtime):
    client, head, provider = runtime
    catalog, _ = setup_catalog()
    _, _, _, ToolError = api()
    body = prepare_route(client, head)
    with pytest.raises(ToolError) as caught:
        catalog.call("onmyway.plan_route", body["parameters"])
    assert caught.value.code == "call_identity_required"
    assert provider.modes == []


def test_mismatched_legacy_identity_and_arguments_are_rejected(runtime):
    client, head, provider = runtime
    catalog, execute = setup_catalog()
    _, _, _, ToolError = api()
    body = prepare_route(client, head)
    bad = sent(catalog, "onmyway.plan_route", body["parameters"], legacy_run_id=str(uuid4()))
    with pytest.raises(ToolError) as caught:
        execute(bad, catalog)
    assert caught.value.code == "call_identity_required"
    bad = deepcopy(body["parameters"])
    bad["request"]["tasks"][0]["priority"] = True
    with pytest.raises(ToolError):
        execute(sent(catalog, "onmyway.plan_route", bad), catalog)
    assert provider.modes == []


def test_wrong_service_token_cannot_read_tasks_or_search_places(runtime):
    _client, _head, provider = runtime
    register, build, Catalog, ToolError = api()
    catalog = Catalog()
    register(catalog, main.app, "wrong-token")
    execute = build(main.app, "wrong-token")
    for name, args in (("read_tasks", {}), ("search_places", {"query": "打印", "city": "成都"}),
                       ("read_execution", {"run_id": str(uuid4())})):
        with pytest.raises(ToolError) as caught:
            execute(sent(catalog, f"onmyway.{name}", args), catalog)
        assert caught.value.code == "authorization_required"
    assert provider.queries is None


def test_route_tool_schema_omits_client_snapshot_and_keeps_reachable_contracts(runtime):
    from jsonschema import Draft202012Validator
    from backend.execution.capabilities import PlanRoute
    catalog, _ = setup_catalog()
    original = PlanRoute.model_json_schema()
    exposed = catalog.describe("onmyway.plan_route")["input_schema"]
    request_name = original["properties"]["request"]["$ref"].removeprefix("#/$defs/")
    assert "context_snapshot" not in exposed["$defs"][request_name]["properties"]
    assert "context_snapshot" in PlanRoute.model_json_schema()["$defs"][request_name]["properties"]
    expected_request = deepcopy(original["$defs"][request_name])
    expected_request["properties"].pop("context_snapshot")
    assert exposed["$defs"][request_name] == expected_request
    assert {k: v for k, v in exposed.items() if k != "$defs"} == {k: v for k, v in original.items() if k != "$defs"}
    reachable = set()
    def visit(node):
        if isinstance(node, dict):
            if "$ref" in node:
                reference = node["$ref"]
                assert reference.startswith("#/$defs/")
                name = reference.removeprefix("#/$defs/")
                assert name in exposed["$defs"]
                if name not in reachable:
                    reachable.add(name)
                    visit(exposed["$defs"][name])
            for key, value in node.items():
                if key != "$defs":
                    visit(value)
        elif isinstance(node, list):
            for value in node:
                visit(value)
    visit(exposed)
    assert set(exposed["$defs"]) == reachable
    task_name = exposed["$defs"][request_name]["properties"]["tasks"]["items"]["$ref"].removeprefix("#/$defs/")
    assert {key: value for key, value in exposed["$defs"][task_name].items() if key != "oneOf"} == original["$defs"][task_name]
    assert all(exposed["$defs"][name] == original["$defs"][name] for name in reachable - {request_name, task_name})
    size = lambda value: len(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
    assert size(exposed) < size(original) * 0.6
    Draft202012Validator.check_schema(exposed)


def test_route_tool_rejects_client_snapshot_without_changing_legacy_contract(runtime):
    from backend.execution.capabilities import PlanRoute
    client, head, provider = runtime
    catalog, execute = setup_catalog()
    _, _, _, ToolError = api()
    parameters = prepare_route(client, head)["parameters"]
    parameters["request"]["context_snapshot"] = None
    assert PlanRoute.model_validate(parameters).request.context_snapshot is None
    with pytest.raises(ToolError) as caught:
        execute(sent(catalog, "onmyway.plan_route", parameters), catalog)
    assert caught.value.code == "invalid_arguments"
    assert provider.modes == []


def test_route_tool_schema_is_visible_with_persisted_task_evidence_at_12000(runtime, tmp_path):
    from backend.agent.decision import build_context
    from backend.agent.store import AgentStore
    client, head, _provider = runtime
    parameters = prepare_route(client, head)["parameters"]
    catalog, execute = setup_catalog()
    store = AgentStore(tmp_path / "agent-context.sqlite3")
    task_record = store.create({"request_id": str(uuid4()), "conversation_id": "route-schema",
        "message_id": str(uuid4()), "goal": "取快递 + 打印论文 + 回宿舍。读取正式待办与版本，用地图规划步行路线，不启动导航。",
        "constraints": {key: parameters["request"][key] for key in ("origin", "destination", "departure")},
        "success_criteria": ["快递和打印都保留，最终到宿舍", "使用已读取的真实任务版本"]})
    run_id = task_record["current_run_id"]
    lease = store.claim(run_id, "schema-test")["lease_token"]
    prepared = store.prepare_call(run_id, lease, "onmyway.read_tasks", catalog.describe("onmyway.read_tasks")["version"], {})
    dispatched = store.dispatch_call(prepared["tool_call_id"], lease)
    store.record_result(dispatched["tool_call_id"], dispatched["receipt_token"], "succeeded", result=execute(dispatched, catalog))
    discovered = catalog.discover("onmyway.plan_route")
    store.record_observation(run_id, lease, "tools_discovered", {"query": "onmyway.plan_route", "tools": discovered})
    descriptor = catalog.describe("onmyway.plan_route")
    context = build_context(store.get(task_record["agent_task_id"]), [descriptor],
                            namespace_index=catalog.namespace_index(), active_tool_ids=["onmyway.plan_route"],
                            max_input_tokens=12000)
    data = json.loads(context.messages[-1]["content"])
    route = next((tool for tool in data["tools"] if tool["tool_id"] == "onmyway.plan_route"), None)
    assert route is not None, "The actual planning contract must fit alongside persisted task evidence"
    assert route["input_schema"]["required"] == descriptor["input_schema"]["required"]
    assert set(route["input_schema"]["$defs"]) == set(descriptor["input_schema"]["$defs"])
    assert context.estimated_input_tokens <= 12000


def recorded_live_route_arguments(store_id):
    """Public synthetic inputs from the failed 20261108T164113Z live run."""
    return {"store_id": store_id, "task_versions": {"parcel": 1, "print": 1}, "request": {
        "origin": {"id": "BV10069531", "name": "春熙路(地铁站)", "longitude": 104.080019,
                   "latitude": 30.653389, "address": "2号线;3号线"},
        "destination": {"id": "B001C00869", "name": "宿舍（合成角色：大慈寺公开地点）",
                        "longitude": 104.083791, "latitude": 30.654243,
                        "address": "大慈寺路21-23号(春熙路地铁站C口步行360米)"},
        "tasks": [
            {"id": "parcel", "title": "取快递", "priority": 2, "service_minutes": 5,
             "place": {"id": "B0L2RCN7OZ", "name": "成都国际金融中心", "longitude": 104.081503,
                       "latitude": 30.654764, "address": "红星路三段1号(春熙路地铁站出入口旁)"},
             "place_type": "PARCEL"},
            {"id": "print", "title": "打印论文", "priority": 2, "service_minutes": 5,
             "place": {"id": "B0FFF6X49V", "name": "成都太古里", "longitude": 104.083809,
                       "latitude": 30.653358, "address": "中纱帽街8号"}, "place_type": "PRINT_SHOP"}],
        "mode": "walking", "preference": "fastest", "departure": "2026-11-09T00:41:17.283407+08:00",
        "max_extra_minutes": 120, "allow_partial": False}}


def test_live_route_with_place_and_type_is_rejected_before_dispatch(runtime):
    from pydantic import ValidationError
    from backend.execution.capabilities import PlanRoute
    _client, head, provider = runtime
    catalog, _execute = setup_catalog()
    _, _, _, ToolError = api()
    parameters = recorded_live_route_arguments(head["store_id"])
    with pytest.raises(ValidationError, match="任务必须选择指定地点或地点类型之一"):
        PlanRoute.model_validate(parameters)
    with pytest.raises(ToolError) as caught:
        catalog.validate("onmyway.plan_route", parameters)
    assert caught.value.code == "invalid_arguments"
    with pytest.raises(ToolError) as caught:
        catalog.call("onmyway.plan_route", parameters)
    assert caught.value.code == "invalid_arguments", "Reject before entering the durable execution handler"
    assert provider.modes == []


@pytest.mark.parametrize("destination,valid", [
    ({"place": A.model_dump(mode="json")}, True),
    ({"place": A.model_dump(mode="json"), "place_type": None}, True),
    ({"place_type": "PRINT_SHOP"}, True),
    ({"place": None, "place_type": "PRINT_SHOP"}, True),
    ({}, False),
    ({"place": None, "place_type": None}, False),
    ({"place": A.model_dump(mode="json"), "place_type": "PRINT_SHOP"}, False),
    ({"place": None, "place_type": "INVALID"}, False),
])
def test_route_task_destination_schema_matches_nullable_exclusive_choice(runtime, destination, valid):
    from pydantic import ValidationError
    from backend.execution.capabilities import PlanRoute
    _client, head, _provider = runtime
    catalog, _execute = setup_catalog()
    _, _, _, ToolError = api()
    parameters = recorded_live_route_arguments(head["store_id"])
    parameters["task_versions"] = {"print": 1}
    parameters["request"]["tasks"] = [{"id": "print", "title": "打印论文", "priority": 2,
                                        "service_minutes": 5, **destination}]
    if valid:
        catalog.validate("onmyway.plan_route", parameters)
        PlanRoute.model_validate(parameters)
    else:
        with pytest.raises(ToolError) as caught:
            catalog.validate("onmyway.plan_route", parameters)
        assert caught.value.code == "invalid_arguments"
        with pytest.raises(ValidationError):
            PlanRoute.model_validate(parameters)


def test_corrected_live_route_keeps_priority_places_and_uses_real_h2_authority(runtime):
    client, head, provider = runtime
    catalog, execute = setup_catalog()
    parameters = recorded_live_route_arguments(head["store_id"])
    for item in parameters["request"]["tasks"]:
        formal = task(item["id"], title=item["title"], priority=item["priority"],
                      serviceMinutes=item["service_minutes"], placeType=item["place_type"],
                      placeJson=json.dumps(item["place"], ensure_ascii=False))
        assert commit(client, head, command(formal)).status_code == 200
    original = client.get("/tasks", headers=TOKEN).json()
    corrected = deepcopy(parameters)
    for item in corrected["request"]["tasks"]:
        item.pop("place_type")
    call = sent(catalog, "onmyway.plan_route", corrected)
    result = execute(call, catalog)["data"]
    assert {identity for stop in result["plan"]["stops"] for identity in stop["task_ids"]} == {"parcel", "print"}
    assert result["plan"]["destination"] == parameters["request"]["destination"]
    assert provider.modes
    persisted = client.get(f'/executions/{call["tool_call_id"]}', headers=TOKEN).json()
    assert persisted["state"] == "completed"
    for requested, stored in zip(parameters["request"]["tasks"], persisted["parameters"]["request"]["tasks"]):
        assert stored["priority"] == requested["priority"] == 2
        assert stored["place"] == requested["place"] and stored["place"]["address"]
        assert stored["place_type"] is None
    assert client.get("/tasks", headers=TOKEN).json() == original


def test_route_selection_error_identifies_version_keys_without_changing_authority(runtime):
    client, head, provider = runtime
    catalog, execute = setup_catalog()
    _, _, _, ToolError = api()
    parameters = prepare_route(client, head)["parameters"]
    original = client.get("/tasks", headers=TOKEN).json()
    parameters["request"]["tasks"] = [item for item in parameters["request"]["tasks"] if item["id"] == "parcel"]
    with pytest.raises(ToolError) as caught:
        execute(sent(catalog, "onmyway.plan_route", parameters), catalog)
    assert caught.value.code == "invalid_arguments"
    assert str(caught.value).startswith("{"), "The model needs a safe, structured validation reason"
    issue = json.loads(str(caught.value))["validation"][0]
    assert issue["loc"] == [] and issue["type"] == "value_error"
    assert "task_versions" in issue["message"] and "request.tasks" in issue["message"]
    assert "read_tasks" in issue["message"] and "不得自行" in issue["message"]
    assert provider.modes == []
    assert client.get("/tasks", headers=TOKEN).json() == original
    corrected = deepcopy(parameters)
    corrected["task_versions"] = {"parcel": original["task_versions"]["parcel"]}
    result = execute(sent(catalog, "onmyway.plan_route", corrected), catalog)["data"]["plan"]
    assert {identity for stop in result["stops"] for identity in stop["task_ids"]} == {"parcel"}
    assert result["destination"]["name"] == "宿舍"
    calls = len(provider.modes)
    corrected["task_versions"]["parcel"] += 1
    with pytest.raises(ToolError) as caught:
        execute(sent(catalog, "onmyway.plan_route", corrected), catalog)
    assert caught.value.code == "stale_business_version"
    assert len(provider.modes) == calls
    assert client.get("/tasks", headers=TOKEN).json() == original


@pytest.mark.parametrize("custom_type", [False, True])
def test_route_validation_feedback_never_echoes_input_context_or_dynamic_keys(runtime, monkeypatch, custom_type):
    from pydantic import ValidationError
    from pydantic_core import PydanticCustomError
    from backend.agent import builtin as agent_builtin
    client, head, _provider = runtime
    catalog, execute = setup_catalog()
    _, _, _, ToolError = api()
    parameters = prepare_route(client, head)["parameters"]
    secret = "private-credential-marker"
    error_type = PydanticCustomError("private_credential_type", secret) if custom_type else "value_error"
    error = ValidationError.from_exception_data("private-title", [{"type": error_type,
        "loc": ("task_versions", secret, 0), "input": {"private_input": secret},
        "ctx": {"error": ValueError(secret)}}])
    def invalid_invocation(**_kwargs):
        raise error
    monkeypatch.setattr(agent_builtin, "Invocation", invalid_invocation)
    with pytest.raises(ToolError) as caught:
        execute(sent(catalog, "onmyway.plan_route", parameters), catalog)
    assert caught.value.code == "invalid_arguments"
    assert str(caught.value).startswith("{")
    issue = json.loads(str(caught.value))["validation"][0]
    assert issue["loc"] == ["task_versions", "<field>", 0]
    assert issue["type"] == ("validation_error" if custom_type else "value_error")
    assert set(issue) == {"loc", "type", "message"}
    assert "private" not in str(caught.value)


def test_route_http_validation_error_does_not_echo_untrusted_detail(runtime, monkeypatch):
    from fastapi import HTTPException
    from backend.agent import builtin as agent_builtin
    client, head, _provider = runtime
    catalog, execute = setup_catalog()
    _, _, _, ToolError = api()
    parameters = prepare_route(client, head)["parameters"]
    def invalid_execution(*_args, **_kwargs):
        raise HTTPException(422, [{"loc": ["private-key"], "msg": "private-credential", "input": "private-input"}])
    monkeypatch.setattr(agent_builtin.execution, "execute", invalid_execution)
    with pytest.raises(ToolError) as caught:
        execute(sent(catalog, "onmyway.plan_route", parameters), catalog)
    assert caught.value.code == "invalid_arguments"
    assert str(caught.value).startswith("{")
    issue = json.loads(str(caught.value))["validation"][0]
    assert issue["loc"] == [] and issue["type"] == "validation_error"
    assert "private" not in str(caught.value)
