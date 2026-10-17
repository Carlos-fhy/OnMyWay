import copy
import json
import sqlite3
import uuid

import pytest
from fastapi.testclient import TestClient

from backend.backup import PREFERENCE_KEYS, ROOM_VERSION, TABLE_COLUMNS, _columns_for, _validate
from backend.main import app

TOKEN = {"X-Backup-Token": "task-tests-" + "x" * 32}


@pytest.fixture
def configured(monkeypatch, tmp_path):
    path = tmp_path / "tasks.sqlite3"
    monkeypatch.setenv("ONMYWAY_BACKUP_DB", str(path))
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", TOKEN["X-Backup-Token"])
    with TestClient(app) as client:
        yield client, path


def task(task_id="first", **changes):
    return {"id": task_id, "title": "打印论文", "priority": 2, "serviceMinutes": 5,
            "deadline": "", "placeJson": "", "placeType": "PRINT_SHOP", "status": "pending",
            "createdAt": 100, "updatedAt": 100, "completedAt": 0, "lastRemindedAt": 0,
            "reminderCount": 0, "reminderPending": 0, "plannedDate": "", **changes}


def snapshot(*tasks, room_version=ROOM_VERSION):
    columns = _columns_for(room_version)
    rows = [{name: row.get(name, "") for name, _ in columns["tasks"]} for row in tasks]
    return {"format": 1, "room_version": room_version, "tables": {name: rows if name == "tasks" else [] for name in columns},
            "preferences": {name: {} for name in PREFERENCE_KEYS}}


def command(value=None, *, task_id="first", version=0, operation="upsert", command_id=None):
    value = value if value is not None else task(task_id)
    return {"command_id": command_id or str(uuid.uuid4()), "operation": operation,
            "task_id": value["id"] if operation == "upsert" else task_id, "expected_version": version,
            **({"task": value} if operation == "upsert" else {})}


def commit(client, head, *commands, request_id=None):
    return client.post("/tasks/commands", headers=TOKEN, json={"store_id": head["store_id"],
        "request_id": request_id or str(uuid.uuid4()), "commands": list(commands)})


def sync(client, head, value, commands=None, request_id=None):
    return client.put("/sync", headers=TOKEN, json={"store_id": head["store_id"],
        "base_revision": head["revision"], "request_id": request_id or str(uuid.uuid4()), "snapshot": value,
        **({"task_commands": commands} if commands is not None else {})})


def test_task_crud_completion_defer_and_empty_store_restore(configured):
    client, path = configured
    head = client.get("/sync", headers=TOKEN).json()
    assert head["task_protocol"] == 1 and head["task_versions"] == {}
    response = commit(client, head, command())
    assert response.status_code == 200, response.text
    created = response.json()
    assert created["revision"] == 1 and created["task_versions"] == {"first": 1}
    restored = client.get("/sync", headers=TOKEN).json()
    assert _validate(restored["snapshot"]) == snapshot(task())
    deferred = task(status="deferred", updatedAt=200)
    assert commit(client, head, command(deferred, version=1)).status_code == 200
    done = task(status="done", updatedAt=300, completedAt=300)
    assert commit(client, head, command(done, version=2)).status_code == 200
    records = client.get("/tasks", headers=TOKEN).json()
    assert records["tasks"] == [{"task_id": "first", "version": 3, "deleted": False, "task": done}]
    deleted = commit(client, head, command(operation="delete", version=3))
    assert deleted.status_code == 200
    assert deleted.json()["task_versions"] == {"first": 4}
    records = client.get("/tasks", headers=TOKEN).json()
    assert records["tasks"] == [{"task_id": "first", "version": 4, "deleted": True, "task": None}]
    assert client.get("/sync", headers=TOKEN).json()["snapshot"] == snapshot()
    assert client.get("/sync/versions/3", headers=TOKEN).json()["snapshot"] == snapshot(done)
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT title, status, version, deleted FROM task_records").fetchone() == ("打印论文", "done", 4, 1)
        assert db.execute("SELECT COUNT(*) FROM backup_tasks").fetchone()[0] == 0


