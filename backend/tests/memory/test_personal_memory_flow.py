"""HTTP acceptance for personal memory; model responses are deterministic fixtures.

These tests exercise production validation/storage/routes in temporary databases.
They do not establish real-model understanding, Android behavior or route quality.
"""
import json
from datetime import datetime, timedelta, timezone
from uuid import uuid4

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from backend.assistant import api as assistant
from backend.memory import personal as personal_memory
from backend.main import app


HEADERS = {"X-Backup-Token": "personal-memory-flow-fixture-" + "x" * 32}
NOW = datetime(2026, 11, 7, 9, 0, tzinfo=timezone(timedelta(hours=8)))


@pytest.fixture
def client(tmp_path, monkeypatch):
    for name in ("MEMORY", "HARNESS", "EVENT", "BACKUP"):
        monkeypatch.setenv(f"ONMYWAY_{name}_DB", str(tmp_path / f"{name.lower()}.sqlite3"))
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", HEADERS["X-Backup-Token"])
    monkeypatch.setattr(assistant, "completion", lambda *a, **k: pytest.fail("Unconfigured model call"))
    with TestClient(app) as http:
        yield http


def snapshot(client):
    response = client.get("/memory", headers=HEADERS)
    assert response.status_code == 200, response.text
    return response.json()


def command(client, operation, *, head=None, **fields):
    head = head or snapshot(client)
    body = dict(operation_id=str(uuid4()), store_id=head["store_id"],
                expected_revision=head["revision"], operation=operation, **fields)
    response = client.post("/memory/commands", headers=HEADERS, json=body)
    assert response.status_code == 200, response.text
    return response.json()


def remember(client, text, *, identity="", **fields):
    head = snapshot(client)
    item = dict(id=identity, text=text, topic="travel", **fields)
    if identity:
        item["revision"] = next(row["revision"] for row in head["items"] if row["id"] == identity)
    result = command(client, "upsert", head=head, item=item)
    return next(item for item in result["snapshot"]["items"] if item["text"] == text and item["status"] == "active")


def chat_body(client, text, *, now=NOW, session="conversation-2", learn=False, **fields):
    head = snapshot(client)
    return dict(request_id=str(uuid4()), messages=[dict(role="user", content=text)], now=now.isoformat(),
                memory_contract=2, memory_enabled=True, memory_auto_learn=learn,
                memory_store_id=head["store_id"], memory_snapshot_revision=head["revision"],
                memory_session_id=session, memory_trip_id="", **fields)


def chat(client, body):
    response = client.post("/assistant/chat", headers=HEADERS, json=body)
    assert response.status_code == 200, response.text
    return response.json()


def model(monkeypatch, response):
    calls = []

    def complete(messages, *args):
        calls.append(messages)
        return json.dumps(response, ensure_ascii=False)

    monkeypatch.setattr(assistant, "completion", complete)
    return calls


def record(record_property, scenario, inputs, actual):
    record_property("scenario", scenario)
    record_property("input", json.dumps(inputs, ensure_ascii=False))
    record_property("actual", json.dumps(actual, ensure_ascii=False))
    record_property("evidence_kind", "production_http_with_fixture_model")


def test_cross_session_preference_changes_plan_mode(client, monkeypatch, record_property):
    preference = remember(client, "以后默认步行出门")
    calls = model(monkeypatch, dict(action="plan_route", reply="请确认路线。", destination="宿舍", mode=""))
    body = chat_body(client, "取快递、打印论文后回宿舍", session="new-conversation")
    without_memory = chat(client, dict(body, request_id=str(uuid4()), memory_enabled=False))
    assert without_memory["mode"] == ""
    result = chat(client, body)
    assert result["mode"] == "walking"
    assert preference["id"] in result["memory_context"]["evidence_ids"]
    assert "以后默认步行出门" not in calls[0][0]["content"]
    assert "以后默认步行出门" in calls[1][0]["content"]
    assert result["action"] == "plan_route" and result["destination"] == "宿舍"
    record(record_property, "跨会话长期偏好进入规划提案", body["messages"], dict(without_memory=without_memory, with_memory=result))


