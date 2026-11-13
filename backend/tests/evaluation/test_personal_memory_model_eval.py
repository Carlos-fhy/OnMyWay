"""The evaluation must detect the prose failures missed by the first live run."""
from backend.paths import PROJECT_ROOT

import json
import importlib.util
from pathlib import Path

import pytest

_spec = importlib.util.spec_from_file_location('personal_memory_eval',
    PROJECT_ROOT / 'tools/evaluate_personal_memory_model.py')
_runner = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_runner)
CASES, grade = _runner.CASES, _runner.grade


def test_grade_rejects_success_prose_without_a_write_receipt():
    request = {'request_id': 'fixture'}
    state = {'items': [dict(status='active', kind='preference', quote=CASES[0][1], source_id='chat:fixture')]}
    result = {'reply': '已记下。', 'memory_context': {}}
    checks = grade(CASES[0][0], request, result, state)
    assert any(not check['passed'] for check in checks)


def test_grade_rejects_cycling_promise_despite_correct_context_storage():
    request = {'request_id': 'fixture', 'now': '2026-11-07T17:16:10+08:00'}
    state = {'items': [dict(status='active', kind='context', quote=CASES[2][1],
                            scope='personal', valid_until='2026-11-09T00:00:00+08:00')]}
    result = {'reply': '好的，这周优先建议骑行。', 'memory_context': {
        'write_status': 'applied', 'applied_ids': ['context'], 'feedback': '已确认 1 项记忆更新。',
        'feedback_version': 1, 'mode_constraint': 'choice_required'}}
    checks = grade(CASES[2][0], request, result, state)
    assert any(not check['passed'] for check in checks)


@pytest.mark.parametrize('rows', [[], [dict(id=CASES[0][0], raw_output=None)],
                                 [dict(id=case_id, raw_output='{}') for case_id, _, _ in CASES]])
def test_replay_requires_complete_recorded_outputs_and_original_times(tmp_path, rows):
    source = tmp_path / 'report.json'
    source.write_text(json.dumps({'cases': rows}), encoding='utf-8')
    with pytest.raises(ValueError):
        _runner.load_replay(source)
