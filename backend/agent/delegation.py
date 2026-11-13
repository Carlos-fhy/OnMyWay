"""One-level durable delegation with explicit evidence grants and a shared budget.

The first slice delegates read-only work. Side effects stay with the coordinator
and its existing approval/receipt protocol. No child inherits ambient memory.
"""
import json
import uuid

from fastapi import HTTPException


READERS = {"agent.read_result", "agent.read_artifact"}


def initialize(db):
    db.execute("""CREATE TABLE IF NOT EXISTS agent_delegations (
        parent_run_id TEXT NOT NULL REFERENCES agent_runs,
        delegation_key TEXT NOT NULL, child_task_id TEXT NOT NULL UNIQUE REFERENCES agent_tasks,
        child_run_id TEXT NOT NULL UNIQUE REFERENCES agent_runs, digest TEXT NOT NULL,
        allowed_tools_json TEXT NOT NULL, inputs_json TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN ('active','submitted','accepted','rejected','cancelled')),
        outputs_json TEXT NOT NULL DEFAULT '[]', review_reason TEXT,
        notified_state TEXT, created_at REAL NOT NULL, updated_at REAL NOT NULL,
        PRIMARY KEY(parent_run_id,delegation_key))""")
    db.execute("""CREATE TABLE IF NOT EXISTS agent_evidence_grants (
        parent_run_id TEXT NOT NULL REFERENCES agent_runs,
        receiver_task_id TEXT NOT NULL REFERENCES agent_tasks,
        source_task_id TEXT NOT NULL REFERENCES agent_tasks,
        kind TEXT NOT NULL, ref TEXT NOT NULL, version INTEGER NOT NULL,
        digest TEXT NOT NULL,
        PRIMARY KEY(parent_run_id,receiver_task_id,kind,ref,version))""")


