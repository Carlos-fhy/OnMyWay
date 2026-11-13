"""Runtime support for domain-neutral delegated work."""
from fastapi import HTTPException

from backend.agent.delegation import READERS
from backend.agent.tools import ToolError


class DelegationRuntime:
    def _catalog_for(self, snapshot):
        link = snapshot.get("delegation")
        return self.catalog.restricted(set(link["allowed_tools"]) | READERS) if link else self.catalog

    def _delegation_action(self, snapshot, token, action):
        """Return True when the worker must yield, False to continue deciding."""
        run_id = snapshot["run"]["agent_run_id"]
        if (not self.delegation_enabled and action.action == "delegate_task") or snapshot.get("delegation"):
            self._observation(run_id, token, "decision_error", code="delegation_unavailable",
                              message="Only the enabled coordinator can delegate or review children.")
            return False
        try:
            if action.action == "delegate_task":
                for tool_id in action.allowed_tools:
                    tool = self.catalog.describe(tool_id)
                    if tool["effect"] != "read" or tool["executor"] != "server":
                        raise ToolError("delegation_scope_rejected", "Children can only use delegated server read tools.")
                payload = action.model_dump(exclude={"action", "evidence_notes"})
                self.store.delegate(run_id, token, payload)
            elif action.action == "wait_children":
                return self.store.wait_for_children(run_id, token)
            else:
                self.store.review_delegation(run_id, token, action.delegation_key, action.verdict, action.reason)
        except ToolError as error:
            self._observation(run_id, token, "decision_error", code=error.code, message=str(error))
        except HTTPException as error:
            if error.status_code not in {409, 422} or self.store.get_run(run_id)["run"]["state"] != "running":
                raise
            # User revisions/expired leases are still fenced by record_observation.
            self._observation(run_id, token, "decision_error", code="delegation_rejected", message=str(error.detail))
        return False

    @staticmethod
    def _unreviewed_children(snapshot):
        return any(item["status"] in {"active", "submitted"} for item in snapshot.get("delegations", []))
