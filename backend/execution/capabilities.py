"""Versioned contracts for the first three existing Harness capabilities."""
import json
import uuid
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator
from fastapi import HTTPException

from backend.planning.models import PlanRequest, PlanResult


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True, allow_inf_nan=False)


Identifier = Annotated[str, Field(strict=True, min_length=1, max_length=200)]
Version = Annotated[int, Field(strict=True, ge=1, le=2_147_483_646)]


class ReadTasks(Contract):
    pass


class VersionedTasks(Contract):
    store_id: Identifier
    task_versions: dict[Identifier, Version] = Field(max_length=6)


class PlanRoute(VersionedTasks):
    request: PlanRequest

    @model_validator(mode="before")
    @classmethod
    def strict_route_parameters(cls, value):
        if isinstance(value, dict) and isinstance(value.get("request"), dict):
            # JSON strict validation accepts ISO timestamps but never coerces a
            # boolean/string into a priority, duration, coordinate, or flag.
            value = {**value, "request": PlanRequest.model_validate_json(
                json.dumps(value["request"], allow_nan=False), strict=True)}
        return value

    @model_validator(mode="after")
    def exactly_selected_tasks(self):
        if set(self.task_versions) != {task.id for task in self.request.tasks}:
            raise ValueError("任务版本必须与本次规划任务完全一致")
        return self


class StartNavigation(VersionedTasks):
    plan_id: Identifier
    plan_version: Version
    route_session_id: Identifier
    confirmation_receipt_id: uuid.UUID


class TaskRecord(Contract):
    task_id: str
    version: int
    deleted: bool
    task: dict[str, Any] | None


class TaskResult(Contract):
    store_id: str
    revision: int
    task_protocol: Literal[1]
    task_versions: dict[str, int]
    tasks: list[TaskRecord]


class NavigationResult(Contract):
    event_id: uuid.UUID
    kind: Literal["NAVIGATION_STARTED", "NAVIGATION_ARRIVED", "NAVIGATION_CANCELLED", "NAVIGATION_FAILED"]
    observed_at: str
    execution_id: uuid.UUID
    plan_id: str
    plan_version: Version
    route_session_id: str


INPUTS = {"read_tasks": ReadTasks, "plan_route": PlanRoute, "start_navigation": StartNavigation}
OUTPUTS = {"read_tasks": TaskResult, "plan_route": PlanResult, "start_navigation": NavigationResult}


class Invocation(Contract):
    run_id: uuid.UUID
    correlation_id: uuid.UUID
    capability_id: Literal["read_tasks", "plan_route", "start_navigation"]
    capability_version: Annotated[int, Field(strict=True, ge=1, le=1)] = 1
    snapshot_id: Identifier
    parameters: dict[str, Any]

    @model_validator(mode="after")
    def known_contract(self):
        parameters = INPUTS[self.capability_id].model_validate(self.parameters)
        self.parameters = parameters.model_dump(mode="json")
        return self

    def arguments(self):
        return INPUTS[self.capability_id].model_validate(self.parameters)


def validate_result(capability_id, result):
    # PlanResult's computed fields are response values, not accepted inputs.
    value = dict(result)
    if capability_id == "plan_route":
        value.pop("total_meters", None)
        value.pop("extra_meters", None)
    try:
        return OUTPUTS[capability_id].model_validate(value).model_dump(mode="json")
    except ValidationError:
        raise HTTPException(502, "能力执行结果不符合已登记契约") from None


def registry():
    return {"protocol": 1, "capabilities": [{
        "id": name, "version": 1,
        "executor": "android" if name == "start_navigation" else "server",
        "permission": "backup_token" if name != "start_navigation" else "backup_token_and_device_location",
        "confirmation_required": name == "start_navigation",
        "confirmation_policy": "plan_receipt_then_explicit_start" if name == "start_navigation" else "none",
        "idempotency": "immutable_run_id",
        "recovery": "reconcile_exact_device_receipts" if name == "start_navigation" else "read_cached_result_no_automatic_replay",
        "input_schema": INPUTS[name].model_json_schema(),
        "output_schema": OUTPUTS[name].model_json_schema(mode="serialization"),
    } for name in INPUTS]}
