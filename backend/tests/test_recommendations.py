from backend.models import PlanRequest
from backend.planner import recommend, PlanningError
from backend.tests.test_planner import START, END, NOW, Maps, task
import pytest


def test_no_tasks_returns_real_direct_route():
    result = recommend(PlanRequest(origin=START, destination=END, departure=NOW), Maps())
    assert result['direct'].total_seconds == 600
    assert result['suggestion'] is None


def test_infeasible_detour_keeps_direct_route():
    request = PlanRequest(origin=START, destination=END, departure=NOW, tasks=[task()], max_extra_minutes=0)
    result = recommend(request, Maps())
    assert result['direct'].total_seconds == 600
    assert result['suggestion'] is None
    assert result['note']
    assert result['assessments'][0]['extra_seconds'] == 120
    assert result['assessments'][0]['within_budget'] is False
    assert request.allow_partial is False


def test_recommendation_uses_real_service_and_travel_cost():
    request = PlanRequest(origin=START, destination=END, departure=NOW, tasks=[task()], max_extra_minutes=10)
    result = recommend(request, Maps())
    assert result['suggestion'].extra_seconds == 120
    assert result['suggestion'].stops[0].task_ids == ['t']
    assert result['assessments'][0]['within_budget'] is True


def test_unreachable_direct_route_is_failure():
    with pytest.raises(PlanningError):
        recommend(PlanRequest(origin=START, destination=END, departure=NOW), Maps(unreachable=[(START.id, END.id)]))
