"""Durable, isolated delegation; all tools and models in these tests are local."""
from uuid import uuid4

import pytest
from fastapi import HTTPException

from backend.agent.store import AgentStore


def task(store, **changes):
    return store.create({"request_id": str(uuid4()), "conversation_id": "delegation-test",
                         "message_id": str(uuid4()), "goal": "Collect parcel, print paper, return to dorm",
                         "constraints": {}, "success_criteria": ["Preserve unaffected stops"],
                         "budget": {"max_steps": 24, "max_tool_calls": 12}, **changes})


def claim(store, snapshot):
    return store.claim(snapshot["run"]["agent_run_id"], "test-worker")["lease_token"]


def delegate(store, parent, token, key="research", **changes):
    return store.delegate(parent["run"]["agent_run_id"], token, {
        "delegation_key": key, "goal": "Check facts relevant to the goal",
        "constraints": {}, "success_criteria": ["Cite evidence"],
        "allowed_tools": ["fixture.lookup"], "input_refs": [],
        "budget": {"max_steps": 8, "max_tool_calls": 3, "max_duration_seconds": 60}, **changes})


@pytest.fixture
def store(tmp_path):
    return AgentStore(tmp_path / "agent.sqlite3")


def test_delegate_is_durable_idempotent_and_independent(store):
    parent = task(store)
    token = claim(store, parent)
    first = delegate(store, parent, token)
    assert delegate(store, parent, token) == first
    assert first["child_task_id"] != parent["agent_task_id"]
    assert store.get(parent["agent_task_id"])["current_run_id"] == parent["current_run_id"]
    reopened = AgentStore(store.path)
    child = reopened.get(first["child_task_id"])
    assert child["delegation"]["parent_run_id"] == parent["current_run_id"]
    assert child["delegation"]["allowed_tools"] == ["fixture.lookup"]
    with pytest.raises(HTTPException):
        delegate(store, parent, token, goal="Different content")
    child_token = claim(store, child)
    with pytest.raises(HTTPException):
        delegate(store, child, child_token)


def test_child_submission_wakes_parent_and_requires_acceptance(store):
    parent = task(store)
    token = claim(store, parent)
    link = delegate(store, parent, token)
    store.wait_for_children(parent["current_run_id"], token)
    assert parent["current_run_id"] not in store.ready_runs()
    child = store.get(link["child_task_id"])
    child_token = claim(store, child)
    artifact = store.publish_artifact(child["current_run_id"], child_token, "text", {"answer": "Two stops"})
    store.transition(child["current_run_id"], child_token, "completed", "Evidence ready")
    reopened = AgentStore(store.path)
    assert parent["current_run_id"] in reopened.ready_runs()
    snapshot = reopened.get(parent["agent_task_id"])
    assert snapshot["delegations"][0]["status"] == "submitted"
    assert snapshot["delegations"][0]["outputs"][0]["artifact_id"] == artifact["artifact_id"]
    token = claim(reopened, snapshot)
    reopened.publish_artifact(parent["current_run_id"], token, "text", {"answer": "checked"})
    with pytest.raises(HTTPException):
        reopened.transition(parent["current_run_id"], token, "completed", "Premature")
    with pytest.raises(HTTPException):
        reopened.review_delegation(parent["current_run_id"], token, "research", "accept", "Not inspected")
    read = reopened.prepare_call(parent["current_run_id"], token, "agent.read_artifact", "v1", {
        "artifact_id": artifact["artifact_id"], "version": 1})
    sent = reopened.dispatch_call(read["tool_call_id"], token)
    reopened.record_result(read["tool_call_id"], sent["receipt_token"], "succeeded", result={"data": {
        "source_kind": "artifact", "source_ref": artifact["artifact_id"], "version": 1,
        "text": "fixture", "offset": 0, "has_more": False, "next_offset": 7, "total_chars": 7}})
    reopened.review_delegation(parent["current_run_id"], token, "research", "accept", "Checked result")
    reopened.transition(parent["current_run_id"], token, "completed", "Delivered")


