"""Existing production task/map services exposed to the general Agent loop.

Route queries retain their H2 execution identity and business-version checks.
This adapter does not write tasks, confirm routes or claim device execution.
"""
from contextvars import ContextVar
import json
from uuid import UUID

from fastapi import HTTPException
from pydantic import ValidationError
from starlette.requests import Request

from backend.execution import runtime as execution
from backend.data_services import tasks
from backend.agent.tools import ToolCatalog, ToolDefinition, ToolError
from backend.data_services.backup import _authorize
from backend.execution.capabilities import Invocation, PlanRoute, ReadTasks
from backend.planning.models import Place


_call_context = ContextVar("agent_builtin_call", default=None)
_store_context = ContextVar("agent_builtin_store", default=None)


def _http_error(error):
    if error.status_code in (401, 403):
        return ToolError("authorization_required", "个人数据服务连接未获授权")
    if error.status_code == 409:
        return ToolError("stale_business_version", "任务版本或执行身份已变化，请读取当前状态后重新判断")
    if error.status_code == 422:
        return ToolError("invalid_arguments", "参数不符合现有业务契约")
    if error.status_code == 429:
        return ToolError("tool_busy", "规划服务正在处理其他请求")
    return ToolError("builtin_unavailable", "个人业务服务暂不可用")


def _route_validation_error(error=None):
    """Expose bounded contract diagnostics, never input, context or raw errors."""
    fields = {"store_id", "task_versions", "request", "origin", "destination", "tasks", "mode", "preference",
              "departure", "arrive_by", "max_extra_minutes", "allow_partial", "id", "title", "priority",
              "service_minutes", "deadline", "place", "place_type", "name", "longitude", "latitude", "address"}
    types = {"value_error", "missing", "extra_forbidden", "int_type", "float_type", "bool_type", "string_type",
             "datetime_parsing", "datetime_type", "datetime_from_date_parsing", "literal_error", "finite_number",
             "greater_than_equal", "less_than_equal", "string_too_short", "string_too_long", "too_long", "too_short",
             "model_type", "dict_type", "list_type"}
    rules = {
        "Value error, 任务版本必须与本次规划任务完全一致":
            "task_versions 的键必须恰好对应 request.tasks 的 id；只保留本次规划任务的版本。版本值来自 read_tasks，不得自行递增或修改。",
        "Value error, 任务必须选择指定地点或地点类型之一":
            "request.tasks 中 place 与 place_type 必须二选一；另一个字段省略或为 null。",
        "Value error, 任务 ID 不能重复": "request.tasks 的 id 不能重复。",
        "Value error, 时间需要包含时区": "departure 和 arrive_by 必须使用带时区的时间。",
        "Value error, 截止时间需要包含时区": "request.tasks 的 deadline 必须使用带时区的时间。",
        "Value error, 最晚到达时间不能早于出发时间": "arrive_by 不能早于 departure。",
        "Value error, 取快递和取外卖必须指定实际领取地点": "快递或外卖任务必须使用已读取的实际 place，不能仅给地点类型。",
        "Value error, 不用出门的待办不参与路线规划": "本次路线不要包含 INDOOR 类型任务，也不要改变正式待办内容。",
    }
    generic = "规划参数不符合契约，请按字段 Schema 修正；任务身份、版本和内容以 read_tasks 为准，不得自行修改版本。"
    errors = error.errors(include_url=False, include_context=False, include_input=False) if error is not None else [{}]
    issues = []
    for item in errors[:5]:
        location = item.get("loc", ())
        safe_location = [part if type(part) is int and 0 <= part <= 1000 else
                         part if isinstance(part, str) and part in fields and (index == 0 or location[index - 1] != "task_versions") else "<field>"
                         for index, part in enumerate(location[:8])]
        kind = item.get("type")
        issues.append({"loc": safe_location, "type": kind if kind in types else "validation_error",
                       "message": rules.get(item.get("msg"), generic)})
    return ToolError("invalid_arguments", json.dumps({"validation": issues}, ensure_ascii=False, separators=(",", ":")))


def _plan_route_schema():
    """Expose route inputs without asking the model to invent client observations."""
    schema = PlanRoute.model_json_schema()
    definitions = schema.pop("$defs")
    request = schema["properties"]["request"]["$ref"].removeprefix("#/$defs/")
    definitions[request]["properties"].pop("context_snapshot")
    task = definitions[request]["properties"]["tasks"]["items"]["$ref"].removeprefix("#/$defs/")
    # Pydantic's destination validator is not emitted into its generated schema.
    # Keep the existing field schemas, and expose their exclusive-choice rule.
    definitions[task]["oneOf"] = [
        {"required": ["place"], "properties": {"place": {"type": "object"}, "place_type": {"type": "null"}}},
        {"required": ["place_type"], "properties": {"place": {"type": "null"}, "place_type": {"type": "string"}}},
    ]
    reachable = set()

    def visit(value):
        if isinstance(value, dict):
            reference = value.get("$ref", "")
            if reference.startswith("#/$defs/"):
                name = reference.removeprefix("#/$defs/")
                if name not in reachable:
                    reachable.add(name)
                    visit(definitions[name])
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    visit(schema)
    schema["$defs"] = {name: value for name, value in definitions.items() if name in reachable}
    return schema


