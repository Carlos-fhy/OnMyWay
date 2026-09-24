import pytest
from backend.amap import Amap
from backend.tests.test_planner import START, END
from backend.assistant import Proposal


@pytest.mark.parametrize('mode,root', [('walking', 'route'), ('cycling', 'data')])
def test_distance_wins_over_first_or_fastest_candidate(monkeypatch, mode, root):
    provider = Amap('test', 0)
    monkeypatch.setattr(provider, '_get', lambda *args: {root: {'paths': [
        {'distance': '1200', 'duration': '100', 'steps': [{'polyline': 'long'}]},
        {'distance': '800', 'duration': '200', 'steps': [{'polyline': 'short'}]},
        {'distance': '800', 'duration': '180', 'steps': [{'polyline': 'shorter-time'}]},
    ]}})
    route = provider.route(START, END, mode)
    assert (route.meters, route.seconds, route.polyline) == (800, 180, 'shorter-time')


def test_unspecified_mode_requires_user_choice():
    assert Proposal(action='plan_route', reply='', destination='library').mode == ''


def test_equal_task_reward_prefers_shorter_distance_not_faster_trip():
    from backend.models import Leg
    from backend.planner import solve
    from backend.tests.test_planner import A, B, request, Maps
    class DistanceMaps(Maps):
        def route(self, a, b, mode):
            if A.id in (a.id, b.id):
                return Leg(seconds=500, meters=100)
            if B.id in (a.id, b.id):
                return Leg(seconds=10, meters=400)
            return Leg(seconds=100, meters=150)
    result = solve(request(), [[A, B]], DistanceMaps())
    assert result.stops[0].place.id == A.id
    assert result.total_meters == 200
    assert result.extra_meters == 50