def test_stale_update_and_tombstone_cannot_resurrect(configured):
    client, _ = configured
    head = client.get("/sync", headers=TOKEN).json()
    assert commit(client, head, command()).status_code == 200
    stale = command(task(title="stale"), version=0)
    assert commit(client, head, stale).status_code == 409
    assert commit(client, head, command(operation="delete", version=1)).status_code == 200
    for version in (0, 1, 2):
        assert commit(client, head, command(task(title="resurrect"), version=version)).status_code == 409
    assert client.get("/sync", headers=TOKEN).json()["task_versions"] == {"first": 2}


def test_batch_and_per_command_receipts_survive_later_edits(configured):
    client, path = configured
    head = client.get("/sync", headers=TOKEN).json()
    create = command()
    first = commit(client, head, create, request_id="lost-response").json()
    assert commit(client, head, command(task(title="new"), version=1)).status_code == 200
    assert commit(client, head, create, request_id="lost-response").json() == first
    retry = commit(client, head, create).json()
    assert retry["revision"] == 2 and retry["task_versions"] == {"first": 2}
    assert retry["results"][0]["version"] == 1
    changed_command = copy.deepcopy(create)
    changed_command["task"]["title"] = "different"
    assert commit(client, head, changed_command).status_code == 409
    assert commit(client, head, changed_command, request_id="lost-response").status_code == 409
    assert client.get("/sync", headers=TOKEN).json()["snapshot"]["tables"]["tasks"][0]["title"] == "new"
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT COUNT(*) FROM task_command_receipts").fetchone()[0] == 2


def test_batch_conflict_rolls_back_all_commands_receipts_and_archives(configured):
    client, path = configured
    head = client.get("/sync", headers=TOKEN).json()
    failed = commit(client, head, command(), command(task("second"), version=7))
    assert failed.status_code == 409
    assert client.get("/sync", headers=TOKEN).json() == head
    with sqlite3.connect(path) as db:
        for table in ("task_records", "task_command_receipts", "task_batch_receipts", "sync_versions"):
            assert db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] == 0


def test_sequential_commands_in_batch_use_each_resulting_version(configured):
    client, _ = configured
    head = client.get("/sync", headers=TOKEN).json()
    response = commit(client, head, command(), command(task(status="done", completedAt=100), version=1))
    assert response.status_code == 200, response.text
    assert response.json()["task_versions"] == {"first": 2}
    assert response.json()["revision"] == 1


def test_sync_gateway_rejects_legacy_overwrite_and_updates_tasks_atomically(configured):
    client, path = configured
    head = client.get("/sync", headers=TOKEN).json()
    first_task, other_task = task(), task("other")
    assert sync(client, head, snapshot(first_task)).status_code == 409
    original_commands = [command(first_task), command(other_task)]
    first = sync(client, head, snapshot(other_task, first_task), original_commands, "sync-create")
    assert first.status_code == 200, first.text
    first = first.json()
    assert first["task_versions"] == {"first": 1, "other": 1}
    same_tasks = snapshot(first_task, other_task)
    same_tasks["preferences"]["settings"]["city"] = "苏州"
    second = sync(client, first, same_tasks).json()
    assert second["revision"] == 2
    assert sync(client, second, snapshot()).status_code == 409
    bad = sync(client, second, snapshot(first_task), [command(task(title="edited"), version=1)])
    assert bad.status_code == 409
    assert client.get("/sync", headers=TOKEN).json()["snapshot"] == same_tasks
    deleted = sync(client, second, snapshot(first_task), [command(operation="delete", task_id="other", version=1)])
    assert deleted.status_code == 200
    assert sync(client, head, snapshot(other_task, first_task), original_commands, "sync-create").json() == first
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT COUNT(*) FROM task_command_receipts").fetchone()[0] == 3
        assert db.execute("SELECT version FROM task_records WHERE id='first'").fetchone()[0] == 1


