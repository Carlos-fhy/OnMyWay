"""Production chat integration, with the paid model replaced by fixed responses."""
import json
from datetime import datetime, timezone
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from backend.assistant import api as assistant
from backend.main import app


TOKEN = 'personal-memory-test-token-0000000000'
NOW = '2026-11-07T09:00:00+08:00'


@pytest.fixture
def client(monkeypatch, tmp_path):
    monkeypatch.setenv('ONMYWAY_MEMORY_DB', str(tmp_path / 'memory.sqlite3'))
    monkeypatch.setenv('ONMYWAY_HARNESS_DB', str(tmp_path / 'harness.sqlite3'))
    monkeypatch.setenv('ONMYWAY_EVENT_DB', str(tmp_path / 'events.sqlite3'))
    monkeypatch.setenv('ONMYWAY_BACKUP_TOKEN', TOKEN)
    return TestClient(app)


def request(**changes):
    body = dict(request_id=str(uuid4()), messages=[dict(role='user', content='回宿舍')],
                now=NOW, memory_contract=2, memory_enabled=False,
                memory_session_id='conversation-one')
    body.update(changes)
    return body


def snapshot(client):
    response = client.get('/memory', headers={'X-Backup-Token': TOKEN})
    assert response.status_code == 200, response.text
    return response.json()


def enabled(client, **changes):
    state = snapshot(client)
    if not state['learning_enabled']:
        response = client.post('/memory/commands', headers={'X-Backup-Token': TOKEN}, json=dict(
            operation_id=str(uuid4()), store_id=state['store_id'], expected_revision=state['revision'],
            operation='settings', learning_enabled=True))
        assert response.status_code == 200, response.text
        state = response.json()['snapshot']
    return request(memory_enabled=True, memory_auto_learn=True,
                   memory_store_id=state['store_id'], memory_snapshot_revision=state['revision'], **changes)


def test_disabled_v2_is_chat_without_memory_or_credentials(client, monkeypatch):
    monkeypatch.setattr(assistant, 'completion', lambda *a: json.dumps(dict(action='reply', reply='可以。')))
    response = client.post('/assistant/chat', json=request())
    assert response.status_code == 200, response.text
    assert response.json()['memory_context']['status'] == 'disabled'


def test_enabled_memory_requires_authorization_before_model(client, monkeypatch):
    monkeypatch.setattr(assistant, 'completion', lambda *a: pytest.fail('unauthorized model call'))
    response = client.post('/assistant/chat', json=request(memory_enabled=True, memory_store_id='untrusted'))
    assert response.status_code == 401, response.text


def test_current_turn_is_persisted_and_retrieved_in_another_conversation(client, monkeypatch):
    text = '以后出门我优先步行'
    monkeypatch.setattr(assistant, 'completion', lambda *a: json.dumps(dict(
        action='reply', reply='收到你的偏好。', memory_actions=[dict(
            op='add', kind='preference', text=text, quote=text, topic='travel')]), ensure_ascii=False))
    body = enabled(client, messages=[dict(role='user', content=text)])
    response = client.post('/assistant/chat', json=body, headers={'X-Backup-Token': TOKEN})
    assert response.status_code == 200, response.text
    assert response.json()['memory_context']['write_status'] == 'applied'
    assert len([i for i in snapshot(client)['items'] if i['status'] == 'active']) == 1

    calls = []
    def plan(messages, *args):
        calls.append(messages)
        assert text in messages[0]['content']
        return json.dumps(dict(action='plan_route', reply='请确认路线。', destination='宿舍', mode=''))
    monkeypatch.setattr(assistant, 'completion', plan)
    response = client.post('/assistant/chat', json=enabled(client, memory_session_id='conversation-two'),
                           headers={'X-Backup-Token': TOKEN})
    assert response.status_code == 200, response.text
    result = response.json()
    assert result['mode'] == 'walking'
    assert result['memory_context']['mode_source'] == 'memory'
    assert result['memory_context']['evidence_ids']
    assert len(calls) == 1


def test_replay_does_not_reapply_and_recovery_is_also_authorized(client, monkeypatch):
    text = '以后出门我优先步行'
    monkeypatch.setattr(assistant, 'completion', lambda *a: json.dumps(dict(action='reply', reply='收到。',
        memory_actions=[dict(op='add', kind='preference', text=text, quote=text, topic='travel')])))
    body = enabled(client, messages=[dict(role='user', content=text)])
    headers = {'X-Backup-Token': TOKEN}
    first = client.post('/assistant/chat', json=body, headers=headers)
    assert first.status_code == 200, first.text
    revision = snapshot(client)['revision']
    monkeypatch.setattr(assistant, 'completion', lambda *a: pytest.fail('replay called model'))
    assert client.post('/assistant/chat', json=body, headers=headers).status_code == 200
    assert snapshot(client)['revision'] == revision
    assert client.post('/assistant/chat/status', json=body).status_code == 401
    assert client.post('/assistant/chat/status', json=body, headers=headers).json()['state'] == 'completed'


