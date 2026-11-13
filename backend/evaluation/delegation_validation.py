"""Check a recorded delegation chain without executing tools or judging prose."""
from datetime import datetime
import json
import math


READERS = {"agent.read_result", "agent.read_artifact"}


def _object(value):
    return value if isinstance(value, dict) else {}


def _rows(value):
    return [item for item in value if isinstance(item, dict)] if isinstance(value, list) else []


def _time(value):
    if type(value) in (int, float) and math.isfinite(value):
        return value
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed.timestamp() if parsed.tzinfo else None
    except (AttributeError, TypeError, ValueError, OverflowError):
        return None


def _before(first, last):
    first, last = _time(first), _time(last)
    return first is not None and last is not None and first <= last


def _complete_read(call, artifact, owner):
    args = _object(call.get("arguments"))
    page = _object(_object(call.get("result")).get("data"))
    version = artifact.get("version")
    text = page.get("text")
    if not (call.get("state") == "succeeded" and call.get("tool_id") == "agent.read_artifact"
            and args.get("artifact_id") == page.get("source_ref") == artifact.get("artifact_id")
            and type(version) is int and version > 0 and type(args.get("version")) is int
            and args["version"] == page.get("version") == version and page.get("source_kind") == "artifact"
            and page.get("offset") == 0 and page.get("has_more") is False and isinstance(text, str) and text
            and page.get("next_offset") == page.get("total_chars") == len(text)):
        return False
    try:
        # The reader serializes get_artifact(), which adds its owning task and
        # revision to the snapshot's artifact row. Compare the entire document.
        expected = {**artifact, "agent_task_id": owner.get("agent_task_id"), "revision": owner.get("revision")}
        return json.loads(text) == expected
    except (TypeError, ValueError):
        return False


