import json
import sqlite3
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from backend.main import app


EMPTY = {
    "format": 1,
    "room_version": 7,
    "tables": {name: [] for name in ("tasks", "saved_trip", "route_history",
                                    "suggestion_feedback", "agent_events")},
    "preferences": {"settings": {}, "assistant_conversation": {},
                    "navigation_context": {}},
}


@pytest.mark.parametrize("version", [7, 8, 9, 10, 11, 12])
def test_backup_contract_matches_exported_android_schema(version):
    from backend.backup import _columns_for

    schema = (Path(__file__).resolve().parents[2] / "app" / "schemas"
              / "com.onmyway.app.TaskDatabase" / f"{version}.json")
    exported = json.loads(schema.read_text(encoding="utf-8"))["database"]
    expected = {
        entity["tableName"]: {
            field["columnName"]: field["affinity"] for field in entity["fields"]
        }
        for entity in exported["entities"]
    }
    assert {name: dict(columns) for name, columns in _columns_for(version).items()} == expected


def test_backup_requires_configured_token_and_preserves_snapshot(monkeypatch, tmp_path):
    monkeypatch.setenv("ONMYWAY_BACKUP_DB", str(tmp_path / "backup.sqlite3"))
    monkeypatch.delenv("ONMYWAY_BACKUP_TOKEN", raising=False)
    with TestClient(app) as client:
        assert client.get("/backup").status_code == 503
        monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", "a" * 32)
        assert client.get("/backup").status_code == 401
        headers = {"X-Backup-Token": "a" * 32}
        assert client.get("/backup", headers=headers).status_code == 404
        assert client.put("/backup", headers=headers, json=EMPTY).json() == {"status": "ok"}
        assert client.get("/backup", headers=headers).json() == EMPTY
        assert client.get("/backup", headers={"X-Backup-Token": "wrong"}).status_code == 401


def test_backup_rejects_invalid_or_large_payload(monkeypatch, tmp_path):
    monkeypatch.setenv("ONMYWAY_BACKUP_DB", str(tmp_path / "backup.sqlite3"))
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", "a" * 32)
    headers = {"X-Backup-Token": "a" * 32}
    with TestClient(app) as client:
        assert client.put("/backup", headers=headers, json={"format": 1}).status_code == 422
        assert client.put("/backup", headers=headers, content=b"x" * (8 * 1024 * 1024 + 1)).status_code == 413
        assert client.put("/backup", headers=headers, json=EMPTY).status_code == 200
        assert client.get("/backup", headers=headers).json() == EMPTY


def test_backup_is_queryable_as_sqlite_rows(monkeypatch, tmp_path):
    path = tmp_path / "backup.sqlite3"
    monkeypatch.setenv("ONMYWAY_BACKUP_DB", str(path))
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", "a" * 32)
    snapshot = {
        **EMPTY,
        "tables": {**EMPTY["tables"], "tasks": [{
            "id": "task-1", "title": "打印论文", "priority": 2,
            "serviceMinutes": 5, "deadline": "", "placeJson": "",
            "placeType": "PRINT_SHOP", "status": "pending",
            "createdAt": 100, "updatedAt": 100, "completedAt": 0,
            "lastRemindedAt": 0, "reminderCount": 0, "reminderPending": 0,
        }]},
        "preferences": {**EMPTY["preferences"], "settings": {
            "city": "苏州", "preferred_detour_minutes": 10,
            "learning_enabled": True}},
    }
    headers = {"X-Backup-Token": "a" * 32}
    with TestClient(app) as client:
        assert client.put("/backup", headers=headers, json=snapshot).status_code == 200
        assert client.get("/backup", headers=headers).json() == snapshot
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT title, status FROM backup_tasks WHERE id='task-1'").fetchone() == ("打印论文", "pending")
        assert db.execute("SELECT integer_value FROM backup_preferences WHERE namespace='settings' AND key='preferred_detour_minutes'").fetchone() == (10,)
        assert db.execute("SELECT integer_value FROM backup_preferences WHERE namespace='settings' AND key='learning_enabled'").fetchone() == (1,)
        assert db.execute("SELECT name FROM sqlite_master WHERE name='backups'").fetchone() is None
        assert db.execute("SELECT format, room_version FROM backup_meta").fetchone() == (1, 7)


