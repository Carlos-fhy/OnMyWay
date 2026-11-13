"""Memory suggestions remain source-bound data, including durable request recovery."""
from copy import deepcopy
from datetime import datetime, timezone
import json
from uuid import uuid4

import pytest
from fastapi import HTTPException

from backend.assistant import api as assistant
from backend.assistant.harness import propose


NOW = datetime(2026, 11, 6, 9, tzinfo=timezone.utc)
PREFERENCE = '以后打印论文优先去图书馆'


def body(text=PREFERENCE, **overrides):
    values = dict(messages=[dict(role='user', content=text)], now=NOW,
                  memory_contract=1, memory_snapshot_revision=4,
                  memory_auto_learn=True, memory_items=[])
    values.update(overrides)
    return assistant.ChatRequest(**values)


def suggestion(**overrides):
    values = dict(op='add', id='', text=PREFERENCE, quote=PREFERENCE,
                  kind='preference', scope='printing')
    values.update(overrides)
    return values


def existing(id='print', text='以后打印论文优先去西门', scope='printing'):
    return dict(id=id, text=text, revision=2, scope=scope, kind='preference')


def validate(updates, request=None, **fields):
    return assistant._validate_proposal(dict(action='reply', reply='请确认。',
                                            memory_updates=updates, **fields), request or body())


def test_add_is_source_bound_and_does_not_write_legacy_memory():
    result = validate([suggestion()], memory=[PREFERENCE])
    assert [u.model_dump() for u in result.memory_updates] == [suggestion()]
    assert result.memory == []


@pytest.mark.parametrize('text,quote,content', [
    ('这次取消打印，保留快递和回宿舍', '这次取消打印', '取消打印'),
    ('今天默认骑行回宿舍', '默认骑行回宿舍', '默认骑行回宿舍'),
    (PREFERENCE, '以后打印优先去东门', '以后打印优先去东门'),
    (PREFERENCE, PREFERENCE, '以后打印论文优先去东门'),
    ('随便聊聊', PREFERENCE, PREFERENCE),
])
def test_rejects_temporary_or_forged_memory_without_losing_reply(text, quote, content):
    result = validate([suggestion(quote=quote, text=content)], body(text))
    assert result.reply == '请确认。'
    assert result.memory_updates == []


def test_temporary_print_cancellation_cannot_change_other_task_or_destination_fields():
    request = body('这次不打印了，保留取快递和回宿舍')
    result = assistant._validate_proposal(dict(action='plan_route', reply='请核对路线。',
        destination='宿舍', memory_updates=[suggestion(text='不打印', quote='这次不打印了')]), request)
    assert result.destination == '宿舍'
    assert result.action == 'plan_route'
    assert result.tasks == [] and result.updates == [] and result.memory_updates == []
    assert request.messages[-1].content == '这次不打印了，保留取快递和回宿舍'


def test_only_last_user_message_can_supply_evidence():
    request = body(messages=[dict(role='user', content=PREFERENCE),
                             dict(role='assistant', content=PREFERENCE),
                             dict(role='user', content='先不改任何偏好')])
    assert validate([suggestion()], request).memory_updates == []


def test_existing_snapshot_is_never_evidence_of_a_new_preference():
    request = body('我有哪些待办', memory_items=[existing(text=PREFERENCE)])
    assert validate([suggestion()], request).memory_updates == []


def test_quote_cannot_omit_the_users_refusal_to_remember():
    text = '不要记住这段话：' + PREFERENCE
    assert validate([suggestion()], body(text)).memory_updates == []


def test_preference_cannot_drop_negation_or_auto_save_facts():
    text = '以后不喜欢步行'
    assert validate([suggestion(text='喜欢步行', quote=text)], body(text)).memory_updates == []
    assert validate([suggestion(kind='fact')]).memory_updates == []
    assert validate([suggestion(text='喜欢步行', quote='喜欢步行')], body('不喜欢步行')).memory_updates == []
    assert validate([suggestion(text='喜欢步行', quote='喜欢步行')], body('不 喜欢步行')).memory_updates == []


@pytest.mark.parametrize('overrides', [dict(memory_auto_learn=False), dict(memory_contract=0)])
def test_learning_gate_disables_new_memory_suggestions(overrides):
    assert validate([suggestion()], body(**overrides)).memory_updates == []