def test_evidence_is_explicit_pinned_and_not_shared_with_siblings(store):
    parent = task(store)
    token = claim(store, parent)
    call = store.prepare_call(parent["current_run_id"], token, "fixture.lookup", "v1", {})
    receipt = store.dispatch_call(call["tool_call_id"], token)
    store.record_result(call["tool_call_id"], receipt["receipt_token"], "succeeded", {"stops": ["parcel", "dorm"]})
    link = delegate(store, parent, token, input_refs=[{"kind": "result", "ref": call["tool_call_id"]}])
    sibling = delegate(store, parent, token, key="independent")
    assert store.evidence_allowed(link["child_task_id"], "result", call["tool_call_id"])
    assert not store.evidence_allowed(sibling["child_task_id"], "result", call["tool_call_id"])
    alien = task(store)
    alien_token = claim(store, alien)
    artifact = store.publish_artifact(alien["current_run_id"], alien_token, "text", {"private": True})
    with pytest.raises(HTTPException):
        delegate(store, parent, token, key="leak", input_refs=[{"kind": "artifact", "ref": artifact["artifact_id"], "version": 1}])


def test_shared_budget_is_atomic_and_survives_restart(store):
    parent = task(store, budget={"max_steps": 16, "max_tool_calls": 3})
    token = claim(store, parent)
    first = delegate(store, parent, token, budget={"max_steps": 6, "max_tool_calls": 3})
    second = delegate(store, parent, token, key="second", budget={"max_steps": 6, "max_tool_calls": 3})
    for link in (first, second):
        child = store.get(link["child_task_id"])
        child_token = claim(store, child)
        for _ in range(3):
            store.reserve_step(child["current_run_id"], child_token)
    reopened = AgentStore(store.path)
    with pytest.raises(HTTPException) as error:
        reopened.reserve_step(child["current_run_id"], child_token)
    assert error.value.detail == "budget_tree_steps"
    # Keep publication, finish and reviews for the coordinator; no reset on restart.
    for _ in range(10):
        reopened.reserve_step(parent["current_run_id"], token)
    with pytest.raises(HTTPException):
        reopened.reserve_step(parent["current_run_id"], token)


def test_revision_revokes_children_and_grants_but_keeps_late_receipt(store):
    parent = task(store)
    token = claim(store, parent)
    artifact = store.publish_artifact(parent["current_run_id"], token, "text", {"stops": ["parcel", "print", "dorm"]})
    link = delegate(store, parent, token, input_refs=[{"kind": "artifact", "ref": artifact["artifact_id"], "version": 1}])
    child = store.get(link["child_task_id"])
    child_token = claim(store, child)
    call = store.prepare_call(child["current_run_id"], child_token, "fixture.lookup", "v1", {})
    receipt = store.dispatch_call(call["tool_call_id"], child_token)
    revised = store.revise(parent["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1,
        "goal": "Cancel printing, retain parcel and dorm", "constraints": {}, "success_criteria": []})
    store.record_result(call["tool_call_id"], receipt["receipt_token"], "succeeded", {"old": True})
    assert store.get(child["agent_task_id"])["run"]["state"] == "cancelled"
    assert not store.evidence_allowed(child["agent_task_id"], "artifact", artifact["artifact_id"], 1)
    assert store.get(parent["agent_task_id"])["current_run_id"] == revised["current_run_id"]
    assert not store.get(parent["agent_task_id"]).get("delegations")


def test_tool_scope_cannot_be_bypassed_via_store(store):
    parent = task(store)
    token = claim(store, parent)
    link = delegate(store, parent, token)
    child = store.get(link["child_task_id"])
    child_token = claim(store, child)
    with pytest.raises(HTTPException):
        store.prepare_call(child["current_run_id"], child_token, "private.delete", "v1", {})


