from datetime import timedelta
import pytest
from backend.models import PlanRequest
from backend.planner import solve, PlanningError
from backend.tests.test_planner import START, END, NOW, Maps


@pytest.mark.parametrize("mode", ["walking", "cycling"])
def test_direct_route_does_not_require_tasks(mode):
    request = PlanRequest(origin=START, destination=END, departure=NOW, mode=mode, max_extra_minutes=0)
    result = solve(request, [], Maps())
    assert result.total_seconds == result.baseline_seconds == 600
    assert result.extra_seconds == result.service_seconds == 0
    assert result.stops == result.omitted == result.warnings == []
    assert len(result.legs) == 1


def test_direct_route_respects_arrival_limit():
    request = PlanRequest(origin=START, destination=END, departure=NOW, tasks=[], arrive_by=NOW + timedelta(seconds=599))
    with pytest.raises(PlanningError, match="直达"):
        solve(request, [], Maps())


def test_unreachable_direct_route_is_not_synthesized():
    request = PlanRequest(origin=START, destination=END, departure=NOW, tasks=[])
    with pytest.raises(PlanningError, match="没有可用路线"):
        solve(request, [], Maps(unreachable=[(START.id, END.id)]))