def test_today_cycling_does_not_replace_long_term_walking(client, monkeypatch, record_property):
    preference = remember(client, "以后默认步行出门")
    command(client, "settings", learning_enabled=True)
    text = "今天骑车回宿舍"
    model(monkeypatch, dict(action="plan_route", reply="请确认路线。", destination="宿舍", mode="cycling",
        memory_actions=[dict(op="add", text=text, quote=text, kind="context", topic="travel")]))
    first = chat(client, chat_body(client, text, session="today", learn=True))
    state = snapshot(client)
    assert first["mode"] == "cycling"
    assert any(item["id"] == preference["id"] and item["text"] == "以后默认步行出门" and item["status"] == "active" for item in state["items"])
    temporary = [item for item in state["items"] if item["kind"] == "context" and item["status"] == "active"]
    assert len(temporary) == 1 and temporary[0]["valid_until"]
    calls = model(monkeypatch, dict(action="plan_route", reply="请确认路线。", destination="宿舍", mode=""))
    other_session = chat(client, chat_body(client, "回宿舍", session="another-session-today"))
    assert other_session["mode"] == "walking"
    assert temporary[0]["id"] not in other_session["memory_context"]["evidence_ids"]
    second = chat(client, chat_body(client, "回宿舍", now=NOW + timedelta(days=1), session="tomorrow"))
    assert second["mode"] == "walking"
    assert temporary[0]["id"] not in second["memory_context"]["evidence_ids"]
    assert text not in calls[0][0]["content"]
    record(record_property, "临时骑行限于本会话，过期后长期步行保留", [text, "另一会话回宿舍", "次日回宿舍"],
           dict(first=first, other_session=other_session, second=second))


def test_cancel_printing_keeps_parcel_and_dorm_without_memory_mutation(client, monkeypatch, record_property):
    preference = remember(client, "以后打印论文优先去图书馆")
    before = snapshot(client)
    model(monkeypatch, dict(action="adjust", reply="已取消打印", destination="宿舍", keep_task_ids=["parcel"]))
    body = dict(request_id=str(uuid4()), text="这次取消打印，取完快递回宿舍", destination="宿舍",
                tasks=[dict(id="parcel", title="取快递", place_type="PARCEL"),
                       dict(id="print", title="打印论文", place_type="PRINT_SHOP")])
    response = client.post("/assistant/navigation", json=body)
    assert response.status_code == 200, response.text
    result = response.json()
    assert result["keep_task_ids"] == ["parcel"] and result["destination"] == "宿舍"
    assert "请确认" in result["reply"] and "已取消" not in result["reply"]
    assert snapshot(client) == before
    assert preference["status"] == "active"
    record(record_property, "取消打印保留快递与宿舍且不污染长期偏好", body, result)


def test_correction_then_forgetting_removes_old_and_new_recall(client, monkeypatch, record_property):
    item = remember(client, "以后默认步行出门")
    corrected = remember(client, "以后默认骑车出门", identity=item["id"])
    assert corrected["revision"] > item["revision"]
    calls = model(monkeypatch, dict(action="plan_route", reply="请确认路线。", destination="宿舍", mode=""))
    after_correction = chat(client, chat_body(client, "回宿舍"))
    assert after_correction["mode"] == "cycling"
    assert "以后默认步行出门" not in calls[0][0]["content"]
    command(client, "forget", id=item["id"])
    after_forget = chat(client, chat_body(client, "回宿舍", session="after-forget"))
    assert after_forget["mode"] == ""
    assert item["id"] not in after_forget["memory_context"]["evidence_ids"]
    assert "以后默认骑车出门" not in calls[-1][0]["content"]
    tombstone = next(row for row in snapshot(client)["items"] if row["id"] == item["id"])
    assert tombstone["status"] == "deleted" and not tombstone["text"] and not tombstone["quote"]
    record(record_property, "纠正后只用新版，遗忘后不召回", dict(id=item["id"]), dict(corrected=after_correction, forgotten=after_forget))


