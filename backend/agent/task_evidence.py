"""Bounded receipts for accepted task definitions, never external side effects."""
import hashlib
import json


def _json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def revision_receipt(previous, current, from_revision, to_revision):
    def digest(value):
        return hashlib.sha256(_json(value).encode("utf-8")).hexdigest()

    changes = []
    def visit(before, after, path, before_present=True, after_present=True):
        if before_present and after_present and _json(before) == _json(after):
            return
        if before_present and after_present and isinstance(before, dict) and isinstance(after, dict):
            for key in sorted(before.keys() | after.keys()):
                visit(before.get(key), after.get(key), path + "/" + key.replace("~", "~0").replace("/", "~1"),
                      key in before, key in after)
            return
        item = {"path": path, "before_present": before_present, "after_present": after_present}
        for name, value, present in (("before", before, before_present), ("after", after, after_present)):
            if not present:
                continue
            if len(_json(value).encode("utf-8")) <= 512:
                item[name] = value
            else:
                item[name + "_omitted"] = {"sha256": digest(value), "bytes": len(_json(value).encode("utf-8"))}
        changes.append(item)

    visit(previous, current, "")
    receipt = {"scope": "task_definition_only", "from_revision": from_revision, "to_revision": to_revision,
               "before_sha256": digest(previous), "after_sha256": digest(current), "changes": [],
               "omitted_changes": len(changes)}
    for change in changes:
        receipt["changes"].append(change)
        receipt["omitted_changes"] -= 1
        if len(_json(receipt).encode("utf-8")) > 2400:
            receipt["changes"].pop()
            receipt["omitted_changes"] += 1
    return receipt
