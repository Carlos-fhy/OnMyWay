"""Regressions from the real server-agent revision; no remote requests."""
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path

import pytest

from backend.agent.decision import build_context, _compact_schema
from backend.agent.review import build_review_context
from backend.agent.runtime import AgentRuntime
from backend.agent.tools import ToolCatalog, ToolDefinition
from backend.tests.agent.test_agent_runtime import parts, create_task, provider


def fixture():
    return json.loads((Path(__file__).parents[1] / 'fixtures/agent_server_revision_20261110.json').read_text('utf-8'))


def catalog_for(value):
    catalog = ToolCatalog()
    for item in value['tool_catalog']:
        namespace, name = item['tool_id'].split('.', 1)
        catalog.register(ToolDefinition(namespace, name, item['description'], item['input_schema']), lambda _: {})
    return catalog


def test_revision_keeps_producer_contract_and_actual_inputs_after_reading_tasks(tmp_path):
    from backend.agent.store import AgentStore
    value = fixture()
    snapshot = deepcopy(value['snapshot'])
    before = datetime.fromisoformat(next(s['at'] for s in value['steps'] if s['index'] == 11)).timestamp()
    snapshot['tool_calls'] = [c for c in snapshot['tool_calls'] if c['created_at'] < before]
    snapshot['observations'] = [o for o in snapshot['observations'] if o['created_at'] < before]
    snapshot['artifacts'] = []
    snapshot['run'].update(state='running', reason=None, steps_used=1, tool_calls_used=1)
    worker = AgentRuntime(AgentStore(tmp_path / 'agent.db'), catalog_for(value), None)
    tools, active = worker._context_tools(snapshot)
    context = build_context(snapshot, tools, active_tool_ids=active, namespace_index=worker.catalog.namespace_index())
    data = json.loads(context.messages[-1]['content'])
    planner = next(t for t in data['tools'] if t['tool_id'] == 'onmyway.plan_route')
    assert planner['input_schema'] == _compact_schema(worker.catalog.describe('onmyway.plan_route')['input_schema'])
    original = snapshot['tool_calls'][-1]
    assert next(r for r in data['tool_results'] if r['tool_call_id'] == original['tool_call_id'])['result'] == original['result']
    assert context.estimated_input_tokens <= 12000


@pytest.mark.parametrize('changed', [False, True])
@pytest.mark.parametrize('first_failed', [False, True])
def test_used_contract_survives_display_eviction_but_not_change(parts, monkeypatch, changed, first_failed):
    import backend.agent.runtime as module
    from backend.agent.tools import ToolError
    tool = parts.Tool('records', 'lookup', 'lookup records', {'type':'object'})
    effects = []
    def execute(_):
        effects.append(True)
        if first_failed and len(effects) == 1:
            raise ToolError('invalid_arguments', 'Correct the business selection')
        return {'count': 1}
    parts.catalog.register(tool, execute)
    task = create_task(parts.store, goal='lookup records')
    original = module.build_context
    def display(snapshot, *args, **kwargs):
        context = original(snapshot, *args, **kwargs)
        if snapshot['tool_calls']:
            data = json.loads(context.messages[-1]['content'])
            data['tools'] = [t for t in data['tools'] if t['tool_id'] != 'records.lookup']
            context.messages[-1]['content'] = json.dumps(data)
        return context
    monkeypatch.setattr(module, 'build_context', display)
    # Exercise contract-version validation independently from display focus.
    monkeypatch.setattr(module, 'resolve_tool_focus', lambda _: None)
    def again(_):
        if changed:
            parts.catalog.register(parts.Tool('records', 'lookup', 'changed contract', {'type':'object'}), lambda _: effects.append(True))
        return {'action':'call_tool','tool_id':'records.lookup','arguments':{}}
    worker = AgentRuntime(parts.store, parts.catalog, provider(parts, [
        {'action':'call_tool','tool_id':'records.lookup','arguments':{}}, again,
        {'action':'ask_user','question':'Stop fixture'}]), memory_reader=lambda _: [])
    worker.run(task['current_run_id'])
    snapshot = parts.store.get(task['agent_task_id'])
    assert len(effects) == (1 if changed else 2)
    assert not any(o['payload'].get('code') == 'discover_tool_schema_first' for o in snapshot['observations'])
    if changed:
        assert any(o['payload'].get('code') == 'tool_changed' for o in snapshot['observations'])


