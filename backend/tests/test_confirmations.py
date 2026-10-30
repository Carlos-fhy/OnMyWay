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


def receipt(**overrides):
    value = {
        "receipt_id": str(uuid4()),
        "subject_type": "plan",
        "subject_id": str(uuid4()),
        "subject_version": 1,
        "correlation_id": str(uuid4()),
        "confirmed_at": "2026-10-19T10:00:00+08:00",
        "snapshot": {"plan_id": "plan-1", "plan_version": 1, "route_session_id": "trip-1",
                     "total_meters": 1866, "selected_task_ids": ["task-1"]},
    }
    value.update(overrides)
    return value


def event(**overrides):
    value = {
        "event_id": str(uuid4()),
        "correlation_id": str(uuid4()),
        "source": "device",
        "kind": "NAVIGATION_STARTED",
        "occurred_at": "2026-10-19T10:00:00+08:00",
        "payload": {"plan_id": "plan-1", "plan_version": 1, "route_session_id": "trip-1"},
    }
    value.update(overrides)
    return value


def test_confirmation_is_recorded_once_and_is_not_execution(client):
    http, database = client
    body = receipt()
    response = http.post("/confirmations", headers=HEADERS, json=body)
    assert response.status_code == 200
    assert response.json() == {
        "receipt_id": body["receipt_id"],
        "correlation_id": body["correlation_id"],
        "subject_type": "plan",
        "subject_id": body["subject_id"],
        "subject_version": 1,
        "status": "accepted",
        "recorded": True,
        "recorded_is_not_executed": True,
    }
    duplicate = http.post("/confirmations", headers=HEADERS, json=body)
    assert duplicate.status_code == 200
    assert duplicate.json()["status"] == "duplicate"
    with sqlite3.connect(database) as db:
        assert db.execute("SELECT COUNT(*) FROM confirmation_receipts").fetchone()[0] == 1


def test_same_subject_version_with_new_receipt_id_is_the_same_confirmation(client):
    http, _ = client
    first = receipt()
    assert http.post("/confirmations", headers=HEADERS, json=first).status_code == 200
    second = receipt(receipt_id=str(uuid4()), subject_type=first["subject_type"],
                     subject_id=first["subject_id"], subject_version=1,
                     correlation_id=first["correlation_id"], confirmed_at=first["confirmed_at"],
                     snapshot=first["snapshot"])
    response = http.post("/confirmations", headers=HEADERS, json=second)
    assert response.status_code == 200
    assert response.json()["status"] == "duplicate"
    assert response.json()["receipt_id"] == first["receipt_id"]


def test_same_receipt_id_with_different_content_is_rejected_without_overwrite(client):
    http, database = client
    body = receipt()
    assert http.post("/confirmations", headers=HEADERS, json=body).status_code == 200
    changed = receipt(**body)
    changed["snapshot"] = {"plan_id": "plan-tampered"}
    response = http.post("/confirmations", headers=HEADERS, json=changed)
    assert response.status_code == 409
    with sqlite3.connect(database) as db:
        snapshot = db.execute("SELECT snapshot FROM confirmation_receipts").fetchone()[0]
    assert '"plan-1"' in snapshot


def test_same_subject_version_with_different_content_is_rejected(client):
    http, _ = client
    body = receipt()
    assert http.post("/confirmations", headers=HEADERS, json=body).status_code == 200
    changed = receipt(subject_type=body["subject_type"], subject_id=body["subject_id"],
                      subject_version=1, correlation_id=body["correlation_id"],
                      snapshot={"plan_id": "different"})
    response = http.post("/confirmations", headers=HEADERS, json=changed)
    assert response.status_code == 409