def test_event_experience_summary_is_invalidated_when_source_is_forgotten(client, record_property):
    command(client, "settings", learning_enabled=True)
    trip = "parcel-print-dorm-trip"
    event = dict(event_id=str(uuid4()), correlation_id=str(uuid4()), source="device", kind="NAVIGATION_CANCELLED",
                 occurred_at=NOW.isoformat(), payload=dict(route_session_id=trip), evidence=[])
    response = client.post("/events", headers=HEADERS, json=event)
    assert response.status_code == 200, response.text
    state = snapshot(client)
    episode = next(row for row in state["items"] if row["kind"] == "episode" and row["source_id"] == event["event_id"])
    assert episode["scope_id"] == trip and episode["source_kind"] == "event"
    assert "取消" in episode["text"] and "成功到达" not in episode["text"]
    reflected = command(client, "reflect")["snapshot"]
    insights = [row for row in reflected["items"] if row["kind"] == "insight" and row["status"] == "active"]
    assert insights and episode["id"] in insights[0]["derived_from"]
    forgotten = command(client, "forget", id=episode["id"])["snapshot"]
    invalidated = next(row for row in forgotten["items"] if row["id"] == insights[0]["id"])
    assert invalidated["status"] != "active" and not invalidated["text"]
    assert client.post("/events", headers=HEADERS, json=event).status_code == 200
    assert not any(row["id"] == episode["id"] and row["status"] == "active" for row in snapshot(client)["items"])
    record(record_property, "真实事件语义及派生摘要遗忘传播", event, dict(episode=episode, invalidated=invalidated))


def test_replayed_chat_does_not_learn_twice_or_call_model_again(client, monkeypatch, record_property):
    command(client, "settings", learning_enabled=True)
    text = "以后默认步行出门"
    calls = model(monkeypatch, dict(action="reply", reply="收到偏好。", memory_actions=[
        dict(op="add", kind="preference", topic="travel", text=text, quote=text)]))
    body = chat_body(client, text, learn=True)
    first = chat(client, body)
    after_first = snapshot(client)
    assert len([row for row in after_first["items"] if row["status"] == "active"]) == 1
    replay = chat(client, body)
    recovered = client.post("/assistant/chat/status", headers=HEADERS, json=body)
    assert recovered.status_code == 200 and recovered.json()["state"] == "completed"
    assert len(calls) == 1 and snapshot(client) == after_first
    record(record_property, "请求重放与状态恢复不重复学习", dict(request_id=body["request_id"]), dict(first=first, replay=replay, recovered=recovered.json()))


def test_enabled_memory_requires_authorization_and_disabled_chat_still_works(client, monkeypatch, record_property):
    secret = remember(client, "以后默认步行出门")
    body = chat_body(client, "回宿舍")
    calls = model(monkeypatch, dict(action="reply", reply="可以继续对话。"))
    denied = client.post("/assistant/chat", json=body)
    assert denied.status_code in (401, 403)
    assert client.get("/memory").status_code in (401, 403)
    assert not calls
    body.update(request_id=str(uuid4()), memory_enabled=False, memory_auto_learn=False)
    allowed = client.post("/assistant/chat", json=body)
    assert allowed.status_code == 200, allowed.text
    assert secret["text"] not in calls[0][0]["content"]
    record(record_property, "鉴权阻止个人记忆访问，禁用记忆可聊天", dict(enabled=True), dict(denied_http=denied.status_code, disabled=allowed.json()))


def test_late_model_memory_write_cannot_undo_user_forgetting(client, monkeypatch, record_property):
    item = remember(client, "以后默认步行出门")
    command(client, "settings", learning_enabled=True)
    text = "以后默认骑车出门"
    body = chat_body(client, text, learn=True)

    def complete(messages, *args):
        command(client, "forget", id=item["id"])
        return json.dumps(dict(action="reply", reply="建议已生成。", memory_actions=[
            dict(op="add", kind="preference", topic="travel", text=text, quote=text)]), ensure_ascii=False)

    monkeypatch.setattr(assistant, "completion", complete)
    result = chat(client, body)
    state = snapshot(client)
    assert result["action"] == "reply" and result["reply"]
    assert not any(row["status"] == "active" and row["text"] == text for row in state["items"])
    assert next(row for row in state["items"] if row["id"] == item["id"])["status"] == "deleted"
    assert result["memory_context"]["write_status"] in ("stale", "conflict")
    record(record_property, "模型返回前遗忘使旧批次失效", body["messages"], result)


