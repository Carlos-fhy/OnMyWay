"""Backend-owned eligibility and ordering for errands that may join a route.

The phone only supplies the pending tasks and the current time. Deciding whether
an errand belongs on a trip (indoor, missing a user-confirmed place, expired) and
which six to offer is business logic that must stay on the server.
"""
from datetime import date, datetime, time
from typing import Literal

from fastapi import APIRouter
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .models import Place


router = APIRouter(prefix="/tasks", tags=["tasks"])

INDOOR = "INDOOR"
BlockedReason = Literal["INDOOR", "MISSING_PLACE", "EXPIRED"]


class _Model(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True, allow_inf_nan=False)


class CandidateTask(_Model):
    id: str = Field(min_length=1, max_length=100)
    title: str = Field(min_length=1, max_length=100)
    place_type: str = Field(default="SUPERMARKET", min_length=1, max_length=50)
    place: Place | None = None
    deadline: datetime | None = None
    planned_date: str = Field(default="", max_length=10)

    @field_validator("deadline")
    @classmethod
    def aware_deadline(cls, value):
        if value is not None and value.utcoffset() is None:
            raise ValueError("截止时间需要包含时区")
        return value

    @field_validator("planned_date")
    @classmethod
    def valid_planned_date(cls, value):
        if value and (len(value) != 10 or date.fromisoformat(value).isoformat() != value):
            raise ValueError("计划日期需要为 YYYY-MM-DD")
        return value


class RouteCandidatesRequest(_Model):
    tasks: list[CandidateTask] = Field(default_factory=list, max_length=100)
    now: datetime
    limit: int = Field(default=6, ge=1, le=20)

    @field_validator("now")
    @classmethod
    def aware_now(cls, value):
        if value.utcoffset() is None:
            raise ValueError("当前时间需要包含时区")
        return value

    @model_validator(mode="after")
    def unique_task_ids(self):
        if len({task.id for task in self.tasks}) != len(self.tasks):
            raise ValueError("任务 ID 不能重复")
        return self


class CandidateState(_Model):
    task_id: str
    title: str
    effective_at: datetime | None = None
    reason: BlockedReason | None = None


class RouteCandidatesResult(_Model):
    candidates: list[CandidateState]
    blocked: list[CandidateState]
    selected_ids: list[str]


def _effective_at(task: CandidateTask, now: datetime) -> datetime | None:
    if task.deadline is not None:
        return task.deadline
    if task.planned_date:
        return datetime.combine(date.fromisoformat(task.planned_date), time(23, 59, 59), tzinfo=now.tzinfo)
    return None


def _sort_key(state: CandidateState, now: datetime):
    # Errands without a date sort last; the boolean keeps aware/naive values apart.
    return (state.effective_at is None, state.effective_at or now, state.title, state.task_id)


def evaluate(body: RouteCandidatesRequest) -> RouteCandidatesResult:
    candidates: list[CandidateState] = []
    blocked: list[CandidateState] = []
    for task in body.tasks:
        state = CandidateState(task_id=task.id, title=task.title, effective_at=_effective_at(task, body.now))
        reason: BlockedReason | None = None
        if task.place_type == INDOOR:
            reason = "INDOOR"
        elif task.place is None:
            reason = "MISSING_PLACE"
        elif task.deadline is not None and task.deadline <= body.now:
            reason = "EXPIRED"
        if reason is None:
            candidates.append(state)
        else:
            blocked.append(state.model_copy(update={"reason": reason}))
    candidates.sort(key=lambda state: _sort_key(state, body.now))
    blocked.sort(key=lambda state: _sort_key(state, body.now))
    return RouteCandidatesResult(candidates=candidates, blocked=blocked,
                                 selected_ids=[state.task_id for state in candidates[:body.limit]])


@router.post("/route-candidates", response_model=RouteCandidatesResult)
def route_candidates(body: RouteCandidatesRequest):
    return evaluate(body)