def test_legacy_json_document_is_migrated_without_data_loss(monkeypatch, tmp_path):
    path = tmp_path / "backup.sqlite3"
    monkeypatch.setenv("ONMYWAY_BACKUP_DB", str(path))
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", "a" * 32)
    old = {
        **EMPTY,
        "tables": {**EMPTY["tables"], "tasks": [{
            "id": "saved-before-upgrade", "title": "取快递", "priority": 3,
            "serviceMinutes": 5, "deadline": "", "placeJson": "",
            "placeType": "PARCEL", "status": "pending",
            "createdAt": 1, "updatedAt": 1, "completedAt": 0,
            "lastRemindedAt": 0, "reminderCount": 0, "reminderPending": 0,
        }]},
        "preferences": {**EMPTY["preferences"], "settings": {"city": "苏州"}},
    }
    with sqlite3.connect(path) as db:
        db.execute("CREATE TABLE backups (id INTEGER PRIMARY KEY, payload TEXT NOT NULL, updated_at TEXT)")
        db.execute("INSERT INTO backups (id, payload, updated_at) VALUES (1, ?, CURRENT_TIMESTAMP)",
                   (json.dumps(old),))
    with TestClient(app) as client:
        assert client.get("/backup", headers={"X-Backup-Token": "a" * 32}).json() == old
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT name FROM sqlite_master WHERE name='backups'").fetchone() is None
        assert db.execute("SELECT room_version FROM backup_meta WHERE id=1").fetchone() == (7,)
        assert db.execute("SELECT title FROM backup_tasks WHERE id='saved-before-upgrade'").fetchone() == ("取快递",)


def test_invalid_update_does_not_replace_existing_sqlite_records(monkeypatch, tmp_path):
    monkeypatch.setenv("ONMYWAY_BACKUP_DB", str(tmp_path / "backup.sqlite3"))
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", "a" * 32)
    headers = {"X-Backup-Token": "a" * 32}
    with TestClient(app) as client:
        assert client.put("/backup", headers=headers, json=EMPTY).status_code == 200
        bad = {**EMPTY, "tables": {**EMPTY["tables"], "tasks": [{"id": "missing-columns"}]}}
        assert client.put("/backup", headers=headers, json=bad).status_code == 422
        assert client.get("/backup", headers=headers).json() == EMPTY



def test_version8_chat_rows_roundtrip_and_old_client_cannot_overwrite(monkeypatch, tmp_path):
    path = tmp_path / "backup.sqlite3"
    monkeypatch.setenv("ONMYWAY_BACKUP_DB", str(path))
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", "a" * 32)
    chat = {"id": "msg-1", "role": "user", "text": "去图书馆", "createdAt": 123,
            "routeHistoryId": "route-1", "conversationId": "primary"}
    draft = {"id": 1, "action": "plan_route", "title": "", "placeType": "",
             "deadline": "", "destination": "图书馆", "mode": "cycling",
             "knownPlaceId": "", "knownPlaceName": "", "knownLongitude": None,
             "knownLatitude": None, "knownAddress": "", "savedAt": 123,
             "expiresAt": 123 + 30 * 60_000, "conversationId": "primary"}
    current = {**EMPTY, "room_version": 8,
               "tables": {**EMPTY["tables"], "assistant_messages": [chat],
                          "assistant_draft": [draft]}}
    headers = {"X-Backup-Token": "a" * 32}
    with TestClient(app) as client:
        assert client.put("/backup", headers=headers, json=current).status_code == 200
        assert client.get("/backup", headers=headers).json() == current
        assert client.put("/backup", headers=headers, json=EMPTY).status_code == 409
        assert client.get("/backup", headers=headers).json() == current
    with sqlite3.connect(path) as db:
        assert db.execute('SELECT text, routeHistoryId FROM backup_assistant_messages').fetchone() == ("去图书馆", "route-1")
        assert db.execute('SELECT destination, mode FROM backup_assistant_draft').fetchone() == ("图书馆", "cycling")
        assert db.execute("SELECT room_version FROM backup_meta").fetchone() == (8,)