def validate_delegation_phase(phase):
    """Audit one complete EvaluationSession phase, including child snapshots.

    `passed` proves the recorded execution/provenance chain only. MCP fixture
    sources remain labelled separately from official sources, and semantic
    review always remains not_run. Counts and feature flags are not evidence.
    """
    checks, failures = {}, []
    evidence = {"delegations": [], "external_sources": [], "official_sources_only": False}

    def check(field, condition, expected, actual=None):
        checks[field] = bool(condition)
        if not condition:
            failures.append({"field": field, "expected": expected, "actual": actual})
        return bool(condition)

    def result(status=None):
        # Source classification is a supplementary live-evaluation gate. A
        # localhost fixture can pass the execution-chain audit without it.
        checks["official_sources_only"] = evidence["official_sources_only"]
        return {"status": status or ("failed" if failures else "passed"), "scope": "delegation_execution_facts",
                "semantic_review": "not_run", "checks": checks, "failures": failures, "evidence": evidence}

    phase = _object(phase)
    root = _object(phase.get("snapshot"))
    run = _object(root.get("run"))
    root_id, task_id, revision = run.get("agent_run_id"), root.get("agent_task_id"), root.get("revision")
    valid_root = (isinstance(root_id, str) and bool(root_id) and isinstance(task_id, str) and bool(task_id)
                  and type(revision) is int and revision > 0 and root_id == root.get("current_run_id") == phase.get("agent_run_id")
                  and task_id == phase.get("agent_task_id") == run.get("agent_task_id")
                  and revision == run.get("revision") == phase.get("revision"))
    if not check("parent.identity", valid_root, "current parent task/run/revision agree"):
        return result()
    evidence.update(parent_task_id=task_id, parent_run_id=root_id, revision=revision)
    links = _rows(root.get("delegations", []))
    child_records = _rows(phase.get("children", []))
    check("tree.shape", isinstance(root.get("delegations", []), list) and isinstance(phase.get("children", []), list)
          and len(links) == len(root.get("delegations", [])) and len(child_records) == len(phase.get("children", [])), "lists of link and child objects")
    if not links and not child_records and not failures:
        checks["delegation.exercised"] = False
        evidence["reason"] = "No durable child delegation was exercised in this phase."
        return result("not_exercised")

    children = [_object(item.get("snapshot")) for item in child_records]
    members = [root, *children]
    by_run = {_object(member.get("run")).get("agent_run_id"): member for member in members}
    links_by_run = {link.get("child_run_id"): link for link in links}
    check("tree.membership", bool(links) and len(by_run) == len(members) and None not in by_run
          and len(links_by_run) == len(links) and set(links_by_run) == set(by_run) - {root_id}
          and len({member.get("agent_task_id") for member in members}) == len(members), "one distinct snapshot for every current child")
    events = {root_id: _rows(phase.get("events"))}
    for member, record in zip(children, child_records):
        identity = _object(member.get("run")).get("agent_run_id")
        events[identity] = _rows(record.get("events"))
        link = links_by_run.get(identity, {})
        coordinator = _object(member.get("coordinator_requirements"))
        check(f"child[{identity}].identity", identity == member.get("current_run_id")
              and member.get("agent_task_id") == link.get("child_task_id") == _object(member.get("run")).get("agent_task_id")
              and member.get("revision") == _object(member.get("run")).get("revision")
              and link.get("parent_run_id") == coordinator.get("agent_run_id") == root_id
              and coordinator.get("revision") == revision and _object(member.get("delegation")) == link
              and not member.get("delegations"), "independent one-level child of the current parent revision")
    if failures:
        return result()

    def event_rows(identity, kind):
        member = by_run[identity]
        return [event for event in events[identity] if event.get("kind") == kind
                and event.get("agent_run_id") == identity and event.get("agent_task_id") == member.get("agent_task_id")
                and event.get("revision") == member.get("revision")]

    models = _rows(phase.get("model_calls"))
    model_ids = [item.get("index") for item in models]
    check("models.identity", bool(models) and len(model_ids) == len(set(model_ids)) and all(
        type(item.get("index")) is int and item["index"] > 0 and item.get("agent_run_id") in by_run
        and item.get("agent_task_id") == by_run[item["agent_run_id"]].get("agent_task_id")
        and item.get("revision") == by_run[item["agent_run_id"]].get("revision") for item in models), "unique model records owned by this task tree")

    def decisions(identity, action):
        return [item for item in models if item.get("agent_run_id") == identity and item.get("purpose") == "decision"
                and item.get("status") == "parsed" and _object(item.get("action")).get("action") == action]

    rows = [call for member in members for call in _rows(member.get("tool_calls"))]
    calls = {call.get("tool_call_id"): call for call in rows}
    check("calls.identity", len(calls) == len(rows) and None not in calls and all(
        call.get("agent_run_id") == _object(member.get("run")).get("agent_run_id")
        for member in members for call in _rows(member.get("tool_calls"))), "unique calls owned by their snapshot run")
    check("calls.read_only_resolved", all(call.get("effect") == "read" and call.get("state") in {"succeeded", "failed", "cancelled"}
          for call in rows) and not any(member.get("unresolved_calls") for member in members), "no writes, inflight or unknown effects")
    for identity, link in links_by_run.items():
        scope = link.get("allowed_tools", [])
        requested = [item["action"].get("tool_id") for item in decisions(identity, "call_tool")]
        check(f"child[{identity}].scope", isinstance(scope, list) and all(isinstance(item, str) for item in scope)
              and all(tool in [*scope, *READERS] for tool in requested)
              and all(call.get("tool_id") in [*scope, *READERS] for call in rows if call.get("agent_run_id") == identity),
              "child calls and attempted calls stay within its explicit read scope")

    dispatches = _rows(phase.get("tool_dispatches"))
    dispatch_ids = [item.get("tool_call_id") for item in dispatches]
    check("dispatches.unique", len(dispatch_ids) == len(set(dispatch_ids)), "one dispatch per tool call")
    sent_ids = {call["tool_call_id"] for call in rows if call.get("state") == "succeeded"}
    sent_ids.update(_object(event.get("payload")).get("tool_call_id") for identity in by_run for event in event_rows(identity, "tool_dispatched"))
    check("dispatches.complete", set(dispatch_ids) == sent_ids, "dispatches match all persisted sent calls")
    matched_models = set()
    for dispatch in dispatches:
        call = calls.get(dispatch.get("tool_call_id"), {})
        identity, call_id = call.get("agent_run_id"), call.get("tool_call_id")
        matching = [item for item in decisions(identity, "call_tool") if item["action"].get("tool_id") == call.get("tool_id")
                    and item["action"].get("arguments") == call.get("arguments") and _before(item.get("at"), call.get("created_at"))
                    and item.get("index") not in matched_models]
        if matching:
            matched_models.add(max(matching, key=lambda item: item["index"])["index"])
        check(f"dispatch[{call_id}].identity", bool(call) and all(dispatch.get(key) == call.get(key)
              for key in ("agent_run_id", "tool_id", "tool_version")) and bool(matching)
              and (call.get("state") != "succeeded" or dispatch.get("status") == "returned"),
              "dispatch, model arguments and persisted call identity agree")
    for call in rows:
        if call.get("state") != "succeeded":
            continue
        payload = _object(call.get("result"))
        check(f"receipt[{call['tool_call_id']}].identity", payload.get("tool_id") == call.get("tool_id")
              and payload.get("version") == call.get("tool_version") and bool(payload.get("source"))
              and _object(payload.get("data")).get("isError") is not True, "successful result matches the registered call")

    artifacts = {(artifact.get("artifact_id"), artifact.get("version")): artifact
                 for member in members for artifact in _rows(member.get("artifacts"))}
    allowed_child_calls, accepted_artifacts = set(), set()

    def provenance(artifact, receiver, visited=None):
        visited = set() if visited is None else visited
        originals = set()
        for source in _rows(artifact.get("sources")):
            ref = source.get("tool_call_id")
            call = calls.get(ref, {})
            if ref in visited or len(visited) >= 100 or call.get("state") != "succeeded" or source.get("tool_id") != call.get("tool_id"):
                return None
            if source.get("tool_version", call.get("tool_version")) != call.get("tool_version"):
                return None
            owner = call.get("agent_run_id")
            explicit = _rows(links_by_run.get(receiver, {}).get("inputs"))
            shared = any(item.get("kind") == "result" and item.get("ref") == ref for item in explicit)
            if owner != receiver and not shared and not (receiver == root_id and ref in allowed_child_calls):
                return None
            if call.get("tool_id") not in READERS:
                originals.add(ref)
                continue
            page = _object(_object(call.get("result")).get("data"))
            if page.get("source_kind") == "artifact":
                key = (page.get("source_ref"), page.get("version"))
                target = artifacts.get(key)
                if target is None or (target.get("agent_run_id") != receiver and key not in accepted_artifacts):
                    return None
                if source.get("source_kind") != "artifact" or (source.get("artifact_id"), source.get("version")) != key:
                    return None
                values = provenance(target, target.get("agent_run_id"), visited | {ref})
            elif page.get("source_kind") == "result":
                target = calls.get(page.get("source_ref"), {})
                values = provenance({"sources": [{"tool_call_id": page.get("source_ref"), "tool_id": target.get("tool_id")}]}, receiver, visited | {ref})
            else:
                return None
            if values is None:
                return None
            originals.update(values)
        return originals

    def publication_events(member, artifact):
        identity = member["current_run_id"]
        return [event for event in event_rows(identity, "artifact_published") if _object(event.get("payload")).get("artifact_id") == artifact.get("artifact_id")
                and _object(event.get("payload")).get("version") == artifact.get("version")
                and _before(artifact.get("created_at"), event.get("created_at"))
                and any(item["action"].get("kind") == artifact.get("kind")
                        and {"title": item["action"].get("title"), "content": item["action"].get("content")} == artifact.get("content")
                        and _before(item.get("at"), event.get("created_at")) for item in decisions(identity, "publish_artifact"))]

    accepted_times = []
    for identity, link in links_by_run.items():
        child = by_run[identity]
        label = f"child[{identity}]"
        check(label + ".accepted", link.get("status") == "accepted" and child["run"].get("state") == link.get("child_state") == "completed", "completed child explicitly accepted")
        external = {call["tool_call_id"] for call in _rows(child.get("tool_calls")) if call.get("state") == "succeeded"
                    and str(_object(call.get("result")).get("source", "")).startswith("mcp:") and call.get("tool_id") not in READERS}
        check(label + ".external_query", bool(external), "at least one successful child MCP read")
        created = [item for item in decisions(root_id, "delegate_task") if item["action"].get("delegation_key") == link.get("delegation_key")
                   and item["action"].get("allowed_tools") == link.get("allowed_tools")]
        check(label + ".created", bool(created) and any(_object(event.get("payload")).get("child_task_id") == child["agent_task_id"]
              and _object(event.get("payload")).get("delegation_key") == link.get("delegation_key") for event in event_rows(root_id, "child_created")), "model delegation plus durable creation")
        outputs = _rows(link.get("outputs"))
        submitted = [event for event in event_rows(root_id, "observation:child_result") if _object(event.get("payload")).get("delegation_key") == link.get("delegation_key")
                     and _object(event.get("payload")).get("state") == "completed" and _object(event.get("payload")).get("outputs") == outputs]
        accepted = [event for event in event_rows(root_id, "observation:child_reviewed") if _object(event.get("payload")).get("delegation_key") == link.get("delegation_key")
                    and _object(event.get("payload")).get("verdict") == "accept"
                    and any(item["action"].get("delegation_key") == link.get("delegation_key") and item["action"].get("verdict") == "accept"
                            and item["action"].get("reason") == _object(event.get("payload")).get("reason")
                            and _before(item.get("at"), event.get("created_at")) for item in decisions(root_id, "review_delegation"))]
        check(label + ".submission_acceptance", bool(outputs and submitted and accepted)
              and any(_before(sent.get("created_at"), review.get("created_at")) for sent in submitted for review in accepted), "submission followed by recorded model acceptance")
        referenced, read_ids = set(), []
        for output in outputs:
            key = (output.get("artifact_id"), output.get("version"))
            artifact = artifacts.get(key, {})
            publications = publication_events(child, artifact)
            valid = artifact.get("agent_run_id") == identity and bool(publications)
            refs = provenance(artifact, identity) if valid else None
            check(label + f".output[{key[0]}]", valid and refs is not None and bool(refs & external), "submitted child artifact cites its own successful MCP query")
            reads = [call for call in _rows(root.get("tool_calls")) if _complete_read(call, artifact, child)
                     and any(_before(call.get("updated_at"), event.get("created_at")) for event in accepted)
                     and any(_before(event.get("created_at"), call.get("created_at")) for event in submitted)]
            check(label + f".read[{key[0]}]", bool(reads), "parent reads the complete exact version after submission and before acceptance")
            read_ids.extend(call["tool_call_id"] for call in reads)
            if refs:
                referenced.update(refs)
            if valid and reads and accepted and link.get("status") == "accepted":
                accepted_artifacts.add(key)
        allowed_child_calls.update(referenced)
        accepted_times.extend(event.get("created_at") for event in accepted)
        evidence["delegations"].append({"delegation_key": link.get("delegation_key"), "child_task_id": child["agent_task_id"],
            "child_run_id": identity, "external_call_ids": sorted(external), "outputs": outputs, "parent_read_call_ids": read_ids})
    evidence["external_sources"] = sorted({_object(calls[ref].get("result")).get("source")
        for item in evidence["delegations"] for ref in item["external_call_ids"]})
    evidence["official_sources_only"] = bool(evidence["external_sources"]) and all(source.startswith("mcp:official:") for source in evidence["external_sources"])

    for identity, member in by_run.items():
        finishes = decisions(identity, "finish")
        check(f"run[{identity}].finish", bool(finishes) and any(
            item["action"].get("summary") == member["run"].get("reason") and _before(item.get("at"), event.get("created_at"))
            for item in finishes for event in event_rows(identity, "run_completed")), "model finish with a persisted completion event")
    root_artifacts = _rows(root.get("artifacts"))
    check("parent.completed", phase.get("execution_status") == run.get("state") == "completed" and bool(root_artifacts), "completed parent with a deliverable")
    contributed = set()
    for artifact in root_artifacts:
        publications = publication_events(root, artifact)
        refs = provenance(artifact, root_id)
        check(f"parent.artifact[{artifact.get('artifact_id')}]", artifact.get("agent_run_id") == root_id and refs is not None
              and bool(publications) and bool(accepted_times) and any(
                  all(_before(stamp, event.get("created_at")) for stamp in accepted_times)
                  and any(_before(event.get("created_at"), finished.get("created_at")) for finished in event_rows(root_id, "run_completed"))
                  for event in publications), "legal sourced publication after acceptance and before completion")
        if refs:
            contributed.update(refs)
    check("parent.child_provenance", bool(contributed & allowed_child_calls), "parent deliverable references the accepted child's source")
    return result()
