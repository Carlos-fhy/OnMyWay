"""The live errands gate checks receipts before allowing the next phase."""
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import importlib.util
import json
from types import SimpleNamespace

import pytest

from backend.paths import PROJECT_ROOT


def validator():
    assert importlib.util.find_spec("backend.evaluation.route_validation") is not None, "Route receipt validation is missing"
    from backend.evaluation.route_validation import validate_errands_phase
    return validate_errands_phase


def fixture(selected=("parcel", "print")):
    places = {name: {"id": name, "name": name, "latitude": 30.6 + index / 1000,
                     "longitude": 104.0 + index / 1000, "address": "public synthetic place"}
              for index, name in enumerate(("origin", "parcel", "print", "dorm"))}
    records = [{"task_id": identity, "version": 1, "deleted": False,
                "task": {"id": identity, "title": identity, "priority": 2, "serviceMinutes": 5,
                         "placeJson": json.dumps(places[identity]), "status": "pending"}}
               for identity in ("parcel", "print")]
    formal = {"store_id": "formal-store", "revision": 1, "task_versions": {"parcel": 1, "print": 1}, "tasks": records}
    departure = datetime(2026, 11, 10, 10, tzinfo=timezone.utc)
    at, stops, legs = departure, [], []
    for index, identity in enumerate(selected, 1):
        legs.append({"meters": index * 100, "seconds": index * 60})
        arrival = at + timedelta(seconds=legs[-1]["seconds"])
        at = arrival + timedelta(seconds=300)
        stops.append({"place": places[identity], "task_ids": [identity], "task_titles": [identity],
                      "arrival": arrival.isoformat(), "departure": at.isoformat(), "service_seconds": 300})
    legs.append({"meters": 300, "seconds": 180})
    arrival = at + timedelta(seconds=180)
    plan = {"origin": places["origin"], "destination": places["dorm"], "mode": "walking",
            "departure": departure.isoformat(), "arrival": arrival.isoformat(), "stops": stops, "legs": legs,
            "total_meters": sum(leg["meters"] for leg in legs), "travel_seconds": sum(leg["seconds"] for leg in legs),
            "service_seconds": 300 * len(selected), "total_seconds": (arrival - departure).total_seconds(),
            "coordinate_system": "GCJ-02", "warnings": ["Public synthetic roles; opening hours unverified"]}
    constraints = {"origin": places["origin"], "destination": places["dorm"], "mode": "walking",
                   "selected_task_ids": list(selected)}
    calls = [
        {"tool_call_id": "tasks-call", "agent_run_id": "parent-current", "tool_id": "onmyway.read_tasks",
         "state": "succeeded", "effect": "read", "arguments": {}, "created_at": 1,
         "result": {"source": "builtin:onmyway", "tool_id": "onmyway.read_tasks", "data": deepcopy(formal)}},
        {"tool_call_id": "planner-call", "agent_run_id": "parent-current", "tool_id": "onmyway.plan_route",
         "state": "succeeded", "effect": "read", "created_at": 2,
         "arguments": {"store_id": "formal-store", "task_versions": {identity: 1 for identity in selected},
                       "request": {"origin": places["origin"], "destination": places["dorm"], "mode": "walking",
                                   "tasks": [{"id": identity, "title": identity, "priority": 2,
                                              "service_minutes": 5, "place": places[identity]} for identity in selected]}},
         "result": {"source": "builtin:onmyway", "tool_id": "onmyway.plan_route",
                    "data": {"plan_id": "current-plan", "plan": deepcopy(plan)}}},
    ]
    artifact = {"artifact_id": "route-artifact", "version": 1, "agent_run_id": "parent-current", "kind": "route",
                "content": {"title": "Synthetic route", "content": {**deepcopy(plan), "plan_id": "current-plan", "navigation_started": False}},
                "sources": [{"tool_call_id": "planner-call", "tool_id": "onmyway.plan_route"}]}
    snapshot = {"agent_task_id": "parent-task", "current_run_id": "parent-current", "revision": 1,
                "run": {"agent_run_id": "parent-current", "state": "completed"}, "constraints": constraints,
                "tool_calls": calls, "artifacts": [artifact], "delegations": [], "unresolved_calls": []}
    phase = {"phase_id": "errands_initial", "agent_run_id": "parent-current", "execution_status": "completed",
             "snapshot": snapshot, "children": [], "checks": {"semantic_review": "not_run"},
             "model_calls": [{"purpose": "completion_review", "review": {"status": "supported", "findings": []}}],
             "tool_dispatches": [{"tool_call_id": call["tool_call_id"], "agent_run_id": call["agent_run_id"], "tool_id": call["tool_id"]} for call in calls]}
    return phase, formal, places


def body(phase):
    return phase["snapshot"]["artifacts"][0]["content"]["content"]


