"""Replay the corrective live failure locally; no provider or map requests."""
from copy import deepcopy
from datetime import datetime
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from backend.agent.decision import build_context, _compact_schema
from backend.agent.runtime import AgentRuntime
from backend.agent.tools import ToolCatalog, ToolDefinition
from backend.tests.agent.context_assertions import assert_revision_receipt_visible


def case_at(index):
    fixture = json.loads((Path(__file__).parents[1] / 'fixtures/agent_correction_context_20261110.json').read_text('utf-8'))
    snapshot = fixture['snapshot']
    at = next((step['at'] for step in fixture['steps'] if step['index'] == index), fixture['terminal_at'])
    cutoff = datetime.fromisoformat(at).timestamp()
    snapshot['tool_calls'] = [call for call in snapshot['tool_calls'] if call['updated_at'] < cutoff]
    snapshot['observations'] = [item for item in snapshot['observations'] if item['created_at'] < cutoff]
    snapshot['run'].update(state='running', reason=None, steps_used=index - 8,
                           tool_calls_used=len(snapshot['tool_calls']))
    snapshot['delegation_enabled'] = True
    return fixture


def context_for(case):
    catalog = ToolCatalog()
    originals = {item['tool_id']: item for item in case['tool_catalog']}
    for item in originals.values():
        namespace, name = item['tool_id'].split('.', 1)
        catalog.register(ToolDefinition(namespace, name, item['description'], item['input_schema'],
            effect=item['effect'], executor=item['executor'], source=item['source'],
            output_schema=item.get('output_schema'), output_key=item.get('output_key')), lambda _: {})
    worker = AgentRuntime.__new__(AgentRuntime)
    worker.catalog = SimpleNamespace(describe=lambda identity: deepcopy(originals[identity]), discover=catalog.discover)
    descriptors, active = worker._context_tools(case['snapshot'])
    descriptors.extend(originals[identity] for identity in ('agent.read_result', 'agent.read_artifact')
                       if identity not in {tool['tool_id'] for tool in descriptors})
    context = build_context(case['snapshot'], descriptors, active_tool_ids=active,
                            namespace_index=catalog.namespace_index())
    assert context.estimated_input_tokens <= 12000
    data = json.loads(context.messages[-1]['content'])
    assert {item['namespace']: item['tool_count'] for item in data['available_namespaces']['items']} == {
        item['namespace']: item['tool_count'] for item in catalog.namespace_index()['items']}
    return data


def restored_arguments(entry, data):
    """Independently decode the documented JSON Pointer representation."""
    arguments = deepcopy(entry.get('arguments', entry.get('arguments_view')))
    assert isinstance(arguments, dict)
    for destination, reference in entry.get('argument_refs', {}).items():
        if reference['path'].startswith('/tool_results/'):
            source_index = int(reference['path'].split('/')[2])
            assert data['tool_results'][source_index]['truncated'] is False
        value = data
        for part in reference['path'].split('/')[1:]:
            key = part.replace('~1', '/').replace('~0', '~')
            value = value[int(key)] if isinstance(value, list) else value[key]
        if reference.get('json'):
            value = json.loads(value)
        target = arguments
        parts = [part.replace('~1', '/').replace('~0', '~') for part in destination.split('/')[1:]]
        for key in parts[:-1]:
            target = target[int(key)] if isinstance(target, list) else target[key]
        target[int(parts[-1]) if isinstance(target, list) else parts[-1]] = deepcopy(value)
    return arguments