def test_newer_version_is_accepted_and_older_is_stale(client):
    http, _ = client
    subject, correlation = str(uuid4()), str(uuid4())
    bodies = {}
    for version in (1, 3):
        body = receipt(subject_type="plan", subject_id=subject, subject_version=version,
                       correlation_id=correlation)
        bodies[version] = body
        assert http.post("/confirmations", headers=HEADERS, json=body).status_code == 200
    # Replaying an exact recorded version stays idempotent even after a newer one exists.
    assert http.post("/confirmations", headers=HEADERS, json=bodies[1]).json()["status"] == "duplicate"
    # A skipped, never-recorded version is stale because the server already has v3.
    stale = receipt(subject_type="plan", subject_id=subject, subject_version=2,
                    correlation_id=correlation)
    response = http.post("/confirmations", headers=HEADERS, json=stale)
    assert response.status_code == 409
    assert "过期" in response.json()["detail"]


@pytest.mark.parametrize("change", [
    {"confirmed_at": "2026-10-19T10:00:00"},
    {"valid_until": "2026-10-19T09:59:59+08:00"},
    {"valid_until": "2026-10-19T11:00:00"},
    {"confirmed_at": "2099-01-01T00:00:00+00:00"},
    {"subject_type": "message"},
    {"subject_id": ""},
    {"subject_version": 0},
    {"schema_version": 2},
    {"extra": "not allowed"},
    {"receipt_id": "not-a-uuid"},
    {"correlation_id": "not-a-uuid"},
])
def test_invalid_receipts_are_rejected(client, change):
    http, _ = client
    response = http.post("/confirmations", headers=HEADERS, json=receipt(**change))
    assert response.status_code == 422


def test_snapshot_is_bounded(client):
    http, _ = client
    response = http.post("/confirmations", headers=HEADERS,
                         json=receipt(snapshot={"blob": "x" * 70_000}))
    assert response.status_code == 422


@pytest.mark.parametrize("headers", [{}, {"X-Backup-Token": "b" * 32}])
def test_confirmation_endpoints_require_the_backup_token(client, headers):
    http, _ = client
    body = receipt()
    assert http.post("/confirmations", headers=headers, json=body).status_code == 401
    assert http.get("/confirmations", headers=headers,
                    params={"correlation_id": body["correlation_id"]}).status_code == 401
    assert http.get("/state", headers=headers,
                    params={"correlation_id": body["correlation_id"]}).status_code == 401


def test_confirmations_are_listed_by_confirmation_time_per_correlation(client):
    http, _ = client
    correlation, other = str(uuid4()), str(uuid4())
    late = receipt(correlation_id=correlation, confirmed_at="2026-10-19T03:00:00+00:00")
    early = receipt(correlation_id=correlation, confirmed_at="2026-10-19T10:00:00+08:00")
    unrelated = receipt(correlation_id=other)
    for body in (late, early, unrelated):
        assert http.post("/confirmations", headers=HEADERS, json=body).status_code == 200
    result = http.get("/confirmations", headers=HEADERS,
                      params={"correlation_id": correlation}).json()
    assert result["count"] == 2
    assert [row["receipt_id"] for row in result["confirmations"]] == [
        early["receipt_id"], late["receipt_id"]]


def test_state_keeps_recorded_and_executed_separate(client):
    http, _ = client
    correlation = str(uuid4())
    plan = receipt(correlation_id=correlation, subject_type="plan", subject_id="plan-1",
                   subject_version=1)
    proposal = receipt(correlation_id=correlation, subject_type="proposal",
                       subject_id=str(uuid4()), snapshot={"task_id": "task-1"})
    for body in (proposal, plan):
        assert http.post("/confirmations", headers=HEADERS, json=body).status_code == 200
    for kind, hour in (("ROUTE_CONFIRMED", 10), ("NAVIGATION_STARTED", 11),
                       ("NAVIGATION_ARRIVED", 12)):
        response = http.post("/events", headers=HEADERS, json=event(
            correlation_id=correlation, kind=kind,
            occurred_at=f"2026-10-19T{hour}:00:00+08:00"))
        assert response.status_code == 200

    state = http.get("/state", headers=HEADERS, params={"correlation_id": correlation}).json()
    assert state["recorded"]["count"] == 2
    assert state["recorded"]["latest_plan_version"] == 1
    assert state["execution"]["state"] == "arrived"
    assert state["execution"]["plan_id"] == plan["snapshot"]["plan_id"]
    assert [row["kind"] for row in state["execution"]["evidence"]] == [
        "NAVIGATION_STARTED", "NAVIGATION_ARRIVED"]
    assert state["execution_matches_recorded_plan"] is True
    assert state["observations"]["kinds"] == [
        "ROUTE_CONFIRMED", "NAVIGATION_STARTED", "NAVIGATION_ARRIVED"]
    # Recorded confirmations never become execution evidence on their own.
    assert state["recorded_is_not_executed"] is True