class DelegationStore:
    def _child_link(self, db, run_id):
        row = db.execute("SELECT * FROM agent_delegations WHERE child_run_id=?", (run_id,)).fetchone()
        return dict(row) if row else None

    def _delegation_view(self, db, row):
        row = dict(row)
        child = self._run(db, row["child_run_id"])
        definition = json.loads(child["definition_json"])
        return {"delegation_key": row["delegation_key"], "parent_run_id": row["parent_run_id"],
                "goal": definition["goal"], "constraints": definition["constraints"], "success_criteria": definition["success_criteria"],
                "child_task_id": row["child_task_id"], "child_run_id": row["child_run_id"],
                "status": row["status"], "child_state": child["state"], "child_reason": child["reason"],
                "allowed_tools": json.loads(row["allowed_tools_json"]),
                "inputs": json.loads(row["inputs_json"]), "outputs": json.loads(row["outputs_json"]),
                "review_reason": row["review_reason"]}

    def _delegation_snapshot(self, db, run):
        rows = db.execute("SELECT * FROM agent_delegations WHERE parent_run_id=? ORDER BY created_at,delegation_key",
                          (run["agent_run_id"],)).fetchall()
        link = self._child_link(db, run["agent_run_id"])
        result = {}
        if rows:
            result["delegations"] = [self._delegation_view(db, row) for row in rows]
        if link:
            result["delegation"] = self._delegation_view(db, link)
            parent = self._run(db, link["parent_run_id"])
            result["coordinator_requirements"] = {**json.loads(parent["definition_json"]),
                "agent_run_id": parent["agent_run_id"], "revision": parent["revision"]}
        if rows or link:
            root_id = link["parent_run_id"] if link else run["agent_run_id"]
            root, members = self._tree_runs(db, root_id)
            result["shared_budget"] = {"root_run_id": root_id, "budget": json.loads(root["budget_json"]),
                "steps_used": sum(r["steps_used"] for r in members),
                "tool_calls_used": sum(r["tool_calls_used"] for r in members),
                "active_used_seconds": sum(self._active_used(r) for r in members),
                **self._coordinator_reserve(db, root_id)}
        return result

    def _coordinator_reserve(self, db, root_id):
        pending = db.execute("SELECT count(*) FROM agent_delegations WHERE parent_run_id=? AND status IN ('active','submitted')", (root_id,)).fetchone()[0]
        return {"coordinator_step_reserve": 4 + 3 * pending, "coordinator_tool_reserve": pending}

    def _tree_runs(self, db, root_id):
        root = self._run(db, root_id)
        children = [dict(row) for row in db.execute("""SELECT r.* FROM agent_runs r JOIN agent_delegations d
            ON d.child_run_id=r.agent_run_id WHERE d.parent_run_id=?""", (root_id,))]
        return root, [root, *children]

    def _tree_budget_reason(self, db, run, *, step=False, call=False):
        link = self._child_link(db, run["agent_run_id"])
        root_id = link["parent_run_id"] if link else run["agent_run_id"]
        root, members = self._tree_runs(db, root_id)
        if len(members) == 1:
            return None
        budget = json.loads(root["budget_json"])
        if sum(self._active_used(r) for r in members) >= budget["max_duration_seconds"]:
            return "budget_tree_duration"
        # Reserve discovery/read/accept per pending child plus publication,
        # finish and two final reviews. A bounded single artifact limits this tail.
        reserve = self._coordinator_reserve(db, root_id)
        step_limit = budget["max_steps"] - (reserve["coordinator_step_reserve"] if link else 0)
        if step and sum(r["steps_used"] for r in members) >= step_limit:
            return "budget_tree_steps"
        call_limit = budget["max_tool_calls"] - (reserve["coordinator_tool_reserve"] if link else 0)
        if call and sum(r["tool_calls_used"] for r in members) >= call_limit:
            return "budget_tree_tool_calls"

    def _source(self, db, kind, ref, version=0):
        from backend.agent.store import _digest
        if not isinstance(ref, str) or not ref or len(ref) > 200 or type(version) is not int:
            raise HTTPException(422, "Invalid evidence identity")
        if kind == "result":
            call = self._call(db, ref)
            if call["state"] != "succeeded" or not call["result_hash"]:
                raise HTTPException(422, "Only successful receipts can be shared")
            owner = self._run(db, call["agent_run_id"])["agent_task_id"]
            return owner, call["result_hash"]
        if kind == "artifact" and type(version) is int and version > 0:
            row = db.execute("SELECT * FROM agent_artifacts WHERE artifact_id=? AND version=?", (ref, version)).fetchone()
            if row:
                owner = self._run(db, row["agent_run_id"])["agent_task_id"]
                return owner, _digest({"content": row["content_json"], "sources": row["sources_json"]})
        raise HTTPException(422, "Evidence requires a result ID or an explicit artifact version")

    def _grant(self, db, root_id, receiver, kind, ref, version=0):
        owner, digest = self._source(db, kind, ref, version)
        db.execute("INSERT OR IGNORE INTO agent_evidence_grants VALUES(?,?,?,?,?,?,?)",
                   (root_id, receiver, owner, kind, ref, version, digest))

    def _share_evidence(self, db, root_id, sender, receiver, kind, ref, version=0, visited=None):
        """Pin only a selected source and its explicit provenance closure."""
        from backend.agent.decision import reader_source
        visited = set() if visited is None else visited
        key = (kind, ref, version)
        if key in visited:
            return
        if len(visited) >= 100:
            raise HTTPException(422, "Selected provenance exceeds the 100-source sharing limit")
        visited.add(key)
        owner, digest = self._source(db, kind, ref, version)
        if owner != sender and not self._granted(db, sender, kind, ref, version, owner, digest):
            raise HTTPException(422, "Selected provenance is outside the sender's evidence scope")
        self._grant(db, root_id, receiver, kind, ref, version)
        if kind == "artifact":
            row = db.execute("SELECT sources_json FROM agent_artifacts WHERE artifact_id=? AND version=?", (ref, version)).fetchone()
            for source in json.loads(row["sources_json"]):
                if isinstance(source, dict) and source.get("tool_call_id"):
                    self._share_evidence(db, root_id, sender, receiver, "result", source["tool_call_id"], visited=visited)
        else:
            call = self._call(db, ref)
            reference = reader_source(self._decoded(call)) if call["tool_id"] in READERS else None
            if reference:
                self._share_evidence(db, root_id, sender, receiver, reference["source_kind"], reference["source_ref"],
                                     reference.get("version", 0), visited)

    def evidence_allowed(self, receiver_task_id, kind, ref, version=0):
        if not receiver_task_id:
            return False
        with self._connection() as db:
            try:
                owner, digest = self._source(db, kind, ref, version)
            except HTTPException:
                return False
            return self._granted(db, receiver_task_id, kind, ref, version, owner, digest)

    def _granted(self, db, receiver_task_id, kind, ref, version, owner, digest):
        rows = db.execute("""SELECT g.*,r.state,t.current_run_id FROM agent_evidence_grants g
            JOIN agent_runs r ON r.agent_run_id=g.parent_run_id
            JOIN agent_tasks t ON t.agent_task_id=r.agent_task_id
            WHERE receiver_task_id=? AND kind=? AND ref=? AND version=?""",
            (receiver_task_id, kind, ref, version)).fetchall()
        for grant in rows:
            if (grant["current_run_id"] == grant["parent_run_id"] and grant["state"] not in {"cancelled", "failed"}
                    and owner == grant["source_task_id"] and digest == grant["digest"]):
                receiver = self._task(db, receiver_task_id)
                if self._run(db, receiver["current_run_id"])["state"] != "cancelled":
                    return True
        return False

    def delegate(self, run_id, token, payload):
        from backend.agent.store import _budget, _definition, _digest, _json, _text
        definition = _definition(payload)
        key = _text(payload.get("delegation_key"), "delegation_key", 80)
        allowed = payload.get("allowed_tools", [])
        inputs = payload.get("input_refs", [])
        if (not isinstance(allowed, list) or len(allowed) > 12 or
                any(not isinstance(x, str) or not 3 <= len(x) <= 200 for x in allowed) or len(set(allowed)) != len(allowed)):
            raise HTTPException(422, "Delegation needs at most 12 distinct tool IDs")
        if not isinstance(inputs, list) or len(inputs) > 8:
            raise HTTPException(422, "Delegation accepts at most eight explicit evidence references")
        _json(definition, maximum=12000)
        budget = _budget(payload.get("budget", {"max_steps": 8, "max_tool_calls": 4, "max_duration_seconds": 90}))
        digest = _digest({"definition": definition, "allowed": allowed, "inputs": inputs, "budget": budget})
        with self._transaction() as db:
            parent = self._owned(db, run_id, token)
            if self._child_link(db, run_id):
                raise HTTPException(422, "This deployment permits one delegation level")
            prior = db.execute("SELECT * FROM agent_delegations WHERE parent_run_id=? AND delegation_key=?", (run_id, key)).fetchone()
            if prior:
                if prior["digest"] != digest:
                    raise HTTPException(409, "Delegation key already belongs to different content")
                return self._delegation_view(db, prior)
            if db.execute("SELECT count(*) FROM agent_delegations WHERE parent_run_id=?", (run_id,)).fetchone()[0] >= 4:
                raise HTTPException(422, "At most four child attempts per parent revision")
            if self._pending(db, run_id) or self._unresolved_effects(db, parent["agent_task_id"]):
                raise HTTPException(409, "Resolve pending effects before delegation")
            reason = self._budget_reason(parent, step=True, db=db)
            if reason:
                self._pause_error(db, parent, reason)
            root_budget = json.loads(parent["budget_json"])
            _, members = self._tree_runs(db, run_id)
            pending = self._coordinator_reserve(db, run_id)["coordinator_tool_reserve"] + 1
            remaining_steps = root_budget["max_steps"] - sum(item["steps_used"] for item in members)
            remaining_calls = root_budget["max_tool_calls"] - sum(item["tool_calls_used"] for item in members)
            if remaining_steps < 4 + 3 * pending + 2 or remaining_calls < pending + bool(allowed or inputs):
                raise HTTPException(422, "Insufficient shared budget for child work and coordinator acceptance; execute directly or reduce scope")
            budget = {name: min(value, root_budget[name]) for name, value in budget.items()}
            pinned = []
            for item in inputs:
                if not isinstance(item, dict) or set(item) - {"kind", "ref", "version"}:
                    raise HTTPException(422, "Invalid evidence reference")
                kind, ref, version = item.get("kind"), item.get("ref"), item.get("version", 0)
                owner, source_hash = self._source(db, kind, ref, version)
                if owner != parent["agent_task_id"] and not self._granted(db, parent["agent_task_id"], kind, ref, version, owner, source_hash):
                    raise HTTPException(422, "Input evidence must belong to the coordinator")
                pinned.append({"kind": kind, "ref": ref, "version": version, "source_sha256": source_hash})
            child_id, now = str(uuid.uuid4()), self.clock()
            source_task = self._task(db, parent["agent_task_id"])
            # Child goal is model-derived, not a new user grant. The snapshot
            # separately carries the immutable coordinator definition.
            db.execute("INSERT INTO agent_tasks VALUES(?,?,?,1,?,?,?,'',?,?)", (child_id,
                source_task["conversation_id"], "delegation:" + str(uuid.uuid4()), definition["goal"],
                _json(definition["constraints"]), _json(definition["success_criteria"]), now, now))
            child = self._new_run(db, child_id, 1, definition, budget)
            db.execute("""INSERT INTO agent_delegations(parent_run_id,delegation_key,child_task_id,child_run_id,
                digest,allowed_tools_json,inputs_json,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'active',?,?)""",
                (run_id, key, child_id, child["agent_run_id"], digest, _json(allowed), _json(pinned), now, now))
            for item in pinned:
                self._share_evidence(db, run_id, parent["agent_task_id"], child_id, item["kind"], item["ref"], item["version"])
            self._event(db, child, "task_delegated", {"parent_run_id": run_id, "delegation_key": key})
            self._event(db, parent, "child_created", {"child_task_id": child_id, "delegation_key": key})
            return self._delegation_view(db, self._child_link(db, child["agent_run_id"]))

    def wait_for_children(self, run_id, token):
        with self._transaction() as db:
            parent = self._owned(db, run_id, token)
            rows = db.execute("SELECT * FROM agent_delegations WHERE parent_run_id=? AND status IN ('active','submitted')", (run_id,)).fetchall()
            if not rows:
                raise HTTPException(422, "No pending children to wait for")
            if self._pending(db, run_id):
                raise HTTPException(409, "Resolve tool calls before waiting")
            # A child may finish between the model decision and this transaction.
            # Record an observation instead of sleeping past a result.
            if not any(row["status"] == "active" and self._run(db, row["child_run_id"])["state"] in
                       {"queued", "running", "waiting_tool"} for row in rows):
                self._event(db, parent, "observation:children_ready", {"message": "Review child results or blockers"})
                return False
            self._settle_active(db, parent)
            db.execute("""UPDATE agent_runs SET state='paused',reason='waiting_children',worker_id=NULL,
                lease_token=NULL,lease_until=NULL,updated_at=? WHERE agent_run_id=?""", (self.clock(), run_id))
            self._event(db, parent, "run_waiting_children", {})
            return True

    def _notify_parent(self, db, child):
        from backend.agent.store import _json
        link = self._child_link(db, child["agent_run_id"])
        if not link or link["status"] != "active":
            return
        current = self._run(db, child["agent_run_id"])
        state = current["state"]
        if state in {"queued", "running", "waiting_tool"} or link["notified_state"] == state:
            return
        parent = self._run(db, link["parent_run_id"])
        task = self._task(db, parent["agent_task_id"])
        if task["current_run_id"] != parent["agent_run_id"] or parent["state"] in {"completed", "failed", "cancelled"}:
            return
        outputs = []
        if state == "completed":
            artifacts = db.execute("""SELECT * FROM agent_artifacts a WHERE agent_run_id=? AND
                version=(SELECT max(version) FROM agent_artifacts b WHERE b.artifact_id=a.artifact_id)""", (child["agent_run_id"],)).fetchall()
            for artifact in artifacts:
                self._share_evidence(db, parent["agent_run_id"], child["agent_task_id"], parent["agent_task_id"],
                                     "artifact", artifact["artifact_id"], artifact["version"])
                outputs.append({"artifact_id": artifact["artifact_id"], "version": artifact["version"], "kind": artifact["kind"]})
            db.execute("UPDATE agent_delegations SET status='submitted',outputs_json=? WHERE child_run_id=?",
                       (_json(outputs), child["agent_run_id"]))
        db.execute("UPDATE agent_delegations SET notified_state=?,updated_at=? WHERE child_run_id=?",
                   (state, self.clock(), child["agent_run_id"]))
        self._event(db, parent, "observation:child_result", {"delegation_key": link["delegation_key"], "state": state,
            "reason": current["reason"], "outputs": outputs, "untrusted": True})
        if parent["state"] == "paused" and parent["reason"] == "waiting_children":
            db.execute("UPDATE agent_runs SET state='queued',reason=NULL,updated_at=? WHERE agent_run_id=?", (self.clock(), parent["agent_run_id"]))

    def review_delegation(self, run_id, token, key, verdict, reason):
        from backend.agent.store import _text
        reason = _text(reason, "review_reason", 2000)
        if verdict not in {"accept", "reject"}:
            raise HTTPException(422, "Review must accept or reject the submitted work")
        with self._transaction() as db:
            parent = self._owned(db, run_id, token)
            row = db.execute("SELECT * FROM agent_delegations WHERE parent_run_id=? AND delegation_key=?", (run_id, key)).fetchone()
            if not row:
                raise HTTPException(422, "Unknown delegation")
            expected = "accepted" if verdict == "accept" else "rejected"
            if row["status"] == expected:
                return self._delegation_view(db, row)
            if row["status"] not in {"active", "submitted"} or (verdict == "accept" and row["status"] != "submitted"):
                raise HTTPException(422, "Only completed submissions can be accepted")
            if verdict == "accept":
                from backend.agent.decision import reader_source
                reads = db.execute("SELECT * FROM agent_tool_calls WHERE agent_run_id=? AND tool_id='agent.read_artifact' AND state='succeeded'", (run_id,)).fetchall()
                seen = {(source["source_ref"], source.get("version")) for call in reads
                        if (source := reader_source(self._decoded(dict(call)))) and source.get("source_kind") == "artifact"
                        and self._complete_artifact_read(json.loads(call["result_json"]))}
                if any((item["artifact_id"], item["version"]) not in seen for item in json.loads(row["outputs_json"])):
                    raise HTTPException(422, "Read each submitted artifact version before accepting")
            db.execute("UPDATE agent_delegations SET status=?,review_reason=?,updated_at=? WHERE parent_run_id=? AND delegation_key=?",
                       (expected, reason, self.clock(), run_id, key))
            if verdict == "reject":
                self._stop(db, self._run(db, row["child_run_id"]), "delegation_rejected")
            self._event(db, parent, "observation:child_reviewed", {"delegation_key": key, "verdict": verdict, "reason": reason})
            return self._delegation_view(db, db.execute("SELECT * FROM agent_delegations WHERE parent_run_id=? AND delegation_key=?", (run_id, key)).fetchone())

    @staticmethod
    def _complete_artifact_read(result):
        page = result.get("data", {}) if isinstance(result, dict) else {}
        return (page.get("offset") == 0 and page.get("has_more") is False
                and isinstance(page.get("text"), str) and bool(page["text"])
                and page.get("next_offset") == page.get("total_chars") == len(page["text"]))

    def _cancel_children(self, db, parent, reason):
        rows = db.execute("SELECT * FROM agent_delegations WHERE parent_run_id=?", (parent["agent_run_id"],)).fetchall()
        db.execute("UPDATE agent_delegations SET status='cancelled',updated_at=? WHERE parent_run_id=?", (self.clock(), parent["agent_run_id"]))
        db.execute("DELETE FROM agent_evidence_grants WHERE parent_run_id=?", (parent["agent_run_id"],))
        for row in rows:
            self._stop(db, self._run(db, row["child_run_id"]), reason)

    def _delegation_current(self, db, run):
        link = self._child_link(db, run["agent_run_id"])
        if link:
            parent = self._run(db, link["parent_run_id"])
            if (link["status"] != "active" or parent["state"] in {"completed", "failed", "cancelled"}
                    or self._task(db, parent["agent_task_id"])["current_run_id"] != parent["agent_run_id"]):
                raise HTTPException(409, "Delegation is no longer current")

    def _delegation_tool(self, db, run, tool_id, effect):
        link = self._child_link(db, run["agent_run_id"])
        if link and (effect != "read" or tool_id not in set(json.loads(link["allowed_tools_json"])) | READERS):
            raise HTTPException(422, "Child tools are restricted to the delegated read-only scope")

    def _pending_children(self, db, run_id):
        return db.execute("SELECT 1 FROM agent_delegations WHERE parent_run_id=? AND status IN ('active','submitted') LIMIT 1", (run_id,)).fetchone() is not None