def test_schema_error_feedback_explains_empty_arguments_without_echoing_input(parts):
    parts.catalog.register(parts.Tool('records', 'lookup', 'lookup records', {
        'type': 'object', 'properties': {}, 'additionalProperties': False}), lambda _: {})
    task = create_task(parts.store, goal='lookup records')
    worker = AgentRuntime(parts.store, parts.catalog, provider(parts, [
        {'action': 'call_tool', 'tool_id': 'records.lookup', 'arguments': {'private-key': 'private-value'}},
        {'action': 'ask_user', 'question': 'Stop fixture'}]), memory_reader=lambda _: [])
    worker.run(task['current_run_id'])
    current = parts.store.get(task['agent_task_id'])
    errors = [item['payload'] for item in current['observations'] if item['kind'] == 'tool_error']
    assert len(errors) == 1 and errors[0]['code'] == 'invalid_arguments'
    assert 'allowed_fields' in errors[0]['message'] and 'additionalProperties' in errors[0]['message']
    assert 'private-' not in errors[0]['message']
    assert current['tool_calls'] == []


@pytest.mark.parametrize('change', ['invented_id', 'old_revision', 'wrong_leg', 'wrong_time', 'wrong_tasks',
                                  'removed_warning', 'added_warning', 'changed_warning', 'duplicate_warning', 'reordered_warning', None])
def test_registered_route_validator_rejects_unreceipted_or_changed_facts(change):
    from backend.agent.builtin import register_builtin
    from backend.agent.tools import ToolError
    from types import SimpleNamespace
    value = fixture()
    snapshot = deepcopy(value['snapshot'])
    # The previous artifact is an actual valid native-plan result in the fixture.
    original = deepcopy(snapshot['prior_artifacts'][-1]['content']['content'])
    call_id = original['plan_id']
    content = deepcopy(original)
    source = {'tool_call_id':call_id, 'tool_id':'onmyway.plan_route', 'state':'succeeded',
              'agent_run_id':snapshot['current_run_id'], 'result':{'data':{'plan_id':call_id, 'plan_version':1, 'plan':original}}}
    snapshot['constraints']['selected_task_ids'] = ['parcel', 'print']
    if change == 'invented_id': content['plan_id'] = 'invented'
    if change == 'old_revision': source['agent_run_id'] = 'old-run'
    if change == 'wrong_leg': content['legs'][-1]['meters'] += 1
    if change == 'wrong_time': content['arrival'] = content['departure']
    if change == 'wrong_tasks': snapshot['constraints']['selected_task_ids'] = ['parcel']
    if change == 'removed_warning': content['warnings'] = []
    if change == 'added_warning': content['warnings'].append('The formal pending record was removed.')
    if change == 'changed_warning': content['warnings'][0] = 'All opening hours verified.'
    if change == 'duplicate_warning': content['warnings'].append(content['warnings'][0])
    if change == 'reordered_warning': content['warnings'].reverse()
    catalog = ToolCatalog()
    register_builtin(catalog, SimpleNamespace(), 'unused')
    if change:
        with pytest.raises(ToolError) as raised:
            catalog.validate_artifact('route', content, snapshot, [source])
        assert raised.value.code == 'artifact_source_mismatch'
        if change and 'warning' in change:
            assert 'warnings' in str(raised.value)
            assert '同一 plan_id' in str(raised.value)
        if change == 'wrong_tasks':
            assert '请发现并调用' in str(raised.value) and '无需重算' not in str(raised.value)
    else:
        catalog.validate_artifact('route', content, snapshot, [source])


def test_runtime_checks_registered_artifact_validator_before_saving(parts):
    from backend.agent.tools import ToolError
    def reject(content, snapshot, sources):
        raise ToolError('artifact_source_mismatch', 'Result differs from original receipt')
    parts.catalog.register_artifact_validator('receipt', reject)
    task = create_task(parts.store)
    actions = [{'action':'publish_artifact','kind':'receipt','title':'Invalid receipt','content':{'id':'invented'},'evidence_call_ids':[]},
               {'action':'ask_user','question':'Stop fixture'}]
    worker = AgentRuntime(parts.store, parts.catalog, provider(parts, actions), memory_reader=lambda _: [])
    worker.run(task['current_run_id'])
    current = parts.store.get(task['agent_task_id'])
    assert current['artifacts'] == []
    assert any(o['payload'].get('code') == 'artifact_source_mismatch' for o in current['observations'])


def test_resume_revalidates_preexisting_artifact_before_completion(parts):
    from backend.agent.tools import ToolError
    from uuid import uuid4
    task = create_task(parts.store)
    worker = AgentRuntime(parts.store, parts.catalog, provider(parts, [
        {'action':'publish_artifact','kind':'receipt','title':'Old receipt','content':{'id':'invalid'},'evidence_call_ids':[]},
        {'action':'ask_user','question':'Pause fixture'}]), memory_reader=lambda _: [])
    worker.run(task['current_run_id'])
    def reject(*_):
        raise ToolError('artifact_source_mismatch', 'Old receipt needs correction')
    parts.catalog.register_artifact_validator('receipt', reject)
    parts.store.resume(task['agent_task_id'], {'request_id':str(uuid4()), 'expected_revision':1, 'reason':'Continue'})
    worker = AgentRuntime(parts.store, parts.catalog, provider(parts, [
        {'action':'finish','summary':'Done'}, {'action':'ask_user','question':'Stop fixture'}]), memory_reader=lambda _: [])
    worker.run(task['current_run_id'])
    assert parts.store.get(task['agent_task_id'])['run']['state'] == 'waiting_user'


