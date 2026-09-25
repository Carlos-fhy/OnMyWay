from datetime import datetime, timezone
import json
import io
import wave
import base64

import pytest
from fastapi import HTTPException
from backend import assistant


def request():
    return assistant.ChatRequest(messages=[assistant.Message(role='user', content='打印论文')], now=datetime.now(timezone.utc))


@pytest.mark.parametrize('payload', [
    {'action': 'delete_all', 'reply': ''},
    {'action': 'create_task', 'reply': '', 'title': ''},
    {'action': 'plan_route', 'reply': '', 'destination': ''},
    {'action': 'create_task', 'reply': '', 'title': '打印', 'deadline': '2026-10-01T12:00:00'},
    {'action': 'create_task', 'reply': '', 'title': '打印', 'deadline': 'next Tuesday'},
    {'action': 'create_task', 'reply': '', 'title': '打印', 'extra_command': 'execute'},
])
def test_rejects_invalid_model_operations(monkeypatch, payload):
    monkeypatch.setattr(assistant, 'completion', lambda *args: json.dumps(payload))
    with pytest.raises(HTTPException) as failure:
        assistant.chat(request())
    assert failure.value.status_code == 502


def test_valid_proposal_only_returns_draft(monkeypatch):
    monkeypatch.setattr(assistant, 'completion', lambda *args: json.dumps({
        'action': 'create_task', 'reply': '请确认', 'title': '打印论文', 'place_type': 'PRINT_SHOP',
        'deadline': '2026-10-01T12:00:00+08:00'}))
    assert assistant.chat(request()).title == '打印论文'


def test_bad_audio_never_calls_provider(monkeypatch):
    monkeypatch.setattr(assistant, 'completion', lambda *args: pytest.fail('invalid audio was sent'))
    with pytest.raises(HTTPException) as failure:
        assistant.transcribe(assistant.AudioRequest(audio='not base64'))
    assert failure.value.status_code == 422


@pytest.mark.parametrize('payload', [
    {'sentence': {'text': '打印论文'}},
    {'output': {'text': '打印论文'}},
    {'output': {'output': {'sentence': {'text': '打印论文'}}}},
])
def test_asr_uses_native_endpoint_and_accepts_documented_responses(monkeypatch, payload):
    monkeypatch.setenv('DASHSCOPE_API_KEY', 'test-key')
    monkeypatch.setenv('DASHSCOPE_BASE_URL', 'https://example.com/compatible-mode/v1')
    def post(url, **kwargs):
        assert url == 'https://example.com/api/v1/services/aigc/multimodal-generation/generation'
        assert kwargs['json']['parameters']['format'] == 'wav'
        return type('Response', (), {'status_code': 200, 'json': lambda self: payload})()
    monkeypatch.setattr(assistant.httpx, 'post', post)
    buffer = io.BytesIO()
    with wave.open(buffer, 'wb') as audio:
        audio.setnchannels(1); audio.setsampwidth(2); audio.setframerate(16000)
        audio.writeframes(b'\0' * 6400)
    assert assistant.transcribe(assistant.AudioRequest(audio=base64.b64encode(buffer.getvalue()).decode()))['text'] == '打印论文'


def test_chat_receives_current_navigation_status(monkeypatch):
    def complete(messages, *args):
        assert '当前正在导航=False' in messages[0]['content']
        assert '不能沿用旧路线' in messages[0]['content']
        return json.dumps({'action': 'reply', 'reply': '请告诉我本次目的地。'})
    monkeypatch.setattr(assistant, 'completion', complete)
    assert assistant.chat(request()).action == 'reply'