def test_v9_planning_choices_are_sqlite_rows_and_roundtrip(monkeypatch, tmp_path):
    from backend.backup import _columns_for
    path = tmp_path / "draft.sqlite3"
    monkeypatch.setenv("ONMYWAY_BACKUP_DB", str(path))
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", "a" * 32)
    snapshot = {**EMPTY, "room_version": 9, "tables": {name: [] for name in _columns_for(9)}}
    snapshot["tables"]["planning_draft"] = [{
        "id": 1, "destination": "Library", "mode": "cycling", "budget": 12,
        "placeId": "library", "placeName": "Library", "longitude": 120.0,
        "latitude": 31.0, "address": "Campus", "savedAt": 1234,
    }]
    snapshot["tables"]["planning_draft_tasks"] = [
        {"id": "parcel", "selected": 1}, {"id": "print", "selected": 0},
    ]
    with TestClient(app) as client:
        headers = {"X-Backup-Token": "a" * 32}
        assert client.put("/backup", headers=headers, json=snapshot).status_code == 200
        assert client.get("/backup", headers=headers).json() == snapshot
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT budget,mode FROM backup_planning_draft").fetchone() == (12, "cycling")
        assert db.execute("SELECT id,selected FROM backup_planning_draft_tasks ORDER BY id").fetchall() == [("parcel", 1), ("print", 0)]

def v11_snapshot():
    return {
        **EMPTY,
        "room_version": 11,
        "tables": {
            "tasks": [],
            "saved_trip": [{
                "id": 1, "requestJson": "{}", "resultJson": "{}", "started": 0,
                "sessionId": "route-session", "startedAt": 0, "plannedAt": 1000,
                "needsReplan": 1, "planId": "plan-library",
            }],
            "route_history": [{
                "id": "route-1", "sessionId": "route-session", "state": "READY",
                "timestamp": 1000, "requestJson": "{}", "resultJson": "{}",
                "planId": "plan-library",
            }],
            "suggestion_feedback": [],
            "agent_events": [],
            "assistant_messages": [],
            "assistant_draft": [{
                "id": 1, "action": "plan_route", "title": "", "placeType": "",
                "deadline": "", "destination": "Library", "mode": "cycling",
                "knownPlaceId": "", "knownPlaceName": "", "knownLongitude": None,
                "knownLatitude": None, "knownAddress": "", "savedAt": 1000,
                "expiresAt": 1000 + 30 * 60_000, "draftId": "draft-selection",
                "conversationId": "primary", "planId": "plan-library",
            }],
            "planning_draft": [{
                "id": 1, "destination": "Library", "mode": "cycling", "budget": 12,
                "placeId": "library", "placeName": "Library", "longitude": 120.0,
                "latitude": 31.0, "address": "Campus", "savedAt": 1000,
                "draftId": "draft-preview", "planId": "plan-library",
            }],
            "planning_draft_tasks": [{"id": "parcel", "selected": 1}],
            "assistant_clarification": [{
                "id": 1, "draftId": "draft-selection", "planId": "plan-library",
                "kind": "place", "prompt": "Which library?", "destination": "Library",
                "mode": "cycling", "savedAt": 1000,
            }],
            "assistant_place_choices": [
                {"id": "poi-north", "draftId": "draft-selection", "name": "North Library",
                 "longitude": 120.0, "latitude": 31.0, "address": "North Campus", "position": 9},
                {"id": "poi-south", "draftId": "draft-selection", "name": "South Library",
                 "longitude": 120.1, "latitude": 31.1, "address": "South Campus", "position": 3},
            ],
        },
    }


def test_v11_plan_and_clarification_rows_roundtrip(monkeypatch, tmp_path):
    path = tmp_path / "v11.sqlite3"
    monkeypatch.setenv("ONMYWAY_BACKUP_DB", str(path))
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", "a" * 32)
    snapshot = v11_snapshot()
    headers = {"X-Backup-Token": "a" * 32}
    with TestClient(app) as client:
        response = client.put("/backup", headers=headers, json=snapshot)
        assert response.status_code == 200, response.text
        assert client.get("/backup", headers=headers).json() == snapshot
    with sqlite3.connect(path) as db:
        for table in ("saved_trip", "route_history", "assistant_draft", "planning_draft"):
            assert db.execute(f'SELECT planId FROM backup_{table}').fetchone() == ("plan-library",)
        assert db.execute("SELECT draftId, planId FROM backup_assistant_clarification").fetchone() == (
            "draft-selection", "plan-library")
        # Candidate ranking is distinct from snapshot serialization order.
        assert db.execute(
            "SELECT position, _snapshot_position FROM backup_assistant_place_choices "
            "ORDER BY _snapshot_position").fetchall() == [(9, 0), (3, 1)]