def test_provider_error_remains_failure_and_does_not_learn(client, monkeypatch, record_property):
    command(client, "settings", learning_enabled=True)
    body = chat_body(client, "以后默认步行出门", learn=True)
    before = snapshot(client)

    def unavailable(*args, **kwargs):
        raise HTTPException(502, "模拟上游服务错误")

    monkeypatch.setattr(assistant, "completion", unavailable)
    response = client.post("/assistant/chat", headers=HEADERS, json=body)
    assert response.status_code == 502
    recovered = client.post("/assistant/chat/status", headers=HEADERS, json=body)
    assert recovered.status_code == 200 and recovered.json()["state"] == "failed"
    assert recovered.json()["error_status"] == 502
    assert snapshot(client) == before
    record(record_property, "上游错误不能计为成功或记忆写入", body["messages"], dict(stage="provider_error", simulated=True, http=502, recovered=recovered.json()))


def test_disabled_learning_keeps_business_events_without_creating_memories(client, record_property):
    command(client, "settings", learning_enabled=False)
    before = snapshot(client)
    event = dict(event_id=str(uuid4()), correlation_id=str(uuid4()), source="device", kind="NAVIGATION_STARTED",
                 occurred_at=NOW.isoformat(), payload=dict(route_session_id="learning-disabled-trip"), evidence=[])
    accepted = client.post("/events", headers=HEADERS, json=event)
    assert accepted.status_code == 200 and accepted.json()["recorded"] is True
    assert client.get(f"/events/{event['event_id']}", headers=HEADERS).status_code == 200
    assert snapshot(client) == before
    record(record_property, "关闭学习仍保存业务回执但不增加经历记忆", event, dict(event_recorded=True, memory_unchanged=True))


def test_legacy_import_cannot_resurrect_a_known_tombstone(client, record_property):
    legacy = dict(id="legacy-phone-preference", kind="preference", topic="printing",
                  text="以后打印论文优先去图书馆", source_kind="legacy", source_id="old-room-id")
    imported = command(client, "import", items=[legacy])["snapshot"]
    item = next(row for row in imported["items"] if row["text"] == legacy["text"])
    command(client, "forget", id=item["id"])
    repeated = command(client, "import", items=[legacy])["snapshot"]
    assert not any(row["status"] == "active" and row["text"] == legacy["text"] for row in repeated["items"])
    assert next(row for row in repeated["items"] if row["id"] == item["id"])["status"] == "deleted"
    record(record_property, "旧偏好导入不能复活已知墓碑", legacy, repeated)


def test_failed_event_projection_recovers_on_memory_read_without_replaying_business(client, monkeypatch, record_property):
    command(client, "settings", learning_enabled=True)
    original = personal_memory.record_episode
    event = dict(event_id=str(uuid4()), correlation_id=str(uuid4()), source="device", kind="NAVIGATION_ARRIVED",
                 occurred_at=NOW.isoformat(), payload=dict(route_session_id="recovered-trip"), evidence=[])

    def unavailable(value):
        raise OSError("simulated memory write failure")

    monkeypatch.setattr(personal_memory, "record_episode", unavailable)
    accepted = client.post("/events", headers=HEADERS, json=event)
    assert accepted.status_code == 200 and accepted.json()["recorded"] is True
    assert not snapshot(client)["items"]
    monkeypatch.setattr(personal_memory, "record_episode", original)
    recovered = snapshot(client)
    episodes = [row for row in recovered["items"] if row["kind"] == "episode" and row["source_id"] == event["event_id"]]
    assert len(episodes) == 1
    assert snapshot(client) == recovered
    business = client.get("/events", headers=HEADERS, params=dict(correlation_id=event["correlation_id"]))
    assert business.status_code == 200 and business.json()["count"] == 1
    record(record_property, "经历投影故障后读取恢复且不重放业务", event, dict(episode=episodes[0], business_event_count=1))