def test_migrated_legacy_invalid_fields_are_preserved_and_can_be_completed(configured):
    client, path = configured
    legacy = task(title="", placeType="OLD_TYPE", deadline="old ambiguous date", serviceMinutes=0)
    assert client.put("/backup", headers=TOKEN, json=snapshot(legacy, room_version=11)).status_code == 200
    first = client.get("/sync", headers=TOKEN).json()
    assert first["snapshot"] == snapshot(legacy, room_version=11) and first["task_versions"] == {"first": 1}
    assert client.get("/sync", headers=TOKEN).json() == first
    done = {**legacy, "status": "done", "completedAt": 200, "updatedAt": 200}
    assert commit(client, first, command(done, version=1)).status_code == 200
    assert client.get("/sync", headers=TOKEN).json()["snapshot"] == snapshot(done, room_version=11)
    changed_invalid = {**done, "title": " "}
    assert commit(client, first, command(changed_invalid, version=2)).status_code == 422
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT COUNT(*) FROM task_records").fetchone()[0] == 1
        assert db.execute("SELECT protocol FROM task_schema").fetchone()[0] == 1


@pytest.mark.parametrize("field,value", [
    ("title", " "), ("title", "x" * 101), ("priority", 0), ("priority", True),
    ("serviceMinutes", 241), ("status", "unknown"), ("reminderCount", -1),
    ("reminderPending", 2), ("createdAt", 2**70), ("updatedAt", -1),
    ("deadline", "tomorrow"), ("deadline", "2026-10-11T12:00:00"),
    ("plannedDate", "2026-02-30"), ("plannedDate", "20261015"),
    ("plannedDate", "2026-W42-4"), ("plannedDate", "2026-10-15T00:00:00"),
    ("plannedDate", None),
    ("placeJson", "null"), ("placeType", "OTHER"),
    ("completedAt", 100), ("status", "done"),
])
def test_new_task_validation_returns_422_without_writes(configured, field, value):
    client, _ = configured
    head = client.get("/sync", headers=TOKEN).json()
    response = commit(client, head, command(task(**{field: value})))
    assert response.status_code == 422, response.text
    assert client.get("/sync", headers=TOKEN).json() == head


def test_task_auth_identity_and_request_limits(configured):
    client, _ = configured
    assert client.get("/tasks").status_code == 401
    assert client.post("/tasks/commands", json={}).status_code == 401
    head = client.get("/sync", headers=TOKEN).json()
    assert commit(client, {"store_id": "other"}, command()).status_code == 409
    bad = command(command_id="not-uuid")
    assert commit(client, head, bad).status_code == 422
    assert client.post("/tasks/commands", headers=TOKEN, content=b"{").status_code == 422
    assert client.post("/tasks/commands", headers=TOKEN, content=b"x" * (8 * 1024 * 1024 + 1)).status_code == 413
    assert client.get("/sync", headers=TOKEN).json() == head


def test_place_validation_and_timezone_deadline_accept_real_values(configured):
    client, _ = configured
    head = client.get("/sync", headers=TOKEN).json()
    place = {"id": "station", "name": "东门", "longitude": 120.5, "latitude": 31, "address": "校园内"}
    value = task(placeJson=json.dumps(place), deadline="2026-10-11T18:00:00+08:00", placeType="PARCEL")
    assert commit(client, head, command(value)).status_code == 200
    for key, coordinate in (("longitude", True), ("longitude", 181), ("latitude", -91)):
        invalid_place = {**place, key: coordinate}
        value["placeJson"] = json.dumps(invalid_place)
        assert commit(client, head, command(value, version=1)).status_code == 422


def test_incremental_task_api_cannot_grow_beyond_restorable_snapshot_capacity(configured, monkeypatch):
    client, path = configured
    head = client.get("/sync", headers=TOKEN).json()
    # Small deterministic capacity stands in for the production 8 MiB boundary.
    limit = len(json.dumps(snapshot(task()), ensure_ascii=False, separators=(",", ":")).encode()) + 10
    monkeypatch.setattr("backend.tasks.MAX_BYTES", limit)
    assert commit(client, head, command()).status_code == 200
    assert commit(client, head, command(task("second"))).status_code == 413
    assert client.get("/sync", headers=TOKEN).json()["snapshot"] == snapshot(task())
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT COUNT(*) FROM task_command_receipts").fetchone()[0] == 1