def test_v11_rejects_invalid_clarification_before_any_write(monkeypatch, tmp_path):
    import copy
    monkeypatch.setenv("ONMYWAY_BACKUP_DB", str(tmp_path / "invalid.sqlite3"))
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", "a" * 32)
    headers = {"X-Backup-Token": "a" * 32}
    with TestClient(app) as client:
        for table, column, value in (
            ("assistant_clarification", "id", 2),
            ("assistant_clarification", "planId", None),
            ("assistant_place_choices", "position", None),
            ("route_history", "planId", None),
        ):
            snapshot = copy.deepcopy(v11_snapshot())
            snapshot["tables"][table][0][column] = value
            assert client.put("/backup", headers=headers, json=snapshot).status_code == 422
        assert client.get("/backup", headers=headers).status_code == 404


def test_v12_pending_items_dates_and_internal_receipts_survive_old_database_upgrade(monkeypatch, tmp_path):
    import copy
    path = tmp_path / "upgrade-v12.sqlite3"
    monkeypatch.setenv("ONMYWAY_BACKUP_DB", str(path))
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", "a" * 32)
    headers = {"X-Backup-Token": "a" * 32}
    legacy = v11_snapshot()
    snapshot = copy.deepcopy(legacy)
    snapshot["room_version"] = 12
    for row in snapshot["tables"]["tasks"]:
        row["plannedDate"] = "2026-10-16"
    snapshot["tables"]["assistant_draft"][0]["action"] = "create_tasks"
    snapshot["tables"]["assistant_task_drafts"] = [
        {"id": "parcel", "planId": "plan-library", "position": 7, "version": 3,
         "title": "取快递", "placeType": "PARCEL", "deadline": "", "plannedDate": "2026-10-15",
         "priority": 2, "serviceMinutes": 5, "placeJson": ""},
        {"id": "print", "planId": "plan-library", "position": 2, "version": 1,
         "title": "打印论文", "placeType": "PRINT_SHOP", "deadline": "2026-10-18T18:00:00+08:00",
         "plannedDate": "2026-10-16", "priority": 3, "serviceMinutes": 10, "placeJson": ""},
    ]
    snapshot["tables"]["assistant_messages"].append({"id": "receipt-print", "role": "receipt", "text": "",
        "createdAt": 1, "routeHistoryId": None, "conversationId": "primary"})
    with TestClient(app) as client:
        assert client.put("/backup", headers=headers, json=legacy).status_code == 200
        from uuid import uuid4
        head = client.get("/sync", headers=headers).json()
        commands = [{"command_id": str(uuid4()), "operation": "upsert", "task_id": row["id"],
                     "expected_version": head["task_versions"][row["id"]], "task": row}
                    for row in snapshot["tables"]["tasks"]]
        response = client.put("/sync", headers=headers, json={"store_id": head["store_id"],
            "base_revision": head["revision"], "request_id": str(uuid4()), "snapshot": snapshot,
            "task_commands": commands})
        assert response.status_code == 200, response.text
        assert client.get("/backup", headers=headers).json() == snapshot
        snapshot["tables"]["assistant_task_drafts"].pop()
        head = client.get("/sync", headers=headers).json()
        assert client.put("/sync", headers=headers, json={"store_id": head["store_id"],
            "base_revision": head["revision"], "request_id": str(uuid4()), "snapshot": snapshot,
            "task_commands": []}).status_code == 200
    # A new connection must read the exact remaining proposal after partial confirmation.
    with TestClient(app) as client:
        assert client.get("/backup", headers=headers).json() == snapshot
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT position, _snapshot_position, version FROM backup_assistant_task_drafts").fetchall() == [(7, 0, 3)]
        assert db.execute("PRAGMA integrity_check").fetchone() == ("ok",)
