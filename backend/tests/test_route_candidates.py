from datetime import datetime

import pytest
from fastapi.testclient import TestClient

from backend.main import app


NOW = "2026-10-18T10:00:00+08:00"


def place(identity="print"):
    return {"id": identity, "name": "文印室", "longitude": 120.5, "latitude": 31.2, "address": "移动营业厅内"}


def task(identity, **changes):
    return {"id": identity, "title": identity, "place_type": "PRINT_SHOP",
            "place": place(identity), "deadline": None, "planned_date": "", **changes}


def request(*tasks, now=NOW, limit=6):
    return {"tasks": list(tasks), "now": now, "limit": limit}


def post(body):
    with TestClient(app) as client:
        return client.post("/tasks/route-candidates", json=body)


def test_indoor_missing_place_and_expired_are_excluded():
    response = post(request(
        task("deadline", deadline="2026-10-18T18:00:00+08:00"),
        task("homework", place_type="INDOOR", place=None),
        task("print", place=None),
        task("expired", deadline="2026-10-18T09:59:00+08:00"),
    ))
    assert response.status_code == 200
    result = response.json()
    assert [row["task_id"] for row in result["candidates"]] == ["deadline"]
    assert result["selected_ids"] == ["deadline"]
    reasons = {row["task_id"]: row["reason"] for row in result["blocked"]}
    assert reasons == {"homework": "INDOOR", "print": "MISSING_PLACE", "expired": "EXPIRED"}


def test_candidates_sort_by_deadline_then_end_of_planned_day_then_undated():
    response = post(request(
        task("undated"),
        task("planned", planned_date="2026-10-18"),
        task("later", deadline="2026-10-19T08:00:00+08:00"),
        task("soon", deadline="2026-10-18T12:00:00+08:00"),
        task("tomorrow", planned_date="2026-10-19"),
    ))
    assert response.status_code == 200
    assert [row["task_id"] for row in response.json()["candidates"]] == [
        "soon", "planned", "later", "tomorrow", "undated"]


def test_limit_selects_only_the_earliest_tasks_but_returns_all_candidates():
    response = post(request(*[
        task(f"t{index}", deadline=f"2026-10-18T{11 + index}:00:00+08:00") for index in range(8)
    ], limit=6))
    assert response.status_code == 200
    result = response.json()
    assert len(result["candidates"]) == 8
    assert result["selected_ids"] == [f"t{index}" for index in range(6)]


@pytest.mark.parametrize("body", [
    request(task("same"), task("same")),
    request(task("bad", planned_date="2026-02-30")),
    request(task("bad", deadline="2026-10-18T12:00:00")),
    request(now="2026-10-18T10:00:00"),
    request(task("bad", place_type="")),
    {**request(), "limit": 0},
    {**request(), "limit": 21},
])
def test_invalid_candidate_requests_are_rejected(body):
    assert post(body).status_code == 422


def test_empty_list_returns_direct_route_choice():
    response = post(request())
    assert response.status_code == 200
    assert response.json() == {"candidates": [], "blocked": [], "selected_ids": []}