def test_blocker_wakes_parent_without_replaying_child(store):
    parent = task(store)
    token = claim(store, parent)
    link = delegate(store, parent, token)
    store.wait_for_children(parent["current_run_id"], token)
    child = store.get(link["child_task_id"])
    child_token = claim(store, child)
    store.transition(child["current_run_id"], child_token, "paused", "provider_error")
    assert parent["current_run_id"] in store.ready_runs()
    assert child["current_run_id"] not in store.ready_runs()
    snapshot = store.get(parent["agent_task_id"])
    assert snapshot["delegations"][0]["child_state"] == "paused"


def test_runtime_delegates_executes_reads_accepts_and_delivers_after_restart(tmp_path):
    import json
    from backend.agent.decision import DecisionProvider
    from backend.agent.runtime import AgentRuntime
    from backend.agent.tools import ToolCatalog, ToolDefinition

    store = AgentStore(tmp_path / "runtime.sqlite3")
    parent = task(store)
    catalog, calls, memory_tasks = ToolCatalog(), [], []
    catalog.register(ToolDefinition("fixture", "lookup", "Check parcel and dorm facts", {"type": "object"}),
                     lambda args: calls.append(args) or {"stops": ["parcel", "dorm"]})
    catalog.register(ToolDefinition("private", "lookup", "Private unrelated source", {"type": "object"}),
                     lambda args: pytest.fail("Child escaped tool scope"))
    parent_step, child_step = 0, 0

    def completion(messages, model, structured=False):
        nonlocal parent_step, child_step
        context = json.loads(messages[-1]["content"])
        if context["task"]["agent_task_id"] == parent["agent_task_id"]:
            parent_step += 1
            if parent_step == 1:
                action = {"action": "delegate_task", "delegation_key": "facts", "goal": "Check parcel and dorm facts",
                          "constraints": {}, "success_criteria": ["Use lookup"], "allowed_tools": ["fixture.lookup"],
                          "input_refs": [], "budget": {"max_steps": 6, "max_tool_calls": 2, "max_duration_seconds": 60}}
            elif parent_step == 2:
                action = {"action": "wait_children"}
            elif parent_step in {3, 4}:
                output = context["delegations"][0]["outputs"][0]
                action = ({"action": "discover_tools", "query": "agent.read_artifact"} if parent_step == 3 else
                          {"action": "call_tool", "tool_id": "agent.read_artifact", "arguments": {
                              "artifact_id": output["artifact_id"], "version": output["version"]}})
            elif parent_step == 5:
                assert "parcel" in json.dumps(context)
                action = {"action": "review_delegation", "delegation_key": "facts", "verdict": "accept", "reason": "Both remaining stops verified"}
            elif parent_step == 6:
                action = {"action": "publish_artifact", "kind": "text", "title": "Remaining stops",
                          "content": {"stops": ["parcel", "dorm"]}, "evidence_call_ids": []}
            else:
                action = {"action": "finish", "summary": "Both stops retained"}
        else:
            child_step += 1
            assert all(t["tool_id"] != "private.lookup" for t in context["tools"])
            assert all(x["namespace"] != "private" for x in context["available_namespaces"]["items"])
            assert context["delegation"]["allowed_tools"] == ["fixture.lookup"]
            if child_step == 1:
                action = {"action": "call_tool", "tool_id": "fixture.lookup", "arguments": {}}
            elif child_step == 2:
                action = {"action": "publish_artifact", "kind": "text", "title": "Lookup result",
                          "content": {"stops": ["parcel", "dorm"]}, "evidence_call_ids": []}
            else:
                action = {"action": "finish", "summary": "Facts ready for coordinator"}
        return json.dumps(action)

    provider = DecisionProvider(completion_fn=completion, model="controlled-test")
    runtime = AgentRuntime(store, catalog, provider, memory_reader=lambda s: memory_tasks.append(s["agent_task_id"]) or [], delegation_enabled=True)
    runtime.run(parent["current_run_id"])
    assert store.get(parent["agent_task_id"])["run"]["reason"] == "waiting_children"
    # Recreate the store/runtime exactly where the background worker would restart.
    store = AgentStore(store.path)
    runtime = AgentRuntime(store, catalog, provider, memory_reader=lambda s: memory_tasks.append(s["agent_task_id"]) or [], delegation_enabled=True)
    for run_id in store.ready_runs():
        runtime.run(run_id)
    assert parent["current_run_id"] in store.ready_runs()
    runtime.run(parent["current_run_id"])
    finished = store.get(parent["agent_task_id"])
    assert finished["run"]["state"] == "completed"
    assert finished["delegations"][0]["status"] == "accepted"
    assert calls == [{}]
    assert set(memory_tasks) == {parent["agent_task_id"]}


