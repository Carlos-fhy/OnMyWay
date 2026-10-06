import copy
import sqlite3
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient
from backend.main import app

EMPTY = {"format": 1, "room_version": 8,
         "tables": {name: [] for name in ("tasks", "saved_trip", "route_history", "suggestion_feedback", "agent_events", "assistant_messages", "assistant_draft")},
         "preferences": {"settings": {}, "assistant_conversation": {}, "navigation_context": {}}}
TOKEN = {"X-Backup-Token": "s" * 32}


@pytest.fixture
def configured(monkeypatch, tmp_path):
    path = tmp_path / "primary.sqlite3"
    monkeypatch.setenv("ONMYWAY_BACKUP_DB", str(path))
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", "s" * 32)
    with TestClient(app) as client:
        yield client, path


def message_snapshot(text="hello"):
    data = copy.deepcopy(EMPTY)
    data["tables"]["assistant_messages"] = [{"id": "m1", "role": "user", "text": text,
        "createdAt": 1, "routeHistoryId": None, "conversationId": "primary"}]
    return data


def envelope(head, snapshot, request_id="write-1"):
    return {"store_id": head["store_id"], "base_revision": head["revision"],
            "request_id": request_id, "snapshot": snapshot}


def test_bootstrap_retry_restore_and_stale_empty_device(configured):
    client, path = configured
    assert client.get("/sync").status_code == 401
    head = client.get("/sync", headers=TOKEN).json()
    assert head["snapshot"] is None and head["revision"] == 0
    request = envelope(head, message_snapshot())
    committed = client.put("/sync", headers=TOKEN, json=request)
    assert committed.status_code == 200
    assert committed.json()["revision"] == 1
    # Lost response retry is idempotent even after commit.
    assert client.put("/sync", headers=TOKEN, json=request).json() == committed.json()
    restored = client.get("/sync", headers=TOKEN).json()
    assert restored["snapshot"] == message_snapshot()
    assert client.put("/sync", headers=TOKEN, json=envelope(head, EMPTY, "fresh-install")).status_code == 409
    assert client.put("/backup", headers=TOKEN, json=EMPTY).status_code == 409
    assert client.get("/sync", headers=TOKEN).json() == restored
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT COUNT(*) FROM sync_receipts").fetchone()[0] == 1


def test_explicit_current_revision_deletion_keeps_history(configured):
    client, path = configured
    head = client.get("/sync", headers=TOKEN).json()
    first = client.put("/sync", headers=TOKEN, json=envelope(head, message_snapshot())).json()
    assert client.put("/sync", headers=TOKEN, json=envelope(first, EMPTY, "delete")).status_code == 200
    assert client.get("/sync", headers=TOKEN).json()["snapshot"] == EMPTY
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT text FROM sync_version_assistant_messages WHERE revision=1").fetchone() == ("hello",)
        assert db.execute("SELECT COUNT(*) FROM backup_assistant_messages").fetchone()[0] == 0
        assert db.execute("SELECT COUNT(*) FROM sync_versions").fetchone()[0] == 2


def test_two_writers_cannot_both_replace_same_revision(configured):
    client, path = configured
    head = client.get("/sync", headers=TOKEN).json()
    def write(index):
        return client.put("/sync", headers=TOKEN, json=envelope(head, message_snapshot(str(index)), str(index))).status_code
    with ThreadPoolExecutor(max_workers=2) as pool:
        assert sorted(pool.map(write, (1, 2))) == [200, 409]
    assert client.get("/sync", headers=TOKEN).json()["revision"] == 1


def test_identity_and_idempotency_key_misuse_preserve_rows(configured):
    client, path = configured
    head = client.get("/sync", headers=TOKEN).json()
    request = envelope(head, message_snapshot())
    assert client.put("/sync", headers=TOKEN, json=request).status_code == 200
    request["snapshot"] = EMPTY
    assert client.put("/sync", headers=TOKEN, json=request).status_code == 409
    request["store_id"] = "other-server"
    assert client.put("/sync", headers=TOKEN, json=request).status_code == 409
    assert client.get("/sync", headers=TOKEN).json()["snapshot"] == message_snapshot()