@pytest.mark.parametrize('index', [11, 12, 16, 18, 21, 24, 25])
def test_failed_planner_schema_and_actual_inputs_remain_jointly_usable(index):
    from backend.execution.capabilities import PlanRoute
    from jsonschema import Draft202012Validator
    case = case_at(index)
    original = deepcopy(case)
    data = context_for(case)
    shown = next((item for item in data['tools'] if item['tool_id'] == 'onmyway.plan_route'), None)
    assert shown is not None, 'The unresolved planner correction must retain its complete contract'
    contract = next(item for item in case['tool_catalog'] if item['tool_id'] == 'onmyway.plan_route')
    assert shown['input_schema'] == _compact_schema(contract['input_schema'])
    source = next(call for call in reversed(case['snapshot']['tool_calls'])
                  if call['tool_id'] == 'onmyway.read_tasks' and call['state'] == 'succeeded')
    visible = next(item for item in data['tool_results'] if item['tool_call_id'] == source['tool_call_id'])
    assert visible.get('result') == source['result'], 'Complete source facts must coexist with the correction schema'
    failed = next(call for call in case['snapshot']['tool_calls'] if call['state'] == 'failed')
    failed_visible = next(item for item in data['tool_results'] if item['tool_call_id'] == failed['tool_call_id'])
    assert failed_visible['error'] == failed['error']
    arguments = restored_arguments(failed_visible, data)
    assert arguments == failed['arguments'], 'All original arguments must restore exactly from visible values'
    assert arguments['task_versions'] == failed['arguments']['task_versions']
    assert [task['id'] for task in arguments['request']['tasks']] == ['parcel']
    assert data['task']['constraints'] == case['snapshot']['constraints']
    assert_revision_receipt_visible(data, case['snapshot']['task_revision_receipt'])
    facts, constraints = visible['result']['data'], data['task']['constraints']
    selected = constraints['selected_task_ids']
    tasks = []
    for row in facts['tasks']:
        if row['task_id'] in selected:
            task = row['task']
            tasks.append({'id': row['task_id'], 'title': task['title'], 'priority': task['priority'],
                          'service_minutes': task['serviceMinutes'], 'place': json.loads(task['placeJson'])})
    corrected = {'store_id': facts['store_id'], 'task_versions': {key: facts['task_versions'][key] for key in selected},
                 'request': {**{key: constraints[key] for key in ('origin', 'destination', 'departure', 'mode')}, 'tasks': tasks}}
    Draft202012Validator(shown['input_schema']).validate(corrected)
    plan = PlanRoute.model_validate(corrected)
    assert plan.task_versions == {'parcel': 1}
    assert next(row for row in facts['tasks'] if row['task_id'] == 'print')['task']['status'] == 'pending'
    assert case == original, 'Display allocation must never mutate the durable failure evidence'


def test_correction_workset_is_not_keyed_to_onmyway_names():
    case = case_at(12)
    names = {'onmyway.plan_route': 'billing.build_invoice', 'onmyway.read_tasks': 'billing.read_records'}
    def rename(value):
        if isinstance(value, str):
            return names.get(value, value)
        if isinstance(value, list):
            return [rename(item) for item in value]
        if isinstance(value, dict):
            return {key: rename(item) for key, item in value.items()}
        return value
    case = rename(case)
    data = context_for(case)
    assert 'billing.build_invoice' in {item['tool_id'] for item in data['tools']}
    source = next(call for call in case['snapshot']['tool_calls'] if call['tool_id'] == 'billing.read_records')
    assert next(item for item in data['tool_results'] if item['tool_call_id'] == source['tool_call_id'])['result'] == source['result']


def test_duplicate_reader_evidence_yields_to_complete_current_business_input():
    case = case_at(25)
    before = deepcopy(case)
    data = context_for(case)
    reads = [call for call in case['snapshot']['tool_calls'] if call['tool_id'] == 'onmyway.read_tasks']
    newest = reads[-1]
    assert next(item for item in data['tool_results'] if item['tool_call_id'] == newest['tool_call_id']).get('result') == newest['result']
    assert len([item for item in data['tool_results'] if item['tool_id'] == 'onmyway.read_tasks']) == 1
    assert not any(item.get('source_ref') in {call['tool_call_id'] for call in reads}
                   for item in data['evidence_sources'] + data['evidence_pages'])
    assert case == before