def test_state_without_execution_reports_not_started(client):
    http, _ = client
    correlation = str(uuid4())
    body = receipt(correlation_id=correlation)
    assert http.post("/confirmations", headers=HEADERS, json=body).status_code == 200
    state = http.get("/state", headers=HEADERS, params={"correlation_id": correlation}).json()
    assert state["execution"] == {
        "state": "not_started", "plan_id": None, "plan_version": None, "route_session_id": None,
        "observed_at": None, "evidence": []}
    assert state["execution_matches_recorded_plan"] is None
    assert state["recorded"]["count"] == 1


def test_state_reports_cancelled_execution(client):
    http, _ = client
    correlation = str(uuid4())
    for kind in ("NAVIGATION_STARTED", "NAVIGATION_CANCELLED"):
        assert http.post("/events", headers=HEADERS, json=event(
            correlation_id=correlation, kind=kind,
            occurred_at=f"2026-10-19T{10 if kind.endswith('STARTED') else 11}:00:00+08:00",
        )).status_code == 200
    state = http.get("/state", headers=HEADERS, params={"correlation_id": correlation}).json()
    assert state["execution"]["state"] == "cancelled"
    assert state["recorded"]["count"] == 0
    assert state["execution_matches_recorded_plan"] is None


def _confirm_plan(http, correlation, version=1, plan_id="plan-1", session_id="trip-1",
                  confirmed_at="2026-10-19T10:00:00+08:00"):
    body = receipt(correlation_id=correlation, subject_id=plan_id, subject_version=version,
                   confirmed_at=confirmed_at, snapshot={
                       "plan_id": plan_id, "plan_version": version,
                       "route_session_id": session_id})
    response = http.post("/confirmations", headers=HEADERS, json=body)
    assert response.status_code == 200
    return body


def _observe(http, correlation, kind="NAVIGATION_STARTED", version=1, plan_id="plan-1",
             session_id="trip-1", occurred_at="2026-10-19T11:00:00+08:00"):
    body = event(correlation_id=correlation, kind=kind, occurred_at=occurred_at, payload={
        "plan_id": plan_id, "plan_version": version, "route_session_id": session_id})
    assert http.post("/events", headers=HEADERS, json=body).status_code == 200
    return body


def _state(http, correlation):
    response = http.get("/state", headers=HEADERS, params={"correlation_id": correlation})
    assert response.status_code == 200
    return response.json()


@pytest.mark.parametrize("change", [
    {"version": 1}, {"session_id": "other-trip"}, {"plan_id": "other-plan"},
])
def test_state_rejects_execution_for_a_different_plan_version_or_session(client, change):
    http, _ = client
    correlation = str(uuid4())
    _confirm_plan(http, correlation, version=2)
    _observe(http, correlation, **{"version": 2, **change})
    state = _state(http, correlation)
    assert state["execution_matches_recorded_plan"] is False
    assert state["current_plan_execution"]["state"] == "not_started"
    assert state["current_plan_execution"]["evidence"] == []


def test_state_matches_the_exact_current_plan_version_and_session(client):
    http, _ = client
    correlation = str(uuid4())
    plan = _confirm_plan(http, correlation, version=2)
    observed = _observe(http, correlation, version=2)
    state = _state(http, correlation)
    assert state["execution_matches_recorded_plan"] is True
    assert state["execution"]["plan_version"] == 2
    assert state["current_plan"] == {
        "plan_id": "plan-1", "plan_version": 2, "route_session_id": "trip-1",
        "receipt_id": plan["receipt_id"], "confirmed_at": "2026-10-19T02:00:00+00:00"}
    assert state["current_plan_execution"]["state"] == "started"
    assert state["current_plan_execution"]["evidence"][0]["event_id"] == observed["event_id"]


