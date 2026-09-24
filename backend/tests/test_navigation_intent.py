import json
import pytest
from fastapi import HTTPException
from backend import assistant


def request():
    return assistant.NavigationRequest(text='取完快递直接回宿舍', destination='图书馆', tasks=[
        assistant.NavigationTask(id='parcel', title='取快递', place_type='PARCEL'),
        assistant.NavigationTask(id='print', title='打印论文', place_type='PRINT_SHOP')])


def test_compound_intent_gets_trip_context(monkeypatch):
    def complete(messages, *args):
        context = json.loads(messages[1]['content'])
        assert context['destination'] == '图书馆'
        assert {t['id'] for t in context['tasks']} == {'parcel', 'print'}
        return json.dumps(dict(action='adjust', destination='宿舍', keep_task_ids=['parcel']))
    monkeypatch.setattr(assistant, 'completion', complete)
    intent = assistant.navigation(request())
    assert intent.destination == '宿舍'
    assert intent.keep_task_ids == ['parcel']


@pytest.mark.parametrize('ids', [['invented'], ['parcel', 'parcel']])
def test_rejects_invalid_selection(monkeypatch, ids):
    monkeypatch.setattr(assistant, 'completion', lambda *args: json.dumps(dict(action='adjust', keep_task_ids=ids)))
    with pytest.raises(HTTPException):
        assistant.navigation(request())


def test_followup_includes_unconfirmed_state_and_history(monkeypatch):
    body = request().model_copy(update={
        'text': '那快递也不取了', 'proposed_destination': '宿舍',
        'proposed_task_ids': ['parcel'],
        'history': [assistant.NavigationTurn(role='user', content='取完快递直接回宿舍')],
    })
    def complete(messages, *args):
        context = json.loads(messages[1]['content'])
        assert context['proposed_destination'] == '宿舍'
        assert context['proposed_task_ids'] == ['parcel']
        assert context['history'][0]['content'] == '取完快递直接回宿舍'
        return json.dumps(dict(action='adjust', destination='', keep_task_ids=[]))
    monkeypatch.setattr(assistant, 'completion', complete)
    assert assistant.navigation(body).keep_task_ids == []