def test_existing_legacy_backup_is_adopted_without_overwrite(configured):
    client, path = configured
    assert client.put("/backup", headers=TOKEN, json=message_snapshot()).status_code == 200
    head = client.get("/sync", headers=TOKEN).json()
    assert head["revision"] == 1 and head["snapshot"] == message_snapshot()
    assert client.put("/sync", headers=TOKEN, json=envelope(head, message_snapshot("updated"))).status_code == 200
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT text FROM sync_version_assistant_messages WHERE revision=1").fetchone() == ("hello",)


def test_sync_rejects_invalid_large_or_unsupported_requests(configured):
    client, _ = configured
    head = client.get("/sync", headers=TOKEN).json()
    assert client.put("/sync", headers=TOKEN, content=b"{").status_code == 422
    assert client.put("/sync", headers=TOKEN, content=b"x" * (8 * 1024 * 1024 + 1)).status_code == 413
    bad = envelope(head, EMPTY)
    bad["base_revision"] = True
    assert client.put("/sync", headers=TOKEN, json=bad).status_code == 422
    assert client.get("/sync", headers=TOKEN).json()["revision"] == 0


def test_history_can_be_read_without_rolling_back_current_records(configured):
    client, _ = configured
    head = client.get("/sync", headers=TOKEN).json()
    first = client.put("/sync", headers=TOKEN, json=envelope(head, message_snapshot())).json()
    client.put("/sync", headers=TOKEN, json=envelope(first, EMPTY, "delete"))
    assert client.get("/sync/versions").status_code == 401
    assert [v["revision"] for v in client.get("/sync/versions", headers=TOKEN).json()["versions"]] == [2, 1]
    assert client.get("/sync/versions/1", headers=TOKEN).json()["snapshot"] == message_snapshot()
    assert client.get("/sync", headers=TOKEN).json()["snapshot"] == EMPTY


def test_invalid_rows_cannot_poison_restorable_store(configured):
    client, _ = configured
    head = client.get("/sync", headers=TOKEN).json()
    for field, value in (("id", None), ("text", None), ("createdAt", None)):
        invalid = message_snapshot()
        invalid["tables"]["assistant_messages"][0][field] = value
        assert client.put("/sync", headers=TOKEN, json=envelope(head, invalid)).status_code == 422
    invalid = message_snapshot()
    invalid["tables"]["assistant_messages"] *= 2
    assert client.put("/sync", headers=TOKEN, json=envelope(head, invalid)).status_code == 422
    assert client.get("/sync", headers=TOKEN).json()["revision"] == 0


def test_v10_draft_identity_survives_upgrade_and_history(configured):
    from backend.backup import _columns_for
    client, path = configured
    # Simulate actual v9 tables lacking the new column, not just a v9 payload.
    with sqlite3.connect(path) as db:
        for prefix in ("backup_", "sync_version_"):
            for name in ("assistant_draft", "planning_draft"):
                fields = ", ".join(f'"{c}" {t}' for c, t in _columns_for(9)[name])
                key = "position INTEGER PRIMARY KEY" if prefix == "backup_" else "revision INTEGER NOT NULL, position INTEGER NOT NULL"
                db.execute(f'CREATE TABLE "{prefix}{name}" ({key}, {fields})')
    head = client.get("/sync", headers=TOKEN).json()
    old = {**copy.deepcopy(EMPTY), "room_version": 9,
           "tables": {name: [] for name in _columns_for(9)}}
    old["tables"]["planning_draft"] = [{
        "id": 1, "destination": "Library", "mode": "cycling", "budget": 10,
        "placeId": "poi", "placeName": "Library", "longitude": 120.0,
        "latitude": 31.0, "address": "Campus", "savedAt": 1,
    }]
    first = client.put("/sync", headers=TOKEN, json=envelope(head, old)).json()
    current = copy.deepcopy(old)
    current["room_version"] = 10
    current["tables"]["planning_draft"][0]["draftId"] = "new-request"
    assert client.put("/sync", headers=TOKEN, json=envelope(first, current, "upgrade")).status_code == 200
    assert client.get("/sync", headers=TOKEN).json()["snapshot"] == current
    assert client.get("/sync/versions/1", headers=TOKEN).json()["snapshot"] == old
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT draftId FROM backup_planning_draft").fetchone() == ("new-request",)

