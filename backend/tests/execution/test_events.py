import sqlite3
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from backend.main import app


TOKEN = "a" * 32
HEADERS = {"X-Backup-Token": TOKEN}


@pytest.fixture
def client(tmp_path, monkeypatch):
    database = tmp_path / "events.sqlite3"
    monkeypatch.setenv("ONMYWAY_EVENT_DB", str(database))
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", TOKEN)
    return TestClient(app), database


def event(**overrides):
    value = {
        "event_id": str(uuid4()),
        "correlation_id": str(uuid4()),
        "source": "device",
        "kind": "NAVIGATION_STARTED",
        "occurred_at": "2026-10-19T10:00:00+08:00",
        "evidence": [{"kind": "local_receipt", "ref": "route-history-1"}],
        "payload": {"route_session_id": "trip-1"},
    }
    value.update(overrides)
    return value


def test_one_correlation_traces_task_route_and_device_execution(client):
    http, database = client
    correlation = str(uuid4())
    kinds = ["TASK_CONFIRMED", "ROUTE_CONFIRMED", "NAVIGATION_STARTED"]
    ids = [str(uuid4()) for _ in kinds]
    for event_id, kind, hour in zip(ids, kinds, (10, 11, 12)):
        response = http.post("/events", headers=HEADERS, json=event(
            event_id=event_id, correlation_id=correlation, kind=kind,
            occurred_at=f"2026-10-19T{hour}:00:00+08:00"))
        assert response.status_code == 200
        assert response.json() == {
            "event_id": event_id, "correlation_id": correlation,
            "status": "accepted", "recorded": True}
        assert "executed" not in response.json()

    assert http.get(f"/events/{ids[1]}", headers=HEADERS).json()["kind"] == "ROUTE_CONFIRMED"
    result = http.get("/events", headers=HEADERS, params={"correlation_id": correlation}).json()
    assert result["count"] == 3
    assert [row["kind"] for row in result["events"]] == kinds
    assert [row["event_id"] for row in result["events"]] == ids
    with sqlite3.connect(database) as db:
        assert db.execute("SELECT COUNT(*) FROM business_events").fetchone()[0] == 3


def test_duplicate_event_is_stored_once_and_accepts_equivalent_time_forms(client):
    http, database = client
    body = event(occurred_at="2026-10-19T02:00:00Z")
    assert http.post("/events", headers=HEADERS, json=body).json()["status"] == "accepted"
    duplicate = event(**{**body, "occurred_at": "2026-10-19T02:00:00+00:00"})
    response = http.post("/events", headers=HEADERS, json=duplicate)
    assert response.status_code == 200
    assert response.json()["status"] == "duplicate"
    with sqlite3.connect(database) as db:
        digest, payload = db.execute("SELECT digest, payload FROM business_events").fetchone()
    assert payload == '{"route_session_id":"trip-1"}'
    assert len(digest) == 64


def test_same_event_id_with_different_content_is_rejected_without_overwrite(client):
    http, _ = client
    body = event()
    assert http.post("/events", headers=HEADERS, json=body).json()["status"] == "accepted"
    changed = event(**body)
    changed["payload"] = {"route_session_id": "different"}
    response = http.post("/events", headers=HEADERS, json=changed)
    assert response.status_code == 409
    stored = http.get(f"/events/{body['event_id']}", headers=HEADERS).json()
    assert stored["payload"] == {"route_session_id": "trip-1"}


def test_events_are_ordered_by_actual_time_not_offset_text(client):
    http, _ = client
    correlation = str(uuid4())
    early = event(correlation_id=correlation, occurred_at="2026-10-19T10:00:00+08:00")
    late = event(correlation_id=correlation, occurred_at="2026-10-19T03:00:00+00:00")
    for body in (late, early):
        assert http.post("/events", headers=HEADERS, json=body).status_code == 200
    result = http.get("/events", headers=HEADERS, params={"correlation_id": correlation}).json()
    assert [row["event_id"] for row in result["events"]] == [early["event_id"], late["event_id"]]


def test_correlations_do_not_leak_into_each_other(client):
    http, _ = client
    first, second = str(uuid4()), str(uuid4())
    for correlation, kind in ((first, "TASK_CONFIRMED"), (second, "NAVIGATION_FAILED")):
        assert http.post("/events", headers=HEADERS, json=event(
            correlation_id=correlation, kind=kind)).status_code == 200
    result = http.get("/events", headers=HEADERS, params={"correlation_id": first}).json()
    assert [row["kind"] for row in result["events"]] == ["TASK_CONFIRMED"]


@pytest.mark.parametrize("change", [
    {"occurred_at": "2026-10-19T10:00:00"},
    {"valid_until": "2026-10-19T09:59:59+08:00"},
    {"valid_until": "2026-10-20T10:00:00"},
    {"kind": "UNKNOWN_KIND"},
    {"source": "unknown"},
    {"schema_version": 2},
    {"extra": "not allowed"},
    {"event_id": "not-a-uuid"},
    {"correlation_id": "not-a-uuid"},
])
def test_invalid_envelopes_are_rejected(client, change):
    http, _ = client
    response = http.post("/events", headers=HEADERS, json=event(**change))
    assert response.status_code == 422


def test_payload_is_bounded(client):
    http, _ = client
    response = http.post("/events", headers=HEADERS, json=event(payload={"blob": "x" * 70_000}))
    assert response.status_code == 422


@pytest.mark.parametrize("headers", [{}, {"X-Backup-Token": "b" * 32}])
def test_event_endpoints_require_the_backup_token(client, headers):
    http, _ = client
    body = event()
    assert http.post("/events", headers=headers, json=body).status_code == 401
    assert http.get("/events", headers=headers, params={"correlation_id": body["correlation_id"]}).status_code == 401
    assert http.get(f"/events/{body['event_id']}", headers=headers).status_code == 401


def test_missing_event_returns_not_found(client):
    http, _ = client
    response = http.get(f"/events/{uuid4()}", headers=HEADERS)
    assert response.status_code == 404


def test_event_database_rejects_newer_protocol_without_deleting_records(client):
    http, database = client
    assert http.post("/events", headers=HEADERS, json=event()).status_code == 200
    with sqlite3.connect(database) as db:
        db.execute("UPDATE event_schema SET protocol=2")
        db.commit()
    response = http.get(f"/events/{uuid4()}", headers=HEADERS)
    assert response.status_code == 409
    with sqlite3.connect(database) as db:
        assert db.execute("SELECT COUNT(*) FROM business_events").fetchone()[0] == 1
