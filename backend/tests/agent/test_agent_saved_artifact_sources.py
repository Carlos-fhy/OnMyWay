"""Saved deliverables retain bounded original inputs before optional schemas."""
from copy import deepcopy
from datetime import datetime
import json

import pytest

from backend.agent import decision
from backend.paths import BACKEND_ROOT


FIXTURE = BACKEND_ROOT / "tests/fixtures/agent_saved_artifact_sources_batch8.json"


def frozen_case(index):
    return json.loads(FIXTURE.read_text(encoding="utf-8"))["cases"][index]


def rebuild(case, monkeypatch):
    class FrozenClock(datetime):
        @classmethod
        def now(cls, tz=None):
            return datetime.fromisoformat(case["evaluated_at"])

    monkeypatch.setattr(decision, "datetime", FrozenClock)
    context = decision.build_context(
        case["snapshot"], case["tool_descriptors"], namespace_index=case["namespace_index"],
        active_tool_ids=case["active_tool_ids"], max_input_tokens=case["max_input_tokens"],
    )
    assert context.estimated_input_tokens <= case["max_input_tokens"] == 12000
    return context, json.loads(context.messages[-1]["content"])


@pytest.mark.parametrize("index", [0, 1], ids=["model4-before-question", "model6-after-question-review"])
def test_frozen_saved_artifact_keeps_complete_small_source_and_readers(index, monkeypatch):
    case = frozen_case(index)
    before = deepcopy(case)
    _, data = rebuild(case, monkeypatch)
    original = case["snapshot"]["tool_calls"][0]
    source = next(item for item in data["tool_results"] if item["tool_call_id"] == original["tool_call_id"])
    assert source.get("result") == original["result"]
    assert source["truncated"] is False and source["result_ref"] == original["tool_call_id"]
    assert source["state"] == original["state"] == "succeeded"
    saved = case["snapshot"]["artifacts"][0]
    artifact = data["artifacts"][0]
    assert artifact["content"] == saved["content"]["content"]
    assert artifact["artifact_id"] == saved["artifact_id"] and artifact["version"] == saved["version"]
    shown = {item["tool_id"]: item for item in data["tools"]}
    for identity in ("agent.read_result", "agent.read_artifact"):
        assert shown[identity] == next(item for item in case["tool_descriptors"] if item["tool_id"] == identity)
    assert data["task"]["constraints"] == case["snapshot"]["constraints"]
    assert data["observations"] == [
        {key: item[key] for key in ("kind", "payload", "created_at")}
        for item in case["snapshot"]["observations"]
    ]
    assert case == before


@pytest.mark.parametrize("path_key", ["source_path", "decoded_path"])
def test_nonempty_array_with_zero_displayed_items_is_not_an_empty_array(path_key):
    source = {"a/b~c": [{"value": 1}, {"value": 2}], "empty": []}
    before = deepcopy(source)
    view = decision._result_view(source, array_items=0, path_key=path_key)
    marker = view["a/b~c"]
    assert marker["truncated"] is True and marker["type"] == "array" and marker["length"] == 2
    assert marker[path_key] == "/a~1b~0c"
    assert "items" not in marker
    assert view["empty"] == [] and source == before


def test_partial_array_still_exposes_actual_preview_entries():
    source = ["first", "second"]
    view = decision._result_view(source, array_items=1)
    assert view == {"truncated": True, "type": "array", "length": 2, "source_path": "", "items": ["first"]}


def test_saved_artifact_source_priority_does_not_depend_on_business_tool_or_field_names(monkeypatch):
    text = json.dumps(frozen_case(1), ensure_ascii=False)
    for old, new in (("onmyway.read_tasks", "ledger.read_records"), ("task_versions", "record_versions"),
                     ('"tasks"', '"records"'), ('"parcel"', '"record-a"'), ('"print"', '"record-b"')):
        text = text.replace(old, new)
    case = json.loads(text)
    original = case["snapshot"]["tool_calls"][0]
    _, data = rebuild(case, monkeypatch)
    source = next(item for item in data["tool_results"] if item["tool_call_id"] == original["tool_call_id"])
    assert source["tool_id"] == "ledger.read_records"
    assert source.get("result") == original["result"]
