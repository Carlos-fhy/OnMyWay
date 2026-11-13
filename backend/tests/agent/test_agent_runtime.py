"""End-to-end loop behavior with controlled model and tool boundaries."""
import importlib.util
import json
from types import SimpleNamespace
from uuid import uuid4

import pytest


def create_task(store, **changes):
    return store.create({"request_id": str(uuid4()), "conversation_id": "trip-chat",
                         "message_id": str(uuid4()), "goal": "成都四天旅行，预算三千，别太累",
                         "constraints": {"days": 4, "budget_cny": 3000},
                         "success_criteria": ["依据查询结果调整"], **changes})


@pytest.fixture
def parts(tmp_path):
    assert importlib.util.find_spec("backend.agent.runtime") is not None, "Generic decision-tool-observation loop is missing"
    from backend.agent.store import AgentStore
    from backend.agent.tools import ToolCatalog, ToolDefinition
    from backend.agent.decision import DecisionProvider
    from backend.agent.runtime import AgentRuntime
    return SimpleNamespace(store=AgentStore(tmp_path / "tasks.sqlite3"),
                           catalog=ToolCatalog(), Tool=ToolDefinition,
                           Provider=DecisionProvider, Runtime=AgentRuntime)


def provider(parts, actions):
    steps = iter(actions)
    def completion(messages, model, structured=False):
        item = next(steps)
        if callable(item):
            item = item(messages)
        return json.dumps(item, ensure_ascii=False)
    return parts.Provider(completion_fn=completion, model="controlled-test")


def run(parts, task, actions, **kwargs):
    worker = parts.Runtime(parts.store, parts.catalog, provider(parts, actions), memory_reader=lambda _: [], **kwargs)
    worker.run(task["current_run_id"])
    return parts.store.get(task["agent_task_id"])


def test_loop_observes_closed_attraction_and_selects_alternative(parts):
    calls = []
    parts.catalog.register(parts.Tool("travel", "hours", "成都景点开放时间查询", {"type": "object", "properties": {}, "additionalProperties": False}),
                           lambda args: calls.append("hours") or {"museum": "closed", "source": "fixture-calendar"})
    parts.catalog.register(parts.Tool("travel", "parks", "成都轻松公园替代查询", {"type": "object", "properties": {}, "additionalProperties": False}),
                           lambda args: calls.append("parks") or {"park": "open", "source": "fixture-park"})
    task = create_task(parts.store)
    def after_closed(messages):
        assert "closed" in json.dumps(messages)
        return {"action": "call_tool", "tool_id": "travel.parks", "arguments": {}}
    result = run(parts, task, [
        {"action": "discover_tools", "query": "成都 景点 开放 公园"},
        {"action": "call_tool", "tool_id": "travel.hours", "arguments": {}},
        after_closed,
        {"action": "publish_artifact", "kind": "itinerary", "title": "四天行程",
         "content": {"day2": "公园", "limitations": ["交通报价尚缺"]}, "evidence_call_ids": []},
        {"action": "finish", "summary": "已根据开放信息调整第二天，交通报价待补"},
    ])
    assert calls == ["hours", "parks"]
    assert result["run"]["state"] == "completed"
    assert len(result["tool_calls"]) == 2
    assert all(c["state"] == "succeeded" for c in result["tool_calls"])
    assert "公园" in json.dumps(result["artifacts"], ensure_ascii=False)


def test_delivery_contract_errors_are_correctable_before_publishing(parts):
    task = create_task(parts.store, constraints={"delivery_contract": {"kind": "record", "content_schema": {
        "type": "object", "required": ["count"], "properties": {"count": {"type": "integer"}}}}})
    def correct(messages):
        data = json.loads(messages[-1]["content"])
        assert not data["artifacts"]
        assert any(x["payload"].get("code") == "artifact_contract_mismatch" for x in data["observations"])
        return {"action": "publish_artifact", "kind": "record", "title": "Count", "content": {"count": 7}, "evidence_call_ids": []}
    result = run(parts, task, [
        {"action": "publish_artifact", "kind": "text", "title": "Count", "content": {"count": 7}, "evidence_call_ids": []},
        correct, {"action": "finish", "summary": "Delivered"}])
    assert result["run"]["state"] == "completed" and len(result["artifacts"]) == 1
    assert result["artifacts"][0]["kind"] == "record"


def test_explicit_wait_rejects_finish_without_invoking_reviewer(parts):
    task = create_task(parts.store, constraints={"delivery_contract": {"completion_mode": "await_user"}})
    class MustNotReview:
        def review(self, _context):
            pytest.fail("Waiting for a user decision is not a completion review")
    def wait(messages):
        data = json.loads(messages[-1]["content"])
        assert any(x["payload"].get("code") == "user_decision_required" for x in data["observations"])
        return {"action": "ask_user", "question": "Keep or cancel this draft?"}
    result = run(parts, task, [
        {"action": "publish_artifact", "kind": "text", "title": "Draft", "content": "Please decide", "evidence_call_ids": []},
        {"action": "finish", "summary": "Waiting"}, wait], artifact_reviewer=MustNotReview())
    assert result["run"]["state"] == "waiting_user" and len(result["artifacts"]) == 1


@pytest.mark.parametrize("extra", [{}, {"need": "user_decision"}])
def test_explicit_delivery_does_not_pause_for_permission_to_publish(parts, extra):
    task = create_task(parts.store, constraints={"delivery_contract": {"kind": "text"}})
    def deliver(messages):
        data = json.loads(messages[-1]["content"])
        assert any(x["payload"].get("code") == "missing_input_required" for x in data["observations"])
        return {"action": "publish_artifact", "kind": "text", "title": "Result", "content": "Prepared result", "evidence_call_ids": []}
    result = run(parts, task, [{"action": "ask_user", "question": "May I deliver?", **extra},
                              deliver, {"action": "finish", "summary": "Delivered"}])
    assert result["run"]["state"] == "completed"
    assert not any(o["kind"] == "question" for o in result["observations"])


def test_delivery_can_still_ask_a_material_missing_input(parts):
    task = create_task(parts.store, constraints={"delivery_contract": {"kind": "itinerary"}, "dates": None})
    result = run(parts, task, [{"action": "ask_user", "question": "What dates?", "need": "missing_input",
                              "missing_inputs": {"/dates": "Opening calendars depend on travel dates"}}])
    assert result["run"]["state"] == "waiting_user"
    assert result["observations"][-1]["payload"]["missing_inputs"] == {"/dates": "Opening calendars depend on travel dates"}


@pytest.mark.parametrize("value", ["2026-11-14", False, 0, {"a": 1}])
def test_delivery_rejects_asking_for_present_constraint(parts, value):
    task = create_task(parts.store, constraints={"delivery_contract": {"kind": "text"}, "known": value})
    def correct(messages):
        data = json.loads(messages[-1]["content"])
        assert any(x["payload"].get("code") == "input_already_supplied" for x in data["observations"])
        return {"action": "ask_user", "question": "What date?", "need": "missing_input",
                "missing_inputs": {"/date": "Needed to check the calendar"}}
    result = run(parts, task, [{"action": "ask_user", "question": "Confirm known?", "need": "missing_input",
                              "missing_inputs": {"/known": "Need it again"}}, correct])
    assert result["run"]["state"] == "waiting_user" and result["run"]["steps_used"] == 2
    assert len([o for o in result["observations"] if o["kind"] == "question"]) == 1


