"""Durable Agent tasks, fenced workers, and tool evidence.

This store never executes a tool or changes a business record. A completed run
means its artifact is ready; tool receipts separately describe external effects.
"""
from backend.paths import DATA_DIR

import hashlib
import hmac
import json
import math
import os
import sqlite3
import time
import uuid
from contextlib import contextmanager
from pathlib import Path

from fastapi import HTTPException

from backend.agent.delivery import DeliveryError, delivery_contract, validate_artifact, validate_completion
from backend.agent.delegation import DelegationStore, initialize as initialize_delegations


TERMINAL = {"completed", "failed", "cancelled"}
PENDING = {"prepared", "waiting_approval", "dispatched", "unknown"}
DEFAULT_BUDGET = {"max_steps": 16, "max_tool_calls": 12, "max_duration_seconds": 300}


def _json(value, *, maximum=524288):
    try:
        encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
    except (ValueError, TypeError, RecursionError):
        raise HTTPException(422, "内容必须是有效 JSON") from None
    if maximum is not None and len(encoded.encode("utf-8")) > maximum:
        raise HTTPException(422, "内容超过本次记录大小限制")
    return encoded


def _digest(value):
    return hashlib.sha256(_json(value).encode("utf-8")).hexdigest()


def _text(value, name, maximum=200):
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise HTTPException(422, f"{name} 必须为非空文本，最多 {maximum} 字符")
    return value.strip()


def _integer(value, name, minimum=1, maximum=1000):
    if type(value) is not int or not minimum <= value <= maximum:
        raise HTTPException(422, f"{name} 超出允许范围")
    return value


def _budget(value):
    if not isinstance(value, dict) or set(value) - set(DEFAULT_BUDGET):
        raise HTTPException(422, "运行预算格式不正确")
    result = {**DEFAULT_BUDGET, **value}
    _integer(result["max_steps"], "max_steps")
    _integer(result["max_tool_calls"], "max_tool_calls")
    duration = result["max_duration_seconds"]
    if type(duration) not in (int, float) or not math.isfinite(duration) or not 0 < duration <= 86400:
        raise HTTPException(422, "max_duration_seconds 超出允许范围")
    return result


def _definition(payload):
    if not isinstance(payload, dict):
        raise HTTPException(422, "任务定义必须为对象")
    goal = _text(payload.get("goal"), "goal", 16000)
    constraints = payload.get("constraints", {})
    criteria = payload.get("success_criteria", [])
    if not isinstance(constraints, dict) or not isinstance(criteria, list) or len(criteria) > 64:
        raise HTTPException(422, "约束与完成条件格式不正确")
    criteria = [_text(x, "success_criteria", 2000) for x in criteria]
    _json(constraints)
    try:
        delivery_contract(constraints)
    except DeliveryError as error:
        raise HTTPException(422, {"code": error.code, "message": str(error)}) from None
    return {"goal": goal, "constraints": constraints, "success_criteria": criteria}


