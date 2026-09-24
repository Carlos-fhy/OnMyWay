from datetime import datetime, timedelta, timezone
from math import hypot

from ortools.sat.python import cp_model

from .models import Leg, Omitted, Place, PlanRequest, PlanResult, Stop


class PlanningError(Exception):
    pass


KEYWORDS = {"PRINT_SHOP": "打印店", "SUPERMARKET": "超市", "PHARMACY": "药店", "PARCEL": "快递驿站"}
HORIZON = 86400


def discover(request: PlanRequest, provider) -> list[list[Place]]:
    midpoint = Place(id="midpoint", name="路线中点",
                     longitude=(request.origin.longitude + request.destination.longitude) / 2,
                     latitude=(request.origin.latitude + request.destination.latitude) / 2)
    candidates = []
    by_type = {}
    for task in request.tasks:
        if task.place:
            candidates.append([task.place])
            continue
        if task.place_type not in by_type:
            found = {}
            for center in (request.origin, midpoint, request.destination):
                for place in provider.nearby(KEYWORDS[task.place_type], center):
                    found[place.location] = place
            # Approximate geometric detour only screens POIs; actual optimization uses map times.
            def score(p):
                return (hypot(p.longitude-request.origin.longitude, p.latitude-request.origin.latitude)
                        + hypot(p.longitude-request.destination.longitude, p.latitude-request.destination.latitude))
            by_type[task.place_type] = sorted(found.values(), key=score)[:2]
        candidates.append(by_type[task.place_type])
    return candidates


def plan(request: PlanRequest, provider) -> PlanResult:
    return solve(request, discover(request, provider), provider, refresh_departure=True)


def recommend(request: PlanRequest, provider):
    """Read-only previews; no route/session is committed by this endpoint."""
    direct = plan(request.model_copy(update={"tasks": []}), provider)
    suggestion = None
    note = ""
    assessments = []
    for task in request.tasks:
        try:
            single = plan(request.model_copy(update={"tasks": [task], "allow_partial": False,
                                                     "max_extra_minutes": None}), provider)
            assessments.append({"task_id": task.id, "title": task.title,
                                "extra_seconds": single.extra_seconds,
                                "extra_meters": single.extra_meters,
                                "within_budget": request.max_extra_minutes is None or single.extra_seconds <= request.max_extra_minutes * 60,
                                "reason": ""})
        except PlanningError as error:
            assessments.append({"task_id": task.id, "title": task.title,
                                "extra_seconds": None, "within_budget": False, "reason": str(error)})
    if request.tasks:
        try:
            suggestion = plan(request.model_copy(update={"allow_partial": True}), provider)
        except PlanningError as error:
            note = str(error)
    return {"direct": direct, "suggestion": suggestion, "note": note, "assessments": assessments}