def test_concurrent_children_cannot_overdraw_root_budget(store):
    from concurrent.futures import ThreadPoolExecutor
    parent = task(store, budget={"max_steps": 13, "max_tool_calls": 3})
    token = claim(store, parent)
    children = [store.get(delegate(store, parent, token, key=key)["child_task_id"]) for key in ("a", "b")]
    leases = [claim(store, child) for child in children]
    def reserve(item):
        child, lease = item
        try:
            store.reserve_step(child["current_run_id"], lease)
            return True
        except HTTPException:
            return False
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(reserve, list(zip(children, leases)) * 3))
    assert results.count(True) == 3
    assert store.get(parent["agent_task_id"])["shared_budget"]["steps_used"] == 3


def test_shared_tool_budget_and_read_only_boundary(store):
    parent = task(store, budget={"max_steps": 20, "max_tool_calls": 2})
    token = claim(store, parent)
    child = store.get(delegate(store, parent, token)["child_task_id"])
    child_token = claim(store, child)
    with pytest.raises(HTTPException):
        store.prepare_call(child["current_run_id"], child_token, "fixture.lookup", "v1", {}, effect="write")
    store.prepare_call(child["current_run_id"], child_token, "fixture.lookup", "v1", {})
    with pytest.raises(HTTPException) as error:
        store.prepare_call(child["current_run_id"], child_token, "fixture.lookup", "v1", {})
    assert error.value.detail == "budget_tree_tool_calls"
    store.prepare_call(parent["current_run_id"], token, "agent.read_artifact", "v1", {})


def test_wait_excludes_idle_duration_and_counts_child_activity(tmp_path):
    clock = [1000.0]
    store = AgentStore(tmp_path / "clock.sqlite3", clock=lambda: clock[0])
    parent = task(store, budget={"max_duration_seconds": 10})
    token = claim(store, parent)
    child = store.get(delegate(store, parent, token)["child_task_id"])
    clock[0] += 2
    store.wait_for_children(parent["current_run_id"], token)
    clock[0] += 100
    lease = claim(store, child)
    clock[0] += 8
    with pytest.raises(HTTPException) as error:
        store.reserve_step(child["current_run_id"], lease)
    assert error.value.detail == "budget_tree_duration"
    assert store.get(parent["agent_task_id"])["shared_budget"]["active_used_seconds"] == 10


def test_unknown_child_receipt_is_not_replayed_after_restart(tmp_path):
    clock = [1000.0]
    store = AgentStore(tmp_path / "recovery.sqlite3", clock=lambda: clock[0])
    parent = task(store)
    token = claim(store, parent)
    child = store.get(delegate(store, parent, token)["child_task_id"])
    store.wait_for_children(parent["current_run_id"], token)
    lease = claim(store, child)
    call = store.prepare_call(child["current_run_id"], lease, "fixture.lookup", "v1", {})
    store.dispatch_call(call["tool_call_id"], lease)
    clock[0] += 31
    reopened = AgentStore(store.path, clock=lambda: clock[0])
    with pytest.raises(HTTPException):
        reopened.claim(child["current_run_id"], "new-worker")
    assert reopened.get_call(call["tool_call_id"])["state"] == "unknown"
    assert parent["current_run_id"] in reopened.ready_runs()
    assert child["current_run_id"] not in reopened.ready_runs()
    with pytest.raises(HTTPException):
        reopened.dispatch_call(call["tool_call_id"], lease)