def test_current_plan_uses_latest_confirmed_subject_instead_of_global_max_version(client):
    http, _ = client
    correlation = str(uuid4())
    # The newer subject arrives first; offline delivery must not change confirmation order.
    _confirm_plan(http, correlation, version=1, plan_id="new-plan", session_id="new-trip",
                  confirmed_at="2026-10-19T10:30:00+08:00")
    old = _confirm_plan(http, correlation, version=9,
                        confirmed_at="2026-10-19T10:00:00+08:00")
    assert http.post("/confirmations", headers=HEADERS, json=old).json()["status"] == "duplicate"
    _observe(http, correlation, plan_id="new-plan", session_id="new-trip")
    state = _state(http, correlation)
    assert state["recorded"]["latest_plan_version"] == 1
    assert state["current_plan"]["plan_id"] == "new-plan"
    assert state["execution_matches_recorded_plan"] is True


def test_current_plan_uses_latest_version_within_latest_subject(client):
    http, _ = client
    correlation = str(uuid4())
    _confirm_plan(http, correlation, version=1, confirmed_at="2026-10-19T10:20:00+08:00")
    _confirm_plan(http, correlation, version=3, confirmed_at="2026-10-19T10:10:00+08:00")
    state = _state(http, correlation)
    assert state["current_plan"]["plan_version"] == 3
    assert state["current_plan_execution"]["state"] == "not_started"


@pytest.mark.parametrize("payload", [
    {"plan_id": "plan-1", "route_session_id": "trip-1"},
    {"plan_id": "plan-1", "plan_version": 1},
    {"plan_version": 1, "route_session_id": "trip-1"},
    {"plan_id": "plan-1", "plan_version": 0, "route_session_id": "trip-1"},
    {"plan_id": "plan-1", "plan_version": True, "route_session_id": "trip-1"},
    {"plan_id": "plan-1", "plan_version": "1", "route_session_id": "trip-1"},
])
def test_incomplete_legacy_execution_is_unknown_not_a_current_plan_match(client, payload):
    http, _ = client
    correlation = str(uuid4())
    _confirm_plan(http, correlation)
    assert http.post("/events", headers=HEADERS, json=event(
        correlation_id=correlation, kind="NAVIGATION_ARRIVED", payload=payload)).status_code == 200
    state = _state(http, correlation)
    assert state["execution_matches_recorded_plan"] is None
    assert state["current_plan_execution"]["state"] == "unknown"
    assert state["current_plan_execution"]["evidence"] == []


def test_known_identity_conflict_is_false_even_when_other_fields_are_missing(client):
    http, _ = client
    correlation = str(uuid4())
    _confirm_plan(http, correlation)
    assert http.post("/events", headers=HEADERS, json=event(
        correlation_id=correlation, payload={"plan_id": "different-plan"})).status_code == 200
    state = _state(http, correlation)
    assert state["execution_matches_recorded_plan"] is False
    assert state["current_plan_execution"]["state"] == "not_started"


def test_legacy_confirmation_without_session_does_not_infer_execution_identity(client):
    http, _ = client
    correlation = str(uuid4())
    body = receipt(correlation_id=correlation, subject_id="plan-1", snapshot={"plan_id": "plan-1"})
    assert http.post("/confirmations", headers=HEADERS, json=body).status_code == 200
    _observe(http, correlation)
    state = _state(http, correlation)
    assert state["execution_matches_recorded_plan"] is None
    assert state["current_plan"]["route_session_id"] is None
    assert state["current_plan_execution"]["state"] == "unknown"


