"""A named schema request stays distinct from broad discovery and permission."""
from copy import deepcopy
import json
from pathlib import Path

import pytest

from backend.agent.decision import build_context
from backend.agent.focus import resolve_tool_focus
from backend.tests.agent.test_agent_decision import snapshot
from backend.tests.agent.test_agent_tool_focus import discovery, schema_error, worker_for


TOOLS = [{"tool_id": identity, "description": "Synthetic capability", "version": "v1", "effect": "write",
          "input_schema": {"type": "object", "properties": {"record": {"type": "string"}},
                           "required": ["record"], "additionalProperties": False}}
         for identity in ("records.export", "records.lookup", "research.lookup")]


@pytest.mark.parametrize("query,namespace", [
    ("please show records.lookup input_schema", None),
    ("records lookup versions", None),
    ("records lookup", None),
    ("lookup", "records"),
    ("查看 records.lookup 的参数", None),
])
def test_unique_named_discovery_loads_that_schema_over_an_old_error(query, namespace):
    task = snapshot()
    selected = discovery(query, TOOLS)
    selected["payload"]["namespace"] = namespace
    task["observations"] = [schema_error("records.export"), selected]
    before = deepcopy(task)
    focus = resolve_tool_focus(task)
    assert focus.tool_id == "records.lookup"
    descriptors, active = worker_for(TOOLS)._context_tools(task)
    assert active[0] == "records.lookup"
    context = build_context(task, descriptors, active_tool_ids=active, max_input_tokens=5000)
    data = json.loads(context.messages[-1]["content"])
    assert data["tools"][0]["tool_id"] == "records.lookup"
    assert data["tools"][0]["input_schema"] == TOOLS[1]["input_schema"]
    assert data["tools"][0]["effect"] == "write"  # Showing a schema grants no authority.
    assert context.estimated_input_tokens <= 5000 and task == before


@pytest.mark.parametrize("query,namespace", [
    ("records", None), ("lookup", None),
    ("records.lookup and records.export", None),
    ("records lookup export", None),
    ("records research lookup", None),
    ("otherrecords.lookup", None), ("records.lookup_extra", None),
    ("records look", None), ("lookup", "missing"),
])
def test_ambiguous_broad_or_partial_queries_do_not_select_a_schema(query, namespace):
    task = snapshot()
    selected = discovery(query, TOOLS)
    selected["payload"]["namespace"] = namespace
    task["observations"] = [selected]
    assert resolve_tool_focus(task) is None
    task["observations"].insert(0, schema_error("records.export"))
    assert resolve_tool_focus(task).tool_id == "records.export"


def test_named_discovery_cannot_select_a_tool_missing_from_its_recorded_page():
    task = snapshot()
    task["observations"] = [discovery("records lookup", [TOOLS[0]])]
    assert resolve_tool_focus(task) is None


@pytest.mark.parametrize("index", [8, 9, 10, 11, 20, 23, 24, 25, 27])
def test_recorded_named_requests_keep_the_selected_contract_and_actual_inputs(index):
    fixture = json.loads((Path(__file__).parents[1] / 'fixtures/a1_named_discovery_workset.json').read_text('utf-8'))
    step = next(item for item in fixture['steps'] if item['model_index'] == index)
    task = deepcopy(fixture['snapshot'])
    task['run'].update(step['run'])
    task['tool_calls'] = [c for c in task['tool_calls'] if c['updated_at'] <= step['at']]
    task['observations'] = [o for o in task['observations'] if o['created_at'] <= step['at']]
    task['artifacts'] = [a for a in task['artifacts'] if a['created_at'] <= step['at']]
    task['delegation_enabled'] = True
    before = deepcopy(task)
    catalog = {tool['tool_id']: tool for tool in fixture['tool_catalog']}
    descriptors, active = worker_for(fixture['tool_catalog'])._context_tools(task)
    descriptors.extend(catalog[identity] for identity in ('agent.read_result', 'agent.read_artifact')
                       if identity not in {tool['tool_id'] for tool in descriptors})
    context = build_context(task, descriptors, active_tool_ids=active,
                            namespace_index=step['namespace_index'], max_input_tokens=12000)
    data = json.loads(context.messages[-1]['content'])
    from backend.agent.decision import _compact_schema
    selected = next((tool for tool in data['tools'] if tool['tool_id'] == step['expected_tool']), {})
    assert selected.get('input_schema') == _compact_schema(catalog[step['expected_tool']]['input_schema'])
    if index < 12:
        assert step['expected_tool'] not in step['recorded_visible_schemas']
    else:
        source = next(c for c in reversed(task['tool_calls'])
                      if c['tool_id'] == 'onmyway.read_tasks' and c['state'] == 'succeeded')
        shown = next(c for c in data['tool_results'] if c['tool_call_id'] == source['tool_call_id'])
        assert shown.get('result') == source['result']
    assert data['task']['constraints'] == task['constraints']
    assert context.estimated_input_tokens <= 12000 and task == before
