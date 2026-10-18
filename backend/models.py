from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator, computed_field


class Model(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True, allow_inf_nan=False)


class Place(Model):
    id: str = Field(min_length=1, max_length=120)
    name: str = Field(min_length=1, max_length=200)
    longitude: float = Field(ge=-180, le=180)
    latitude: float = Field(ge=-90, le=90)
    address: str = Field(default="", max_length=500)

    @property
    def location(self) -> str:
        return f"{self.longitude:.6f},{self.latitude:.6f}"


class LocationRequest(Model):
    longitude: float = Field(ge=-180, le=180)
    latitude: float = Field(ge=-90, le=90)


class Task(Model):
    id: str = Field(min_length=1, max_length=100)
    title: str = Field(min_length=1, max_length=100)
    priority: int = Field(default=2, ge=1, le=3)
    service_minutes: int = Field(default=5, ge=1, le=240)
    deadline: datetime | None = None
    place: Place | None = None
    place_type: Literal["PRINT_SHOP", "SUPERMARKET", "PHARMACY", "PARCEL", "TAKEOUT", "INDOOR"] | None = None

    @field_validator("deadline")
    @classmethod
    def aware_deadline(cls, value):
        if value is not None and value.utcoffset() is None:
            raise ValueError("截止时间需要包含时区")
        return value

    @model_validator(mode="after")
    def destination_defined(self):
        if (self.place is None) == (self.place_type is None):
            raise ValueError("任务必须选择指定地点或地点类型之一")
        if self.place_type in ("PARCEL", "TAKEOUT"):
            raise ValueError("取快递和取外卖必须指定实际领取地点")
        return self


class PlanRequest(Model):
    origin: Place
    destination: Place
    tasks: list[Task] = Field(default_factory=list, max_length=6)
    mode: Literal["walking", "cycling"] = "walking"
    preference: Literal["fastest", "most_tasks", "priority"] = "most_tasks"
    departure: datetime
    arrive_by: datetime | None = None
    max_extra_minutes: int | None = Field(default=None, ge=0, le=240)
    allow_partial: bool = False

    @field_validator("departure", "arrive_by")
    @classmethod
    def aware_time(cls, value):
        if value is not None and value.utcoffset() is None:
            raise ValueError("时间需要包含时区")
        return value

    @model_validator(mode="after")
    def valid_request(self):
        if len({task.id for task in self.tasks}) != len(self.tasks):
            raise ValueError("任务 ID 不能重复")
        if self.arrive_by is not None and self.arrive_by < self.departure:
            raise ValueError("最晚到达时间不能早于出发时间")
        if any(task.place_type == "INDOOR" for task in self.tasks):
            raise ValueError("不用出门的待办不参与路线规划")
        return self


class Leg(Model):
    seconds: int = Field(ge=0)
    meters: int = Field(ge=0)
    polyline: str = ""


class Stop(Model):
    place: Place
    task_ids: list[str]
    task_titles: list[str]
    arrival: datetime
    departure: datetime
    service_seconds: int


class Omitted(Model):
    task_id: str
    title: str
    reason: str


class PlanResult(Model):
    baseline_meters: int = 0
    mode: str
    origin: Place
    destination: Place
    baseline_seconds: int
    travel_seconds: int
    service_seconds: int
    total_seconds: int
    extra_seconds: int
    extra_travel_seconds: int
    arrival: datetime
    stops: list[Stop]
    legs: list[Leg]
    omitted: list[Omitted]
    warnings: list[str]
    solver_status: str

    @computed_field
    @property
    def total_meters(self) -> int:
        return sum(leg.meters for leg in self.legs)

    @computed_field
    @property
    def extra_meters(self) -> int:
        return self.total_meters - self.baseline_meters