def register_builtin(catalog: ToolCatalog, app, token: str):
    def read_tasks(_arguments):
        try:
            return tasks.read_tasks(token)
        except HTTPException as error:
            raise _http_error(error) from None

    def search_places(arguments):
        try:
            _authorize(token)
            values = app.state.provider.search(arguments["query"], arguments["city"])
            return {"places": [Place.model_validate(value).model_dump(mode="json") for value in values]}
        except HTTPException as error:
            raise _http_error(error) from None

    def read_execution(arguments):
        try:
            return execution.read_execution(UUID(arguments["run_id"]), token)
        except (ValueError, KeyError):
            raise ToolError("invalid_arguments", "执行记录编号无效") from None
        except HTTPException as error:
            if error.status_code == 404:
                raise ToolError("execution_not_found", "原执行记录不存在，请核对编号") from None
            raise _http_error(error) from None

    def plan_route(arguments):
        call = _call_context.get()
        if call is None:
            raise ToolError("call_identity_required", "路线规划必须由持久化工具调用派发")
        try:
            call_id = UUID(call["tool_call_id"])
            correlation_id = UUID(call["agent_run_id"])
            if call.get("legacy_run_id") not in (None, str(call_id)):
                raise ValueError("legacy identity mismatch")
        except (KeyError, TypeError, ValueError):
            raise ToolError("call_identity_required", "路线调用与旧执行身份必须明确关联") from None
        try:
            body = Invocation(run_id=call_id, correlation_id=correlation_id,
                              capability_id="plan_route", snapshot_id=f"agent-call:{call_id}",
                              parameters=arguments)
            from backend.agent.route_authority import register
            route_identity = register(call, arguments, _store_context.get())
            receipt = execution.execute(body, Request({"type": "http", "app": app}), token)
        except HTTPException as error:
            if error.status_code == 422:
                raise _route_validation_error() from None
            raise _http_error(error) from None
        except ValidationError as error:
            raise _route_validation_error(error) from None
        if receipt["state"] != "completed":
            if receipt["state"] in ("unknown", "running"):
                raise ToolError("builtin_execution_unknown", "原规划调用尚未核实，不能作为已完成方案", outcome="unknown")
            if receipt.get("error_status") == 409:
                raise ToolError("stale_business_version", "规划期间任务发生变化，旧结果不能使用")
            raise ToolError("builtin_execution_failed", "规划未完成，请检查约束和服务状态")
        # Geometry is machine data; keep it in the durable original receipt and
        # expose a read reference so planning facts fit the Agent's workset.
        plan = {**receipt["result"], "legs": [
            {key: value for key, value in leg.items() if key != "polyline"}
            for leg in receipt["result"]["legs"]]}
        return {"plan": plan, **route_identity, "legacy_run_id": str(call_id),
                "geometry_ref": {"tool_id": "onmyway.read_execution", "arguments": {"run_id": str(call_id)}},
                "correlation_id": str(correlation_id), "snapshot_id": body.snapshot_id}

    tools = [
        (ToolDefinition("onmyway", "read_tasks",
                        "读取正式待办。store_id/revision/task_versions identify business state, independent of Agent revisions; "
                        "preserve exact task fields/versions. Read-only, not completion.",
                        ReadTasks.model_json_schema()), read_tasks),
        (ToolDefinition("onmyway", "read_execution",
                        "只读查询原能力执行编号 run_id 的当前状态和已核实设备回执。请求已记录或已确认不代表设备已执行；"
                        "仅 device_execution_observed=true 且匹配 result 回执可证明设备行为。不会确认、派发、重试、核实新回执或启动导航。",
                        {"type": "object", "properties": {"run_id": {
                            "type": "string", "pattern": "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"}},
                         "required": ["run_id"], "additionalProperties": False}), read_execution),
        (ToolDefinition("onmyway", "search_places",
                        "按城市搜索真实地点、门店或旅行景点，返回地点身份和坐标。先搜索再使用地点；结果不证明营业时间或预约可用性。",
                        {"type": "object", "properties": {
                            "query": {"type": "string", "minLength": 1, "maxLength": 100},
                            "city": {"type": "string", "minLength": 1, "maxLength": 50}},
                         "required": ["query", "city"], "additionalProperties": False}), search_places),
        (ToolDefinition("onmyway", "plan_route",
                        "步行/骑行办事路线规划。 read_tasks supplies store_id/versions and exact task fields incl.priority/place/address. "
                        "task_versions keys=request.tasks IDs; place XOR place_type; no snapshot. "
                        "Read-only: no todo edits/confirmation/navigation; removed stop stays pending. "
                        "Result /data/plan_id; /data/plan=intermediate stops, legs to destination, times/totals/warnings; geometry_ref reads full geometry. "
                        "Publish exact facts/warnings; extra prose in summary. navigation_started=false; new revision=new receipt. "
                        "Z=UTC; label/convert timezone in prose.",
                        _plan_route_schema()), plan_route),
    ]
    for definition, handler in tools:
        catalog.register(definition, handler)
    from backend.planning.agent_artifact import validate_route
    catalog.register_artifact_validator("route", validate_route)
    return [definition.tool_id for definition, _ in tools]


def build_executor(app, token: str, *, store=None):
    """Bind durable call identity around the same catalog path used by MCP.

    ``app`` and ``token`` identify the configured service at assembly time; handlers
    are registered by register_builtin with that same service configuration.
    """
    def execute(call, catalog: ToolCatalog):
        context = _call_context.set(call)
        store_context = _store_context.set(store)
        try:
            return catalog.call(call["tool_id"], call["arguments"],
                                expected_version=call["tool_version"],
                                authorized=call.get("approved", False))
        finally:
            _store_context.reset(store_context)
            _call_context.reset(context)
    return execute
