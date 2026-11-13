"""Authenticated task API. Accepting a task does not claim it has executed."""
import json
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Query, Request
from pydantic import BaseModel, ConfigDict, Field, JsonValue, model_validator

from backend.agent.store import AgentStore
from backend.data_services.backup import _authorize


Identifier = Annotated[str, Field(strict=True, min_length=1, max_length=200)]
Goal = Annotated[str, Field(strict=True, min_length=1, max_length=8000)]
Criterion = Annotated[str, Field(strict=True, min_length=1, max_length=1000)]
Revision = Annotated[int, Field(strict=True, ge=1)]


def authorize(x_backup_token: str | None = Header(default=None)):
    _authorize(x_backup_token)


router = APIRouter(prefix="/agent", tags=["agent"], dependencies=[Depends(authorize)])


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True, allow_inf_nan=False)

    @model_validator(mode="after")
    def bounded_document(self):
        if len(json.dumps(self.model_dump(mode="json"), ensure_ascii=False, allow_nan=False).encode()) > 65536:
            raise ValueError("请求内容超过 64 KiB，请缩小本次目标或约束")
        return self


class Budget(Contract):
    max_steps: Annotated[int, Field(strict=True, ge=1, le=100)] = 16
    max_tool_calls: Annotated[int, Field(strict=True, ge=1, le=100)] = 12
    max_duration_seconds: Annotated[int, Field(strict=True, ge=1, le=3600)] = 300


class CreateTask(Contract):
    request_id: Identifier
    conversation_id: Identifier
    message_id: Identifier
    goal: Goal
    constraints: dict[str, JsonValue] = Field(default_factory=dict)
    success_criteria: list[Criterion] = Field(default_factory=list, max_length=20)
    budget: Budget = Field(default_factory=Budget)


class ReviseTask(Contract):
    request_id: Identifier
    expected_revision: Revision
    goal: Goal
    constraints: dict[str, JsonValue] = Field(default_factory=dict)
    success_criteria: list[Criterion] = Field(default_factory=list, max_length=20)


class TaskCommand(Contract):
    request_id: Identifier
    expected_revision: Revision
    reason: Annotated[str, Field(strict=True, max_length=8000)] = ""


def store():
    return AgentStore()


@router.get("/status")
def agent_status(request: Request):
    worker = getattr(request.app.state, "agent_worker", None)
    return worker.status() if worker else {"enabled": False, "state": "disabled", "errors": []}


@router.post("/tasks", status_code=201)
def create_task(body: CreateTask, database: AgentStore = Depends(store)):
    return database.create(body.model_dump(mode="json"))


@router.get("/tasks")
def list_tasks(limit: int = Query(default=50, ge=1, le=100), database: AgentStore = Depends(store)):
    return {"tasks": database.list_tasks(limit=limit)}


@router.get("/tasks/{agent_task_id}")
def get_task(agent_task_id: UUID, database: AgentStore = Depends(store)):
    return database.get(str(agent_task_id))


@router.post("/tasks/{agent_task_id}/revisions")
def revise_task(agent_task_id: UUID, body: ReviseTask, database: AgentStore = Depends(store)):
    return database.revise(str(agent_task_id), body.model_dump(mode="json"))


@router.post("/tasks/{agent_task_id}/cancel")
def cancel_task(agent_task_id: UUID, body: TaskCommand, database: AgentStore = Depends(store)):
    return database.cancel(str(agent_task_id), body.model_dump(mode="json"))


@router.post("/tasks/{agent_task_id}/resume")
def resume_task(agent_task_id: UUID, body: TaskCommand, database: AgentStore = Depends(store)):
    return database.resume(str(agent_task_id), body.model_dump(mode="json"))


@router.post("/tasks/{agent_task_id}/calls/{tool_call_id}/approve")
def approve_call(agent_task_id: UUID, tool_call_id: UUID, body: TaskCommand,
                 database: AgentStore = Depends(store)):
    return database.approve_call(str(agent_task_id), str(tool_call_id), body.model_dump(mode="json"))


@router.get("/tasks/{agent_task_id}/events")
def task_events(agent_task_id: UUID, after: int = Query(default=0, ge=0),
                limit: int = Query(default=100, ge=1, le=200), database: AgentStore = Depends(store)):
    return database.events(str(agent_task_id), after=after, limit=limit)
