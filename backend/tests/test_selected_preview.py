"""The selection preview removes only the detour cap, never selected tasks or deadlines."""
from datetime import timedelta

import pytest

from backend.planner import plan, PlanningError
from backend.tests.test_planner import A, B, NOW, Maps, request, task


def test_over_budget_preview_keeps_every_selected_task():
    selection = request([task('one', A), task('two', B)],
                        allow_partial=False, max_extra_minutes=None)
    preview = plan(selection, Maps())
    assert {id for stop in preview.stops for id in stop.task_ids} == {'one', 'two'}
    assert preview.extra_seconds == 240
    assert not preview.omitted
    with pytest.raises(PlanningError):
        plan(selection.model_copy(update={'max_extra_minutes': 3}), Maps())


def test_preview_does_not_relax_deadline():
    selection = request([task('one', A, deadline=NOW + timedelta(seconds=100))],
                        allow_partial=False, max_extra_minutes=None)
    with pytest.raises(PlanningError):
        plan(selection, Maps())
