"""Room 13 reminder times stay independent from task dates and old receipts."""
import copy
import sqlite3

import pytest

from backend.backup import _columns_for, _position_column
from backend.tasks import _digest
from backend.tests.test_tasks import TOKEN, command, commit, configured, snapshot, sync, task


def test_reminder_task_roundtrips_and_only_changes_target(configured):
    client, path = configured
    head = client.get("/sync", headers=TOKEN).json()
    parcel = task("parcel", title="取快递", placeType="PARCEL")
    printing = task(plannedDate="2026-10-18", deadline="2026-10-19T18:00:00+08:00")
    assert commit(client, head, command(parcel), command(printing)).status_code == 200
    scheduled = {**printing, "reminderAt": 1792315200000, "reminderPending": 0}
    changed = commit(client, head, command(scheduled, version=1))
    assert changed.status_code == 200, changed.text
    assert changed.json()["task_protocol"] == 1
    rows = client.get("/tasks", headers=TOKEN).json()["tasks"]
    assert {row["task_id"]: row["task"] for row in rows} == {"parcel": parcel, "first": scheduled}
    assert client.get("/sync", headers=TOKEN).json()["snapshot"] == snapshot(scheduled, parcel)
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT reminderAt, plannedDate, deadline FROM task_records WHERE id='first'").fetchone() == (
            1792315200000, printing["plannedDate"], printing["deadline"])
        for table in ("task_records", "backup_tasks", "sync_version_tasks"):
            info = {row[1]: row for row in db.execute(f'PRAGMA table_info("{table}")')}
            assert info["reminderAt"][3:5] == (1, "0")


def test_v12_snapshot_and_authority_upgrade_preserve_history_and_versions(configured):
    client, path = configured
    old = snapshot(task(plannedDate="2026-10-18"), room_version=12)
    assert client.put("/backup", headers=TOKEN, json=old).status_code == 200
    old_columns = _columns_for(12)["tasks"]
    fields = ", ".join(f'"{name}" {kind}' + (" PRIMARY KEY" if name == "id" else "") for name, kind in old_columns)
    with sqlite3.connect(path) as db:
        db.execute(f"CREATE TABLE task_records ({fields}, version INTEGER NOT NULL, deleted INTEGER NOT NULL)")
        markers = ",".join("?" for _ in old_columns)
        db.execute(f"INSERT INTO task_records VALUES ({markers}, 7, 0)",
                   [old["tables"]["tasks"][0][name] for name, _ in old_columns])
        db.execute("CREATE TABLE task_schema (id INTEGER PRIMARY KEY, protocol INTEGER NOT NULL)")
        db.execute("INSERT INTO task_schema VALUES (1, 1)")
    head = client.get("/sync", headers=TOKEN).json()
    assert head["snapshot"] == old and head["task_versions"] == {"first": 7}
    record = client.get("/tasks", headers=TOKEN).json()["tasks"][0]
    assert record["task"] == task(plannedDate="2026-10-18") and record["version"] == 7
    scheduled = {**record["task"], "reminderAt": 1792315200000}
    assert commit(client, head, command(scheduled, version=7)).status_code == 200
    current = client.get("/sync", headers=TOKEN).json()
    assert current["snapshot"] == snapshot(scheduled, room_version=13)
    assert current["task_versions"] == {"first": 8}
    assert client.get(f"/sync/versions/{head['revision']}", headers=TOKEN).json()["snapshot"] == old
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT reminderAt, version FROM task_records").fetchone() == (1792315200000, 8)
        assert db.execute("PRAGMA integrity_check").fetchone() == ("ok",)


def test_v12_commands_keep_wire_digest_and_replay_after_scheduling(configured):
    client, path = configured
    head = client.get("/sync", headers=TOKEN).json()
    old_task = task()
    old_task.pop("reminderAt")
    create = command(old_task)
    first = commit(client, head, create, request_id="v12-create")
    assert first.status_code == 200
    wire = {"store_id": head["store_id"], "request_id": "v12-create", "commands": [create]}
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT digest FROM task_command_receipts").fetchone()[0] == _digest(create)
        assert db.execute("SELECT digest FROM task_batch_receipts").fetchone()[0] == _digest(wire)
    assert commit(client, head, command(task(reminderAt=1792315200000), version=1)).status_code == 200
    assert commit(client, head, create, request_id="v12-create").json() == first.json()
    retry = commit(client, head, create).json()
    assert retry["task_versions"] == {"first": 2} and retry["results"][0]["version"] == 1
    altered = copy.deepcopy(create)
    altered["task"]["reminderAt"] = 0
    assert commit(client, head, altered).status_code == 409
    assert client.get("/tasks", headers=TOKEN).json()["tasks"][0]["task"]["reminderAt"] == 1792315200000


def test_v12_upsert_preserves_pending_reminder_and_explicit_zero_clears(configured):
    client, _ = configured
    head = client.get("/sync", headers=TOKEN).json()
    assert commit(client, head, command(task(reminderAt=1792315200000))).status_code == 200
    old_task = task(title="打印两份论文")
    old_task.pop("reminderAt")
    assert commit(client, head, command(old_task, version=1)).status_code == 200
    saved = client.get("/tasks", headers=TOKEN).json()["tasks"][0]["task"]
    assert saved["reminderAt"] == 1792315200000 and saved["title"] == "打印两份论文"
    assert commit(client, head, command({**saved, "reminderAt": 0}, version=2)).status_code == 200
    assert client.get("/tasks", headers=TOKEN).json()["tasks"][0]["task"]["reminderAt"] == 0