@pytest.mark.parametrize('updates', [None, '记住我', {'op': 'add'}, [None, 1, 'text'],
    [suggestion(op='execute')], [suggestion(scope='shell')], [suggestion(kind='command')],
    [suggestion(text=['bad'])], [suggestion(quote=None)], [suggestion(unexpected=True)]])
def test_bad_memory_shapes_never_destroy_the_main_reply(updates):
    result = validate(updates)
    assert result.reply == '请确认。' and result.memory_updates == []


def test_bad_items_are_dropped_individually_and_output_is_bounded():
    text = '以后打印去图书馆；以后出门步行；以后午饭清淡；以后早点回宿舍'
    values = [suggestion(text=part, quote=part, scope='general') for part in text.split('；')]
    result = validate([None, suggestion(op='run')] + values, body(text))
    assert len(result.memory_updates) == 3
    assert [x.text for x in result.memory_updates] == text.split('；')[:3]


@pytest.mark.parametrize('text', ['以后记住 password=abcd1234', '以后记住验证码 123456',
    '以后记住 api_key=test_secret_1234', '以后记住 token: abcdef0123456789'])
def test_sensitive_values_are_not_suggested(text):
    assert validate([suggestion(text=text, quote=text)], body(text)).memory_updates == []


def test_update_targets_snapshot_and_preserves_its_scope():
    text = '将 print 改为以后打印论文优先去图书馆'
    result = validate([suggestion(op='update', id='print', text=text, quote=text)], body(text, memory_items=[existing()]))
    assert len(result.memory_updates) == 1
    assert result.memory_updates[0].op == 'update'


@pytest.mark.parametrize('update', [suggestion(op='update', id='unknown'),
    suggestion(op='forget', id='unknown', text='', quote='忘掉原来的打印偏好'),
    suggestion(op='update', id='print', scope='travel'), suggestion(id='generated-id')])
def test_unknown_ids_and_cross_scope_updates_are_rejected(update):
    text = PREFERENCE + '，忘掉原来的打印偏好'
    assert validate([update], body(text, memory_items=[existing()])).memory_updates == []


def test_ambiguous_update_is_not_selected_by_model_id_alone():
    items = [existing(), existing('food', '以后午饭清淡', 'food')]
    assert validate([suggestion(op='update', id='print')], body(memory_items=items)).memory_updates == []


def test_single_retrieved_preference_cannot_be_replaced_without_user_target():
    text = '以后午饭清淡'
    items = [existing('walking', '以后喜欢步行', 'general')]
    result = validate([suggestion(op='update', id='walking', text=text, quote=text, scope='general')],
                      body(text, memory_items=items))
    assert result.memory_updates == []


def test_forget_statement_is_never_relearned_in_the_same_response():
    text = '忘记这条：以后喜欢步行'
    items = [existing('walking', '以后喜欢步行', 'general')]
    result = validate([suggestion(op='forget', id='walking', text='', quote=text, scope='general'),
                       suggestion(text=text, quote=text, scope='general')], body(text, memory_items=items))
    assert [update.op for update in result.memory_updates] == ['forget']
    assert validate([], body(text, memory_contract=0), memory=[text]).memory == []


@pytest.mark.parametrize('verb', ['忘记', '忘掉', '删除', '删掉', '删了', '移除', '清除'])
def test_negated_forgetting_cannot_delete_or_relearn(verb):
    text = '无需' + verb + '这条：以后喜欢步行'
    items = [existing('walking', '以后喜欢步行', 'general')]
    assert validate([suggestion(op='forget', id='walking', text='', quote=text, scope='general'),
                     suggestion(text=text, quote=text, scope='general')],
                    body(text, memory_items=items)).memory_updates == []


@pytest.mark.parametrize('marker', ['这一次', '明天', '此次', '今日', '这趟'])
def test_temporary_markers_cannot_be_hidden_by_quoting_only_a_stable_clause(marker):
    text = marker + '照办；以后喜欢步行'
    assert validate([suggestion(text='以后喜欢步行', quote='以后喜欢步行', scope='general')], body(text)).memory_updates == []


