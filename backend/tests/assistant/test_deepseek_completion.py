"""Official provider request compatibility, using a local HTTP stub only."""
import pytest

from backend.assistant import api as assistant


@pytest.mark.parametrize("base,official", [
    ("https://api.deepseek.com", True), ("https://api.deepseek.com/v1/", True),
    ("https://model.example.test/v1", False), ("https://api.deepseek.com.example.test/v1", False),
])
def test_structured_completion_uses_provider_specific_thinking_switch(monkeypatch, base, official):
    monkeypatch.setenv("DASHSCOPE_BASE_URL", base)
    monkeypatch.setenv("DASHSCOPE_API_KEY", "local-test-key")
    sent = []

    class Response:
        status_code = 200

        def json(self):
            return {"choices": [{"message": {"content": '{"ok":true}'}}]}

    def post(url, **kwargs):
        sent.append((url, kwargs["json"]))
        return Response()

    monkeypatch.setattr(assistant.httpx, "post", post)
    assert assistant.completion([{"role": "user", "content": "Return JSON."}], "deepseek-flash",
                                structured=True, max_tokens=2048) == '{"ok":true}'
    url, body = sent[0]
    assert url == base.rstrip("/") + "/chat/completions"
    assert body["model"] == "deepseek-flash" and body["max_tokens"] == 2048
    assert body["response_format"] == {"type": "json_object"}
    if official:
        assert body["thinking"] == {"type": "disabled"}
        assert "enable_thinking" not in body
    else:
        assert body["enable_thinking"] is False
        assert "thinking" not in body
