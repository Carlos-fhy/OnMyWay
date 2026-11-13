"""Memory receipts and travel constraints, including saved real-model regressions."""
import json

import pytest
from fastapi import HTTPException

from backend.assistant import api as assistant
from backend.memory import assistant as runtime
from backend.memory import personal as personal_memory
from backend.tests.memory.test_personal_memory_assistant import (
    TOKEN, client, enabled, request, snapshot,
)


HEADERS = {'X-Backup-Token': TOKEN}
PREFERENCE = '以后出门我优先步行'


def candidate(text=PREFERENCE, kind='preference'):
    return dict(op='add', kind=kind, text=text, quote=text, topic='travel')


def send(client, monkeypatch, raw, body):
    monkeypatch.setattr(assistant, 'completion', lambda *a: json.dumps(raw, ensure_ascii=False))
    response = client.post('/assistant/chat', headers=HEADERS, json=body)
    assert response.status_code == 200, response.text
    return response.json()


@pytest.mark.parametrize('outcome', ['applied', 'rejected', 'partial', 'disabled', 'unavailable', 'stale'])
def test_feedback_comes_from_actual_write_receipt(client, monkeypatch, outcome):
    actions = [candidate()]
    body = enabled(client, messages=[dict(role='user', content=PREFERENCE)])
    if outcome == 'rejected':
        actions = [candidate('以后出门我优先骑行')]
    elif outcome == 'partial':
        actions.append(candidate('以后出门我优先骑行'))
    elif outcome == 'disabled':
        body['memory_auto_learn'] = False
    elif outcome in ('unavailable', 'stale'):
        def fail(*args, **kwargs):
            raise OSError('storage unavailable') if outcome == 'unavailable' else HTTPException(409)
        monkeypatch.setattr(personal_memory, 'apply_conversation', fail)
    result = send(client, monkeypatch, dict(action='reply',
        reply='已记下：以后出门你优先步行。', memory_actions=actions), body)
    context = result['memory_context']
    assert context['write_status'] == outcome
    assert context['feedback'] and context['feedback'] in result['reply']
    assert '已记下：以后出门你优先步行。' != result['reply']
    if outcome in ('rejected', 'disabled', 'unavailable', 'stale'):
        assert not context['applied_ids']
        assert '已保存' not in result['reply'] and '已记下' not in result['reply']
    if outcome == 'partial':
        assert '1' in result['reply'] and '未' in result['reply']


def test_filtered_unknown_target_is_reported_rejected(client, monkeypatch):
    action = dict(op='forget', id='not-in-evidence', quote='忘记旧偏好', text='')
    result = send(client, monkeypatch, dict(action='reply', reply='已经忘记了。', memory_actions=[action]),
        enabled(client, messages=[dict(role='user', content='忘记旧偏好')]))
    assert result['memory_context']['write_status'] == 'rejected'
    assert result['memory_context']['rejected_count'] == 1
    assert '已经忘记' not in result['reply']


def test_business_proposal_and_clarification_survive_memory_receipt(client, monkeypatch):
    body = enabled(client, messages=[dict(role='user', content=PREFERENCE + '，还要取快递')])
    result = send(client, monkeypatch, dict(action='create_tasks',
        reply='我会一直记着，还帮你保存了快递任务。',
        tasks=[dict(title='取快递', place_type='PARCEL')], memory_actions=[candidate()]), body)
    assert result['action'] == 'create_tasks'
    assert result['tasks'][0]['title'] == '取快递'
    assert '确认' in result['reply'] and '保存了快递任务' not in result['reply']
    result = send(client, monkeypatch, dict(action='reply', reply='已记住，请告诉我目的地。',
        clarification='destination', memory_actions=[candidate()]),
        enabled(client, messages=[dict(role='user', content=PREFERENCE + '，帮我规划出门路线')]))
    assert '目的地' in result['reply']
    assert result['memory_context']['feedback'] in result['reply']


def test_ordinary_reply_is_not_replaced(client, monkeypatch):
    result = send(client, monkeypatch, dict(action='reply', reply='请把取件地址告诉我。'), request())
    assert result['reply'] == '请把取件地址告诉我。'


@pytest.mark.parametrize('text', [PREFERENCE, '以后出门我都步行', '记住我不吃辣'])
def test_missing_candidate_does_not_make_memory_promise_true(client, monkeypatch, text):
    result = send(client, monkeypatch, dict(action='reply', reply='我会一直记着，以后都会照办。'),
        enabled(client, messages=[dict(role='user', content=text)]))
    assert result['memory_context']['write_status'] == 'not_requested'
    assert '没有保存' in result['reply'] and '一直记着' not in result['reply']


def test_disabled_memory_without_candidates_does_not_echo_success(client, monkeypatch):
    result = send(client, monkeypatch, dict(action='reply', reply='已记下你的偏好。'),
        request(messages=[dict(role='user', content=PREFERENCE)]))
    assert result['memory_context']['write_status'] == 'disabled'
    assert '已记下' not in result['reply'] and '关闭' in result['reply']


@pytest.mark.parametrize('reply', ['已记下。', '我会一直记着你的偏好。'])
def test_unsolicited_memory_success_without_candidate_is_not_a_receipt(client, monkeypatch, reply):
    result = send(client, monkeypatch, dict(action='reply', reply=reply),
        enabled(client, messages=[dict(role='user', content='你好')]))
    assert result['reply'] == '本轮没有保存新的记忆。'
    assert result['memory_context']['write_status'] == 'not_requested'


