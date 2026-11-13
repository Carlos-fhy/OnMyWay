"""The explicit collaboration scenario must not mistake enabled for exercised."""
from backend.tests.evaluation.test_agent_a1_evaluation import evaluator
import json
from types import SimpleNamespace
import pytest


def test_delegation_fixture_requires_query_and_parent_acceptance_without_side_effects():
    module = evaluator()
    request = module.delegation_request()
    assert request['budget'] == {'max_steps': 24, 'max_tool_calls': 20, 'max_duration_seconds': 900}
    assert request['constraints']['city'] == '成都'
    assert request['constraints']['query_only'] is True
    assert request['constraints']['delivery_contract'] == {'kind': 'text', 'max_artifacts': 1}
    assert all(word in request['goal'] for word in ('子任务', '高德', '读取', '验收', '来源', '不启动导航'))
    assert len(request['success_criteria']) >= 3


def test_delegation_cli_rejects_disabled_runtime_before_reading_configuration(tmp_path, capsys):
    module = evaluator()
    with pytest.raises(SystemExit) as stopped:
        module.main(['--live', '--scenario', 'delegation', '--env-file', str(tmp_path / 'absent.env'),
                     '--output', str(tmp_path / 'reports')])
    assert stopped.value.code == 2
    assert '--scenario delegation requires --delegation' in capsys.readouterr().err
    assert not list(tmp_path.iterdir())


@pytest.mark.parametrize('waits_for_user', [False, True])
def test_delegation_gate_preserves_actual_report_without_claiming_collaboration(tmp_path, waits_for_user):
    from backend.agent.store import AgentStore
    from backend.agent.tools import ToolCatalog
    from backend.evaluation.agent import EvaluationSession
    module = evaluator()
    store = AgentStore(tmp_path / 'agent.sqlite3')
    actions = iter([
        {'action': 'ask_user', 'question': 'Stop controlled test', 'need': 'missing_input',
         'missing_inputs': {'/test_dependency': 'Injected dependency for pause coverage'}} if waits_for_user else
        {'action': 'publish_artifact', 'kind': 'text', 'title': 'No delegation',
         'content': {'limitations': 'This model did not delegate'}, 'evidence_call_ids': []},
        {'action': 'finish', 'summary': 'No child was created'},
    ])
    session = EvaluationSession(store, ToolCatalog(), tmp_path / 'report.json',
        completion_fn=lambda *args, **kwargs: json.dumps(next(actions)), delegation_enabled=True)
    with pytest.raises(module.EvaluationPhaseFailed) as stopped:
        module._run_delegation(session, store)
    assert stopped.value.status == ('waiting_user' if waits_for_user else 'not_exercised')
    saved = json.loads((tmp_path / 'report.json').read_text('utf-8'))
    phase = saved['phases'][-1]
    assert phase['checks']['delegation_validation']['status'] == 'not_exercised'
    assert phase['checks']['delegation_validation']['semantic_review'] == 'not_run'
    assert phase['execution_status'] == ('waiting_user' if waits_for_user else 'completed')
    assert not phase['children']
    assert saved['limits']['delegation_enabled'] is True


def test_live_scenario_cannot_accept_only_fixture_mcp_provenance(monkeypatch):
    from backend.evaluation import delegation_validation
    module = evaluator()
    record = {'execution_status': 'completed', 'model_calls': [], 'tool_dispatches': [],
              'snapshot': {}, 'checks': {}}
    validation = {'status': 'passed', 'semantic_review': 'not_run',
                  'checks': {'official_sources_only': False}}
    monkeypatch.setattr(delegation_validation, 'validate_delegation_phase', lambda phase: validation)
    store = SimpleNamespace(create=lambda request: {'agent_task_id': 'parent'})
    session = SimpleNamespace(run=lambda *args, **kwargs: record,
                              report={'phases': [record]}, flush=lambda: None)
    with pytest.raises(module.EvaluationPhaseFailed) as stopped:
        module._run_delegation(session, store)
    assert stopped.value.status == 'delegation_official_sources_required'
    assert record['checks']['delegation_validation'] == validation
