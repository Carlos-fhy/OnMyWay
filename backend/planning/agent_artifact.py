"""Validate executable route artifacts against native planner receipts."""
from datetime import datetime
import math

from backend.agent.tools import ToolError


def _instant(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError("Route times require a timezone")
    return parsed


def _same_time(first, second):
    try:
        return _instant(first) == _instant(second)
    except (AttributeError, TypeError, ValueError, OverflowError, OSError):
        return False


def _same_stops(first, second):
    if not isinstance(first, list) or not isinstance(second, list) or len(first) != len(second):
        return False
    times = {"arrival", "departure"}
    return all(isinstance(left, dict) and isinstance(right, dict)
               and {k: v for k, v in left.items() if k not in times} == {k: v for k, v in right.items() if k not in times}
               and all(_same_time(left.get(key), right.get(key)) for key in times)
               for left, right in zip(first, second))


def validate_route(content, snapshot, sources):
    def reject(fields, *, receipt_available=False):
        if receipt_available:
            raise ToolError("artifact_source_mismatch", "已有当前规划回执；用同一 plan_id 修正成果，无需重算。"
                            "按回执保留原值及 warnings 原数组；补充说明放 completion summary。"
                            "不匹配字段：" + ", ".join(fields))
        raise ToolError("artifact_source_mismatch", "正式 route 必须引用当前修订的 onmyway.plan_route 成功回执，"
                        "plan_id、地点、分段、时间和距离按回执保留，不能用旧路线或单段查询拼造。"
                        "请发现并调用 onmyway.plan_route 后重发；不匹配字段：" + ", ".join(fields))

    if not isinstance(content, dict):
        reject(["content"])
    allowed_runs = {snapshot["current_run_id"]}
    allowed_runs.update(item["child_run_id"] for item in snapshot.get("delegations", []) if item["status"] == "accepted")
    matches = [call for call in sources if call.get("tool_id") == "onmyway.plan_route"
               and call.get("state") == "succeeded" and call.get("agent_run_id") in allowed_runs
               and call.get("tool_call_id") == content.get("plan_id")]
    if len(matches) != 1:
        reject(["plan_id/source"])
    data = (matches[0].get("result") or {}).get("data", {})
    plan = data.get("plan")
    if not isinstance(plan, dict) or data.get("plan_id") != content["plan_id"]:
        reject(["planner_receipt"])
    fields = [key for key in ("origin", "destination", "mode",
                             "coordinate_system", "total_meters", "travel_seconds", "service_seconds", "total_seconds")
              if key not in plan or content.get(key) != plan[key]]
    fields.extend(key for key in ("departure", "arrival") if not _same_time(content.get(key), plan.get(key)))
    if not _same_stops(content.get("stops"), plan.get("stops")):
        fields.append("stops")
    if "plan_version" in content and content["plan_version"] != data.get("plan_version"):
        fields.append("plan_version")
    legs = content.get("legs")
    originals = plan.get("legs")
    if (not isinstance(legs, list) or not isinstance(originals, list) or len(legs) != len(originals)
            or any(not isinstance(leg, dict) or not isinstance(original, dict)
                   or any(leg.get(k) != original.get(k) for k in ("meters", "seconds"))
                   for leg, original in zip(legs, originals))):
        fields.append("legs")
    if content.get("navigation_started") is not False:
        fields.append("navigation_started")
    warnings = content.get("warnings")
    if not isinstance(warnings, list) or warnings != plan.get("warnings", []):
        fields.append("warnings")
    constraints = snapshot.get("constraints", {})
    for key in ("origin", "destination", "mode"):
        if key in constraints and plan.get(key) != constraints[key]:
            fields.append("constraints/" + key)
    selected = constraints.get("selected_task_ids")
    omitted = plan.get("omitted", [])
    if omitted or "omitted" in content:
        if content.get("omitted") != omitted:
            fields.append("omitted")
    if omitted and (matches[0].get("arguments", {}).get("request", {}).get("allow_partial") is not True):
        fields.append("allow_partial")
    if selected is not None:
        covered = {identity for stop in plan.get("stops", []) for identity in stop.get("task_ids", [])}
        covered.update(item["task_id"] for item in omitted)
        if (not isinstance(selected, list) or any(not isinstance(item, str) for item in selected)
                or covered != set(selected)):
            fields.append("selected_task_ids")
    if fields:
        source_satisfies_task = not any(key.startswith("constraints/") or key in {"selected_task_ids", "allow_partial"}
                                       for key in fields)
        reject(fields, receipt_available=source_satisfies_task)
    try:
        numbers = [content[key] for key in ("total_seconds", "travel_seconds", "service_seconds", "total_meters")]
        numbers += [leg[key] for leg in legs for key in ("seconds", "meters")]
        numbers += [stop["service_seconds"] for stop in content["stops"]]
        if any(type(value) not in (int, float) or not math.isfinite(value) or value < 0 for value in numbers):
            reject(["arithmetic"])
        duration = content["total_seconds"]
        if (abs((_instant(content["arrival"]) - _instant(content["departure"])).total_seconds() - duration) > 0.001
                or content["travel_seconds"] + content["service_seconds"] != duration
                or sum(leg["seconds"] for leg in legs) != content["travel_seconds"]
                or sum(leg["meters"] for leg in legs) != content["total_meters"]
                or sum(stop["service_seconds"] for stop in content["stops"]) != content["service_seconds"]):
            fields.append("arithmetic")
    except (KeyError, TypeError, ValueError, AttributeError, OverflowError, OSError):
        fields.append("arithmetic")
    if fields:
        reject(fields)