def test_real_revision_can_reach_review_without_duplicating_current_requirements():
    value = fixture()
    snapshot = deepcopy(value['snapshot'])
    snapshot['completion_summary'] = value['completion_summary']
    calls = [c for c in snapshot['tool_calls'] if c['state'] == 'succeeded' and not c['tool_id'].startswith('agent.')]
    original = deepcopy(snapshot)
    context = build_review_context(snapshot, calls)
    assert context.estimated_input_tokens <= 12000
    data = json.loads(context.messages[-1]['content'])
    assert data['requirements']['constraints'] == snapshot['constraints']
    assert data['artifacts'][0]['content'] == snapshot['artifacts'][0]['content']
    assert snapshot == original


def route_receipt_fixture():
    snapshot = deepcopy(fixture()['snapshot'])
    content = deepcopy(snapshot['prior_artifacts'][-1]['content']['content'])
    snapshot['constraints']['selected_task_ids'] = ['parcel', 'print']
    source = {'tool_call_id': content['plan_id'], 'tool_id': 'onmyway.plan_route',
              'state': 'succeeded', 'agent_run_id': snapshot['current_run_id'],
              'arguments': {'request': {'allow_partial': False}},
              'result': {'data': {'plan_id': content['plan_id'], 'plan_version': 1,
                                  'plan': deepcopy(content)}}}
    return snapshot, content, source


@pytest.mark.parametrize('scope', ['route', 'stops', 'both'])
def test_route_validator_accepts_equivalent_aware_times(scope):
    from backend.planning.agent_artifact import validate_route
    snapshot, content, source = route_receipt_fixture()
    values = ([content] if scope in {'route', 'both'} else [])
    if scope in {'stops', 'both'}:
        values += content['stops']
    for value in values:
        for key in ('departure', 'arrival'):
            value[key] = datetime.fromisoformat(value[key]).astimezone(timezone(timedelta(hours=8))).isoformat()
    validate_route(content, snapshot, [source])


@pytest.mark.parametrize('change', ['early_naive_date', 'huge_duration', 'selected_object', 'selected_integer',
                                  'selected_string', 'null_stops', 'invalid_leg', 'non_object'])
def test_route_validator_turns_malformed_content_into_recoverable_error(change):
    from backend.agent.tools import ToolError
    from backend.planning.agent_artifact import validate_route
    snapshot, content, source = route_receipt_fixture()
    if change == 'early_naive_date': content['departure'] = '0001-01-01T00:00:00'
    if change == 'huge_duration': content['total_seconds'] = 10**400
    if change == 'selected_object': snapshot['constraints']['selected_task_ids'] = [{'task_id': 'parcel'}]
    if change == 'selected_integer': snapshot['constraints']['selected_task_ids'] = 3
    if change == 'selected_string': snapshot['constraints']['selected_task_ids'] = 'parcel'
    if change == 'null_stops': content['stops'] = None
    if change == 'invalid_leg': content['legs'] = [None]
    if change == 'non_object': content = []
    with pytest.raises(ToolError) as raised:
        validate_route(content, snapshot, [source])
    assert raised.value.code == 'artifact_source_mismatch'


@pytest.mark.parametrize('scenario', ['shared_place', 'partial_allowed', 'partial_hidden', 'partial_not_allowed'])
def test_route_validator_preserves_native_solver_contract(scenario):
    from backend.agent.tools import ToolError
    from backend.planning.agent_artifact import validate_route
    from backend.tests.planning.test_planner import A, B, Maps, request, solve, task
    shared = scenario == 'shared_place'
    tasks = [task('one', A), task('two', A if shared else B)]
    requested = request(tasks, allow_partial=not shared)
    plan = solve(requested, [[A], [A] if shared else []], Maps()).model_dump(mode='json')
    content = {**deepcopy(plan), 'plan_id': 'native-plan', 'plan_version': 1, 'navigation_started': False}
    snapshot = {'current_run_id': 'current', 'constraints': {'selected_task_ids': ['one', 'two']}}
    source = {'tool_call_id': 'native-plan', 'tool_id': 'onmyway.plan_route', 'state': 'succeeded',
              'agent_run_id': 'current', 'arguments': {'request': requested.model_dump(mode='json')},
              'result': {'data': {'plan_id': 'native-plan', 'plan_version': 1, 'plan': plan}}}
    if shared:
        assert len(plan['stops']) == 1 and len(plan['legs']) == 3
    if scenario == 'partial_hidden': content['omitted'] = []
    if scenario == 'partial_not_allowed': source['arguments']['request']['allow_partial'] = False
    if scenario in {'partial_hidden', 'partial_not_allowed'}:
        with pytest.raises(ToolError) as raised:
            validate_route(content, snapshot, [source])
        assert raised.value.code == 'artifact_source_mismatch'
    else:
        validate_route(content, snapshot, [source])