def test_invalid_action_receives_safe_action_correction(parts):
    task = create_task(parts.store)
    def correct(messages):
        data = json.loads(messages[-1]["content"])
        error = next(x["payload"] for x in data["observations"] if x["kind"] == "decision_error")
        assert error["code"] == "invalid_decision"
        assert error["diagnostic"]["rule"] == "unsupported_action"
        assert "call_tool" in error["diagnostic"]["allowed_actions"]
        assert "sensitive-invalid-output" not in error["message"]
        return {"action": "ask_user", "question": "Missing input"}
    result = run(parts, task, [{"action": "read_result", "tool_id": "sensitive-invalid-output", "arguments": {}}, correct])
    assert result["run"]["state"] == "waiting_user"


def test_write_waits_for_approval_and_resume_dispatches_once(parts):
    sends = []
    parts.catalog.register(parts.Tool("demo", "save", "保存合成计划", {"type": "object", "additionalProperties": False}, effect="write"),
                           lambda args: sends.append(args) or {"receipt": "saved-once"})
    task = create_task(parts.store)
    first = run(parts, task, [{"action": "discover_tools", "query": "保存合成计划"},
                              {"action": "call_tool", "tool_id": "demo.save", "arguments": {}}])
    assert first["run"]["state"] == "waiting_approval" and not sends
    call_id = first["tool_calls"][0]["tool_call_id"]
    parts.store.approve_call(task["agent_task_id"], call_id,
                             {"request_id": str(uuid4()), "expected_revision": 1})
    result = run(parts, task, [
        {"action": "publish_artifact", "kind": "receipt", "title": "保存结果", "content": "已保存", "evidence_call_ids": [call_id]},
        {"action": "finish", "summary": "保存回执已获得"},
    ])
    assert sends == [{}]
    assert len(result["tool_calls"]) == 1 and result["run"]["state"] == "completed"


def test_changed_goal_during_tool_call_keeps_receipt_but_no_stale_artifact(parts):
    task = create_task(parts.store)
    def revise_during_query(args):
        parts.store.revise(task["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1,
                           "goal": "改成两天", "constraints": {"days": 2}, "success_criteria": []})
        return {"old_result": "四天方案"}
    parts.catalog.register(parts.Tool("travel", "query", "旅行查询", {"type": "object"}), revise_during_query)
    run(parts, task, [{"action": "call_tool", "tool_id": "travel.query", "arguments": {}}])
    current = parts.store.get(task["agent_task_id"])
    old = parts.store.get_run(task["current_run_id"])
    assert current["revision"] == 2 and current["constraints"] == {"days": 2}
    assert not current["artifacts"]
    assert old["tool_calls"][0]["state"] == "succeeded"


def test_unknown_external_write_pauses_without_retry(parts):
    from backend.agent.tools import ToolError
    attempts = []
    def unknown(args):
        attempts.append(1)
        raise ToolError("connection_lost", "响应丢失", outcome="unknown")
    parts.catalog.register(parts.Tool("demo", "write", "合成写入", {"type": "object"}, effect="write"), unknown)
    task = create_task(parts.store)
    pending = run(parts, task, [{"action": "discover_tools", "query": "合成写入"},
                                {"action": "call_tool", "tool_id": "demo.write", "arguments": {}}])
    call_id = pending["tool_calls"][0]["tool_call_id"]
    parts.store.approve_call(task["agent_task_id"], call_id, {"request_id": str(uuid4()), "expected_revision": 1})
    result = run(parts, task, [])
    assert attempts == [1]
    assert result["run"]["state"] == "paused"
    assert result["tool_calls"][0]["state"] == "unknown"


def test_model_errors_stop_at_persisted_step_budget(parts):
    task = create_task(parts.store, budget={"max_steps": 2, "max_tool_calls": 2, "max_duration_seconds": 300})
    result = run(parts, task, [{"action": "invented"}, {"action": "invented"}])
    assert result["run"]["state"] == "paused"
    assert result["run"]["steps_used"] == 2
    assert result["run"]["tool_calls_used"] == 0


def test_missing_tool_returns_observation_to_model(parts):
    task = create_task(parts.store)
    def react(messages):
        assert "tool_error" in json.dumps(messages)
        return {"action": "ask_user", "question": "当前没有交通查询工具，请提供出发地和交通预算"}
    result = run(parts, task, [{"action": "call_tool", "tool_id": "missing.tool", "arguments": {}}, react])
    assert result["run"]["state"] == "waiting_user"
    assert not result["tool_calls"]


def test_finish_without_artifact_is_not_completed(parts):
    task = create_task(parts.store, budget={"max_steps": 1, "max_tool_calls": 1, "max_duration_seconds": 300})
    result = run(parts, task, [{"action": "finish", "summary": "假装完成"}])
    assert result["run"]["state"] == "paused"
    assert not result["artifacts"]


def test_context_exhaustion_pauses_without_model_or_tool_call(parts):
    task = create_task(parts.store, constraints={"irreducible": "x" * 50000})
    result = run(parts, task, [], max_input_tokens=1000)
    assert result["run"]["state"] == "paused"
    assert result["run"]["tool_calls_used"] == 0


def test_unavailable_memory_does_not_block_task(parts):
    task = create_task(parts.store)
    def unavailable(_):
        raise OSError("memory unavailable")
    def sees_downgrade(messages):
        assert "memory_unavailable" in json.dumps(messages)
        return {"action": "ask_user", "question": "从哪个城市出发？"}
    worker = parts.Runtime(parts.store, parts.catalog, provider(parts, [sees_downgrade]), memory_reader=unavailable)
    worker.run(task["current_run_id"])
    assert parts.store.get(task["agent_task_id"])["run"]["state"] == "waiting_user"


def test_accepted_long_goal_is_not_rejected_by_tool_search_limit(parts):
    goal = "travel " * 600
    task = create_task(parts.store, goal=goal)
    def sees_full_goal(messages):
        assert json.loads(messages[-1]["content"])["task"]["goal"] == goal.strip()
        return {"action": "ask_user", "question": "具体日期是什么"}
    result = run(parts, task, [sees_full_goal])
    assert result["run"]["state"] == "waiting_user"


