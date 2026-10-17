"""Independent compatibility/race checks for the shared task write authority."""
import copy
import hashlib
import json
import sqlite3
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient

from backend.backup import PREFERENCE_KEYS, TABLE_COLUMNS, _columns_for, _connect, _write_snapshot
from backend.main import app


TOKEN = {"X-Backup-Token": "r" * 32}


def task(task_id="task", title="Before"):
    return {"id": task_id, "title": title, "priority": 2, "serviceMinutes": 5,
            "deadline": "", "placeJson": "", "placeType": "SUPERMARKET",
            "status": "pending", "createdAt": 10, "updatedAt": 10,
            "completedAt": 0, "lastRemindedAt": 0, "reminderCount": 0,
            "reminderPending": 0}


def snapshot(*tasks):
    return {"format": 1, "room_version": 11,
            "tables": {name: list(tasks) if name == "tasks" else [] for name in _columns_for(11)},
            "preferences": {name: {} for name in PREFERENCE_KEYS}}


def command(row, version=1):
    return {"command_id": str(uuid.uuid4()), "operation": "upsert",
            "task_id": row["id"], "expected_version": version, "task": row}


def envelope(head, data, commands=None):
    body = {"store_id": head["store_id"], "base_revision": head["revision"],
            "request_id": str(uuid.uuid4()), "snapshot": data}
    if commands is not None:
        body["task_commands"] = commands
    return body


@pytest.fixture
def configured(monkeypatch, tmp_path):
    path = tmp_path / "race.sqlite3"
    monkeypatch.setenv("ONMYWAY_BACKUP_DB", str(path))
    monkeypatch.setenv("ONMYWAY_BACKUP_TOKEN", "r" * 32)
    with TestClient(app) as client:
        yield client, path


def seed_legacy(data):
    db = _connect()
    try:
        with db:
            _write_snapshot(db, data)
    finally:
        db.close()


def test_parallel_first_open_migrates_legacy_tasks_once(configured):
    client, path = configured
    original = snapshot(task("z"), task("a"))
    seed_legacy(original)
    barrier = threading.Barrier(2)

    def read(route):
        barrier.wait(timeout=10)
        return client.get(route, headers=TOKEN)

    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(read, ("/sync", "/tasks")))
    assert [response.status_code for response in responses] == [200, 200]
    sync, tasks = [response.json() for response in responses]
    assert sync["store_id"] == tasks["store_id"]
    assert sync["revision"] == tasks["revision"] == 1
    assert sync["task_versions"] == tasks["task_versions"] == {"a": 1, "z": 1}
    assert sync["snapshot"] == original
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT id, version, deleted FROM task_records ORDER BY id").fetchall() == [
            ("a", 1, 0), ("z", 1, 0)]
        assert db.execute("SELECT COUNT(*) FROM task_schema").fetchone()[0] == 1


def test_old_receipt_replay_after_new_task_edit_keeps_historical_acknowledgement(configured):
    client, path = configured
    original = snapshot(task())
    seed_legacy(original)
    old_request = envelope({"store_id": "legacy-store", "revision": 0}, original)
    digest = hashlib.sha256(json.dumps(old_request, sort_keys=True, ensure_ascii=False,
                                      separators=(",", ":"), allow_nan=False).encode()).hexdigest()
    with sqlite3.connect(path) as db:
        db.execute("CREATE TABLE sync_head (id INTEGER PRIMARY KEY, store_id TEXT, revision INTEGER)")
        db.execute("INSERT INTO sync_head VALUES (1, 'legacy-store', 1)")
        db.execute("CREATE TABLE sync_receipts (request_id TEXT PRIMARY KEY, digest TEXT, revision INTEGER)")
        db.execute("INSERT INTO sync_receipts VALUES (?, ?, 1)", (old_request["request_id"], digest))
    head = client.get("/sync", headers=TOKEN).json()
    edit = client.post("/tasks/commands", headers=TOKEN, json={"store_id": head["store_id"],
        "request_id": str(uuid.uuid4()), "commands": [command(task(title="Later"))]})
    assert edit.status_code == 200, edit.text
    assert edit.json()["task_versions"] == {"task": 2}
    replay = client.put("/sync", headers=TOKEN, json=old_request)
    assert replay.status_code == 200, replay.text
    assert replay.json() == {"store_id": "legacy-store", "revision": 1}
    current = client.get("/sync", headers=TOKEN).json()
    assert current["revision"] == 2 and current["task_versions"] == {"task": 2}
    assert current["snapshot"]["tables"]["tasks"][0]["title"] == "Later"