def test_rejected_attempt_can_be_reworked_with_explicit_output_grant(store):
    parent = task(store)
    token = claim(store, parent)
    first = store.get(delegate(store, parent, token)["child_task_id"])
    lease = claim(store, first)
    artifact = store.publish_artifact(first["current_run_id"], lease, "text", {"answer": "incomplete"})
    store.transition(first["current_run_id"], lease, "completed", "Submitted")
    store.review_delegation(parent["current_run_id"], token, "research", "reject", "Missing source")
    second = delegate(store, parent, token, key="rework", input_refs=[{
        "kind": "artifact", "ref": artifact["artifact_id"], "version": 1}])
    assert store.evidence_allowed(second["child_task_id"], "artifact", artifact["artifact_id"], 1)
    assert store.get(second["child_task_id"])["shared_budget"]["root_run_id"] == parent["current_run_id"]


def test_repeated_pause_after_child_resume_wakes_parent_again(store):
    parent = task(store)
    token = claim(store, parent)
    child = store.get(delegate(store, parent, token)["child_task_id"])
    store.wait_for_children(parent["current_run_id"], token)
    lease = claim(store, child)
    store.transition(child["current_run_id"], lease, "paused", "provider_error")
    store.resume(child["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1, "reason": "Retry when available"})
    token = claim(store, store.get(parent["agent_task_id"]))
    store.wait_for_children(parent["current_run_id"], token)
    lease = claim(store, child)
    store.transition(child["current_run_id"], lease, "paused", "provider_error")
    assert parent["current_run_id"] in store.ready_runs()


@pytest.mark.parametrize("budget", [{"max_steps": 8}, {"max_tool_calls": 1}])
def test_cannot_delegate_without_minimum_work_and_acceptance_budget(store, budget):
    parent = task(store, budget=budget)
    token = claim(store, parent)
    with pytest.raises(HTTPException) as error:
        delegate(store, parent, token)
    assert "Insufficient shared budget" in error.value.detail
    assert not store.get(parent["agent_task_id"]).get("delegations")


@pytest.mark.parametrize("args", [{"offset": 999999}, {"max_chars": 1}])
def test_empty_or_partial_artifact_read_does_not_count_as_acceptance(store, args):
    from backend.agent.runtime import AgentRuntime
    parent = task(store)
    token = claim(store, parent)
    child = store.get(delegate(store, parent, token)["child_task_id"])
    lease = claim(store, child)
    artifact = store.publish_artifact(child["current_run_id"], lease, "text", {"answer": "Must actually inspect this"})
    store.transition(child["current_run_id"], lease, "completed", "Done")
    call = store.prepare_call(parent["current_run_id"], token, "agent.read_artifact", "v1", {
        "artifact_id": artifact["artifact_id"], "version": 1, **args})
    sent = store.dispatch_call(call["tool_call_id"], token)
    data = {**AgentRuntime._page(store.get_artifact(artifact["artifact_id"], 1), args),
            "source_kind": "artifact", "source_ref": artifact["artifact_id"], "version": 1}
    store.record_result(call["tool_call_id"], sent["receipt_token"], "succeeded", result={"data": data})
    with pytest.raises(HTTPException):
        store.review_delegation(parent["current_run_id"], token, "research", "accept", "Cannot accept unread result")


def test_child_output_is_one_bounded_versioned_artifact(store):
    parent = task(store)
    child = store.get(delegate(store, parent, claim(store, parent))["child_task_id"])
    lease = claim(store, child)
    with pytest.raises(HTTPException):
        store.publish_artifact(child["current_run_id"], lease, "text", {"large": "x" * 2000})
    assert not store.get(child["agent_task_id"])["artifacts"]
    first = store.publish_artifact(child["current_run_id"], lease, "text", {"answer": "small"})
    with pytest.raises(HTTPException):
        store.publish_artifact(child["current_run_id"], lease, "text", {"answer": "another"})
    store.publish_artifact(child["current_run_id"], lease, "text", {"answer": "updated"},
                           artifact_id=first["artifact_id"], expected_version=1)
    assert store.get(child["agent_task_id"])["artifacts"][0]["version"] == 2


def test_child_keeps_root_requirements_and_parent_keeps_delegation_contract(store):
    from backend.agent.decision import build_context
    import json
    parent = task(store, constraints={"must_keep": ["parcel", "dorm"], "forbidden": "printing"})
    link = delegate(store, parent, claim(store, parent), goal="Different model suggestion")
    child = store.get(link["child_task_id"])
    assert child["coordinator_requirements"]["constraints"] == parent["constraints"]
    data = json.loads(build_context(child, []).messages[-1]["content"])
    assert data["coordinator_requirements"]["constraints"]["must_keep"] == ["parcel", "dorm"]
    root_data = json.loads(build_context(store.get(parent["agent_task_id"]), []).messages[-1]["content"])
    assert root_data["delegations"][0]["goal"] == "Different model suggestion"
    assert root_data["delegations"][0]["success_criteria"] == ["Cite evidence"]


def test_background_worker_runs_two_children_without_parent_blocking_queue(tmp_path):
    import json
    import threading
    from types import SimpleNamespace
    from backend.agent.decision import DecisionProvider
    from backend.agent.runtime import AgentRuntime
    from backend.agent.service import AgentService
    from backend.agent.tools import ToolCatalog

    store = AgentStore(tmp_path / "service.sqlite3")
    parent = task(store, budget={"max_steps": 32})
    def completion(messages, model, structured=False):
        data = json.loads(messages[-1]["content"])
        if data.get("delegation"):
            if data["artifacts"]:
                action = {"action": "finish", "summary": "Submitted local calculation"}
            else:
                action = {"action": "publish_artifact", "kind": "text", "title": "Calculation",
                          "content": {"value": 4}, "evidence_call_ids": []}
        elif len(data.get("delegations", [])) < 2:
            action = {"action": "delegate_task", "delegation_key": str(len(data.get("delegations", []))),
                      "goal": "Compute two plus two", "allowed_tools": []}
        elif any(d["status"] == "active" for d in data["delegations"]):
            action = {"action": "wait_children"}
        elif pending := next((d for d in data["delegations"] if d["status"] == "submitted"), None):
            output = pending["outputs"][0]
            # This fixture is a deterministic coordinator, not a model evaluation.
            seen = [call for call in store.get(parent["agent_task_id"])["tool_calls"]
                    if call["state"] == "succeeded" and call["arguments"].get("artifact_id") == output["artifact_id"]]
            if seen:
                action = {"action": "review_delegation", "delegation_key": pending["delegation_key"],
                          "verdict": "accept", "reason": "Calculation checked"}
            elif "agent.read_artifact" not in {tool["tool_id"] for tool in data["tools"]}:
                action = {"action": "discover_tools", "query": "agent.read_artifact"}
            else:
                action = {"action": "call_tool", "tool_id": "agent.read_artifact", "arguments": {
                    "artifact_id": output["artifact_id"], "version": output["version"]}}
        elif not data["artifacts"]:
            action = {"action": "publish_artifact", "kind": "text", "title": "Combined result",
                      "content": {"value": 8}, "evidence_call_ids": []}
        else:
            action = {"action": "finish", "summary": "Both contributions checked"}
        return json.dumps(action)

    runtime = AgentRuntime(store, ToolCatalog(), DecisionProvider(completion_fn=completion, model="fixture"),
                           memory_reader=lambda _: [], delegation_enabled=True)
    service = AgentService(SimpleNamespace(), runtime_factory=lambda _: runtime, poll_seconds=0.01)
    service.start()
    try:
        for _ in range(500):
            final = store.get(parent["agent_task_id"])
            if final["run"]["state"] == "completed":
                break
            threading.Event().wait(0.01)
        assert final["run"]["state"] == "completed", final["run"]
        assert [item["status"] for item in final["delegations"]] == ["accepted", "accepted"]
        assert not service.errors
    finally:
        service.stop()
    assert not service.thread.is_alive()


def test_shared_artifact_includes_only_its_provenance_for_child_completion_review(store):
    import json
    from backend.agent.decision import DecisionProvider
    from backend.agent.review import ReviewProvider
    from backend.agent.runtime import AgentRuntime
    from backend.agent.tools import ToolCatalog

    parent = task(store)
    token = claim(store, parent)
    call = store.prepare_call(parent["current_run_id"], token, "fixture.lookup", "v1", {})
    sent = store.dispatch_call(call["tool_call_id"], token)
    store.record_result(call["tool_call_id"], sent["receipt_token"], "succeeded", {"data": {"answer": 4}})
    hidden = store.prepare_call(parent["current_run_id"], token, "fixture.lookup", "v1", {})
    sent_hidden = store.dispatch_call(hidden["tool_call_id"], token)
    store.record_result(hidden["tool_call_id"], sent_hidden["receipt_token"], "succeeded", {"data": {"private": True}})
    artifact = store.publish_artifact(parent["current_run_id"], token, "text", {"answer": 4},
                                     sources=[{"tool_call_id": call["tool_call_id"]}])
    link = delegate(store, parent, token, allowed_tools=[], input_refs=[{
        "kind": "artifact", "ref": artifact["artifact_id"], "version": 1}])
    child = store.get(link["child_task_id"])
    actions = iter([
        {"action": "discover_tools", "query": "agent.read_artifact"},
        {"action": "call_tool", "tool_id": "agent.read_artifact", "arguments": {"artifact_id": artifact["artifact_id"], "version": 1}},
        {"action": "publish_artifact", "kind": "text", "title": "Checked", "content": {"answer": 4}, "evidence_call_ids": []},
        {"action": "finish", "summary": "Source checked"},
    ])
    reviewed = []
    def review(messages, model, structured=False, max_tokens=None):
        reviewed.append(messages)
        assert call["tool_call_id"] in json.dumps(messages)
        assert hidden["tool_call_id"] not in json.dumps(messages)
        return '{"status":"supported","findings":[]}'
    runtime = AgentRuntime(store, ToolCatalog(), DecisionProvider(completion_fn=lambda *a, **k: json.dumps(next(actions))),
                           memory_reader=lambda _: [], artifact_reviewer=ReviewProvider(completion_fn=review), delegation_enabled=True)
    runtime.run(child["current_run_id"])
    outcome = store.get(child["agent_task_id"])
    assert outcome["run"]["state"] == "completed", (outcome["run"]["reason"], [(x["kind"], x["payload"]) for x in outcome["observations"]])
    assert len(reviewed) == 1
    assert store.evidence_allowed(child["agent_task_id"], "result", call["tool_call_id"])
    assert not store.evidence_allowed(child["agent_task_id"], "result", hidden["tool_call_id"])


def test_revision_retains_previously_submitted_evidence_for_parent_but_revokes_children(store):
    parent = task(store)
    token = claim(store, parent)
    first = store.get(delegate(store, parent, token)["child_task_id"])
    first_token = claim(store, first)
    artifact = store.publish_artifact(first["current_run_id"], first_token, "text", {"answer": "previous evidence"})
    store.transition(first["current_run_id"], first_token, "completed", "Submitted before revision")
    second = store.get(delegate(store, parent, token, key="pending", input_refs=[{
        "kind": "artifact", "ref": artifact["artifact_id"], "version": 1}])["child_task_id"])
    revised = store.revise(parent["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1,
        "goal": "Remove printing and retain parcel/dorm", "constraints": {}, "success_criteria": []})
    assert store.evidence_allowed(parent["agent_task_id"], "artifact", artifact["artifact_id"], 1)
    assert not store.evidence_allowed(second["agent_task_id"], "artifact", artifact["artifact_id"], 1)
    assert store.get(second["agent_task_id"])["run"]["state"] == "cancelled"
    assert revised["run"]["state"] == "queued" and not revised.get("delegations")