@pytest.mark.parametrize("status,completed", [("done", 200), ("deferred", 0)])
def test_v12_completion_or_defer_without_new_field_cancels_reminder(configured, status, completed):
    client, _ = configured
    head = client.get("/sync", headers=TOKEN).json()
    assert commit(client, head, command(task(reminderAt=1792315200000))).status_code == 200
    old_task = task(status=status, completedAt=completed)
    old_task.pop("reminderAt")
    assert commit(client, head, command(old_task, version=1)).status_code == 200
    assert client.get("/tasks", headers=TOKEN).json()["tasks"][0]["task"]["reminderAt"] == 0


def test_v12_mirror_accepts_missing_zero_but_rejects_losing_scheduled_reminder(configured):
    client, _ = configured
    old = snapshot(task(), room_version=12)
    assert client.put("/backup", headers=TOKEN, json=old).status_code == 200
    head = client.get("/sync", headers=TOKEN).json()
    assert sync(client, head, old).status_code == 200
    scheduled = task(reminderAt=1792315200000)
    assert commit(client, head, command(scheduled, version=1)).status_code == 200
    current = client.get("/sync", headers=TOKEN).json()
    assert current["snapshot"] == snapshot(scheduled, room_version=13)
    assert sync(client, current, old).status_code == 409
    assert client.get("/sync", headers=TOKEN).json() == current


@pytest.mark.parametrize("value", [-1, True, 1.5, "123", None, 2**63])
def test_invalid_reminder_time_rejects_without_business_changes(configured, value):
    client, _ = configured
    head = client.get("/sync", headers=TOKEN).json()
    response = commit(client, head, command(task(reminderAt=value)))
    assert response.status_code == 422
    assert client.get("/sync", headers=TOKEN).json() == head


@pytest.mark.parametrize("status,completed", [("done", 200), ("deferred", 0)])
def test_explicit_reminder_is_rejected_for_nonpending_task(configured, status, completed):
    client, _ = configured
    head = client.get("/sync", headers=TOKEN).json()
    response = commit(client, head, command(task(status=status, completedAt=completed, reminderAt=1792315200000)))
    assert response.status_code == 422
    assert client.get("/sync", headers=TOKEN).json() == head


def test_v13_backup_roundtrip_and_legacy_history_keep_their_schema(configured):
    client, _ = configured
    old = snapshot(task(plannedDate="2026-10-18"), room_version=12)
    assert client.put("/backup", headers=TOKEN, json=old).status_code == 200
    head = client.get("/sync", headers=TOKEN).json()
    scheduled = task(plannedDate="2026-10-18", reminderAt=1792315200000)
    data = snapshot(scheduled, room_version=13)
    result = sync(client, head, data, [command(scheduled, version=1)])
    assert result.status_code == 200, result.text
    assert client.get("/backup", headers=TOKEN).json() == data
    assert client.get(f"/sync/versions/{head['revision']}", headers=TOKEN).json()["snapshot"] == old
    assert client.get(f"/sync/versions/{result.json()['revision']}", headers=TOKEN).json()["snapshot"] == data


@pytest.mark.parametrize("value", [-1, True, 1.5, "123", None, 2**63])
def test_invalid_reminder_backup_is_rejected_before_any_rows_are_stored(configured, value):
    client, path = configured
    response = client.put("/backup", headers=TOKEN, json=snapshot(task(reminderAt=value), room_version=13))
    assert response.status_code == 422
    assert client.get("/backup", headers=TOKEN).status_code == 404
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT COUNT(*) FROM backup_tasks").fetchone()[0] == 0


def test_actual_v12_backup_and_history_tables_gain_reminder_without_rewriting_rows(configured):
    client, path = configured
    old = snapshot(task(plannedDate="2026-10-18"), room_version=12)
    columns = _columns_for(12)
    with sqlite3.connect(path) as db:
        db.execute("CREATE TABLE backup_meta (id INTEGER PRIMARY KEY, format INTEGER, room_version INTEGER, updated_at TEXT)")
        db.execute("INSERT INTO backup_meta VALUES (1, 1, 12, CURRENT_TIMESTAMP)")
        db.execute("CREATE TABLE sync_head (id INTEGER PRIMARY KEY, store_id TEXT, revision INTEGER)")
        db.execute("INSERT INTO sync_head VALUES (1, 'existing-store', 1)")
        db.execute("CREATE TABLE sync_versions (revision INTEGER PRIMARY KEY, room_version INTEGER, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
        db.execute("INSERT INTO sync_versions (revision, room_version) VALUES (1, 12)")
        for prefix in ("backup_", "sync_version_"):
            for table, fields in columns.items():
                sql_fields = ", ".join(f'"{column}" {kind}' for column, kind in fields)
                position = _position_column(table)
                key = f'"{position}" INTEGER PRIMARY KEY' if prefix == "backup_" else f'revision INTEGER, "{position}" INTEGER'
                db.execute(f'CREATE TABLE "{prefix}{table}" ({key}, {sql_fields})')
                for index, row in enumerate(old["tables"][table]):
                    values = ([index] if prefix == "backup_" else [1, index]) + [row[column] for column, _ in fields]
                    markers = ", ".join("?" for _ in values)
                    db.execute(f'INSERT INTO "{prefix}{table}" VALUES ({markers})', values)
    head = client.get("/sync", headers=TOKEN).json()
    assert head["snapshot"] == old and head["task_versions"] == {"first": 1}
    assert client.get("/sync/versions/1", headers=TOKEN).json()["snapshot"] == old
    with sqlite3.connect(path) as db:
        for prefix in ("backup_", "sync_version_"):
            assert db.execute(f'SELECT reminderAt, plannedDate FROM "{prefix}tasks"').fetchone() == (0, "2026-10-18")
        assert db.execute("SELECT reminderAt FROM task_records").fetchone() == (0,)
