from datetime import datetime, timedelta, timezone
from itertools import combinations, permutations
from math import hypot

import pytest
from pydantic import ValidationError

from backend.models import Leg, Place, PlanRequest, Task
from backend.planner import PlanningError, _distance_to_route, discover, solve


NOW = datetime(2026, 9, 21, 8, tzinfo=timezone.utc)


def point(name, x):
    return Place(id=name, name=name, longitude=x, latitude=30)


START, END, A, B, C = [point(name, x) for name, x in [("start", 110), ("end", 110.01), ("a", 110.004), ("b", 110.007), ("c", 110.02)]]


class Maps:
    key = "test"

    def __init__(self, unreachable=()):
        self.unreachable = unreachable
        self.modes = []

    def route(self, a, b, mode):
        self.modes.append(mode)
        if (a.id, b.id) in self.unreachable:
            return None
        seconds = round(abs(a.longitude-b.longitude)*60000)
        return Leg(seconds=seconds, meters=seconds)

    def nearby(self, keyword, center):
        return [A, B]


def task(name="t", place=A, **kwargs):
    return Task(id=name, title=name, place=place, service_minutes=2, **kwargs)


def request(tasks=None, **kwargs):
    return PlanRequest(origin=START, destination=END, departure=NOW, tasks=tasks or [task()], **kwargs)


def test_total_includes_service_and_mode():
    provider = Maps()
    result = solve(request(mode="cycling"), [[A]], provider)
    assert result.baseline_seconds == 600
    assert result.travel_seconds == 600
    assert result.service_seconds == 120
    assert result.total_seconds == 720
    assert result.extra_seconds == 120
    assert result.arrival == NOW + timedelta(seconds=720)
    assert set(provider.modes) == {"cycling"}


def test_selects_one_candidate_and_keeps_fixed_place():
    t = Task(id="type", title="buy", place_type="SUPERMARKET", service_minutes=1)
    result = solve(request([t, task("fixed", B)]), [[A, C], [B]], Maps())
    assert {s.place.id for s in result.stops} == {"a", "b"}
    assert sum(len(s.task_ids) for s in result.stops) == 2


def test_deadline_applies_to_completion_not_arrival():
    with pytest.raises(PlanningError):
        solve(request([task(deadline=NOW + timedelta(seconds=300))]), [[A]], Maps())
    result = solve(request([task(deadline=NOW + timedelta(seconds=360))]), [[A]], Maps())
    assert result.stops[0].departure == NOW + timedelta(seconds=360)


@pytest.mark.parametrize("options", [{"arrive_by": NOW + timedelta(seconds=719)}, {"max_extra_minutes": 1}])
def test_hard_time_constraints(options):
    with pytest.raises(PlanningError):
        solve(request(**options), [[A]], Maps())


def test_indoor_task_cannot_enter_route_planning():
    indoor = Task(id="homework", title="写作业", place_type="INDOOR", service_minutes=1)
    with pytest.raises(ValidationError, match="不用出门"):
        request([indoor])


def test_no_destination_deadline_does_not_invent_one():
    result = solve(request(), [[C]], Maps())
    assert result.total_seconds > result.baseline_seconds


def test_unreachable_arc_is_not_zero_cost():
    with pytest.raises(PlanningError):
        solve(request(), [[A]], Maps(unreachable=[("start", "a")]))


def test_missing_baseline_is_reported():
    with pytest.raises(PlanningError, match="起点到目的地"):
        solve(request(), [[A]], Maps(unreachable=[("start", "end")]))


def test_missing_candidates_strict_and_partial():
    tasks = [task("one", A), task("two", B)]
    with pytest.raises(PlanningError, match="未找到候选"):
        solve(request(tasks), [[A], []], Maps())
    result = solve(request(tasks, allow_partial=True), [[A], []], Maps())
    assert [o.task_id for o in result.omitted] == ["two"]
    assert "未找到" in result.omitted[0].reason


def test_shared_place_merges_stops_but_preserves_both_tasks():
    result = solve(request([task("one"), task("two")]), [[A], [A]], Maps())
    assert len(result.stops) == 1
    assert set(result.stops[0].task_ids) == {"one", "two"}
    assert result.stops[0].service_seconds == 240


@pytest.mark.parametrize("preference", ["fastest", "most_tasks", "priority"])
@pytest.mark.parametrize("budget", [2, 4, 30])
def test_solver_matches_independent_exhaustive_oracle(preference, budget):
    tasks = [task("one", A, priority=1), task("two", B, priority=2), task("three", C, priority=3)]
    provider = Maps()
    feasible = []
    for n in range(1, 4):
        for subset in combinations(range(3), n):
            for order in permutations(subset):
                places = [START] + [tasks[i].place for i in order] + [END]
                total = sum(provider.route(a, b, "walking").seconds for a, b in zip(places, places[1:])) + n*120
                if total <= 600 + budget*60:
                    reward = n if preference == "most_tasks" else sum(tasks[i].priority for i in order)
                    score = (total, -n) if preference == "fastest" else (-reward, total)
                    feasible.append(score)
    result = solve(request(tasks, allow_partial=True, preference=preference, max_extra_minutes=budget), [[A], [B], [C]], provider)
    ids = {task_id for stop in result.stops for task_id in stop.task_ids}
    reward = len(ids) if preference == "most_tasks" else sum(t.priority for t in tasks if t.id in ids)
    score = (result.total_seconds, -len(ids)) if preference == "fastest" else (-reward, result.total_seconds)
    assert score == min(feasible)