def test_valid_route_requires_matching_receipts_and_original_warnings():
    phase, formal, _ = fixture()
    result = validator()(phase, formal, deepcopy(formal))
    assert result["status"] == "passed" and not result["failures"]
    assert result["evidence"]["planner_call_id"] == "planner-call"
    assert result["semantic_review"] == "not_run"


@pytest.mark.parametrize("damage,field", [
    (lambda p: body(p).update(plan_id="invented-plan"), "planner_source"),
    (lambda p: body(p)["legs"][-1].update(meters=445, seconds=356), "route.legs"),
    (lambda p: body(p).update(arrival="2026-11-10T11:00:00+00:00"), "route.arrival"),
    (lambda p: body(p)["destination"].update(latitude=31), "route.destination"),
    (lambda p: body(p).update(warnings=[]), "route.warnings"),
    (lambda p: body(p)["warnings"].append("The formal pending record was removed."), "route.warnings"),
    (lambda p: body(p)["warnings"].append(body(p)["warnings"][0]), "route.warnings"),
    (lambda p: body(p).update(navigation_started=True), "no_navigation"),
    (lambda p: p["snapshot"]["tool_calls"][1]["arguments"].update(task_versions={"parcel": 9, "print": 1}), "planner.task_versions"),
    (lambda p: p["snapshot"]["tool_calls"][1]["arguments"]["request"].update(tasks=[]), "planner.selected_tasks"),
    (lambda p: p["snapshot"]["artifacts"][0].update(sources=[]), "artifact.provenance"),
    (lambda p: p["snapshot"]["tool_calls"][1].update(agent_run_id="old-revision"), "tree.call_ownership"),
    (lambda p: p["snapshot"]["tool_calls"][0].update(state="unknown"), "no_unknown_calls"),
    (lambda p: p["snapshot"]["tool_calls"][0].update(effect="write"), "read_only_calls"),
])
def test_supported_model_review_does_not_hide_fact_failures(damage, field):
    phase, formal, _ = fixture()
    damage(phase)
    result = validator()(phase, formal, deepcopy(formal))
    assert result["status"] == "failed"
    assert field in {failure["field"] for failure in result["failures"]}


def test_route_and_source_cannot_agree_on_invalid_arithmetic():
    phase, formal, _ = fixture()
    body(phase)["total_seconds"] = 7
    phase["snapshot"]["tool_calls"][1]["result"]["data"]["plan"]["total_seconds"] = 7
    result = validator()(phase, formal, formal)
    assert result["status"] == "failed"
    assert "arithmetic.elapsed_seconds" in {failure["field"] for failure in result["failures"]}
    assert "arithmetic.total_seconds" in {failure["field"] for failure in result["failures"]}


def test_formal_task_changes_fail_even_when_route_is_correct():
    phase, before, _ = fixture()
    after = deepcopy(before)
    after["tasks"][1]["task"]["status"] = "completed"
    result = validator()(phase, before, after)
    assert result["status"] == "failed"
    assert "formal_tasks_unchanged" in {failure["field"] for failure in result["failures"]}


def test_accepted_child_provenance_is_resolved_within_current_tree():
    phase, formal, _ = fixture()
    root = phase["snapshot"]
    child = deepcopy(root)
    child.update(agent_task_id="child-task", current_run_id="child-current")
    child["run"] = {"agent_run_id": "child-current", "state": "completed"}
    child["artifacts"][0].update(artifact_id="child-artifact", agent_run_id="child-current")
    for call in child["tool_calls"]:
        call["agent_run_id"] = "child-current"
    reader = {"tool_call_id": "child-reader", "agent_run_id": "parent-current", "tool_id": "agent.read_artifact",
              "state": "succeeded", "effect": "read", "created_at": 3,
              "result": {"data": {"source_kind": "artifact", "source_ref": "child-artifact", "version": 1}}}
    root["tool_calls"] = [reader]
    root["artifacts"][0]["sources"] = [{"tool_call_id": "child-reader", "tool_id": "agent.read_artifact"}]
    root["delegations"] = [{"child_run_id": "child-current", "child_task_id": "child-task", "status": "accepted"}]
    phase["children"] = [{"snapshot": child, "events": []}]
    phase["tool_dispatches"] = [{"tool_call_id": c["tool_call_id"], "agent_run_id": c["agent_run_id"], "tool_id": c["tool_id"]}
                                for c in [*child["tool_calls"], reader]]
    assert validator()(phase, formal, formal)["status"] == "passed"
    root["delegations"][0]["status"] = "submitted"
    assert validator()(phase, formal, formal)["status"] == "failed"


def test_missing_or_incomplete_artifact_is_a_recorded_failure():
    phase, formal, _ = fixture()
    phase["snapshot"]["artifacts"] = []
    result = validator()(phase, formal, formal)
    assert result["status"] == "failed" and result["failures"]