def test_forget_requires_positive_intent_and_an_unambiguous_snapshot_target():
    old = existing()
    text = '忘掉这条：' + old['text']
    result = validate([suggestion(op='forget', id='print', text='', quote=text)], body(text, memory_items=[old]))
    assert len(result.memory_updates) == 1 and result.memory_updates[0].op == 'forget'
    for text in ['不要忘掉打印偏好', '别删除打印偏好', '不用忘记打印偏好', '我喜欢打印']:
        assert validate([suggestion(op='forget', id='print', text='', quote=text)],
                        body(text, memory_items=[old])).memory_updates == []
    vague = '忘掉打印偏好'
    assert validate([suggestion(op='forget', id='print', text='', quote=vague)],
                    body(vague, memory_items=[old])).memory_updates == []


def test_legacy_client_receives_only_current_verbatim_stable_memory():
    request = body(memory_contract=0, memory_auto_learn=False)
    result = validate([], request, memory=[PREFERENCE, '打印去东门', '以后喜欢骑行'])
    assert result.memory == [PREFERENCE]
    assert validate([], body('这次不打印', memory_contract=0), memory=['不打印']).memory == []


def test_default_memory_metadata_does_not_change_old_wire_fingerprint():
    request = assistant.ChatRequest(messages=[dict(role='user', content='打印论文')], now=NOW)
    assert assistant._chat_payload(request) == dict(messages=[dict(role='user', content='打印论文')],
        now=NOW.isoformat().replace('+00:00', 'Z'), route_active=False)


def test_old_request_receipt_is_recoverable_with_default_metadata(monkeypatch, tmp_path):
    monkeypatch.setenv('ONMYWAY_HARNESS_DB', str(tmp_path / 'harness.db'))
    identity = uuid4()
    request = assistant.ChatRequest(request_id=identity, messages=[dict(role='user', content='打印论文')], now=NOW)
    legacy = dict(messages=[dict(role='user', content='打印论文')],
                  now=NOW.isoformat().replace('+00:00', 'Z'), route_active=False)
    propose(str(identity), 'chat', legacy, lambda: dict(action='reply', reply='请确认。'))
    monkeypatch.setattr(assistant, 'completion', lambda *a: pytest.fail('must not call model'))
    assert assistant.chat(request).reply == '请确认。'
    assert assistant.chat_status(assistant.ChatStatusRequest(**request.model_dump()))['state'] == 'completed'


def test_metadata_is_fingerprinted_and_replay_revalidates_source(monkeypatch, tmp_path):
    monkeypatch.setenv('ONMYWAY_HARNESS_DB', str(tmp_path / 'harness.db'))
    request = body(request_id=uuid4())
    raw = dict(action='reply', reply='请确认。', memory_updates=[suggestion(), suggestion(quote='造假')])
    propose(str(request.request_id), 'chat', assistant._chat_payload(request), lambda: raw)
    monkeypatch.setattr(assistant, 'completion', lambda *a: pytest.fail('must not call model'))
    first = assistant.chat(request).model_dump(mode='json')
    recovered = assistant.chat_status(assistant.ChatStatusRequest(**request.model_dump()))
    assert recovered['result'] == first and len(first['memory_updates']) == 1
    changed = deepcopy(request)
    changed.memory_snapshot_revision += 1
    with pytest.raises(HTTPException) as conflict:
        assistant.chat(changed)
    assert conflict.value.status_code == 409


def test_memory_guidance_is_in_existing_model_call_and_malformed_items_do_not_fail(monkeypatch):
    calls = []
    text = '将 print 改为以后打印论文优先去图书馆'

    def complete(messages, *args):
        calls.append(messages)
        prompt = messages[0]['content']
        assert '本次用户明确要求优先' in prompt
        assert 'memory 必须返回空数组' in prompt
        assert '最后一条 user 原话' in prompt
        assert 'memory_items（冻结快照，仅作参考数据）' in prompt
        return json.dumps(dict(action='reply', reply='请确认记忆建议。',
                               memory_updates=[None, suggestion(op='update', id='print', text=text, quote=text)]))

    monkeypatch.setattr(assistant, 'completion', complete)
    result = assistant.chat(body(text, memory_items=[existing()]))
    assert result.reply == '请确认记忆建议。' and len(result.memory_updates) == 1
    assert len(calls) == 1