def test_input_rejects_duplicate_ids_naive_times_and_ambiguous_place():
    with pytest.raises(ValidationError):
        request([task(), task()])
    with pytest.raises(ValidationError):
        PlanRequest(origin=START, destination=END, departure=NOW.replace(tzinfo=None), tasks=[task()])
    with pytest.raises(ValidationError):
        Task(id="x", title="x", place=A, place_type="PARCEL")


def test_discover_preserves_fixed_place_and_limits_type_candidates():
    tasks = [task(), Task(id="buy", title="buy", place_type="SUPERMARKET")]
    result = discover(request(tasks), Maps())
    assert result[0] == [A]
    assert {place.id for place in result[1]} == {A.id, B.id}


def test_discover_searches_along_bent_route_and_broadens_water_stops():
    origin = Place(id="origin", name="origin", longitude=120.55, latitude=31.25)
    destination = Place(id="destination", name="destination", longitude=120.65, latitude=31.25)
    campus_store = Place(id="campus", name="campus store", longitude=120.60, latitude=31.30)
    query = PlanRequest(origin=origin, destination=destination, departure=NOW,
                        tasks=[Task(id="water", title="buy water", place_type="SUPERMARKET")])

    class CampusMaps(Maps):
        def __init__(self):
            super().__init__()
            self.queries = []

        def route(self, a, b, mode):
            if (a.id, b.id) == ("origin", "destination"):
                return Leg(seconds=600, meters=1000,
                           polyline="120.550000,31.250000;120.600000,31.300000;120.650000,31.250000")
            return Leg(seconds=360, meters=600)

        def nearby(self, keyword, center):
            self.queries.append((keyword, center))
            return [campus_store] if abs(center.latitude - 31.30) < 0.001 else []

    provider = CampusMaps()
    assert discover(query, provider) == [[campus_store]]
    assert all("便利店" in keyword and "小卖部" in keyword for keyword, _ in provider.queries)
    assert any(abs(center.latitude - 31.30) < 0.001 for _, center in provider.queries)


def test_discover_ranks_candidates_by_route_distance_for_selected_mode():
    query = request([Task(id="water", title="buy water", place_type="SUPERMARKET")], mode="cycling")

    class RoadMaps(Maps):
        def route(self, a, b, mode):
            self.modes.append(mode)
            if (a.id, b.id) == ("start", "end"):
                return Leg(seconds=500, meters=1000)
            if "a" in (a.id, b.id):
                return Leg(seconds=600, meters=2000)
            return Leg(seconds=200, meters=600)

    provider = RoadMaps()
    assert [place.id for place in discover(query, provider)[0]] == ["b", "a"]
    assert set(provider.modes) == {"cycling"}


def test_corridor_distance_uses_full_segment_not_only_route_vertices():
    on_route = Place(id="on", name="on", longitude=120.01, latitude=30.0)
    near_vertex = Place(id="off", name="off", longitude=120.0001, latitude=30.001)
    line = [(120.0, 30.0), (120.02, 30.0)]
    assert _distance_to_route(on_route, line) < 1
    assert _distance_to_route(near_vertex, line) > 100


def test_discover_uses_confirmed_waypoint_and_destination_as_search_centers():
    origin = Place(id="start", name="start", longitude=120.0, latitude=30.0)
    waypoint = Place(id="parcel", name="parcel", longitude=120.01, latitude=30.02)
    destination = Place(id="end", name="end", longitude=120.02, latitude=30.0)
    near_waypoint = Place(id="near", name="near", longitude=120.0101, latitude=30.0201)
    near_direct_line = Place(id="direct", name="direct", longitude=120.01, latitude=30.0)
    query = PlanRequest(origin=origin, destination=destination, departure=NOW,
                        tasks=[Task(id="pickup", title="pickup", place=waypoint),
                               Task(id="water", title="buy water", place_type="SUPERMARKET")])

    class WaypointMaps(Maps):
        def __init__(self):
            super().__init__()
            self.queries = []

        def route(self, a, b, mode):
            meters = round(hypot(a.longitude - b.longitude, a.latitude - b.latitude) * 100000)
            return Leg(seconds=meters, meters=meters, polyline=f"{a.location};{b.location}")

        def nearby(self, keyword, center):
            self.queries.append(center.id)
            return [near_direct_line, near_waypoint] if center.id == "parcel" else []

    provider = WaypointMaps()
    options = discover(query, provider)
    assert options[0] == [waypoint]
    assert options[1][0] == near_waypoint
    assert "parcel" in provider.queries
    assert "end" in provider.queries


def test_map_query_time_cannot_make_expired_deadline_look_feasible(monkeypatch):
    import backend.planner as planner
    class Clock(datetime):
        @classmethod
        def now(cls, tz=None):
            return NOW + timedelta(minutes=10)
    monkeypatch.setattr(planner, "datetime", Clock)
    with pytest.raises(PlanningError):
        solve(request([task(deadline=NOW + timedelta(minutes=8))]), [[A]], Maps(), refresh_departure=True)


def test_partial_mode_cannot_return_empty_trip_as_success():
    with pytest.raises(PlanningError):
        solve(request(allow_partial=True), [[]], Maps())