@pytest.mark.parametrize("value", [["not-a-stop"], [{"task_ids": [None]}], [{"task_ids": "parcel"}]])
def test_malformed_model_stop_data_records_failure_instead_of_crashing(value):
    phase, formal, _ = fixture()
    body(phase)["stops"] = value
    result = validator()(phase, formal, formal)
    assert result["status"] == "failed" and result["failures"]


def test_equal_timestamps_with_different_offsets_are_accepted():
    phase, formal, _ = fixture()
    route = body(phase)
    for item in [route, *route["stops"]]:
        for field in ("arrival", "departure"):
            item[field] = datetime.fromisoformat(item[field]).astimezone(timezone(timedelta(hours=8))).isoformat()
    assert validator()(phase, formal, formal)["status"] == "passed"


def test_planner_receipt_without_actual_phase_dispatch_is_not_evidence():
    phase, formal, _ = fixture()
    phase["tool_dispatches"] = phase["tool_dispatches"][:1]
    result = validator()(phase, formal, formal)
    assert result["status"] == "failed"
    assert "dispatch_receipts" in {failure["field"] for failure in result["failures"]}


def test_failed_initial_factual_gate_is_saved_before_any_revision(monkeypatch):
    spec = importlib.util.spec_from_file_location("route_gate_evaluator", PROJECT_ROOT / "tools/evaluate_agent_a1.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    from backend.data_services import tasks
    phase, formal, places = fixture()
    body(phase)["plan_id"] = "invented-plan"
    monkeypatch.setattr(module, "_seed_tasks", lambda *args, **kwargs: deepcopy(formal))
    monkeypatch.setattr(tasks, "read_tasks", lambda *args: deepcopy(formal))
    revisions, saved = [], []
    store = SimpleNamespace(create=lambda _: {"agent_task_id": "parent-task"},
                            revise=lambda *args: revisions.append(args))
    session = SimpleNamespace(report={"phases": []})
    def run(*args, **kwargs):
        session.report["phases"].append(deepcopy(phase))
        return deepcopy(phase)
    session.run = run
    session.flush = lambda: saved.append(deepcopy(session.report))
    with pytest.raises(module.EvaluationPhaseFailed) as error:
        module._run_errands(session, store, "synthetic-token", places, draft_only=False)
    assert error.value.status == "route_validation_failed"
    assert revisions == []
    assert saved[-1]["phases"][0]["checks"]["route_validation"]["status"] == "failed"
    assert saved[-1]["phases"][0]["formal_task_evidence"]["after"] == formal


def test_execution_failure_still_records_route_and_formal_evidence(monkeypatch):
    spec = importlib.util.spec_from_file_location("route_gate_failed_evaluator", PROJECT_ROOT / "tools/evaluate_agent_a1.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    from backend.data_services import tasks
    phase, formal, _ = fixture()
    phase["execution_status"] = "budget_exhausted"
    phase["snapshot"]["run"].update(state="paused", reason="artifact_review_context_budget")
    monkeypatch.setattr(tasks, "read_tasks", lambda *args: deepcopy(formal))
    saved = []
    session = SimpleNamespace(run=lambda *args, **kwargs: deepcopy(phase), report={"phases": [phase]},
                              flush=lambda: saved.append(deepcopy(phase)))
    with pytest.raises(module.EvaluationPhaseFailed) as error:
        module._route_phase(session, "parent-task", "errands_remove_print", "synthetic-token", formal)
    assert error.value.status == "budget_exhausted"
    assert saved[-1]["checks"]["route_validation"]["checks"]["execution_completed"] is False
    assert saved[-1]["checks"]["formal_tasks_unchanged"] is True


def test_two_valid_route_phases_continue_without_treating_model_review_as_acceptance(monkeypatch):
    spec = importlib.util.spec_from_file_location("route_gate_valid_evaluator", PROJECT_ROOT / "tools/evaluate_agent_a1.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    from backend.data_services import tasks
    initial, formal, places = fixture()
    revised, _, _ = fixture(("parcel",))
    revised["phase_id"] = "errands_remove_print"
    records = iter([initial, revised])
    monkeypatch.setattr(module, "_seed_tasks", lambda *args, **kwargs: deepcopy(formal))
    monkeypatch.setattr(tasks, "read_tasks", lambda *args: deepcopy(formal))
    revisions = []
    store = SimpleNamespace(create=lambda _: {"agent_task_id": "parent-task"}, revise=lambda *args: revisions.append(args))
    session = SimpleNamespace(report={"phases": []}, flush=lambda: None)
    def run(*args, **kwargs):
        record = deepcopy(next(records))
        session.report["phases"].append(record)
        return deepcopy(record)
    session.run = run
    module._run_errands(session, store, "synthetic-token", places, draft_only=False)
    assert len(revisions) == 1
    assert all(record["checks"]["route_validation"]["status"] == "passed" for record in session.report["phases"])
    assert all(record["checks"]["semantic_review"] == "not_run" for record in session.report["phases"])