@pytest.mark.parametrize("terminal", ["NAVIGATION_ARRIVED", "NAVIGATION_CANCELLED"])
@pytest.mark.parametrize("late_kind", ["NAVIGATION_STARTED", "NAVIGATION_FAILED"])
def test_current_plan_terminal_survives_late_attempt_event_and_duplicate_delivery(client, terminal, late_kind):
    http, _ = client
    correlation = str(uuid4())
    _confirm_plan(http, correlation)
    end = _observe(http, correlation, kind=terminal, occurred_at="2026-10-19T12:00:00+08:00")
    _observe(http, correlation, occurred_at="2026-10-19T11:00:00+08:00")
    _observe(http, correlation, kind=late_kind, occurred_at="2026-10-19T13:00:00+08:00")
    assert http.post("/events", headers=HEADERS, json=end).json()["status"] == "duplicate"
    state = _state(http, correlation)
    # Keep the last raw observation for diagnostics while the current lifecycle stays terminal.
    assert state["execution"]["state"] == late_kind.removeprefix("NAVIGATION_").lower()
    assert state["current_plan_execution"]["state"] == terminal.removeprefix("NAVIGATION_").lower()
    assert state["current_plan_execution"]["observed_at"] == "2026-10-19T04:00:00+00:00"
    assert len(state["current_plan_execution"]["evidence"]) == 3


@pytest.mark.parametrize("failure_delivered_last", [False, True])
def test_failed_attempt_can_retry_the_same_plan_revision(client, failure_delivered_last):
    http, _ = client
    correlation = str(uuid4())
    _confirm_plan(http, correlation)
    _observe(http, correlation, occurred_at="2026-10-19T11:00:00+08:00")
    if failure_delivered_last:
        _observe(http, correlation, occurred_at="2026-10-19T13:00:00+08:00")
        _observe(http, correlation, kind="NAVIGATION_FAILED", occurred_at="2026-10-19T12:00:00+08:00")
    else:
        _observe(http, correlation, kind="NAVIGATION_FAILED", occurred_at="2026-10-19T12:00:00+08:00")
        assert _state(http, correlation)["current_plan_execution"]["state"] == "failed"
        _observe(http, correlation, occurred_at="2026-10-19T13:00:00+08:00")

    state = _state(http, correlation)
    assert state["current_plan_execution"]["state"] == "started"
    assert state["current_plan_execution"]["observed_at"] == "2026-10-19T05:00:00+00:00"
    assert state["current_plan_execution"]["plan_version"] == 1
    assert state["execution_matches_recorded_plan"] is True
    assert len(state["current_plan_execution"]["evidence"]) == 3


def test_failed_attempt_is_not_recovered_by_an_earlier_start_delivered_late(client):
    http, _ = client
    correlation = str(uuid4())
    _confirm_plan(http, correlation)
    _observe(http, correlation, kind="NAVIGATION_FAILED", occurred_at="2026-10-19T12:00:00+08:00")
    _observe(http, correlation, occurred_at="2026-10-19T11:00:00+08:00")

    state = _state(http, correlation)
    assert state["current_plan_execution"]["state"] == "failed"
    assert state["current_plan_execution"]["observed_at"] == "2026-10-19T04:00:00+00:00"


def test_later_observation_from_old_revision_does_not_replace_current_execution(client):
    http, _ = client
    correlation = str(uuid4())
    _confirm_plan(http, correlation, version=1)
    _confirm_plan(http, correlation, version=2, confirmed_at="2026-10-19T10:30:00+08:00")
    _observe(http, correlation, version=2, kind="NAVIGATION_ARRIVED")
    _observe(http, correlation, version=1, kind="NAVIGATION_CANCELLED",
             occurred_at="2026-10-19T12:00:00+08:00")
    state = _state(http, correlation)
    assert state["execution"]["state"] == "cancelled"
    assert state["execution_matches_recorded_plan"] is False
    assert state["current_plan_execution"]["state"] == "arrived"
    assert state["current_plan_execution"]["plan_version"] == 2
    assert len(state["current_plan_execution"]["evidence"]) == 1


def test_no_confirmed_plan_has_no_current_plan_execution(client):
    http, _ = client
    correlation = str(uuid4())
    _observe(http, correlation)
    state = _state(http, correlation)
    assert state["current_plan"] is None
    assert state["current_plan_execution"] is None