def test_question_about_memory_is_still_ordinary_chat(client, monkeypatch):
    text = '如何修改我的偏好？'
    result = send(client, monkeypatch, dict(action='reply', reply='可以在设置的个人记忆中编辑。'),
        enabled(client, messages=[dict(role='user', content=text)]))
    assert result['reply'] == '可以在设置的个人记忆中编辑。'


def test_saved_less_walking_model_output_cannot_promise_cycling(client, monkeypatch):
    text = '这周希望少走路'
    result = send(client, monkeypatch, dict(action='reply',
        reply='好的，这周我尽量少安排步行，优先建议骑行。', memory_actions=[candidate(text, 'context')]),
        enabled(client, messages=[dict(role='user', content=text)]))
    assert result['memory_context']['write_status'] == 'applied'
    assert '优先建议骑行' not in result['reply'] and '少安排步行' not in result['reply']
    assert '出行方式' in result['reply']
    item = next(i for i in snapshot(client)['items'] if i['status'] == 'active')
    assert item['kind'] == 'context' and item['text'] == text


@pytest.mark.parametrize('text,expected', [
    ('这周少走路，回宿舍', ''),
    ('这周希望减少步行，回宿舍', ''),
    ('如果下雨，骑车回宿舍', ''),
    ('如果不下雨就步行，否则骑车', ''),
    ('如果下雨不骑车，走路回宿舍', ''),
    ('不要骑车或步行，回宿舍', ''),
    ('不步行改骑车回宿舍', 'cycling'),
    ('今天不打印，骑车回宿舍', 'cycling'),
    ('今天不打印但骑车回宿舍', 'cycling'),
    ('这周少走路，今天骑车回宿舍', 'cycling'),
    ('以后优先步行，这次骑车回宿舍', 'cycling'),
])
def test_mode_never_drops_restriction_or_condition(text, expected, client, monkeypatch):
    result = send(client, monkeypatch, dict(action='plan_route', reply='请确认。',
        destination='宿舍', mode='walking'), request(messages=[dict(role='user', content=text)]))
    assert result['mode'] == expected


def test_temporary_constraint_blocks_old_walking_until_explicit_choice(client, monkeypatch):
    send(client, monkeypatch, dict(action='reply', reply='收到。', memory_actions=[candidate()]),
         enabled(client, messages=[dict(role='user', content=PREFERENCE)]))
    text = '这周希望少走路'
    send(client, monkeypatch, dict(action='reply', reply='收到。', memory_actions=[candidate(text, 'context')]),
         enabled(client, messages=[dict(role='user', content=text)]))
    raw = dict(action='plan_route', reply='优先步行。', destination='宿舍', mode='walking')
    result = send(client, monkeypatch, raw, enabled(client, memory_session_id='another-conversation'))
    assert result['mode'] == ''
    assert result['memory_context']['mode_constraint'] == 'choice_required'
    assert '出行方式' in result['reply']
    result = send(client, monkeypatch, raw, enabled(client,
        messages=[dict(role='user', content='今天骑车回宿舍')]))
    assert result['mode'] == 'cycling'
    assert result['memory_context']['mode_source'] == 'current_instruction'
    result = send(client, monkeypatch, raw, enabled(client, now='2026-11-09T09:00:00+08:00',
        memory_session_id='after-expiration'))
    assert result['mode'] == 'walking'
    assert result['memory_context']['mode_source'] == 'memory'


@pytest.mark.parametrize('constraint', ['不能走太远', '走不了太远', '少走一点', '尽量少走几步'])
def test_plain_walking_limit_blocks_long_term_mode(client, monkeypatch, constraint):
    send(client, monkeypatch, dict(action='reply', reply='收到。', memory_actions=[candidate()]),
         enabled(client, messages=[dict(role='user', content=PREFERENCE)]))
    raw = dict(action='plan_route', reply='优先步行。', destination='宿舍', mode='walking')
    result = send(client, monkeypatch, raw, enabled(client,
        messages=[dict(role='user', content=f'这周{constraint}，回宿舍')]))
    assert result['mode'] == ''
    assert result['memory_context']['mode_constraint'] == 'choice_required'
    assert '出行方式' in result['reply']
    text = f'这周{constraint}'
    send(client, monkeypatch, dict(action='reply', reply='收到。', memory_actions=[candidate(text, 'context')]),
         enabled(client, messages=[dict(role='user', content=text)]))
    result = send(client, monkeypatch, raw, enabled(client, memory_session_id='limit-other-session'))
    assert result['mode'] == ''
    assert result['memory_context']['mode_constraint'] == 'choice_required'


@pytest.mark.parametrize('write_status,applied,rejected', [
    ('applied', [], 1), ('unavailable', [], 0), ('disabled', [], 0),
])
def test_old_recovered_reply_is_rebuilt_from_receipt(client, write_status, applied, rejected):
    body = assistant.ChatRequest(**enabled(client, messages=[dict(role='user', content=PREFERENCE)]))
    state = snapshot(client)
    context = runtime.empty_context('ready')
    context.update(store_id=state['store_id'], revision=state['revision'],
        write_status=write_status, applied_ids=applied, rejected_count=rejected)
    proposal = assistant.Proposal(action='reply', reply='我会一直记住你的偏好。', memory_context=context)
    result = runtime.recovered(proposal, body)
    assert '一直记住' not in result.reply
    assert result.memory_context['feedback'] in result.reply