def test_task_api_and_snapshot_cannot_both_update_same_task_version(configured):
    client, path = configured
    seed_legacy(snapshot(task()))
    head = client.get("/sync", headers=TOKEN).json()
    api_command = command(task(title="API"))
    sync_command = command(task(title="Snapshot"))
    barrier = threading.Barrier(2)

    def write(kind):
        barrier.wait(timeout=10)
        if kind == "api":
            return client.post("/tasks/commands", headers=TOKEN, json={"store_id": head["store_id"],
                "request_id": str(uuid.uuid4()), "commands": [api_command]})
        return client.put("/sync", headers=TOKEN,
                          json=envelope(head, snapshot(sync_command["task"]), [sync_command]))

    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(write, ("api", "sync")))
    assert sorted(response.status_code for response in responses) == [200, 409]
    current = client.get("/sync", headers=TOKEN).json()
    assert current["revision"] == 2 and current["task_versions"] == {"task": 2}
    winner = "API" if responses[0].status_code == 200 else "Snapshot"
    assert current["snapshot"]["tables"]["tasks"][0]["title"] == winner
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT title, version FROM task_records").fetchone() == (winner, 2)
        assert db.execute("SELECT title FROM backup_tasks").fetchone() == (winner,)
        assert db.execute("SELECT COUNT(*) FROM task_command_receipts").fetchone()[0] == 1


def test_snapshot_mismatch_rolls_back_command_receipt_and_all_business_writes(configured):
    client, path = configured
    seed_legacy(snapshot(task()))
    head = client.get("/sync", headers=TOKEN).json()
    update = command(task(title="Accepted later"))
    invalid = envelope(head, snapshot(task()), [update])
    invalid["snapshot"]["tables"]["assistant_messages"] = [{"id": "unexpected", "role": "user",
        "text": "Must also roll back", "createdAt": 10, "routeHistoryId": None, "conversationId": "primary"}]
    assert client.put("/sync", headers=TOKEN, json=invalid).status_code == 409
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT title, version FROM task_records").fetchone() == ("Before", 1)
        assert db.execute("SELECT COUNT(*) FROM task_command_receipts").fetchone()[0] == 0
        assert db.execute("SELECT COUNT(*) FROM backup_assistant_messages").fetchone()[0] == 0
        assert db.execute("SELECT revision FROM sync_head").fetchone()[0] == 1
    valid = copy.deepcopy(invalid)
    valid["snapshot"]["tables"]["tasks"] = [update["task"]]
    result = client.put("/sync", headers=TOKEN, json=valid)
    assert result.status_code == 200, result.text
    assert result.json()["task_versions"] == {"task": 2}


def test_legacy_task_order_change_is_not_a_task_mutation(configured):
    client, _ = configured
    original = snapshot(task("z"), task("a"))
    seed_legacy(original)
    head = client.get("/sync", headers=TOKEN).json()
    reordered = copy.deepcopy(original)
    reordered["tables"]["tasks"].reverse()
    response = client.put("/sync", headers=TOKEN, json=envelope(head, reordered))
    assert response.status_code == 200, response.text
    assert response.json()["task_versions"] == {"a": 1, "z": 1}
    assert client.get("/sync", headers=TOKEN).json()["snapshot"] == reordered


def test_extreme_coordinate_is_rejected_without_a_partial_task_commit(configured):
    client, path = configured
    head = client.get("/tasks", headers=TOKEN).json()
    invalid = task()
    invalid["placeJson"] = json.dumps({"id": "poi", "name": "Place", "address": "",
                                       "longitude": 10**400, "latitude": 31})
    response = client.post("/tasks/commands", headers=TOKEN, json={"store_id": head["store_id"],
        "request_id": str(uuid.uuid4()), "commands": [command(invalid, 0)]})
    assert response.status_code == 422, response.text
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT COUNT(*) FROM task_records").fetchone()[0] == 0
        assert db.execute("SELECT COUNT(*) FROM task_command_receipts").fetchone()[0] == 0
        assert db.execute("SELECT revision FROM sync_head").fetchone()[0] == 0
