"""Deterministic receipt checks for the synthetic errands evaluation only."""
from datetime import datetime
import json
import math


def _object(value):
    return value if isinstance(value, dict) else {}


def _number(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def _elapsed(start, end):
    try:
        first, last = (datetime.fromisoformat(value.replace("Z", "+00:00")) for value in (start, end))
        return (last - first).total_seconds() if first.tzinfo and last.tzinfo else None
    except (AttributeError, TypeError, ValueError, OverflowError):
        return None


def _same_time(first, second):
    elapsed = _elapsed(first, second)
    return elapsed is not None and abs(elapsed) <= 0.000001


def _location(place):
    place = _object(place)
    return {key: place.get(key) for key in ("id", "latitude", "longitude")}


def _same_stops(expected, actual):
    return (isinstance(expected, list) and len(expected) == len(actual)
            and all(isinstance(first, dict) and isinstance(second, dict)
                    and all(first.get(key) == second.get(key) for key in ("task_ids", "task_titles", "service_seconds"))
                    and _location(first.get("place")) == _location(second.get("place"))
                    and all(_same_time(first.get(key), second.get(key)) for key in ("arrival", "departure"))
                    for first, second in zip(expected, actual)))


def _provenance(artifact, calls, artifacts):
    """Follow only successful, explicit references present in this phase's tree."""
    pending = [source.get("tool_call_id") for source in artifact.get("sources", []) if isinstance(source, dict)]
    visited, originals = set(), set()
    while pending:
        ref = pending.pop()
        if not isinstance(ref, str) or ref in visited:
            continue
        if len(visited) >= 100:
            return set()
        visited.add(ref)
        call = calls.get(ref, {})
        if call.get("state") != "succeeded":
            continue
        if call.get("tool_id") not in {"agent.read_result", "agent.read_artifact"}:
            originals.add(ref)
            continue
        data = _object(_object(call.get("result")).get("data"))
        if data.get("source_kind") == "result":
            pending.append(data.get("source_ref"))
        elif data.get("source_kind") == "artifact":
            source = artifacts.get((data.get("source_ref"), data.get("version")))
            if source is not None:
                pending.extend(item.get("tool_call_id") for item in source.get("sources", []) if isinstance(item, dict))
    return originals


def validate_errands_phase(phase, formal_before, formal_after):
    """Return auditable checks, without sending requests or judging arbitrary prose.

    A passed result covers the fixed fixture's receipt-backed route facts. The
    phase and overall evaluation still require their independent semantic review.
    """
    checks, failures, evidence = {}, [], {}

    def check(field, condition, expected, actual):
        checks[field] = bool(condition)
        if not condition:
            failures.append({"field": field, "expected": expected, "actual": actual})

    def result():
        return {"status": "failed" if failures else "passed", "scope": "synthetic_errands_route_facts",
                "semantic_review": "not_run", "checks": checks, "failures": failures, "evidence": evidence}

    root = _object(phase.get("snapshot"))
    root_id = _object(root.get("run")).get("agent_run_id")
    children = [_object(item.get("snapshot")) for item in phase.get("children", [])]
    members = [root, *children]
    links = {item.get("child_run_id"): item for item in root.get("delegations", [])}
    member_ids = {_object(member.get("run")).get("agent_run_id") for member in members}
    check("tree.current_revision", bool(root_id) and root_id == phase.get("agent_run_id") == root.get("current_run_id"),
          phase.get("agent_run_id"), root_id)
    check("tree.children_reviewed", all(link.get("status") in {"accepted", "rejected", "cancelled"} for link in links.values()),
          "every child accepted, rejected or cancelled", [link.get("status") for link in links.values()])
    check("tree.membership", all(_object(child.get("run")).get("agent_run_id") in links
          and links[_object(child.get("run")).get("agent_run_id")].get("child_task_id") == child.get("agent_task_id") for child in children),
          "children linked to the current parent", sorted(str(identity) for identity in member_ids))
    rows = [call for member in members for call in member.get("tool_calls", [])]
    calls = {call.get("tool_call_id"): call for call in rows}
    owned = all(call.get("agent_run_id") == _object(member.get("run")).get("agent_run_id")
                for member in members for call in member.get("tool_calls", []))
    check("tree.call_ownership", owned and len(calls) == len(rows), "unique calls belonging to the current tree",
          [{"tool_call_id": call.get("tool_call_id"), "agent_run_id": call.get("agent_run_id")} for call in rows])
    unknown = [call.get("tool_call_id") for call in rows if call.get("state") in {"unknown", "dispatched"}]
    check("no_unknown_calls", not unknown and not any(member.get("unresolved_calls") for member in members), [], unknown)
    non_read = [call.get("tool_call_id") for call in rows if call.get("effect") != "read"]
    check("read_only_calls", not non_read, [], non_read)
    dispatches = phase.get("tool_dispatches", [])
    dispatch_ids = [call.get("tool_call_id") for call in dispatches]
    check("unique_dispatches", len(dispatch_ids) == len(set(dispatch_ids)), "one dispatch per call identity", dispatch_ids)
    sent_ids = {call.get("tool_call_id") for call in rows if call.get("state") in {"succeeded", "unknown", "dispatched"}}
    check("dispatch_receipts", sent_ids.issubset(set(dispatch_ids)) and all(item.get("tool_call_id") in calls
          and calls[item["tool_call_id"]].get("agent_run_id") == item.get("agent_run_id")
          and calls[item["tool_call_id"]].get("tool_id") == item.get("tool_id") for item in dispatches),
          "each dispatch belongs to a tree receipt", dispatch_ids)
    check("formal_tasks_unchanged", isinstance(formal_before, dict) and formal_before == formal_after,
          formal_before, formal_after)
    check("execution_completed", phase.get("execution_status") == "completed" and _object(root.get("run")).get("state") == "completed",
          "completed", phase.get("execution_status"))
    outputs = root.get("artifacts", [])
    valid_output = len(outputs) == 1 and outputs[0].get("kind") == "route" and outputs[0].get("agent_run_id") == root_id
    check("artifact.current_route", valid_output, "one current route artifact", [{"kind": item.get("kind"), "agent_run_id": item.get("agent_run_id")} for item in outputs])
    if not valid_output:
        return result()
    artifact = outputs[0]
    route = _object(_object(artifact.get("content")).get("content"))
    evidence.update(artifact_id=artifact.get("artifact_id"), artifact_version=artifact.get("version"), plan_id=route.get("plan_id"))
    check("no_navigation", route.get("navigation_started") is False, False, route.get("navigation_started"))
    candidates = [call for call in rows if call.get("tool_id") == "onmyway.plan_route" and call.get("state") == "succeeded"
                  and call.get("agent_run_id") in member_ids
                  and _object(_object(call.get("result")).get("data")).get("plan_id") == route.get("plan_id")
                  and isinstance(route.get("plan_id"), str) and route["plan_id"]]
    check("planner_source", len(candidates) == 1, "one successful current-tree planning receipt matching plan_id", len(candidates))
    constraints = _object(root.get("constraints"))
    selected = constraints.get("selected_task_ids", [])
    stops, legs = route.get("stops"), route.get("legs")
    segments_valid = (isinstance(stops, list) and isinstance(legs, list)
                      and all(isinstance(leg, dict) for leg in legs)
                      and all(isinstance(stop, dict) and isinstance(stop.get("task_ids"), list)
                              and all(isinstance(identity, str) for identity in stop["task_ids"]) for stop in stops))
    check("route.segment_shape", segments_valid, "object legs and stops with explicit task ID lists", {"stops": stops, "legs": legs})
    if not segments_valid:
        return result()
    stop_ids = [identity for stop in stops for identity in stop["task_ids"]]
    check("route.selected_tasks", bool(selected) and sorted(stop_ids) == sorted(selected), selected, stop_ids)
    check("route.final_leg", len(legs) == len(stops) + 1, len(stops) + 1, len(legs))
    for field in ("origin", "destination"):
        check("task." + field, _location(route.get(field)) == _location(constraints.get(field)),
              _location(constraints.get(field)), _location(route.get(field)))
    check("task.mode", route.get("mode") == constraints.get("mode"), constraints.get("mode"), route.get("mode"))
    for label, field, values in (("meters", "total_meters", [leg.get("meters") for leg in legs]),
                                 ("travel_seconds", "travel_seconds", [leg.get("seconds") for leg in legs]),
                                 ("service_seconds", "service_seconds", [stop.get("service_seconds") for stop in stops])):
        total = sum(values) if all(_number(value) for value in values) else None
        check("arithmetic." + label, _number(route.get(field)) and total == route[field], route.get(field), total)
    travel, service, total = (route.get(field) for field in ("travel_seconds", "service_seconds", "total_seconds"))
    summed = travel + service if _number(travel) and _number(service) else None
    check("arithmetic.total_seconds", _number(total) and summed == total, total, summed)
    elapsed = _elapsed(route.get("departure"), route.get("arrival"))
    check("arithmetic.elapsed_seconds", _number(total) and elapsed is not None and abs(elapsed - total) <= 0.000001, total, elapsed)
    previous = route.get("departure")
    for index, stop in enumerate(stops):
        leg_seconds = _object(legs[index]).get("seconds") if index < len(legs) else None
        elapsed = _elapsed(previous, stop.get("arrival"))
        check(f"arithmetic.stop[{index}].arrival", _number(leg_seconds) and elapsed == leg_seconds, leg_seconds, elapsed)
        elapsed = _elapsed(stop.get("arrival"), stop.get("departure"))
        check(f"arithmetic.stop[{index}].service", _number(stop.get("service_seconds")) and elapsed == stop["service_seconds"], stop.get("service_seconds"), elapsed)
        previous = stop.get("departure")
    elapsed = _elapsed(previous, route.get("arrival"))
    final_seconds = _object(legs[-1]).get("seconds") if legs else None
    check("arithmetic.final_leg", _number(final_seconds) and elapsed == final_seconds, final_seconds, elapsed)
    if len(candidates) != 1:
        return result()
    planner = candidates[0]
    evidence["planner_call_id"] = planner["tool_call_id"]
    payload = _object(planner.get("result"))
    plan = _object(_object(payload.get("data")).get("plan"))
    check("planner_receipt_identity", payload.get("source") == "builtin:onmyway" and payload.get("tool_id") == "onmyway.plan_route",
          "builtin:onmyway / onmyway.plan_route", {"source": payload.get("source"), "tool_id": payload.get("tool_id")})
    artifacts = {(item.get("artifact_id"), item.get("version")): item for member in members for item in member.get("artifacts", [])}
    sources = _provenance(artifact, calls, artifacts)
    check("artifact.provenance", planner["tool_call_id"] in sources, planner["tool_call_id"], sorted(sources))
    for field in ("origin", "destination", "mode", "departure", "arrival", "stops", "total_meters", "travel_seconds", "service_seconds", "total_seconds", "coordinate_system"):
        expected, actual = plan.get(field), route.get(field)
        same = _same_time(expected, actual) if field in {"departure", "arrival"} else expected == actual
        if field in {"origin", "destination"}:
            same = _location(expected) == _location(actual)
        elif field == "stops":
            same = _same_stops(expected, actual)
        check("route." + field, field in plan and field in route and same, expected, actual)
    expected_legs = [{key: _object(leg).get(key) for key in ("meters", "seconds")} for leg in plan.get("legs", [])]
    actual_legs = [{key: _object(leg).get(key) for key in ("meters", "seconds")} for leg in legs]
    check("route.legs", expected_legs == actual_legs, expected_legs, actual_legs)
    warnings, actual_warnings = plan.get("warnings"), route.get("warnings")
    check("route.warnings", isinstance(warnings, list) and isinstance(actual_warnings, list)
          and warnings == actual_warnings, warnings, actual_warnings)
    args = _object(planner.get("arguments"))
    request = _object(args.get("request"))
    inputs = request.get("tasks") if isinstance(request.get("tasks"), list) else []
    input_ids = [item.get("id") for item in inputs]
    check("planner.selected_tasks", sorted(input_ids) == sorted(selected), selected, input_ids)
    expected_versions = {identity: _object(formal_before.get("task_versions")).get(identity) for identity in selected}
    check("planner.task_versions", all(type(value) is int and value > 0 for value in expected_versions.values())
          and args.get("task_versions") == expected_versions, expected_versions, args.get("task_versions"))
    check("planner.store_id", args.get("store_id") == formal_before.get("store_id"), formal_before.get("store_id"), args.get("store_id"))
    for field in ("origin", "destination", "mode"):
        expected, actual = constraints.get(field), request.get(field)
        same = expected == actual if field == "mode" else _location(expected) == _location(actual)
        check("planner." + field, same, expected, actual)
    reads = [call for call in rows if call.get("tool_id") == "onmyway.read_tasks" and call.get("state") == "succeeded"
             and _object(_object(call.get("result")).get("data")) == formal_before
             and _number(call.get("created_at")) and _number(planner.get("created_at")) and call["created_at"] <= planner["created_at"]]
    evidence["task_read_call_ids"] = [call["tool_call_id"] for call in reads]
    check("planner.task_read", bool(reads), "successful current-tree task snapshot before planning", evidence["task_read_call_ids"])
    formal_tasks = {item.get("task_id"): _object(item.get("task")) for item in formal_before.get("tasks", []) if not item.get("deleted")}
    for task in inputs:
        source = formal_tasks.get(task.get("id"), {})
        try:
            expected_place = json.loads(source.get("placeJson", "null"))
        except (TypeError, ValueError):
            expected_place = None
        same = (bool(source) and task.get("title") == source.get("title") and task.get("priority", 2) == source.get("priority")
                and task.get("service_minutes", 5) == source.get("serviceMinutes")
                and _location(task.get("place")) == _location(expected_place))
        check("planner.task." + str(task.get("id")), same, source, task)
    return result()