def solve(request: PlanRequest, candidates: list[list[Place]], provider, refresh_departure=False) -> PlanResult:
    baseline = provider.route(request.origin, request.destination, request.mode)
    if baseline is None:
        raise PlanningError("起点到目的地没有可用路线，请修改地点或出行方式。")
    if not request.tasks:
        departure = max(request.departure, datetime.now(timezone.utc)) if refresh_departure else request.departure
        arrival = departure + timedelta(seconds=baseline.seconds)
        if request.arrive_by and arrival > request.arrive_by:
            raise PlanningError("直达也无法在要求时间前到达，请调整最晚到达时间。")
        return PlanResult(mode=request.mode, origin=request.origin, destination=request.destination,
                          baseline_meters=baseline.meters, baseline_seconds=baseline.seconds, travel_seconds=baseline.seconds,
                          service_seconds=0, total_seconds=baseline.seconds, extra_seconds=0,
                          extra_travel_seconds=0, arrival=arrival, stops=[], legs=[baseline],
                          omitted=[], warnings=[], solver_status="DIRECT")
    if not request.allow_partial and any(not options for options in candidates):
        missing = "、".join(t.title for t, options in zip(request.tasks, candidates) if not options)
        raise PlanningError(f"未找到候选地点：{missing}。请选择指定地点或允许部分完成。")

    places = [request.origin, request.destination]
    task_index = [None, None]
    groups = []
    for index, options in enumerate(candidates):
        group = []
        for place in options:
            group.append(len(places))
            places.append(place)
            task_index.append(index)
        groups.append(group)
    size = len(places)
    service = [0, 0] + [request.tasks[i].service_minutes * 60 for i in task_index[2:]]
    model = cp_model.CpModel()
    active = {i: model.NewBoolVar(f"visit_{i}") for i in range(2, size)}
    arrivals = [model.NewIntVar(0, HORIZON, f"time_{i}") for i in range(size)]
    model.Add(arrivals[0] == 0)
    arcs = [(1, 0, model.NewConstant(1))]
    links = {}
    legs = {}
    for i in range(2, size):
        arcs.append((i, i, active[i].Not()))
    for i in range(size):
        if i == 1:
            continue
        for j in range(1, size):
            if i == j or (i >= 2 and j >= 2 and task_index[i] == task_index[j]):
                continue
            leg = baseline if (i, j) == (0, 1) else provider.route(places[i], places[j], request.mode)
            if leg is None:
                continue
            edge = model.NewBoolVar(f"edge_{i}_{j}")
            links[i, j] = edge
            legs[i, j] = leg
            arcs.append((i, j, edge))
            model.Add(arrivals[j] == arrivals[i] + service[i] + leg.seconds).OnlyEnforceIf(edge)
    # Map matrix queries may take minutes. Start the actual trip after they finish,
    # reserving the solver's time limit, rather than treating query time as travel.
    if refresh_departure:
        effective_departure = max(request.departure, datetime.now(timezone.utc) + timedelta(seconds=3))
        request = request.model_copy(update={"departure": effective_departure})
    model.AddCircuit(arcs)
    selected = []
    for index, group in enumerate(groups):
        chosen = model.NewBoolVar(f"task_{index}")
        model.Add(sum(active[i] for i in group) == chosen)
        if not request.allow_partial:
            model.Add(chosen == 1)
        selected.append(chosen)
        task = request.tasks[index]
        if task.deadline:
            limit = int((task.deadline - request.departure).total_seconds())
            for i in group:
                model.Add(arrivals[i] + service[i] <= limit).OnlyEnforceIf(active[i])
    model.Add(sum(selected) >= 1)
    # Tasks at a shared place must be consecutive, allowing one physical stop.
    location_groups = {}
    for i in range(2, size):
        location_groups.setdefault(places[i].location, set()).add(i)
    for members in location_groups.values():
        model.Add(sum(edge for (i, j), edge in links.items() if j in members and i not in members) <= 1)
    if request.arrive_by:
        model.Add(arrivals[1] <= int((request.arrive_by - request.departure).total_seconds()))
    if request.max_extra_minutes is not None:
        model.Add(arrivals[1] <= baseline.seconds + request.max_extra_minutes * 60)
    count = sum(selected)
    if request.preference == "fastest":
        model.Minimize(arrivals[1] * (len(selected) + 1) - count)
    else:
        reward = count if request.preference == "most_tasks" else sum(
            selected[i] * request.tasks[i].priority for i in range(len(selected)))
        distance = sum(edge * legs[i, j].meters for (i, j), edge in links.items())
        # Preserve task/priority preference, then minimize actual road distance.
        distance_bound = sum(leg.meters for leg in legs.values())
        model.Minimize(distance - (distance_bound + 1) * reward)
    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = 3
    solver.parameters.num_search_workers = 2
    status = solver.Solve(model)
    if status not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
        if status == cp_model.UNKNOWN:
            raise PlanningError("求解超时，尚未找到可行方案，请减少任务后重试。")
        raise PlanningError("在当前候选地点、截止时间和额外时间预算内没有可行方案。请调整任务、时间或允许部分完成。")

    ordered = []
    route_legs = []
    current = 0
    while current != 1:
        following = next(j for (i, j), edge in links.items() if i == current and solver.Value(edge))
        route_legs.append(legs[current, following])
        if following != 1:
            ordered.append(following)
        current = following
    stops = []
    completed = set()
    for i in ordered:
        task = request.tasks[task_index[i]]
        completed.add(task.id)
        arrival = request.departure + timedelta(seconds=solver.Value(arrivals[i]))
        departure = arrival + timedelta(seconds=service[i])
        if task.deadline and departure > task.deadline:
            raise PlanningError("方案未通过截止时间校验，请重试。")
        if stops and stops[-1].place.location == places[i].location:
            stop = stops[-1]
            stop.task_ids.append(task.id)
            stop.task_titles.append(task.title)
            stop.departure = departure
            stop.service_seconds += service[i]
        else:
            stops.append(Stop(place=places[i], task_ids=[task.id], task_titles=[task.title],
                              arrival=arrival, departure=departure, service_seconds=service[i]))
    travel = sum(leg.seconds for leg in route_legs)
    service_total = sum(stop.service_seconds for stop in stops)
    total = travel + service_total
    arrival = request.departure + timedelta(seconds=total)
    if total != solver.Value(arrivals[1]) or (request.arrive_by and arrival > request.arrive_by):
        raise PlanningError("方案未通过到达时间校验，请重试。")
    if request.max_extra_minutes is not None and total > baseline.seconds + request.max_extra_minutes * 60:
        raise PlanningError("方案超过额外时间预算，请重试。")
    omitted = [Omitted(task_id=t.id, title=t.title,
                       reason="附近未找到匹配地点" if not candidates[i] else "当前偏好与约束下未安排，可调整选择重新规划")
               for i, t in enumerate(request.tasks) if t.id not in completed]
    return PlanResult(mode=request.mode, origin=request.origin, destination=request.destination,
                      baseline_meters=baseline.meters, baseline_seconds=baseline.seconds, travel_seconds=travel, service_seconds=service_total,
                      total_seconds=total, extra_seconds=total-baseline.seconds,
                      extra_travel_seconds=travel-baseline.seconds, arrival=arrival, stops=stops,
                      legs=route_legs, omitted=omitted, solver_status=solver.StatusName(status),
                      warnings=["办事时长为估算，未包含未知排队时间；营业时间尚未校验。",
                                "仅在已搜索到的候选地点中规划，不保证全城最优。"])