@pytest.mark.parametrize('status', ['accepted', 'submitted', 'rejected', 'revised', 'unshared'])
def test_route_validator_resolves_only_current_accepted_child_receipts(tmp_path, status):
    from uuid import uuid4
    from backend.agent.store import AgentStore
    from backend.agent.tools import ToolError
    from backend.planning.agent_artifact import validate_route
    _, content, source = route_receipt_fixture()
    store = AgentStore(tmp_path / 'delegated-route.sqlite3')
    parent = create_task(store, constraints={'selected_task_ids': ['parcel', 'print']},
                         budget={'max_steps': 24, 'max_tool_calls': 12})
    parent_id = parent['current_run_id']
    token = store.claim(parent_id, 'coordinator')['lease_token']
    link = store.delegate(parent_id, token, {
        'delegation_key': 'route', 'goal': 'Plan the selected errands', 'constraints': {},
        'success_criteria': ['Return the native planning receipt'],
        'allowed_tools': ['onmyway.plan_route'], 'input_refs': [],
        'budget': {'max_steps': 8, 'max_tool_calls': 3}})
    child_id = link['child_run_id']
    child_token = store.claim(child_id, 'child')['lease_token']
    call_id = str(uuid4())
    content['plan_id'] = call_id
    source['result']['data'].update(plan_id=call_id, plan=deepcopy(content))
    call = store.prepare_call(child_id, child_token, 'onmyway.plan_route', 'v1', source['arguments'],
                              tool_call_id=call_id, legacy_run_id=call_id)
    sent = store.dispatch_call(call['tool_call_id'], child_token)
    store.record_result(call_id, sent['receipt_token'], 'succeeded', source['result'])
    unshared_id = None
    if status == 'unshared':
        unshared_id = str(uuid4())
        call = store.prepare_call(child_id, child_token, 'onmyway.plan_route', 'v1', source['arguments'],
                                  tool_call_id=unshared_id, legacy_run_id=unshared_id)
        sent = store.dispatch_call(unshared_id, child_token)
        store.record_result(unshared_id, sent['receipt_token'], 'succeeded', source['result'])
    artifact = store.publish_artifact(child_id, child_token, 'text',
        {'title': 'Planning receipt', 'content': {'plan_id': call_id}},
        sources=[{'tool_call_id': call_id, 'tool_id': 'onmyway.plan_route'}])
    store.transition(child_id, child_token, 'completed', 'Receipt ready')
    catalog = ToolCatalog()
    catalog.register_artifact_validator('route', validate_route)
    runtime = AgentRuntime(store, catalog, None)
    arguments = {'artifact_id': artifact['artifact_id'], 'version': artifact['version'], 'max_chars': 3000}
    read = store.prepare_call(parent_id, token, 'agent.read_artifact', 'v1', arguments)
    sent = store.dispatch_call(read['tool_call_id'], token)
    scope = runtime._active_task.set(parent['agent_task_id'])
    try:
        page = runtime._read_artifact(arguments)
    finally:
        runtime._active_task.reset(scope)
    assert page['offset'] == 0 and page['has_more'] is False
    store.record_result(read['tool_call_id'], sent['receipt_token'], 'succeeded', {'data': page})
    if status != 'submitted':
        store.review_delegation(parent_id, token, 'route', 'reject' if status == 'rejected' else 'accept',
                                'Read the submitted artifact and its planning source')
    if status == 'revised':
        store.revise(parent['agent_task_id'], {
            'request_id': str(uuid4()), 'expected_revision': parent['revision'],
            'goal': 'Revise the route', 'constraints': parent['constraints'],
            'success_criteria': parent['success_criteria']})
    snapshot = store.get(parent['agent_task_id'])
    if status == 'unshared':
        with pytest.raises(ToolError) as raised:
            runtime._publication_sources(snapshot, [unshared_id])
        assert raised.value.code == 'evidence_not_found'
        return
    sources = runtime._publication_sources(snapshot, [read['tool_call_id']])
    if status == 'accepted':
        runtime._validate_publication(snapshot, 'route', content, sources)
    else:
        with pytest.raises(ToolError) as raised:
            runtime._validate_publication(snapshot, 'route', content, sources)
        assert raised.value.code == 'artifact_source_mismatch'
