"""Check lossless context references independently of the context builder."""
from copy import deepcopy


def assert_revision_receipt_visible(data, expected):
    actual = deepcopy(data["task_revision_receipt"])
    for change in actual["changes"]:
        if "after_ref" in change:
            node = data
            for segment in change.pop("after_ref").split("/")[1:]:
                key = segment.replace("~1", "/").replace("~0", "~")
                node = node[int(key)] if isinstance(node, list) else node[key]
            change["after"] = node
        for side in ("before", "after"):
            if side in change or side + "_omitted" in change:
                change.setdefault(side + "_present", True)
    assert actual == expected