def test_memory_read_failure_degrades_without_losing_reply(client, monkeypatch):
    body = enabled(client)
    from backend.memory import personal as personal_memory
    monkeypatch.setattr(personal_memory, 'read_snapshot', lambda: (_ for _ in ()).throw(OSError('unavailable')))
    monkeypatch.setattr(assistant, 'completion', lambda *a: json.dumps(dict(action='reply', reply='可以继续。')))
    response = client.post('/assistant/chat', json=body, headers={'X-Backup-Token': TOKEN})
    assert response.status_code == 200, response.text
    assert response.json()['reply'] == '可以继续。'
    assert response.json()['memory_context']['status'] == 'unavailable'


def test_model_cannot_forge_memory_receipt(client, monkeypatch):
    monkeypatch.setattr(assistant, 'completion', lambda *a: json.dumps(dict(action='reply', reply='可以。',
        memory_context=dict(status='ready', write_status='applied', evidence_ids=['forged']))))
    result = client.post('/assistant/chat', json=request()).json()
    assert result['memory_context']['status'] == 'disabled'
    assert result['memory_context']['evidence_ids'] == []


def test_legacy_request_fingerprint_is_unchanged():
    body = assistant.ChatRequest(messages=[dict(role='user', content='你好')], now=datetime(2026, 11, 7, tzinfo=timezone.utc))
    assert set(assistant._chat_payload(body)) == {'messages', 'now', 'route_active'}


def test_conversation_forget_does_not_return_deleted_reference_or_use_it_for_mode(client, monkeypatch):
    state = snapshot(client)
    response = client.post('/memory/commands', headers={'X-Backup-Token': TOKEN}, json=dict(
        operation_id=str(uuid4()), store_id=state['store_id'], expected_revision=state['revision'],
        operation='upsert', item=dict(text='以后默认步行出门', topic='travel')))
    item = response.json()['snapshot']['items'][0]
    text = '忘记这条：以后默认步行出门，回宿舍'
    monkeypatch.setattr(assistant, 'completion', lambda *a: json.dumps(dict(
        action='plan_route', reply='请确认。', destination='宿舍', mode='walking',
        memory_actions=[dict(op='forget', id=item['id'], text='', quote='忘记这条：以后默认步行出门')]), ensure_ascii=False))
    result = client.post('/assistant/chat', headers={'X-Backup-Token': TOKEN},
                         json=enabled(client, messages=[dict(role='user', content=text)])).json()
    assert result['memory_context']['write_status'] == 'applied'
    assert result['mode'] == ''
    assert item['id'] not in result['memory_context']['evidence_ids']
    assert not result['memory_context']['items']


def test_unrelated_cancellation_does_not_hide_explicit_cycling(client, monkeypatch):
    monkeypatch.setattr(assistant, 'completion', lambda *a: json.dumps(dict(
        action='plan_route', reply='请确认。', destination='宿舍', mode='')))
    result = client.post('/assistant/chat', json=request(messages=[dict(role='user', content='今天不打印，骑车回宿舍')])).json()
    assert result['mode'] == 'cycling'


def test_recovery_cannot_execute_memory_based_plan_when_current_memory_cannot_be_checked(client, monkeypatch):
    state = snapshot(client)
    client.post('/memory/commands', headers={'X-Backup-Token': TOKEN}, json=dict(
        operation_id=str(uuid4()), store_id=state['store_id'], expected_revision=state['revision'],
        operation='upsert', item=dict(text='以后默认步行出门', topic='travel')))
    monkeypatch.setattr(assistant, 'completion', lambda *a: json.dumps(dict(
        action='plan_route', reply='请确认。', destination='宿舍', mode='')))
    body = enabled(client)
    assert client.post('/assistant/chat', json=body, headers={'X-Backup-Token': TOKEN}).json()['mode'] == 'walking'
    from backend.memory import personal as personal_memory
    monkeypatch.setattr(personal_memory, 'read_snapshot', lambda: (_ for _ in ()).throw(OSError('unavailable')))
    result = client.post('/assistant/chat/status', json=body, headers={'X-Backup-Token': TOKEN}).json()['result']
    assert result['action'] == 'reply' and not result['mode']
    assert result['memory_context']['status'] == 'unavailable'