@pytest.mark.parametrize("old_version", [7, 8, 9, 10])
def test_v11_upgrade_preserves_actual_old_tables_and_historical_protocol(configured, old_version):
    from backend.backup import _columns_for
    from backend.tests.test_backup import v11_snapshot

    client, path = configured
    current = v11_snapshot()
    old_columns = _columns_for(old_version)
    old = {
        **copy.deepcopy(EMPTY), "room_version": old_version,
        "tables": {
            table: [
                {column: row[column] for column, _ in columns}
                for row in current["tables"][table]
            ]
            for table, columns in old_columns.items()
        },
    }
    # Build actual old tables with their original physical column ordering and rows.
    # In particular assistant_draft gains draftId after conversationId during upgrade.
    with sqlite3.connect(path) as db:
        db.execute("CREATE TABLE backup_meta (id INTEGER PRIMARY KEY, format INTEGER, room_version INTEGER, updated_at TEXT)")
        db.execute("INSERT INTO backup_meta VALUES (1, 1, ?, CURRENT_TIMESTAMP)", (old_version,))
        db.execute("CREATE TABLE sync_head (id INTEGER PRIMARY KEY, store_id TEXT, revision INTEGER)")
        db.execute("INSERT INTO sync_head VALUES (1, 'existing-store', 1)")
        db.execute("CREATE TABLE sync_versions (revision INTEGER PRIMARY KEY, room_version INTEGER, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
        db.execute("INSERT INTO sync_versions (revision, room_version) VALUES (1, ?)", (old_version,))
        for prefix in ("backup_", "sync_version_"):
            for table, columns in old_columns.items():
                fields = ", ".join(f'"{column}" {kind}' for column, kind in columns)
                key = "position INTEGER PRIMARY KEY" if prefix == "backup_" else "revision INTEGER, position INTEGER"
                db.execute(f'CREATE TABLE "{prefix}{table}" ({key}, {fields})')
                for position, row in enumerate(old["tables"][table]):
                    values = ([position] if prefix == "backup_" else [1, position]) + [
                        row[column] for column, _ in columns]
                    markers = ", ".join("?" for _ in values)
                    db.execute(f'INSERT INTO "{prefix}{table}" VALUES ({markers})', values)

    head = client.get("/sync", headers=TOKEN).json()
    assert head["snapshot"] == old and head["store_id"] == "existing-store"
    assert client.get("/sync/versions/1", headers=TOKEN).json()["snapshot"] == old
    with sqlite3.connect(path) as db:
        for prefix in ("backup_", "sync_version_"):
            for table in ("saved_trip", "route_history", "assistant_draft", "planning_draft"):
                assert "planId" in {row[1] for row in db.execute(f'PRAGMA table_info("{prefix}{table}")')}
            assert db.execute(f'SELECT planId FROM "{prefix}route_history"').fetchone() == ("",)

    committed = client.put("/sync", headers=TOKEN, json=envelope(head, current, "upgrade-to-v11"))
    assert committed.status_code == 200, committed.text
    assert committed.json()["revision"] == 2
    assert client.get("/sync", headers=TOKEN).json()["snapshot"] == current
    assert client.get("/sync/versions/2", headers=TOKEN).json()["snapshot"] == current
    assert client.get("/sync/versions/1", headers=TOKEN).json()["snapshot"] == old
    assert client.put("/sync", headers=TOKEN, json=envelope(committed.json(), old, "old-client")).status_code == 409


def test_v11_clarification_replacement_archives_previous_choices(configured):
    from backend.tests.test_backup import v11_snapshot
    client, _ = configured
    head = client.get("/sync", headers=TOKEN).json()
    old = v11_snapshot()
    first = client.put("/sync", headers=TOKEN, json=envelope(head, old)).json()
    updated = copy.deepcopy(old)
    updated["tables"]["assistant_clarification"] = []
    updated["tables"]["assistant_place_choices"] = []
    body = envelope(first, updated, "answer-clarification")
    response = client.put("/sync", headers=TOKEN, json=body)
    assert response.status_code == 200, response.text
    assert client.put("/sync", headers=TOKEN, json=body).json() == response.json()
    assert client.get("/sync", headers=TOKEN).json()["snapshot"] == updated
    assert client.get("/sync/versions/1", headers=TOKEN).json()["snapshot"] == old