def test_read_result_is_paged_and_cannot_read_another_task(parts):
    task = create_task(parts.store)
    lease = parts.store.claim(task["current_run_id"], "seed")
    call = parts.store.prepare_call(task["current_run_id"], lease["lease_token"], "demo.large", "v1", {})
    sent = parts.store.dispatch_call(call["tool_call_id"], lease["lease_token"])
    parts.store.record_result(call["tool_call_id"], sent["receipt_token"], "succeeded", result={"text": "abcdef" * 2000})
    parts.store.transition(task["current_run_id"], lease["lease_token"], "waiting_user", reason="等待继续")
    parts.store.resume(task["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1})
    result = run(parts, task, [
        {"action": "call_tool", "tool_id": "agent.read_result", "arguments": {"tool_call_id": call["tool_call_id"], "offset": 0, "max_chars": 200}},
        {"action": "ask_user", "question": "已读取首段，继续确认要求"},
    ])
    page = result["tool_calls"][-1]["result"]["data"]
    assert len(page["text"]) == 200 and page["has_more"] and page["next_offset"] == 200
    other = create_task(parts.store)
    denied = run(parts, other, [
        {"action": "discover_tools", "query": "agent.read_result"},
        {"action": "call_tool", "tool_id": "agent.read_result", "arguments": {"tool_call_id": call["tool_call_id"]}},
        {"action": "ask_user", "question": "没有此任务的来源"},
    ])
    assert denied["tool_calls"][0]["state"] == "failed"
    assert "abcdef" not in json.dumps(denied)


def seed_selection_source(parts, value, *, state="succeeded"):
    task = create_task(parts.store)
    lease = parts.store.claim(task["current_run_id"], "seed")
    call = parts.store.prepare_call(task["current_run_id"], lease["lease_token"], "demo.query", "v1", {})
    sent = parts.store.dispatch_call(call["tool_call_id"], lease["lease_token"])
    parts.store.record_result(call["tool_call_id"], sent["receipt_token"], state, result=value)
    worker = parts.Runtime(parts.store, parts.catalog, provider(parts, []), memory_reader=lambda _: [])
    return task, call, worker


def test_result_selection_keeps_exact_values_and_original_source(parts):
    import hashlib
    from backend.agent.decision import reader_source
    original = {"data": {"rows": [{"a/b": {"~name": "原值"}, "count": 7}], "unused": "x" * 10000}}
    task, call, worker = seed_selection_source(parts, original)
    scope = worker._active_task.set(task["agent_task_id"])
    try:
        selected = worker._read_result({"tool_call_id": call["tool_call_id"],
                                       "paths": ["/data/rows/0/a~1b/~0name", "/data/rows/0/count"]})
    finally:
        worker._active_task.reset(scope)
    assert selected["representation"] == "json_selection"
    assert selected["values"] == {"/data/rows/0/a~1b/~0name": "原值", "/data/rows/0/count": 7}
    assert selected["source_ref"] == call["tool_call_id"] and selected["source_state"] == "succeeded"
    expected_hash = hashlib.sha256(json.dumps(original, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    assert selected["source_sha256"] == expected_hash
    assert "offset" not in selected and "read_ranges" not in selected
    assert parts.store.get_call(call["tool_call_id"])["result"] == original
    reference = reader_source({"tool_id": "agent.read_result", "result": {"data": selected}})
    assert reference == {"source_kind": "result", "source_ref": call["tool_call_id"]}


@pytest.mark.parametrize("paths,extra,code", [
    (["/missing"], {}, "selection_path_not_found"),
    (["data"], {}, "invalid_selection"),
    (["/data/~2bad"], {}, "invalid_selection"),
    (["/data/rows/01"], {}, "selection_path_not_found"),
    (["/data/rows/-1"], {}, "selection_path_not_found"),
    (["/data/rows/0", "/data/rows/0"], {}, "invalid_selection"),
    (["/data/rows/0"], {"offset": 0}, "invalid_selection"),
    (["/data/rows/0"], {"max_chars": 1}, "invalid_selection"),
    (["/data/large"], {}, "selection_too_large"),
])
def test_result_selection_rejects_ambiguous_or_unbounded_reads(parts, paths, extra, code):
    from backend.agent.tools import ToolError
    task, call, worker = seed_selection_source(parts, {"data": {"rows": [7], "large": "值" * 1000}})
    scope = worker._active_task.set(task["agent_task_id"])
    try:
        with pytest.raises(ToolError) as error:
            worker._read_result({"tool_call_id": call["tool_call_id"], "paths": paths, **extra})
        assert error.value.code == code
    finally:
        worker._active_task.reset(scope)


def test_result_selection_keeps_failed_source_state_and_task_isolation(parts):
    from backend.agent.tools import ToolError
    task, call, worker = seed_selection_source(parts, {"partial": 7}, state="failed")
    scope = worker._active_task.set(task["agent_task_id"])
    try:
        assert worker._read_result({"tool_call_id": call["tool_call_id"], "paths": ["/partial"]})["source_state"] == "failed"
    finally:
        worker._active_task.reset(scope)
    other = create_task(parts.store)
    scope = worker._active_task.set(other["agent_task_id"])
    try:
        with pytest.raises(ToolError, match="来源不属于"):
            worker._read_result({"tool_call_id": call["tool_call_id"], "paths": ["/partial"]})
    finally:
        worker._active_task.reset(scope)


def test_result_selection_reaches_model_and_publishes_original_provenance(parts):
    parts.catalog.register(parts.Tool("demo", "query", "Read structured facts", {"type": "object"}),
                           lambda _: {"count": 7, "detail": "irrelevant " * 800})
    task = create_task(parts.store, goal="Read structured facts", constraints={})
    references = {}
    def select(messages):
        data = json.loads(messages[-1]["content"])
        source = data["tool_results"][0]["tool_call_id"]
        references["source"] = source
        reader = next(item for item in data["tools"] if item["tool_id"] == "agent.read_result")
        assert "paths" in reader["input_schema"]["properties"]
        return {"action": "call_tool", "tool_id": "agent.read_result", "arguments": {
            "tool_call_id": source, "paths": ["/data/count"]}}
    def publish(messages):
        data = json.loads(messages[-1]["content"])
        selection = data["evidence_selections"][0]
        assert selection["values"] == {"/data/count": 7}
        assert selection["source_ref"] == references["source"]
        assert selection["evidence_call_ids"] == [references["source"]]
        assert not data["evidence_pages"] and not data["evidence_sources"]
        assert selection["source_state"] == "succeeded"
        return {"action": "publish_artifact", "kind": "text", "title": "Count", "content": {"count": 7},
                "evidence_call_ids": [references["source"]]}
    result = run(parts, task, [
        {"action": "call_tool", "tool_id": "demo.query", "arguments": {}}, select, publish,
        {"action": "finish", "summary": "Count checked against the original source"}])
    assert len(result["tool_calls"]) == 2
    assert result["tool_calls"][-1]["state"] == "succeeded"
    assert result["run"]["state"] == "completed"
    assert result["artifacts"][0]["sources"][0]["tool_call_id"] == references["source"]
    worker = parts.Runtime(parts.store, parts.catalog, provider(parts, []), memory_reader=lambda _: [])
    scope = worker._active_task.set(task["agent_task_id"])
    try:
        chain = worker._read_result({"tool_call_id": result["tool_calls"][1]["tool_call_id"], "paths": ["/data/count"]})
        assert chain["source_ref"] == references["source"] and chain["values"] == {"/data/count": 7}
    finally:
        worker._active_task.reset(scope)


def test_stop_during_model_response_prevents_new_tool_dispatch(parts):
    import threading
    stopped = threading.Event()
    calls = []
    parts.catalog.register(parts.Tool("travel", "query", "成都旅行查询", {"type": "object"}), lambda _: calls.append(1) or {})
    def stop_then_return(_messages):
        stopped.set()
        return {"action": "call_tool", "tool_id": "travel.query", "arguments": {}}
    task = create_task(parts.store)
    result = run(parts, task, [stop_then_return], stop_event=stopped)
    assert calls == []
    assert result["run"]["state"] == "paused" and result["run"]["reason"] == "worker_stopping"
    assert result["tool_calls"] == []


def test_tool_contract_change_during_model_response_requires_rediscovery(parts):
    calls = []
    parts.catalog.register(parts.Tool("travel", "query", "成都旅行只读查询", {"type": "object"}), lambda _: {})
    def replace_then_return(_messages):
        parts.catalog.register(parts.Tool("travel", "query", "已改变含义的查询", {"type": "object"}), lambda _: calls.append(1) or {})
        return {"action": "call_tool", "tool_id": "travel.query", "arguments": {}}
    task = create_task(parts.store)
    result = run(parts, task, [replace_then_return, {"action": "ask_user", "question": "工具发生变化，需要重新核对"}])
    assert calls == []
    assert result["tool_calls"] == []
    assert result["run"]["state"] == "waiting_user"


def test_revoked_prepared_tool_can_return_to_decision_loop(parts):
    task = create_task(parts.store)
    definition = parts.Tool("demo", "save", "保存旅行计划", {"type": "object"}, effect="write")
    parts.catalog.register(definition, lambda _: {"saved": True})
    first = run(parts, task, [{"action": "call_tool", "tool_id": "demo.save", "arguments": {}}])
    call_id = first["tool_calls"][0]["tool_call_id"]
    parts.store.approve_call(task["agent_task_id"], call_id, {"request_id": str(uuid4()), "expected_revision": 1})
    parts.catalog.revoke("demo.save")
    result = run(parts, task, [{"action": "ask_user", "question": "保存工具已经撤销，保留本地草案"}])
    assert result["run"]["state"] == "waiting_user"
    assert result["tool_calls"][0]["state"] == "failed"


def test_read_prior_artifact_uses_original_version_and_blocks_other_task(parts):
    task = create_task(parts.store)
    lease = parts.store.claim(task["current_run_id"], "seed")
    artifact = parts.store.publish_artifact(task["current_run_id"], lease["lease_token"], "itinerary", {"day1": "原第一天", "day2": "原第二天"})
    current = parts.store.revise(task["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1, "goal": "只修改第二天", "constraints": {}})
    result = run(parts, current, [
        {"action": "discover_tools", "query": "agent.read_artifact"},
        {"action": "call_tool", "tool_id": "agent.read_artifact", "arguments": {"artifact_id": artifact["artifact_id"]}},
        {"action": "ask_user", "question": "第二天希望安排什么"},
    ])
    assert "原第一天" in result["tool_calls"][0]["result"]["data"]["text"]
    other = create_task(parts.store)
    denied = run(parts, other, [
        {"action": "discover_tools", "query": "agent.read_artifact"},
        {"action": "call_tool", "tool_id": "agent.read_artifact", "arguments": {"artifact_id": artifact["artifact_id"]}},
        {"action": "ask_user", "question": "没有此任务的行程来源"},
    ])
    assert denied["tool_calls"][0]["state"] == "failed"


def test_chinese_evidence_pages_are_byte_bounded_and_offsets_do_not_skip(parts):
    worker = parts.Runtime(parts.store, parts.catalog, provider(parts, []), memory_reader=lambda _: [])
    value = {"text": "开放时间与预约说明🙂" * 700}
    expected = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    offset, pages = 0, []
    while offset < len(expected):
        page = worker._page(value, {"offset": offset, "max_chars": 3000})
        assert len(page["text"].encode("utf-8")) <= 2400
        assert page["next_offset"] == offset + len(page["text"])
        assert page["next_offset"] > offset
        pages.append(page["text"])
        offset = page["next_offset"]
    assert "".join(pages) == expected
    assert page["has_more"] is False


def test_reading_reader_result_resolves_original_source_and_observation_time(parts):
    task = create_task(parts.store)
    lease = parts.store.claim(task["current_run_id"], "seed")
    source = parts.store.prepare_call(task["current_run_id"], lease["lease_token"], "demo.source", "v1", {})
    sent = parts.store.dispatch_call(source["tool_call_id"], lease["lease_token"])
    source = parts.store.record_result(source["tool_call_id"], sent["receipt_token"], "succeeded", result={"verified": "source fact"})
    reader = parts.store.prepare_call(task["current_run_id"], lease["lease_token"], "agent.read_result", "v1", {"tool_call_id": source["tool_call_id"]})
    sent = parts.store.dispatch_call(reader["tool_call_id"], lease["lease_token"])
    parts.store.record_result(reader["tool_call_id"], sent["receipt_token"], "succeeded", result={"data": {"text": "old page envelope"}})
    worker = parts.Runtime(parts.store, parts.catalog, provider(parts, []), memory_reader=lambda _: [])
    scope = worker._active_task.set(task["agent_task_id"])
    try:
        original = worker._read_result({"tool_call_id": source["tool_call_id"], "max_chars": 3000})
        normalized = worker._read_result({"tool_call_id": reader["tool_call_id"], "max_chars": 3000})
    finally:
        worker._active_task.reset(scope)
    assert normalized["source_ref"] == source["tool_call_id"]
    assert normalized["source_kind"] == "result"
    assert normalized["observed_at"] == original["observed_at"]
    assert normalized["text"] == original["text"]
    assert "old page envelope" not in normalized["text"]


def test_bounded_evidence_loop_reaches_artifact_and_finish_without_recursive_reads(parts):
    parts.catalog.register(parts.Tool("demo", "query", "资料查询", {"type": "object"}),
                           lambda _: {"text": "可靠来源事实与适用限制。" * 900})
    task = create_task(parts.store, goal="查询资料并交付有来源的简短摘要",
                       budget={"max_steps": 8, "max_tool_calls": 4, "max_duration_seconds": 60})
    source_ids = []

    def read_source(messages):
        data = json.loads(messages[-1]["content"])
        original = data["tool_results"][0]
        assert original["truncated"] is True
        source_ids.append(original["tool_call_id"])
        return {"action": "call_tool", "tool_id": "agent.read_result",
                "arguments": {"tool_call_id": source_ids[0], "max_chars": 3000}}

    def read_next(messages):
        data = json.loads(messages[-1]["content"])
        page = data["evidence_pages"][0]
        assert page["source_ref"] == source_ids[0]
        assert len(page["text"].encode("utf-8")) <= 2400
        durable_page = parts.store.get(task["agent_task_id"])["tool_calls"][-1]["result"]["data"]
        assert page["text"] == durable_page["text"]
        assert {item["tool_call_id"] for item in data["tool_results"]} == set(source_ids)
        return {"action": "call_tool", "tool_id": "agent.read_result",
                "arguments": {"tool_call_id": source_ids[0], "offset": page["next_offset"], "max_chars": 3000}}

    def publish(messages):
        data = json.loads(messages[-1]["content"])
        assert data["evidence_pages"][0]["offset"] > 0
        assert "可靠来源事实" in data["evidence_pages"][0]["text"]
        assert data["evidence_sources"][0]["read_ranges"][0][0] == 0
        assert "agent.read_result" in {item["tool_id"] for item in data["tools"]}
        return {"action": "publish_artifact", "kind": "text", "title": "资料摘要",
                "content": {"summary": "可靠来源事实，未读内容不作完整性承诺",
                            "queried_at": data["evidence_sources"][0]["observed_at"]},
                "evidence_call_ids": source_ids}

    result = run(parts, task, [{"action": "call_tool", "tool_id": "demo.query", "arguments": {}},
                               read_source, read_next, publish, {"action": "finish", "summary": "摘要已保存"}])
    assert result["run"]["state"] == "completed"
    assert result["run"]["steps_used"] == 5
    assert len(result["tool_calls"]) == 3
    assert result["artifacts"][0]["sources"][0]["tool_call_id"] == source_ids[0]


def test_reader_of_historical_artifact_keeps_exact_version(parts):
    task = create_task(parts.store)
    lease = parts.store.claim(task["current_run_id"], "seed")
    artifact = parts.store.publish_artifact(task["current_run_id"], lease["lease_token"], "text", {"fact": "version one"})
    parts.store.publish_artifact(task["current_run_id"], lease["lease_token"], "text", {"fact": "version two"}, artifact_id=artifact["artifact_id"])
    call = parts.store.prepare_call(task["current_run_id"], lease["lease_token"], "agent.read_artifact", "v1",
                                    {"artifact_id": artifact["artifact_id"], "version": 1})
    sent = parts.store.dispatch_call(call["tool_call_id"], lease["lease_token"])
    parts.store.record_result(call["tool_call_id"], sent["receipt_token"], "succeeded", result={"data": {"text": "old page"}})
    worker = parts.Runtime(parts.store, parts.catalog, provider(parts, []), memory_reader=lambda _: [])
    scope = worker._active_task.set(task["agent_task_id"])
    try:
        page = worker._read_result({"tool_call_id": call["tool_call_id"]})
    finally:
        worker._active_task.reset(scope)
    assert page["source_kind"] == "artifact" and page["source_ref"] == artifact["artifact_id"]
    assert page["version"] == 1
    assert "version one" in page["text"] and "version two" not in page["text"]


@pytest.mark.parametrize("legacy", [False, True])
def test_reader_without_version_resolves_persisted_artifact_version(parts, legacy):
    task = create_task(parts.store)
    lease = parts.store.claim(task["current_run_id"], "seed")
    artifact = parts.store.publish_artifact(task["current_run_id"], lease["lease_token"], "text", {"fact": "version one"})
    worker = parts.Runtime(parts.store, parts.catalog, provider(parts, []), memory_reader=lambda _: [])
    scope = worker._active_task.set(task["agent_task_id"])
    try:
        first_page = worker._read_artifact({"artifact_id": artifact["artifact_id"]})
        if legacy:
            first_page = {key: value for key, value in first_page.items()
                          if key in {"text", "offset", "next_offset", "total_chars", "has_more"}}
        call = parts.store.prepare_call(task["current_run_id"], lease["lease_token"], "agent.read_artifact", "v1",
                                        {"artifact_id": artifact["artifact_id"]})
        sent = parts.store.dispatch_call(call["tool_call_id"], lease["lease_token"])
        parts.store.record_result(call["tool_call_id"], sent["receipt_token"], "succeeded", result={"data": first_page})
        parts.store.publish_artifact(task["current_run_id"], lease["lease_token"], "text", {"fact": "version two"}, artifact_id=artifact["artifact_id"])
        page = worker._read_result({"tool_call_id": call["tool_call_id"]})
    finally:
        worker._active_task.reset(scope)
    assert page["version"] == 1
    assert "version one" in page["text"] and "version two" not in page["text"]


def test_revision_can_publish_read_original_source_with_original_query_time(parts):
    first = create_task(parts.store)
    lease = parts.store.claim(first["current_run_id"], "seed")
    source = parts.store.prepare_call(first["current_run_id"], lease["lease_token"], "demo.query", "v1", {})
    sent = parts.store.dispatch_call(source["tool_call_id"], lease["lease_token"])
    source = parts.store.record_result(source["tool_call_id"], sent["receipt_token"], "succeeded", result={"fact": "confirmed"})
    changed = parts.store.revise(first["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1,
                                                        "goal": "Revise using the original source", "constraints": {}})
    def publish(messages):
        data = json.loads(messages[-1]["content"])
        reference = data["evidence_sources"][0]
        assert reference["evidence_call_ids"] == [source["tool_call_id"]]
        return {"action": "publish_artifact", "kind": "text", "title": "Revision", "content": {"fact": "confirmed"},
                "evidence_call_ids": reference["evidence_call_ids"]}
    result = run(parts, changed, [
        {"action": "discover_tools", "query": "agent.read_result"},
        {"action": "call_tool", "tool_id": "agent.read_result", "arguments": {"tool_call_id": source["tool_call_id"]}},
        publish, {"action": "finish", "summary": "Revision saved"}])
    assert result["run"]["state"] == "completed"
    evidence = result["artifacts"][0]["sources"][0]
    assert evidence["tool_call_id"] == source["tool_call_id"]
    from backend.agent.decision import observed_at
    assert evidence["observed_at"] == observed_at(source)


def test_explicit_oversize_schema_pauses_even_when_other_tool_schema_fits(parts):
    parts.catalog.register(parts.Tool("demo", "huge", "huge query", {"type": "object", "description": "x" * 20000}), lambda _: {})
    parts.catalog.register(parts.Tool("demo", "small", "small query", {"type": "object"}), lambda _: {})
    task = create_task(parts.store, goal="Find the appropriate demo query")
    result = run(parts, task, [{"action": "discover_tools", "query": "demo.huge"}])
    assert result["run"]["state"] == "paused"
    assert result["run"]["reason"] == "tool_schema_exceeds_context_budget"
    assert result["run"]["steps_used"] == 2
    assert result["tool_calls"] == []


def test_call_request_for_oversize_schema_pauses_without_discovery_loop(parts):
    parts.catalog.register(parts.Tool("fixture", "read", "Read initial fixture", {"type": "object"}),
                           lambda _: {"fact": "known"})
    parts.catalog.register(parts.Tool("other", "huge", "Unrelated operation", {
        "type": "object", "properties": {"value": {"const": "x" * 20000}}}), lambda _: {})
    task = create_task(parts.store, goal="Read initial fixture", constraints={})
    result = run(parts, task, [
        {"action": "call_tool", "tool_id": "fixture.read", "arguments": {}},
        {"action": "call_tool", "tool_id": "other.huge", "arguments": {}}])
    assert result["run"]["reason"] == "tool_schema_exceeds_context_budget"
    assert len(result["tool_calls"]) == 1
    assert result["tool_calls"][0]["tool_id"] == "fixture.read"
    assert result["run"]["steps_used"] == 3  # Third context stops before the provider.


@pytest.mark.parametrize("foreign, state", [(True, "succeeded"), (False, "unknown"), (False, "failed")])
def test_publication_rejects_foreign_or_unsuccessful_original_sources(parts, foreign, state):
    from backend.agent.tools import ToolError
    task = create_task(parts.store)
    owner = create_task(parts.store) if foreign else task
    lease = parts.store.claim(owner["current_run_id"], "seed")
    source = parts.store.prepare_call(owner["current_run_id"], lease["lease_token"], "demo.query", "v1", {})
    sent = parts.store.dispatch_call(source["tool_call_id"], lease["lease_token"])
    parts.store.record_result(source["tool_call_id"], sent["receipt_token"], state,
                              result={"fact": "source"} if state == "succeeded" else None)
    worker = parts.Runtime(parts.store, parts.catalog, provider(parts, []), memory_reader=lambda _: [])
    with pytest.raises(ToolError):
        worker._publication_sources(parts.store.get(task["agent_task_id"]), [source["tool_call_id"]])


def test_successful_reader_cannot_launder_unknown_source_into_publication(parts):
    from backend.agent.tools import ToolError
    task = create_task(parts.store)
    lease = parts.store.claim(task["current_run_id"], "seed")
    source = parts.store.prepare_call(task["current_run_id"], lease["lease_token"], "demo.query", "v1", {})
    sent = parts.store.dispatch_call(source["tool_call_id"], lease["lease_token"])
    parts.store.record_result(source["tool_call_id"], sent["receipt_token"], "unknown")
    changed = parts.store.revise(task["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1,
                                                       "goal": "Inspect old source", "constraints": {}})
    result = run(parts, changed, [
        {"action": "discover_tools", "query": "agent.read_result"},
        {"action": "call_tool", "tool_id": "agent.read_result", "arguments": {"tool_call_id": source["tool_call_id"]}},
        {"action": "ask_user", "question": "Source outcome remains unknown"}])
    reader = result["tool_calls"][0]
    assert reader["state"] == "succeeded"
    worker = parts.Runtime(parts.store, parts.catalog, provider(parts, []), memory_reader=lambda _: [])
    with pytest.raises(ToolError) as error:
        worker._publication_sources(result, [reader["tool_call_id"]])
    assert error.value.code == "invalid_evidence_reference"
    assert worker._publication_sources(result, []) == []


def test_legacy_artifact_reader_with_unrecoverable_version_is_rejected(parts):
    from backend.agent.tools import ToolError
    task = create_task(parts.store)
    lease = parts.store.claim(task["current_run_id"], "seed")
    artifact = parts.store.publish_artifact(task["current_run_id"], lease["lease_token"], "text", {"fact": "v1"})
    call = parts.store.prepare_call(task["current_run_id"], lease["lease_token"], "agent.read_artifact", "v1",
                                    {"artifact_id": artifact["artifact_id"], "offset": 100})
    sent = parts.store.dispatch_call(call["tool_call_id"], lease["lease_token"])
    parts.store.record_result(call["tool_call_id"], sent["receipt_token"], "succeeded",
                              result={"data": {"text": "fragment", "offset": 100, "next_offset": 108,
                                                "total_chars": 200, "has_more": True}})
    worker = parts.Runtime(parts.store, parts.catalog, provider(parts, []), memory_reader=lambda _: [])
    scope = worker._active_task.set(task["agent_task_id"])
    try:
        with pytest.raises(ToolError) as error:
            worker._read_result({"tool_call_id": call["tool_call_id"]})
    finally:
        worker._active_task.reset(scope)
    assert error.value.code == "ambiguous_evidence_reference"


def test_two_sources_complete_from_saved_quotes_after_original_pages_are_evicted(parts):
    import re
    facts = {name: "record=" + str(uuid4()) for name in ("alpha", "beta")}
    parts.catalog.register(parts.Tool("demo", "query", "Query source records", {
        "type": "object", "properties": {"name": {"type": "string", "enum": ["alpha", "beta"]}},
        "required": ["name"], "additionalProperties": False}),
        lambda args: {"text": facts[args["name"]] + "\n" + "irrelevant filler " * 1500})
    task = create_task(parts.store, goal="Query alpha and beta records and preserve both in a sourced comparison",
                       constraints={}, budget={"max_steps": 24, "max_tool_calls": 20, "max_duration_seconds": 60})
    seen = {"evicted": False}

    def decide(messages):
        data = json.loads(messages[-1]["content"])
        assert len(messages) == 2
        notes = {note["id"]: note for note in data.get("working_notes", [])}
        originals = [entry for entry in data["tool_results"] if entry["tool_id"] == "demo.query"]
        visible = {tool["tool_id"] for tool in data["tools"]}
        if data["artifacts"]:
            return {"action": "finish", "summary": "Both source records were retained"}
        if not originals:
            return {"action": "call_tool", "tool_id": "demo.query", "arguments": {"name": "alpha"}}
        if len(originals) == 1 and "alpha" in notes:
            if "demo.query" not in visible:
                return {"action": "discover_tools", "query": "demo.query"}
            return {"action": "call_tool", "tool_id": "demo.query", "arguments": {"name": "beta"}}
        if set(notes) == {"alpha", "beta"}:
            seen["evicted"] = all(page["source_ref"] != originals[0]["tool_call_id"] for page in data["evidence_pages"])
            assert seen["evicted"] and data["omitted"]["evidence_pages"] > 0
            assert data["evidence_pages"][0]["offset"] > 0
            # No fixture fact values are available to this controlled model:
            # the deliverable is built exclusively from its current context.
            return {"action": "publish_artifact", "kind": "table", "title": "Source comparison",
                    "content": {"records": {key: value["quote"] for key, value in notes.items()}},
                    "evidence_call_ids": [value["source_call_id"] for value in notes.values()]}
        name = "alpha" if len(originals) == 1 else "beta"
        source_id = originals[-1]["tool_call_id"]
        pages = [page for page in data["evidence_pages"] if page["source_ref"] == source_id]
        if not pages:
            return {"action": "call_tool", "tool_id": "agent.read_result", "arguments": {"tool_call_id": source_id}}
        match = re.search(r"record=[0-9a-f-]{36}", pages[0]["text"])
        assert match is not None
        update = {"upsert": [{"id": name, "source_call_id": source_id, "quote": match.group()}], "delete": []}
        if name == "alpha":
            if "demo.query" not in visible:
                return {"action": "discover_tools", "query": "demo.query", "evidence_notes": update}
            return {"action": "call_tool", "tool_id": "demo.query", "arguments": {"name": "beta"}, "evidence_notes": update}
        return {"action": "call_tool", "tool_id": "agent.read_result",
                "arguments": {"tool_call_id": source_id, "offset": pages[0]["next_offset"]}, "evidence_notes": update}

    result = run(parts, task, [decide] * 24)
    assert result["run"]["state"] == "completed", result["observations"]
    assert seen["evicted"]
    assert result["artifacts"][0]["content"]["content"]["records"] == facts
    assert len(result["working_notes"]) == 2
    assert result["run"]["steps_used"] <= 12 and result["run"]["tool_calls_used"] == 5


def test_invalid_evidence_quote_prevents_action_dispatch_and_returns_correction(parts):
    writes = []
    parts.catalog.register(parts.Tool("demo", "query", "Query facts", {"type": "object"}), lambda _: {"text": "verified\nrecord"})
    parts.catalog.register(parts.Tool("demo", "write", "Write record", {"type": "object"}, effect="write"), lambda _: writes.append(1) or {})
    task = create_task(parts.store, goal="Query facts then write record", constraints={})
    def invalid_update(messages):
        data = json.loads(messages[-1]["content"])
        return {"action": "call_tool", "tool_id": "demo.write", "arguments": {}, "evidence_notes": {
            "upsert": [{"id": "fake", "source_call_id": data["tool_results"][0]["tool_call_id"], "quote": "invented approval"}], "delete": []}}
    def after_rejection(messages):
        data = json.loads(messages[-1]["content"])
        assert any(item["payload"].get("code") == "invalid_evidence_quote" for item in data["observations"])
        return {"action": "ask_user", "question": "Please clarify the unsupported claim"}
    result = run(parts, task, [{"action": "call_tool", "tool_id": "demo.query", "arguments": {}}, invalid_update, after_rejection])
    assert result["run"]["state"] == "waiting_user"
    assert writes == [] and len(result["tool_calls"]) == 1
    assert result.get("working_notes", []) == []


def test_notes_preserve_multiline_text_and_exact_json_numeric_fact(parts):
    parts.catalog.register(parts.Tool("demo", "query", "Query records", {"type": "object"}),
                           lambda _: {"name": "水\n质", "cost": 120})
    task = create_task(parts.store, goal="Query records", constraints={})
    def save_notes(messages):
        source_id = json.loads(messages[-1]["content"])["tool_results"][0]["tool_call_id"]
        return {"action": "ask_user", "question": "Which record matters?", "evidence_notes": {"upsert": [
            {"id": "name", "source_call_id": source_id, "quote": "水\n质"},
            {"id": "cost", "source_call_id": source_id, "quote": '"cost":120'}], "delete": []}}
    result = run(parts, task, [{"action": "call_tool", "tool_id": "demo.query", "arguments": {}}, save_notes])
    assert result["run"]["state"] == "waiting_user"
    notes = {note["id"]: note for note in result["working_notes"]}
    assert notes["name"]["quote"] == "水\n质" and notes["name"]["source_path"] == "/data/name"
    assert notes["name"]["quote_format"] == "text" and notes["name"]["source_offset"] == 0
    assert notes["cost"]["quote_format"] == "json" and notes["cost"]["quote"] == '"cost":120'
    assert notes["cost"]["source_offset"] >= 0 and notes["cost"]["source_revision"] == 1


@pytest.mark.parametrize("foreign, state", [(True, "succeeded"), (False, "unknown"), (False, "failed")])
def test_notes_reject_foreign_or_unsuccessful_sources(parts, foreign, state):
    task = create_task(parts.store)
    owner = create_task(parts.store) if foreign else task
    lease = parts.store.claim(owner["current_run_id"], "seed")
    source = parts.store.prepare_call(owner["current_run_id"], lease["lease_token"], "demo.query", "v1", {})
    sent = parts.store.dispatch_call(source["tool_call_id"], lease["lease_token"])
    parts.store.record_result(source["tool_call_id"], sent["receipt_token"], state, result={"fact": "source"})
    if not foreign:
        task = parts.store.revise(task["agent_task_id"], {"request_id": str(uuid4()), "expected_revision": 1,
                                                        "goal": "Inspect source", "constraints": {}})
    result = run(parts, task, [
        {"action": "ask_user", "question": "Must not run", "evidence_notes": {"upsert": [
            {"id": "bad", "source_call_id": source["tool_call_id"], "quote": "source"}], "delete": []}},
        {"action": "ask_user", "question": "No verified source"}])
    assert result["run"]["state"] == "waiting_user" and result["run"]["reason"] == "No verified source"
    assert result["working_notes"] == []
    assert any(item["payload"].get("code") == "invalid_notes_source" for item in result["observations"])


def test_note_storage_overflow_returns_correction_without_dispatch(parts):
    queries = []
    parts.catalog.register(parts.Tool("demo", "query", "Query records", {"type": "object"}),
                           lambda _: queries.append(1) or {"text": "x" * 2200})
    task = create_task(parts.store, goal="Query records", constraints={})
    def too_large(messages):
        source_id = json.loads(messages[-1]["content"])["tool_results"][0]["tool_call_id"]
        return {"action": "call_tool", "tool_id": "demo.query", "arguments": {}, "evidence_notes": {"upsert": [
            {"id": "large", "source_call_id": source_id, "quote": "x" * 2200}], "delete": []}}
    result = run(parts, task, [{"action": "call_tool", "tool_id": "demo.query", "arguments": {}}, too_large,
                               {"action": "ask_user", "question": "Need a shorter excerpt"}])
    assert result["run"]["state"] == "waiting_user" and len(queries) == 1
    assert result["working_notes"] == []
    assert any(item["payload"].get("code") == "evidence_notes_rejected" for item in result["observations"])


def test_namespace_inventory_allows_discovery_when_goal_language_does_not_match(parts):
    parts.catalog.register(parts.Tool("atlasdata", "lookup", "Retrieve public datasets", {"type": "object"}),
                           lambda _: {"record": "public-data"})
    task = create_task(parts.store, goal="查询资料后回答", constraints={})
    def discover_namespace(messages):
        data = json.loads(messages[-1]["content"])
        assert data["tools"] == []
        assert {"namespace": "atlasdata", "tool_count": 1} in data["available_namespaces"]["items"]
        return {"action": "discover_tools", "query": "atlasdata"}
    result = run(parts, task, [discover_namespace,
        {"action": "call_tool", "tool_id": "atlasdata.lookup", "arguments": {}},
        {"action": "ask_user", "question": "已找到资料，请明确输出范围"}])
    assert result["run"]["state"] == "waiting_user"
    assert result["tool_calls"][0]["tool_id"] == "atlasdata.lookup"


def test_discovery_candidate_cache_retains_prior_namespace_and_recent_used_tool(parts):
    for namespace in ("alpha", "beta", "gamma"):
        for index in range(4):
            parts.catalog.register(parts.Tool(namespace, f"query{index}", f"{namespace} data", {"type": "object"}), lambda _: {})
    worker = parts.Runtime(parts.store, parts.catalog, provider(parts, []), memory_reader=lambda _: [])
    task = {"goal": "collect data", "observations": [
        {"kind": "tools_discovered", "created_at": 1, "payload": {"query": "alpha", "tools": parts.catalog.discover("alpha")}},
        {"kind": "tools_discovered", "created_at": 2, "payload": {"query": "beta.query2", "tools": parts.catalog.discover("beta.query2")}}],
        "tool_calls": [{"tool_id": "alpha.query1", "state": "succeeded"}, {"tool_id": "agent.read_result", "state": "succeeded"}]}
    descriptors, active = worker._context_tools(task)
    assert active == ["beta.query2", "alpha.query1"]
    assert len(descriptors) <= 6
    assert {item["tool_id"].split(".")[0] for item in descriptors} >= {"alpha", "beta"}


def test_derived_summary_is_persisted_unverified_and_keeps_original_source(parts):
    from backend.agent.store import AgentStore
    parts.catalog.register(parts.Tool("demo", "query", "Query requirements", {"type": "object"}),
                           lambda _: {"requirements": ["身份核验", "提前登记"]})
    task = create_task(parts.store, goal="Query requirements", constraints={})
    def summarize(messages):
        source = json.loads(messages[-1]["content"])["tool_results"][0]
        return {"action": "ask_user", "question": "需要继续核对哪条要求？", "evidence_notes": {"upsert": [
            {"id": "requirements", "source_call_id": source["tool_call_id"],
             "summary": "来源列出核验身份和预先登记两个要求；具体适用范围待核实。",
             "observed_at": "2099-01-01T00:00:00Z", "revision": 99, "source_revision": 99,
             "verification": "verified", "model_derived": False, "source_path": "/forged"}], "delete": []}}
    result = run(parts, task, [{"action": "call_tool", "tool_id": "demo.query", "arguments": {}}, summarize])
    assert result["run"]["state"] == "waiting_user"
    notes = AgentStore(parts.store.path).get(task["agent_task_id"])["working_notes"]
    assert len(notes) == 1 and notes[0]["verification"] == "unverified" and notes[0]["model_derived"] is True
    assert notes[0]["source_call_id"] == result["tool_calls"][0]["tool_call_id"]
    assert "quote" not in notes[0] and "source_path" not in notes[0]
    assert notes[0]["observed_at"] != "2099-01-01T00:00:00Z"
    assert notes[0]["revision"] == notes[0]["source_revision"] == 1
    assert len(result["tool_calls"]) == 1  # A summary cannot authorize any new effect.


@pytest.mark.parametrize("rediscovered", [False, True])
def test_explicit_reader_schema_request_survives_discovery_until_a_call_progresses(rediscovered):
    from types import SimpleNamespace
    from backend.agent.runtime import AgentRuntime
    descriptors = {identity: {"tool_id": identity} for identity in ("agent.read_result", "demo.query")}
    worker = AgentRuntime.__new__(AgentRuntime)
    worker.catalog = SimpleNamespace(describe=lambda identity: descriptors[identity])
    discovery = {"kind": "tools_discovered", "created_at": 1,
                 "payload": {"tools": [descriptors["demo.query"], descriptors["agent.read_result"]]}}
    task = {"goal": "read the missing source", "tool_calls": [], "observations": [discovery,
            {"kind": "tool_error", "created_at": 2,
             "payload": {"code": "discover_tool_schema_first", "tool_id": "agent.read_result"}}]}
    if rediscovered:
        task["observations"].append({**discovery, "created_at": 3})
    _, active = worker._context_tools(task)
    assert active[0] == "agent.read_result"
    task["tool_calls"].append({"tool_id": "agent.read_result", "state": "succeeded", "created_at": 4})
    _, active = worker._context_tools(task)
    assert active[0] == "demo.query"


def test_explicit_artifact_update_keeps_identity_and_old_version_readable(parts):
    task = create_task(parts.store, goal="Create and revise one document", constraints={})
    identity = {}
    def revise(messages):
        current = json.loads(messages[-1]["content"])["artifacts"][0]
        identity.update(artifact_id=current["artifact_id"], version=current["version"])
        return {"action": "publish_artifact", "kind": "text", "title": "Updated", "content": {"fact": "v2"},
                "evidence_call_ids": [], "artifact_id": current["artifact_id"], "expected_version": 1}
    result = run(parts, task, [
        {"action": "publish_artifact", "kind": "text", "title": "First", "content": {"fact": "v1"}, "evidence_call_ids": []},
        revise, {"action": "finish", "summary": "Updated the saved document"}])
    assert result["run"]["state"] == "completed"
    assert len(result["artifacts"]) == 1
    assert result["artifacts"][0]["artifact_id"] == identity["artifact_id"]
    assert result["artifacts"][0]["version"] == 2
    assert parts.store.get_artifact(identity["artifact_id"], version=1)["content"]["content"] == {"fact": "v1"}


@pytest.mark.parametrize("bad_identity", [False, True])
def test_artifact_update_conflict_is_a_correctable_decision(parts, bad_identity):
    task = create_task(parts.store, goal="Correct a rejected artifact update", constraints={})
    def rejected(messages):
        current = json.loads(messages[-1]["content"])["artifacts"][0]
        return {"action": "publish_artifact", "kind": "text", "title": "Rejected", "content": "must not save",
                "evidence_call_ids": [], "artifact_id": "unknown-id" if bad_identity else current["artifact_id"],
                "expected_version": 1 if bad_identity else 99}
    def corrected(messages):
        data = json.loads(messages[-1]["content"])
        assert any(item["kind"] == "decision_error" and item["payload"]["code"].startswith("artifact_")
                   for item in data["observations"])
        current = data["artifacts"][0]
        return {"action": "publish_artifact", "kind": "text", "title": "Corrected", "content": "v2",
                "evidence_call_ids": [], "artifact_id": current["artifact_id"], "expected_version": current["version"]}
    result = run(parts, task, [
        {"action": "publish_artifact", "kind": "text", "title": "First", "content": "v1", "evidence_call_ids": []},
        rejected, corrected, {"action": "finish", "summary": "Corrected"}])
    assert result["run"]["state"] == "completed" and len(result["artifacts"]) == 1
    assert result["artifacts"][0]["version"] == 2
    assert result["artifacts"][0]["content"]["content"] == "v2"


def test_broad_discovery_does_not_promote_the_first_match_to_explicit_selection():
    from backend.agent.runtime import AgentRuntime
    descriptors = {f"records.action{index}": {"tool_id": f"records.action{index}"} for index in range(3)}
    worker = AgentRuntime.__new__(AgentRuntime)
    worker.catalog = SimpleNamespace(describe=lambda identity: descriptors[identity])
    task = {"goal": "browse records", "tool_calls": [], "observations": [{"kind": "tools_discovered", "created_at": 1,
             "payload": {"query": "records", "namespace": "records", "offset": 0, "next_offset": None,
                         "total": 3, "directory_version": "v1", "tools": list(descriptors.values())}}]}
    _, active = worker._context_tools(task)
    assert active == []
    task["observations"][-1]["payload"]["query"] = "records.action2"
    _, active = worker._context_tools(task)
    assert active[0] == "records.action2"


def test_fresh_directory_does_not_treat_previously_used_tool_as_current_selection():
    from backend.agent.runtime import AgentRuntime
    descriptors = {identity: {"tool_id": identity} for identity in ("search.query", "records.lookup")}
    worker = AgentRuntime.__new__(AgentRuntime)
    worker.catalog = SimpleNamespace(describe=lambda identity: descriptors[identity])
    task = {"goal": "collect records", "tool_calls": [
        {"tool_id": "search.query", "state": "succeeded", "created_at": 1, "updated_at": 2}],
        "observations": [{"kind": "tools_discovered", "created_at": 3, "payload": {
            "query": "look up records", "namespace": "records", "directory_version": "v1",
            "tools": [descriptors["records.lookup"]]}}]}
    candidates, active = worker._context_tools(task)
    assert active == []
    assert {item["tool_id"] for item in candidates} == set(descriptors)
    task["tool_calls"].append({"tool_id": "records.lookup", "state": "succeeded",
                               "created_at": 4, "updated_at": 5})
    _, active = worker._context_tools(task)
    assert active == ["records.lookup"]


def test_namespace_discovery_exposes_later_tools_and_exact_selection_loads_schema(parts):
    for index in range(8):
        parts.catalog.register(parts.Tool("records", f"action{index}", f"Record operation {index}", {"type": "object"}),
                               lambda _: {"ok": True})
    task = create_task(parts.store, goal="Inspect later record capabilities", constraints={})
    def page_two(messages):
        data = json.loads(messages[-1]["content"])
        assert len(data["discovered_tools"]) == 5
        page = data["discovery_page"]
        assert page["next_offset"] == 5 and page["total"] == 8
        return {"action": "discover_tools", "query": "", "namespace": "records", "offset": page["next_offset"],
                "directory_version": page["directory_version"]}
    def select(messages):
        data = json.loads(messages[-1]["content"])
        assert [item["tool_id"] for item in data["discovered_tools"]] == [f"records.action{i}" for i in range(5, 8)]
        assert data["discovery_page"]["next_offset"] is None
        return {"action": "discover_tools", "query": "records.action7"}
    def call(messages):
        data = json.loads(messages[-1]["content"])
        assert next(item for item in data["tools"] if item["tool_id"] == "records.action7")["input_schema"] == {"type": "object"}
        return {"action": "call_tool", "tool_id": "records.action7", "arguments": {}}
    result = run(parts, task, [{"action": "discover_tools", "query": "", "namespace": "records"},
                               page_two, select, call, {"action": "ask_user", "question": "Which record next?"}])
    assert result["run"]["state"] == "waiting_user"
    assert len(result["tool_calls"]) == 1 and result["tool_calls"][0]["tool_id"] == "records.action7"


def test_directory_change_returns_correctable_error_before_restarting_browse(parts):
    for index in range(6):
        parts.catalog.register(parts.Tool("records", f"action{index}", f"Record operation {index}", {"type": "object"}),
                               lambda _: {"ok": True})
    task = create_task(parts.store, goal="Browse changing capabilities", constraints={})
    def stale_page(messages):
        data = json.loads(messages[-1]["content"])
        page = data["discovery_page"]
        parts.catalog.revoke("records.action5")
        return {"action": "discover_tools", "query": "", "namespace": "records", "offset": 5,
                "directory_version": page["directory_version"]}
    def restart(messages):
        data = json.loads(messages[-1]["content"])
        assert any(item["kind"] == "tool_error" and item["payload"]["code"] == "directory_changed"
                   for item in data["observations"])
        return {"action": "discover_tools", "query": "", "namespace": "records"}
    def done(messages):
        page = json.loads(messages[-1]["content"])["discovery_page"]
        assert page["offset"] == 0 and page["total"] == 5 and page["next_offset"] is None
        return {"action": "ask_user", "question": "Which available operation?"}
    result = run(parts, task, [{"action": "discover_tools", "query": "", "namespace": "records"},
                               stale_page, restart, done])
    assert result["run"]["state"] == "waiting_user" and result["tool_calls"] == []