def test_old_command_receipts_preserve_original_payload_digest(configured):
    from backend.tasks import _digest
    client, path = configured
    head = client.get("/sync", headers=TOKEN).json()
    legacy = task()
    legacy.pop("plannedDate")
    create = command(legacy)
    original = commit(client, head, create, request_id="legacy-request")
    assert original.status_code == 200
    wire = {"store_id": head["store_id"], "request_id": "legacy-request", "commands": [create]}
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT digest FROM task_command_receipts").fetchone()[0] == _digest(create)
        assert db.execute("SELECT digest FROM task_batch_receipts").fetchone()[0] == _digest(wire)
    assert commit(client, head, command(task(plannedDate="2026-10-16"), version=1)).status_code == 200
    assert commit(client, head, create, request_id="legacy-request").json() == original.json()
    retry = commit(client, head, create).json()
    assert retry["task_versions"] == {"first": 2} and retry["results"][0]["version"] == 1
    assert client.get("/tasks", headers=TOKEN).json()["tasks"][0]["task"]["plannedDate"] == "2026-10-16"
    # Adding even the empty new field changes the original command's identity.
    altered = copy.deepcopy(create)
    altered["task"]["plannedDate"] = ""
    assert commit(client, head, altered).status_code == 409


def test_legacy_upsert_preserves_scheduled_date_and_explicit_empty_clears_it(configured):
    client, _ = configured
    head = client.get("/sync", headers=TOKEN).json()
    assert commit(client, head, command(task(plannedDate="2026-10-16"))).status_code == 200
    legacy = task(title="打印两份论文")
    legacy.pop("plannedDate")
    assert commit(client, head, command(legacy, version=1)).status_code == 200
    stored = client.get("/tasks", headers=TOKEN).json()["tasks"][0]["task"]
    assert stored["plannedDate"] == "2026-10-16" and stored["title"] == "打印两份论文"
    assert commit(client, head, command({**stored, "plannedDate": ""}, version=2)).status_code == 200
    assert client.get("/tasks", headers=TOKEN).json()["tasks"][0]["task"]["plannedDate"] == ""


def test_legacy_mirror_accepts_missing_empty_date_but_never_erases_scheduled_date(configured):
    client, _ = configured
    legacy = snapshot(task(), room_version=11)
    assert client.put("/backup", headers=TOKEN, json=legacy).status_code == 200
    head = client.get("/sync", headers=TOKEN).json()
    same = sync(client, head, legacy)
    assert same.status_code == 200, same.text
    scheduled = task(plannedDate="2026-10-16")
    assert commit(client, head, command(scheduled, version=1)).status_code == 200
    current = client.get("/sync", headers=TOKEN).json()
    assert current["snapshot"] == snapshot(scheduled)
    assert sync(client, current, legacy).status_code == 409
    old = client.get("/sync/versions/1", headers=TOKEN).json()
    assert old["snapshot"] == legacy


def test_existing_task_authority_adds_planned_date_without_losing_rows_or_versions(configured):
    client, path = configured
    legacy = task()
    legacy.pop("plannedDate")
    assert client.put("/backup", headers=TOKEN, json=snapshot(legacy, room_version=11)).status_code == 200
    # Reproduce a populated pre-M1 authority table. Startup owns the additive
    # migration; business versions must not increase just because a column exists.
    old_columns = _columns_for(11)["tasks"]
    fields = ", ".join(f'"{name}" {kind}' + (" PRIMARY KEY" if name == "id" else "")
                       for name, kind in old_columns)
    with sqlite3.connect(path) as db:
        db.execute(f"CREATE TABLE task_records ({fields}, version INTEGER NOT NULL, deleted INTEGER NOT NULL)")
        placeholders = ",".join("?" for _ in old_columns)
        db.execute(f"INSERT INTO task_records VALUES ({placeholders}, 7, 0)",
                   [legacy[name] for name, _ in old_columns])
        db.execute("CREATE TABLE task_schema (id INTEGER PRIMARY KEY, protocol INTEGER NOT NULL)")
        db.execute("INSERT INTO task_schema VALUES (1, 1)")
    current = client.get("/tasks", headers=TOKEN)
    assert current.status_code == 200, current.text
    assert current.json()["tasks"] == [{"task_id": "first", "version": 7, "deleted": False, "task": task()}]
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT title, plannedDate, version FROM task_records").fetchone() == ("打印论文", "", 7)
