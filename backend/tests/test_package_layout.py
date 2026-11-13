"""Package moves must preserve existing databases and offline evaluation inputs."""
import importlib
from pathlib import Path
import sqlite3

import pytest

from backend.paths import BACKEND_ROOT, DATA_DIR, PROJECT_ROOT


DATABASES = [
    ("backend.agent.store", "AgentStore", "ONMYWAY_AGENT_DB", "agent.sqlite3"),
    ("backend.data_services.backup", "_connect", "ONMYWAY_BACKUP_DB", "backups.sqlite3"),
    ("backend.execution.events", "_open", "ONMYWAY_EVENT_DB", "events.sqlite3"),
    ("backend.assistant.harness", "_open", "ONMYWAY_HARNESS_DB", "harness.db"),
    ("backend.memory.personal", "_open", "ONMYWAY_MEMORY_DB", "personal_memory.sqlite3"),
]


class ConnectionIntercepted(Exception):
    pass


@pytest.fixture
def attempted_connections(monkeypatch):
    attempts = []

    def intercept(database, *args, **kwargs):
        attempts.append(Path(database))
        raise ConnectionIntercepted

    # Stop before SQLite opens any real database or creates a new directory.
    monkeypatch.setattr(sqlite3, "connect", intercept)
    monkeypatch.setattr(Path, "mkdir", lambda *args, **kwargs: None)
    return attempts


@pytest.mark.parametrize("module_name,entrypoint,environment,filename", DATABASES)
@pytest.mark.parametrize("configured", [False, True], ids=["default", "environment-override"])
def test_storage_location_survives_package_move(
    module_name, entrypoint, environment, filename, configured,
    monkeypatch, tmp_path, attempted_connections,
):
    module = importlib.import_module(module_name)
    original_backend = Path(__file__).resolve().parents[1]
    assert BACKEND_ROOT == original_backend
    assert PROJECT_ROOT == original_backend.parent
    assert DATA_DIR == original_backend / "data"

    if configured:
        expected = tmp_path / "configured-store" / filename
        monkeypatch.setenv(environment, f"  {expected}  ")
    else:
        expected = original_backend / "data" / filename
        monkeypatch.delenv(environment, raising=False)

    with pytest.raises(ConnectionIntercepted):
        getattr(module, entrypoint)()

    assert attempted_connections == [expected]


def test_agent_explicit_database_path_takes_precedence(
    monkeypatch, tmp_path, attempted_connections,
):
    from backend.agent.store import AgentStore

    monkeypatch.setenv("ONMYWAY_AGENT_DB", str(tmp_path / "environment.sqlite3"))
    expected = tmp_path / "explicit.sqlite3"
    with pytest.raises(ConnectionIntercepted):
        AgentStore(expected)

    assert attempted_connections == [expected]


@pytest.mark.parametrize(
    "module_name,filename,required_case",
    [
        ("backend.evaluation.replay", "harness_cases.json", "cancel_print_keep_parcel_dorm"),
        ("backend.evaluation.model", "model_cases.json", "cancel_print_direct"),
    ],
)
def test_evaluation_loads_default_cases_from_any_working_directory(
    module_name, filename, required_case, monkeypatch, tmp_path,
):
    module = importlib.import_module(module_name)
    monkeypatch.chdir(tmp_path)

    suite = module.load_cases()

    assert module.DEFAULT_CASES == BACKEND_ROOT / "evaluation" / "cases" / filename
    assert required_case in {case["id"] for case in suite["cases"]}