class AgentStore(DelegationStore):
    def __init__(self, path=None, clock=time.time):
        self.path = Path(path or os.getenv("ONMYWAY_AGENT_DB", "").strip() or
                         DATA_DIR / "agent.sqlite3")
        self.clock = clock
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connection() as db:
            # SQLite does not consistently apply busy_timeout while switching the
            # journal mode on a brand-new file. Retry initialization only; never
            # retry a business transaction or a tool dispatch here.
            for attempt in range(8):
                try:
                    if db.execute("PRAGMA journal_mode").fetchone()[0] != "wal":
                        db.execute("PRAGMA journal_mode=WAL")
                    break
                except sqlite3.OperationalError as error:
                    if error.sqlite_errorcode not in {sqlite3.SQLITE_BUSY, sqlite3.SQLITE_LOCKED} or attempt == 7:
                        raise
                    time.sleep(0.01 * 2 ** min(attempt, 5))
            db.execute("BEGIN IMMEDIATE")
            version = db.execute("PRAGMA user_version").fetchone()[0]
            if version not in (0, 1, 2, 3):
                raise HTTPException(409, "Agent 数据库版本较新，请升级服务")
            statements = (
                """CREATE TABLE IF NOT EXISTS agent_tasks (
                    agent_task_id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL,
                    message_id TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision>0),
                    goal TEXT NOT NULL, constraints_json TEXT NOT NULL,
                    success_criteria_json TEXT NOT NULL, current_run_id TEXT NOT NULL,
                    created_at REAL NOT NULL, updated_at REAL NOT NULL)""",
                """CREATE TABLE IF NOT EXISTS agent_runs (
                    agent_run_id TEXT PRIMARY KEY, agent_task_id TEXT NOT NULL REFERENCES agent_tasks,
                    revision INTEGER NOT NULL, state TEXT NOT NULL CHECK(state IN
                    ('queued','running','waiting_user','waiting_approval','waiting_tool','paused','completed','failed','cancelled')),
                    reason TEXT, budget_json TEXT NOT NULL, definition_json TEXT NOT NULL,
                    steps_used INTEGER NOT NULL DEFAULT 0, tool_calls_used INTEGER NOT NULL DEFAULT 0,
                    worker_id TEXT, lease_token TEXT, lease_until REAL, started_at REAL,
                    active_elapsed REAL NOT NULL DEFAULT 0, active_since REAL,
                    created_at REAL NOT NULL, updated_at REAL NOT NULL,
                    UNIQUE(agent_task_id,revision))""",
                """CREATE TABLE IF NOT EXISTS agent_requests (
                    request_id TEXT PRIMARY KEY, operation TEXT NOT NULL, digest TEXT NOT NULL,
                    agent_task_id TEXT NOT NULL REFERENCES agent_tasks, response_json TEXT NOT NULL)""",
                """CREATE TABLE IF NOT EXISTS agent_messages (
                    conversation_id TEXT NOT NULL, message_id TEXT NOT NULL, digest TEXT NOT NULL,
                    agent_task_id TEXT NOT NULL REFERENCES agent_tasks, response_json TEXT NOT NULL,
                    PRIMARY KEY(conversation_id,message_id))""",
                """CREATE TABLE IF NOT EXISTS agent_tool_calls (
                    tool_call_id TEXT PRIMARY KEY, agent_run_id TEXT NOT NULL REFERENCES agent_runs,
                    tool_id TEXT NOT NULL, tool_version TEXT NOT NULL, arguments_json TEXT NOT NULL,
                    effect TEXT NOT NULL, requires_approval INTEGER NOT NULL,
                    approved INTEGER NOT NULL DEFAULT 0,
                    state TEXT NOT NULL CHECK(state IN
                    ('prepared','waiting_approval','dispatched','succeeded','failed','unknown','cancelled')),
                    identity_hash TEXT NOT NULL, receipt_token TEXT, result_json TEXT, error_json TEXT,
                    result_hash TEXT, legacy_run_id TEXT UNIQUE,
                    created_at REAL NOT NULL, updated_at REAL NOT NULL)""",
                """CREATE TABLE IF NOT EXISTS agent_artifacts (
                    artifact_id TEXT NOT NULL, version INTEGER NOT NULL,
                    agent_run_id TEXT NOT NULL REFERENCES agent_runs, kind TEXT NOT NULL,
                    content_json TEXT NOT NULL, sources_json TEXT NOT NULL, created_at REAL NOT NULL,
                    PRIMARY KEY(artifact_id,version))""",
                """CREATE TABLE IF NOT EXISTS agent_events (
                    cursor INTEGER PRIMARY KEY AUTOINCREMENT,
                    agent_task_id TEXT NOT NULL REFERENCES agent_tasks,
                    agent_run_id TEXT NOT NULL REFERENCES agent_runs, revision INTEGER NOT NULL,
                    kind TEXT NOT NULL, payload_json TEXT NOT NULL, created_at REAL NOT NULL)""",
                "CREATE INDEX IF NOT EXISTS agent_events_task ON agent_events(agent_task_id,cursor)",
                "CREATE INDEX IF NOT EXISTS agent_calls_run ON agent_tool_calls(agent_run_id,created_at)",
                "CREATE INDEX IF NOT EXISTS agent_runs_ready ON agent_runs(state,lease_until)",
            )
            for statement in statements:
                db.execute(statement)
            columns = {row[1] for row in db.execute("PRAGMA table_info(agent_runs)")}
            if "active_elapsed" not in columns:
                db.execute("ALTER TABLE agent_runs ADD COLUMN active_elapsed REAL NOT NULL DEFAULT 0")
                db.execute("ALTER TABLE agent_runs ADD COLUMN active_since REAL")
                self._migrate_active_time(db)
            initialize_delegations(db)
            db.execute("PRAGMA user_version=3")
            db.commit()

    def _migrate_active_time(self, db):
        """Recover v1 execution intervals from durable events, excluding user waits.

        Old heartbeat extensions were not events. For the latest open interval,
        the durable lease is the last reliable bound; earlier closed intervals
        retain their recorded elapsed time rather than silently reset the budget.
        """
        stops = {"run_waiting_user", "run_paused", "run_completed", "run_failed", "run_cancelled"}
        for row in db.execute("SELECT * FROM agent_runs").fetchall():
            run, elapsed, start = dict(row), 0.0, None
            for event in db.execute("SELECT kind,payload_json,created_at FROM agent_events WHERE agent_run_id=? ORDER BY cursor", (run["agent_run_id"],)):
                kind, payload, at = event["kind"], json.loads(event["payload_json"]), event["created_at"]
                if kind == "run_claimed":
                    if start is not None:
                        elapsed += max(0.0, at - start)
                    start = at
                elif kind in stops or (kind == "tool_prepared" and payload.get("state") == "waiting_approval") or (kind == "tool_result" and payload.get("state") == "unknown"):
                    if start is not None:
                        elapsed += max(0.0, at - start)
                        start = None
            if start is not None:
                bound = run["lease_until"] if run["lease_until"] is not None else run["updated_at"]
                elapsed += max(0.0, min(self.clock(), bound) - start)
            continuing = run["state"] in {"running", "waiting_tool"} and run["lease_until"] is not None and run["lease_until"] > self.clock()
            db.execute("UPDATE agent_runs SET active_elapsed=?,active_since=? WHERE agent_run_id=?",
                       (elapsed, self.clock() if continuing else None, run["agent_run_id"]))
            if run["state"] == "paused" and run["reason"] == "budget_duration" and elapsed < json.loads(run["budget_json"])["max_duration_seconds"]:
                waiting = db.execute("SELECT 1 FROM agent_tool_calls WHERE agent_run_id=? AND state='waiting_approval' LIMIT 1", (run["agent_run_id"],)).fetchone() is not None
                state = "waiting_approval" if waiting else "paused"
                reason = "tool_approval_required" if waiting else "duration_accounting_migrated"
                db.execute("UPDATE agent_runs SET state=?,reason=? WHERE agent_run_id=?", (state, reason, run["agent_run_id"]))
                self._event(db, run, "duration_accounting_migrated", {"active_elapsed": elapsed, "state": state})

    @contextmanager
    def _connection(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            yield db
        finally:
            db.close()

    @contextmanager
    def _transaction(self):
        with self._connection() as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                yield db
                db.commit()
            except BaseException:
                db.rollback()
                raise

    def _row(self, db, table, column, identity):
        row = db.execute(f"SELECT * FROM {table} WHERE {column}=?", (identity,)).fetchone()
        if row is None:
            raise HTTPException(404, "Agent 记录不存在")
        return dict(row)

    def _task(self, db, task_id):
        return self._row(db, "agent_tasks", "agent_task_id", task_id)

    def _run(self, db, run_id):
        return self._row(db, "agent_runs", "agent_run_id", run_id)

    def _call(self, db, call_id):
        return self._row(db, "agent_tool_calls", "tool_call_id", call_id)

    def _decoded(self, row, secrets=False):
        result = {}
        for key, value in row.items():
            if key in {"identity_hash", "result_hash", "definition_json"}:
                continue
            if not secrets and key in {"lease_token", "receipt_token", "worker_id"}:
                continue
            if key.endswith("_json"):
                result[key[:-5]] = json.loads(value) if value is not None else None
            elif key in {"requires_approval", "approved"}:
                result[key] = bool(value)
            else:
                result[key] = value
        if "active_elapsed" in row:
            result["active_used_seconds"] = self._active_used(row)
        return result

    def _event(self, db, run, kind, payload):
        cursor = db.execute("INSERT INTO agent_events(agent_task_id,agent_run_id,revision,kind,payload_json,created_at) VALUES(?,?,?,?,?,?)",
                            (run["agent_task_id"], run["agent_run_id"], run["revision"], kind, _json(payload), self.clock())).lastrowid
        return self._decoded(dict(db.execute("SELECT * FROM agent_events WHERE cursor=?", (cursor,)).fetchone()))

    def _evidence_notes(self, db, task_id):
        # Working evidence outlives the bounded recent-observation window and
        # belongs to the task, while each note retains its source/update revision.
        row = db.execute("""SELECT e.payload_json FROM agent_events e
            JOIN agent_runs r ON e.agent_run_id=r.agent_run_id
            WHERE r.agent_task_id=? AND e.kind='observation:evidence_notes'
            ORDER BY e.cursor DESC LIMIT 1""", (task_id,)).fetchone()
        return json.loads(row[0]) if row else []

    def _snapshot(self, db, task_id, run_id=None):
        task = self._task(db, task_id)
        run = self._run(db, run_id or task["current_run_id"])
        result = self._decoded(task)
        if run_id:
            result.update(json.loads(run["definition_json"]))
            result["revision"] = run["revision"]
        result["run"] = self._decoded(run)
        previous = db.execute("SELECT definition_json,revision FROM agent_runs WHERE agent_task_id=? AND revision<? ORDER BY revision DESC LIMIT 1",
                              (task_id, run["revision"])).fetchone()
        if previous is not None:
            from backend.agent.task_evidence import revision_receipt
            result["task_revision_receipt"] = revision_receipt(
                json.loads(previous["definition_json"]), json.loads(run["definition_json"]),
                previous["revision"], run["revision"])
        result["tool_calls"] = [self._decoded(dict(row)) for row in db.execute(
            "SELECT * FROM agent_tool_calls WHERE agent_run_id=? ORDER BY created_at, rowid", (run["agent_run_id"],))]
        result["artifacts"] = [self._decoded(dict(row)) for row in db.execute(
            "SELECT a.* FROM agent_artifacts a WHERE agent_run_id=? AND version=(SELECT max(version) FROM agent_artifacts b WHERE b.artifact_id=a.artifact_id) ORDER BY created_at,artifact_id LIMIT 100", (run["agent_run_id"],))]
        prior = db.execute("""SELECT a.*,r.agent_task_id,r.revision FROM agent_artifacts a
            JOIN agent_runs r ON a.agent_run_id=r.agent_run_id
            WHERE r.agent_task_id=? AND r.revision=(
                SELECT max(p.revision) FROM agent_runs p WHERE p.agent_task_id=? AND p.revision<?
                AND EXISTS(SELECT 1 FROM agent_artifacts old WHERE old.agent_run_id=p.agent_run_id))
            AND a.version=(SELECT max(b.version) FROM agent_artifacts b WHERE b.artifact_id=a.artifact_id)
            ORDER BY a.created_at,a.artifact_id LIMIT 100""", (task_id, task_id, run["revision"]))
        result["prior_artifacts"] = [{**self._decoded(dict(row)), "reference_only": True} for row in prior]
        result["working_notes"] = [dict(note, reference_only=note["revision"] != run["revision"])
                                   for note in self._evidence_notes(db, task_id)]
        # User clarifications are task constraints, not disposable diagnostic
        # history. The context builder may pause on its budget, but must see them.
        observations = db.execute("""SELECT * FROM agent_events WHERE agent_run_id=?
            AND kind='observation:user_input' UNION ALL
            SELECT * FROM (SELECT * FROM agent_events WHERE agent_run_id=?
                AND kind LIKE 'observation:%' AND kind NOT IN ('observation:user_input','observation:evidence_notes')
                ORDER BY cursor DESC LIMIT 100)
            ORDER BY cursor""", (run["agent_run_id"], run["agent_run_id"])).fetchall()
        result["observations"] = [self._observation(dict(row)) for row in observations]
        result["unresolved_calls"] = self._unresolved_effects(db, task_id)
        result.update(self._delegation_snapshot(db, run))
        return result

    def _observation(self, row):
        value = self._decoded(row)
        value["kind"] = value["kind"].removeprefix("observation:")
        return value

    def get(self, agent_task_id):
        with self._connection() as db:
            db.execute("BEGIN")
            return self._snapshot(db, agent_task_id)

    def get_run(self, agent_run_id):
        with self._connection() as db:
            db.execute("BEGIN")
            run = self._run(db, agent_run_id)
            return self._snapshot(db, run["agent_task_id"], agent_run_id)

    def get_call(self, tool_call_id, *, include_receipt=False):
        """Internal recovery can read the original receipt identity; HTTP cannot."""
        with self._connection() as db:
            return self._decoded(self._call(db, tool_call_id), secrets=include_receipt)

    def get_artifact(self, artifact_id, version=None):
        artifact_id = _text(artifact_id, "artifact_id")
        if version is not None:
            _integer(version, "version", maximum=2147483647)
        with self._connection() as db:
            row = db.execute("""SELECT a.*,r.agent_task_id,r.revision FROM agent_artifacts a
                JOIN agent_runs r ON a.agent_run_id=r.agent_run_id
                WHERE a.artifact_id=? AND (? IS NULL OR a.version=?)
                ORDER BY a.version DESC LIMIT 1""", (artifact_id, version, version)).fetchone()
            if row is None:
                raise HTTPException(404, "Agent 成果不存在")
            return self._decoded(dict(row))

    def list_tasks(self, limit=50):
        _integer(limit, "limit", maximum=100)
        with self._connection() as db:
            db.execute("BEGIN")
            ids = [row[0] for row in db.execute("SELECT agent_task_id FROM agent_tasks ORDER BY updated_at DESC,agent_task_id LIMIT ?", (limit,))]
            return [self._snapshot(db, task_id) for task_id in ids]

    def ready_runs(self, limit=10):
        _integer(limit, "limit", maximum=100)
        with self._connection() as db:
            return [row[0] for row in db.execute("""SELECT r.agent_run_id FROM agent_runs r JOIN agent_tasks t
                ON t.current_run_id=r.agent_run_id WHERE r.state='queued' OR
                (r.state IN ('running','waiting_tool') AND r.lease_until<=?)
                ORDER BY r.created_at,r.agent_run_id LIMIT ?""", (self.clock(), limit))]

    def _replay(self, db, request_id, operation, digest):
        row = db.execute("SELECT * FROM agent_requests WHERE request_id=?", (request_id,)).fetchone()
        if row is None:
            return None
        if row["operation"] != operation or row["digest"] != digest:
            raise HTTPException(409, "请求编号已用于其他内容")
        return json.loads(row["response_json"])

    def _receipt(self, db, request_id, operation, digest, response):
        # A snapshot aggregates already bounded records. Applying the per-record
        # limit again here could roll back a user's cancellation after large reads.
        db.execute("INSERT INTO agent_requests VALUES(?,?,?,?,?)", (request_id, operation, digest, response["agent_task_id"], _json(response, maximum=None)))
        return response

    def _new_run(self, db, task_id, revision, definition, budget):
        run_id, now = str(uuid.uuid4()), self.clock()
        db.execute("""INSERT INTO agent_runs(agent_run_id,agent_task_id,revision,state,budget_json,definition_json,created_at,updated_at)
            VALUES(?,?,?,'queued',?,?,?,?)""", (run_id, task_id, revision, _json(budget), _json(definition), now, now))
        db.execute("UPDATE agent_tasks SET current_run_id=? WHERE agent_task_id=?", (run_id, task_id))
        return self._run(db, run_id)

    def create(self, payload):
        definition = _definition(payload)
        request_id = _text(payload.get("request_id"), "request_id")
        conversation = _text(payload.get("conversation_id"), "conversation_id")
        message = _text(payload.get("message_id"), "message_id")
        budget = _budget(payload.get("budget", {}))
        identity = {**definition, "conversation_id": conversation, "message_id": message, "budget": budget}
        digest = _digest(identity)
        with self._transaction() as db:
            replay = self._replay(db, request_id, "create", digest)
            if replay is not None:
                return replay
            prior = db.execute("SELECT * FROM agent_messages WHERE conversation_id=? AND message_id=?", (conversation, message)).fetchone()
            if prior is not None:
                if prior["digest"] != digest:
                    raise HTTPException(409, "消息编号已用于不同任务内容")
                return self._receipt(db, request_id, "create", digest, json.loads(prior["response_json"]))
            task_id, now = str(uuid.uuid4()), self.clock()
            db.execute("INSERT INTO agent_tasks VALUES(?,?,?,1,?,?,?,'',?,?)", (task_id, conversation, message,
                       definition["goal"], _json(definition["constraints"]), _json(definition["success_criteria"]), now, now))
            run = self._new_run(db, task_id, 1, definition, budget)
            self._event(db, run, "task_created", {"goal": definition["goal"]})
            response = self._snapshot(db, task_id)
            db.execute("INSERT INTO agent_messages VALUES(?,?,?,?,?)", (conversation, message, digest, task_id, _json(response)))
            return self._receipt(db, request_id, "create", digest, response)

    def _command(self, payload, task_id, operation):
        if not isinstance(payload, dict):
            raise HTTPException(422, "任务操作必须为对象")
        request = _text(payload.get("request_id"), "request_id")
        _integer(payload.get("expected_revision"), "expected_revision", maximum=2147483647)
        return request, _digest({"agent_task_id": task_id, "operation": operation,
                                 "body": {key: value for key, value in payload.items() if key != "request_id"}})

    @staticmethod
    def _revision(task, payload):
        if task["revision"] != payload["expected_revision"]:
            raise HTTPException(409, "任务已经修改，请先读取当前版本")

    def _stop(self, db, run, reason):
        self._cancel_children(db, run, reason)
        if run["state"] not in TERMINAL:
            self._settle_active(db, run)
            db.execute("UPDATE agent_runs SET state='cancelled',reason=?,lease_token=NULL,lease_until=NULL,worker_id=NULL,updated_at=? WHERE agent_run_id=?",
                       (reason, self.clock(), run["agent_run_id"]))
            db.execute("UPDATE agent_tool_calls SET state='cancelled',updated_at=? WHERE agent_run_id=? AND state IN ('prepared','waiting_approval')", (self.clock(), run["agent_run_id"]))
            self._event(db, run, "run_cancelled", {"reason": reason})
            self._notify_parent(db, run)

    def revise(self, task_id, payload):
        definition = _definition(payload)
        request, digest = self._command(payload, task_id, "revise")
        with self._transaction() as db:
            replay = self._replay(db, request, "revise", digest)
            if replay is not None:
                return replay
            task = self._task(db, task_id)
            self._revision(task, payload)
            old = self._run(db, task["current_run_id"])
            if self._child_link(db, old["agent_run_id"]):
                raise HTTPException(409, "Revise the coordinator; child revisions require a new delegation")
            budget = _budget(payload.get("budget", json.loads(old["budget_json"])))
            # Already imported, pinned evidence remains readable by the same
            # coordinator as history. This copies no execution authority and
            # no child input grants; late submissions cannot join this snapshot.
            historical_grants = db.execute("SELECT * FROM agent_evidence_grants WHERE parent_run_id=? AND receiver_task_id=?",
                                            (old["agent_run_id"], task_id)).fetchall()
            self._stop(db, old, "superseded")
            revision = task["revision"] + 1
            db.execute("UPDATE agent_tasks SET revision=?,goal=?,constraints_json=?,success_criteria_json=?,updated_at=? WHERE agent_task_id=?",
                       (revision, definition["goal"], _json(definition["constraints"]), _json(definition["success_criteria"]), self.clock(), task_id))
            run = self._new_run(db, task_id, revision, definition, budget)
            for grant in historical_grants:
                db.execute("INSERT INTO agent_evidence_grants VALUES(?,?,?,?,?,?,?)", (run["agent_run_id"], task_id,
                    grant["source_task_id"], grant["kind"], grant["ref"], grant["version"], grant["digest"]))
            self._event(db, run, "task_revised", {"previous_run_id": old["agent_run_id"]})
            return self._receipt(db, request, "revise", digest, self._snapshot(db, task_id))

    def cancel(self, task_id, payload):
        request, digest = self._command(payload, task_id, "cancel")
        with self._transaction() as db:
            replay = self._replay(db, request, "cancel", digest)
            if replay is not None:
                return replay
            task = self._task(db, task_id)
            self._revision(task, payload)
            run = self._run(db, task["current_run_id"])
            if run["state"] in {"completed", "failed"}:
                raise HTTPException(409, "运行已结束，不能改变终态")
            self._stop(db, run, payload.get("reason") or "user_cancelled")
            return self._receipt(db, request, "cancel", digest, self._snapshot(db, task_id))

    def _pending(self, db, run_id, states=PENDING):
        placeholders = ",".join("?" for _ in states)
        return db.execute(f"SELECT 1 FROM agent_tool_calls WHERE agent_run_id=? AND state IN ({placeholders}) LIMIT 1", (run_id, *states)).fetchone() is not None

    def _unresolved_effects(self, db, task_id, except_run=None):
        rows = db.execute("""SELECT c.*,r.revision FROM agent_tool_calls c JOIN agent_runs r
            ON c.agent_run_id=r.agent_run_id WHERE r.agent_task_id=? AND c.effect!='read'
            AND c.state IN ('dispatched','unknown') ORDER BY r.revision,c.created_at""", (task_id,))
        return [self._decoded(dict(row)) for row in rows if row["agent_run_id"] != except_run]

    def _active_used(self, run):
        elapsed = run["active_elapsed"]
        if run["active_since"] is not None:
            bound = min(self.clock(), run["lease_until"]) if run["lease_until"] is not None else self.clock()
            elapsed += max(0.0, bound - run["active_since"])
        return elapsed

    def _settle_active(self, db, run, *, continuing=False):
        elapsed = self._active_used(run)
        start = self.clock() if continuing else None
        db.execute("UPDATE agent_runs SET active_elapsed=?,active_since=? WHERE agent_run_id=?",
                   (elapsed, start, run["agent_run_id"]))
        run.update(active_elapsed=elapsed, active_since=start)

    def _budget_reason(self, run, step=False, call=False, *, db=None):
        if db is not None:
            shared = self._tree_budget_reason(db, run, step=step, call=call)
            if shared:
                return shared
        budget = json.loads(run["budget_json"])
        if self._active_used(run) >= budget["max_duration_seconds"]:
            return "budget_duration"
        if step and run["steps_used"] >= budget["max_steps"]:
            return "budget_steps"
        if call and run["tool_calls_used"] >= budget["max_tool_calls"]:
            return "budget_tool_calls"

    def _pause_error(self, db, run, reason):
        self._settle_active(db, run)
        db.execute("UPDATE agent_runs SET state='paused',reason=?,lease_token=NULL,lease_until=NULL,worker_id=NULL,updated_at=? WHERE agent_run_id=?",
                   (reason, self.clock(), run["agent_run_id"]))
        self._event(db, run, "run_paused", {"reason": reason})
        self._notify_parent(db, run)
        # Pausing is an intentional state transition even though the command is refused.
        db.commit()
        raise HTTPException(409, reason)

    def resume(self, task_id, payload):
        request, digest = self._command(payload, task_id, "resume")
        with self._transaction() as db:
            replay = self._replay(db, request, "resume", digest)
            if replay is not None:
                return replay
            task = self._task(db, task_id)
            self._revision(task, payload)
            run = self._run(db, task["current_run_id"])
            if run["state"] not in {"waiting_user", "paused"}:
                raise HTTPException(409, "该运行不能继续，请读取当前状态")
            if run["reason"] == "waiting_children":
                raise HTTPException(409, "Child results wake this task automatically")
            if self._pending(db, run["agent_run_id"], {"unknown", "dispatched", "waiting_approval"}):
                raise HTTPException(409, "仍有未核实或未批准的调用")
            if self._unresolved_effects(db, task_id, except_run=run["agent_run_id"]):
                raise HTTPException(409, "原任务修订还有未核实的外部操作")
            reason = self._budget_reason(run, step=True, db=db)
            if reason or (run["reason"] or "").startswith("budget_"):
                raise HTTPException(409, "原运行预算已耗尽，继续不能重置预算")
            db.execute("UPDATE agent_runs SET state='queued',reason=NULL,lease_token=NULL,lease_until=NULL,worker_id=NULL,updated_at=? WHERE agent_run_id=?", (self.clock(), run["agent_run_id"]))
            db.execute("UPDATE agent_delegations SET notified_state=NULL WHERE child_run_id=? AND status='active'", (run["agent_run_id"],))
            if payload.get("reason"):
                self._event(db, run, "observation:user_input", {"text": _text(payload["reason"], "reason", 16000)})
            self._event(db, run, "run_resumed", {})
            return self._receipt(db, request, "resume", digest, self._snapshot(db, task_id))

    def _current(self, db, run):
        task = self._task(db, run["agent_task_id"])
        if task["current_run_id"] != run["agent_run_id"] or task["revision"] != run["revision"] or run["state"] in TERMINAL:
            raise HTTPException(409, "运行已取消、结束或被新任务版本替代")
        self._delegation_current(db, run)

    def _owned(self, db, run_id, lease_token):
        run = self._run(db, run_id)
        self._current(db, run)
        if run["state"] not in {"running", "waiting_tool"} or not isinstance(lease_token, str) or not run["lease_token"] or not hmac.compare_digest(run["lease_token"], lease_token) or run["lease_until"] <= self.clock():
            raise HTTPException(409, "运行租约失效，请重新核对状态")
        return run

    def claim(self, agent_run_id, worker_id, lease_seconds=30):
        worker_id = _text(worker_id, "worker_id")
        if type(lease_seconds) not in (int, float) or not math.isfinite(lease_seconds) or not 0 < lease_seconds <= 300:
            raise HTTPException(422, "租约时长超出范围")
        with self._transaction() as db:
            run = self._run(db, agent_run_id)
            self._current(db, run)
            if run["state"] not in {"queued", "running", "waiting_tool"}:
                raise HTTPException(409, "运行正在等待用户或核实")
            if run["lease_token"] and run["lease_until"] > self.clock():
                raise HTTPException(409, "运行已由其他执行者认领")
            self._settle_active(db, run)
            if self._pending(db, agent_run_id, {"dispatched", "unknown"}):
                db.execute("UPDATE agent_tool_calls SET state='unknown',updated_at=? WHERE agent_run_id=? AND state='dispatched'", (self.clock(), agent_run_id))
                self._pause_error(db, run, "unresolved_tool_call")
            if self._unresolved_effects(db, run["agent_task_id"], except_run=agent_run_id):
                self._pause_error(db, run, "unresolved_prior_effect")
            reason = self._budget_reason(run, db=db)
            if reason:
                self._pause_error(db, run, reason)
            now, token = self.clock(), str(uuid.uuid4())
            db.execute("UPDATE agent_runs SET state='running',reason=NULL,worker_id=?,lease_token=?,lease_until=?,started_at=coalesce(started_at,?),active_since=?,updated_at=? WHERE agent_run_id=?",
                       (worker_id, token, now + lease_seconds, now, now, now, agent_run_id))
            self._event(db, run, "run_claimed", {})
            return self._decoded(self._run(db, agent_run_id), secrets=True)

    def heartbeat(self, agent_run_id, lease_token, lease_seconds=30):
        if type(lease_seconds) not in (int, float) or not math.isfinite(lease_seconds) or not 0 < lease_seconds <= 300:
            raise HTTPException(422, "租约时长超出范围")
        with self._transaction() as db:
            run = self._owned(db, agent_run_id, lease_token)
            reason = self._budget_reason(run, db=db)
            if reason:
                self._pause_error(db, run, reason)
            self._settle_active(db, run, continuing=True)
            db.execute("UPDATE agent_runs SET lease_until=?,updated_at=? WHERE agent_run_id=?", (self.clock() + lease_seconds, self.clock(), agent_run_id))
            return self._decoded(self._run(db, agent_run_id), secrets=True)

    def reserve_step(self, agent_run_id, lease_token):
        with self._transaction() as db:
            run = self._owned(db, agent_run_id, lease_token)
            if self._pending(db, agent_run_id):
                raise HTTPException(409, "先处理已有工具调用，再继续决策")
            reason = self._budget_reason(run, step=True, db=db)
            if reason:
                self._pause_error(db, run, reason)
            db.execute("UPDATE agent_runs SET steps_used=steps_used+1,updated_at=? WHERE agent_run_id=?", (self.clock(), agent_run_id))
            self._event(db, run, "decision_reserved", {"step": run["steps_used"] + 1})
            return self._decoded(self._run(db, agent_run_id))

    def prepare_call(self, agent_run_id, lease_token, tool_id, tool_version, arguments, *, effect="read", requires_approval=False, tool_call_id=None, legacy_run_id=None):
        tool_id, tool_version = _text(tool_id, "tool_id"), _text(tool_version, "tool_version")
        if not isinstance(arguments, dict) or effect not in {"read", "write", "device"} or type(requires_approval) is not bool:
            raise HTTPException(422, "工具调用参数格式不正确")
        needs_approval = requires_approval or effect != "read"
        call_id = _text(tool_call_id, "tool_call_id") if tool_call_id else str(uuid.uuid4())
        identity = _digest({"run": agent_run_id, "tool": tool_id, "version": tool_version, "arguments": arguments,
                            "effect": effect, "approval": needs_approval, "legacy_run_id": legacy_run_id})
        with self._transaction() as db:
            run = self._owned(db, agent_run_id, lease_token)
            self._delegation_tool(db, run, tool_id, effect)
            prior = db.execute("SELECT * FROM agent_tool_calls WHERE tool_call_id=?", (call_id,)).fetchone()
            if prior is not None:
                if prior["identity_hash"] != identity:
                    raise HTTPException(409, "工具调用编号已用于其他内容")
                return self._decoded(dict(prior))
            reason = self._budget_reason(run, call=True, db=db)
            if reason:
                self._pause_error(db, run, reason)
            state = "waiting_approval" if needs_approval else "prepared"
            now = self.clock()
            try:
                db.execute("""INSERT INTO agent_tool_calls(tool_call_id,agent_run_id,tool_id,tool_version,arguments_json,effect,requires_approval,state,identity_hash,legacy_run_id,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""", (call_id, agent_run_id, tool_id, tool_version, _json(arguments), effect, int(needs_approval), state, identity, legacy_run_id, now, now))
            except sqlite3.IntegrityError:
                raise HTTPException(409, "旧能力运行已关联其他工具调用") from None
            db.execute("UPDATE agent_runs SET tool_calls_used=tool_calls_used+1,updated_at=? WHERE agent_run_id=?", (now, agent_run_id))
            if needs_approval:
                self._settle_active(db, run)
                db.execute("UPDATE agent_runs SET state='waiting_approval',reason='tool_approval_required',lease_token=NULL,lease_until=NULL,worker_id=NULL WHERE agent_run_id=?", (agent_run_id,))
                self._notify_parent(db, run)
            self._event(db, run, "tool_prepared", {"tool_call_id": call_id, "tool_id": tool_id, "state": state})
            return self._decoded(self._call(db, call_id))

    def approve_call(self, task_id, call_id, payload):
        request, digest = self._command({**payload, "tool_call_id": call_id}, task_id, "approve")
        with self._transaction() as db:
            replay = self._replay(db, request, "approve", digest)
            if replay is not None:
                return replay
            task = self._task(db, task_id)
            self._revision(task, payload)
            call = self._call(db, call_id)
            run = self._run(db, call["agent_run_id"])
            self._current(db, run)
            if run["agent_task_id"] != task_id or run["state"] != "waiting_approval" or call["state"] != "waiting_approval":
                raise HTTPException(409, "该确认已失效或不属于当前任务")
            reason = self._budget_reason(run, db=db)
            if reason:
                self._pause_error(db, run, reason)
            db.execute("UPDATE agent_tool_calls SET state='prepared',approved=1,updated_at=? WHERE tool_call_id=?", (self.clock(), call_id))
            db.execute("UPDATE agent_runs SET state='queued',reason=NULL,updated_at=? WHERE agent_run_id=?", (self.clock(), run["agent_run_id"]))
            self._event(db, run, "tool_approved", {"tool_call_id": call_id, "request_id": request})
            return self._receipt(db, request, "approve", digest, self._snapshot(db, task_id))

    def dispatch_call(self, tool_call_id, lease_token):
        with self._transaction() as db:
            call = self._call(db, tool_call_id)
            run = self._owned(db, call["agent_run_id"], lease_token)
            if call["state"] != "prepared":
                raise HTTPException(409, "该调用不能再次派发")
            reason = self._budget_reason(run, db=db)
            if reason:
                self._pause_error(db, run, reason)
            token = str(uuid.uuid4())
            db.execute("UPDATE agent_tool_calls SET state='dispatched',receipt_token=?,updated_at=? WHERE tool_call_id=?", (token, self.clock(), tool_call_id))
            db.execute("UPDATE agent_runs SET state='waiting_tool',updated_at=? WHERE agent_run_id=?", (self.clock(), run["agent_run_id"]))
            self._event(db, run, "tool_dispatched", {"tool_call_id": tool_call_id})
            return self._decoded(self._call(db, tool_call_id), secrets=True)

    def reject_prepared(self, tool_call_id, lease_token, error):
        """Reject a contract before dispatch, without inventing an external receipt."""
        encoded = _json(error)
        with self._transaction() as db:
            call = self._call(db, tool_call_id)
            run = self._owned(db, call["agent_run_id"], lease_token)
            if call["state"] != "prepared":
                raise HTTPException(409, "仅明确尚未派发的调用可以本地拒绝")
            db.execute("UPDATE agent_tool_calls SET state='failed',error_json=?,updated_at=? WHERE tool_call_id=?", (encoded, self.clock(), tool_call_id))
            self._event(db, run, "tool_rejected", {"tool_call_id": tool_call_id, "error": error})
            return self._decoded(self._call(db, tool_call_id))

    def record_result(self, tool_call_id, receipt_token, state, result=None, error=None):
        if state not in {"succeeded", "failed", "unknown"}:
            raise HTTPException(422, "工具结果状态不正确")
        digest = _digest({"state": state, "result": result, "error": error})
        with self._transaction() as db:
            call = self._call(db, tool_call_id)
            if not isinstance(receipt_token, str) or not call["receipt_token"] or not hmac.compare_digest(call["receipt_token"], receipt_token):
                raise HTTPException(409, "工具回执身份不匹配")
            if call["result_hash"] == digest:
                return self._decoded(call)
            if call["state"] not in {"dispatched", "unknown"}:
                raise HTTPException(409, "工具终态不能由迟到结果改写")
            if call["state"] == "unknown" and state == "unknown" and call["result_hash"]:
                raise HTTPException(409, "未知结果已记录，请核实而非覆盖")
            db.execute("UPDATE agent_tool_calls SET state=?,result_json=?,error_json=?,result_hash=?,updated_at=? WHERE tool_call_id=?",
                       (state, _json(result), _json(error), digest, self.clock(), tool_call_id))
            run = self._run(db, call["agent_run_id"])
            task = self._task(db, run["agent_task_id"])
            if task["current_run_id"] == run["agent_run_id"] and run["state"] not in TERMINAL:
                if state == "unknown":
                    self._settle_active(db, run)
                    db.execute("UPDATE agent_runs SET state='paused',reason='unresolved_tool_call',lease_token=NULL,lease_until=NULL,worker_id=NULL,updated_at=? WHERE agent_run_id=?", (self.clock(), run["agent_run_id"]))
                elif run["state"] == "waiting_tool" and not self._pending(db, run["agent_run_id"], {"dispatched", "unknown"}):
                    db.execute("UPDATE agent_runs SET state='running',updated_at=? WHERE agent_run_id=?", (self.clock(), run["agent_run_id"]))
            self._event(db, run, "tool_result", {"tool_call_id": tool_call_id, "state": state})
            self._notify_parent(db, run)
            return self._decoded(self._call(db, tool_call_id))

    def publish_artifact(self, agent_run_id, lease_token, kind, content, *, artifact_id=None, expected_version=None, sources=None):
        kind = _text(kind, "kind", 100)
        if expected_version is not None:
            _integer(expected_version, "expected_version", maximum=2147483647)
            if not artifact_id:
                raise HTTPException(422, "指定成果版本时必须提供 artifact_id")
        if sources is None:
            sources = []
        if not isinstance(sources, list):
            raise HTTPException(422, "成果来源必须为列表")
        with self._transaction() as db:
            run = self._owned(db, agent_run_id, lease_token)
            try:
                if self._child_link(db, agent_run_id) and not artifact_id and db.execute(
                        "SELECT 1 FROM agent_artifacts WHERE agent_run_id=? LIMIT 1", (agent_run_id,)).fetchone():
                    raise DeliveryError("artifact_contract_mismatch", "A child submits one versioned artifact; update its ID and version.")
                constraints = json.loads(run["definition_json"])["constraints"]
                validate_artifact(constraints, kind, content)
                maximum = delivery_contract(constraints).get("max_artifacts")
                if not artifact_id and maximum is not None:
                    existing = db.execute("SELECT artifact_id,max(version) AS version FROM agent_artifacts WHERE agent_run_id=? GROUP BY artifact_id",
                                          (agent_run_id,)).fetchall()
                    if len(existing) >= maximum:
                        targets = [{"artifact_id": item["artifact_id"], "expected_version": item["version"]} for item in existing]
                        raise DeliveryError("artifact_contract_mismatch",
                            "已达到成果数量上限；修改时沿用artifact_id和expected_version，不要新建。当前成果：" + _json(targets))
            except DeliveryError as error:
                raise HTTPException(422, {"code": error.code, "message": str(error)}) from None
            if self._pending(db, agent_run_id):
                raise HTTPException(409, "仍有未解决的工具调用，不能发布成果")
            reason = self._budget_reason(run, db=db)
            if reason:
                self._pause_error(db, run, reason)
            identity, version = artifact_id or str(uuid.uuid4()), 1
            if artifact_id:
                previous = db.execute("SELECT * FROM agent_artifacts WHERE artifact_id=? ORDER BY version DESC LIMIT 1", (artifact_id,)).fetchone()
                if previous is None or previous["agent_run_id"] != agent_run_id:
                    raise HTTPException(409, "成果不属于当前运行")
                if expected_version is not None and previous["version"] != expected_version:
                    raise HTTPException(409, "成果版本已变化，请读取最新版本后重新判断")
                version = previous["version"] + 1
            db.execute("INSERT INTO agent_artifacts VALUES(?,?,?,?,?,?,?)", (identity, version, agent_run_id, kind, _json(content), _json(sources), self.clock()))
            if self._child_link(db, agent_run_id):
                artifact = self._decoded(dict(db.execute("SELECT * FROM agent_artifacts WHERE artifact_id=? AND version=?", (identity, version)).fetchone()))
                envelope = {**artifact, "agent_task_id": run["agent_task_id"], "revision": run["revision"]}
                if len(_json(envelope).encode("utf-8")) > 2000:
                    raise HTTPException(422, {"code": "artifact_contract_mismatch", "message":
                        "Child artifact including metadata must fit 2000 UTF-8 bytes for a complete coordinator read; shorten content and cite only relevant sources."})
            self._event(db, run, "artifact_published", {"artifact_id": identity, "version": version})
            return self._decoded(dict(db.execute("SELECT * FROM agent_artifacts WHERE artifact_id=? AND version=?", (identity, version)).fetchone()))

    def transition(self, agent_run_id, lease_token, state, reason=None):
        if state not in {"waiting_user", "paused", "completed", "failed"}:
            raise HTTPException(422, "不允许该运行状态转换")
        reason = _text(reason, "reason", 16000)
        with self._transaction() as db:
            run = self._owned(db, agent_run_id, lease_token)
            if state in {"waiting_user", "completed"} and self._pending(db, agent_run_id):
                raise HTTPException(409, "先解决未决调用")
            if state == "completed" and db.execute("SELECT 1 FROM agent_artifacts WHERE agent_run_id=? LIMIT 1", (agent_run_id,)).fetchone() is None:
                raise HTTPException(409, "没有当前成果，不能标记任务完成")
            if state == "completed":
                if self._pending_children(db, agent_run_id):
                    raise HTTPException(422, "Review or reject every child before completing the coordinator")
                try:
                    validate_completion(json.loads(run["definition_json"])["constraints"])
                except DeliveryError as error:
                    raise HTTPException(422, {"code": error.code, "message": str(error)}) from None
                budget_reason = self._budget_reason(run, db=db)
                if budget_reason:
                    self._pause_error(db, run, budget_reason)
            self._settle_active(db, run)
            db.execute("UPDATE agent_runs SET state=?,reason=?,lease_token=NULL,lease_until=NULL,worker_id=NULL,updated_at=? WHERE agent_run_id=?", (state, reason, self.clock(), agent_run_id))
            if state == "failed":
                self._cancel_children(db, run, "parent_failed")
                db.execute("UPDATE agent_tool_calls SET state='cancelled',updated_at=? WHERE agent_run_id=? AND state IN ('prepared','waiting_approval')", (self.clock(), agent_run_id))
            self._event(db, run, "run_" + state, {"reason": reason})
            self._notify_parent(db, run)
            return self._decoded(self._run(db, agent_run_id))

    def update_evidence_notes(self, run_id, lease_token, upserts, delete_ids):
        """Atomically retain bounded, runtime-validated quotes for this task."""
        if not isinstance(upserts, list) or not isinstance(delete_ids, list) or any(not isinstance(note, dict) for note in upserts):
            raise HTTPException(422, "工作证据更新必须包含摘录列表和删除编号列表")
        identities = [_text(note.get("id"), "摘录编号") for note in upserts]
        deleted = [_text(identity, "删除编号") for identity in delete_ids]
        if len(set(identities)) != len(identities) or len(set(deleted)) != len(deleted) or set(identities) & set(deleted):
            raise HTTPException(422, "摘录编号不能重复，也不能在同次更新中同时保留和删除")
        with self._transaction() as db:
            run = self._owned(db, run_id, lease_token)
            notes = {note["id"]: note for note in self._evidence_notes(db, run["agent_task_id"])
                     if note["id"] not in deleted}
            for identity, note in zip(identities, upserts):
                stored = {**note, "id": identity, "revision": run["revision"]}
                stored.pop("reference_only", None)
                notes[identity] = stored
            final = list(notes.values())
            if len(final) > 8:
                raise HTTPException(422, "工作证据最多保留8条，请删除不再需要的摘录")
            if len(_json(final, maximum=None).encode("utf-8")) > 2400:
                raise HTTPException(422, "工作证据及来源信息最多2400字节，请缩短或删除摘录")
            self._event(db, run, "observation:evidence_notes", final)
            return [dict(note, reference_only=note["revision"] != run["revision"]) for note in final]

    def record_observation(self, agent_run_id, lease_token, kind, payload):
        kind = _text(kind, "kind", 100)
        with self._transaction() as db:
            run = self._owned(db, agent_run_id, lease_token)
            event = self._event(db, run, "observation:" + kind, payload)
            event["kind"] = kind
            return event

    def events(self, agent_task_id, after=0, limit=100):
        _integer(after, "after", minimum=0, maximum=9223372036854775807)
        _integer(limit, "limit", maximum=1000)
        with self._connection() as db:
            db.execute("BEGIN")
            self._task(db, agent_task_id)
            rows = db.execute("SELECT * FROM agent_events WHERE agent_task_id=? AND cursor>? ORDER BY cursor LIMIT ?", (agent_task_id, after, limit + 1)).fetchall()
            values = [self._decoded(dict(row)) for row in rows[:limit]]
            return {"events": values, "next_cursor": values[-1]["cursor"] if values else after, "has_more": len(rows) > limit}
