import json

import pytest
from fastapi.testclient import TestClient

from app import costguard, image_vision, llm
from app.auth import verify
from app.main import app

PNG = b"\x89PNG\r\n\x1a\n" + b"0" * 64
SCAM = {"source": "sms", "sender": "+919840012345",
        "text": "Digital arrest: police case on you. Pay now on video call or you will be arrested."}
GOOD = {"explanation_en": "This message asks for money in a way scammers do. Do not pay; ask someone you trust.", "explanation_ta": "இந்த மெசேஜ் பணம் கேட்கிறது. பணம் அனுப்பாதீர்கள்."}


class Fake:
    def __init__(self):
        self.calls = 0

    def complete(self, system, user, timeout):
        self.calls += 1
        return json.dumps(GOOD)


@pytest.fixture
def client():
    app.dependency_overrides[verify] = lambda: "u"
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_client_ip_uses_the_address_our_proxy_appended():
    class R:
        headers = {"x-forwarded-for": "1.1.1.1, 9.9.9.9"}
        client = None
    assert costguard.client_ip(R) == "9.9.9.9"


def test_daily_cap_is_global_across_ips(monkeypatch):
    monkeypatch.setenv("AI_DAILY_CAP", "2")
    assert costguard.ai.allow("a") and costguard.ai.allow("b")
    assert not costguard.ai.allow("c")


def test_per_ip_cap(monkeypatch):
    monkeypatch.setenv("AI_IP_PER_HOUR", "1")
    assert costguard.ai.allow("a")
    assert not costguard.ai.allow("a")
    assert costguard.ai.allow("b")


def test_ai_past_the_cap_falls_back_to_template_text(client, monkeypatch):
    fake = Fake()
    app.dependency_overrides[llm.get_llm] = lambda: fake
    monkeypatch.setenv("AI_DAILY_CAP", "1")
    first = client.post("/analyze", json=SCAM).json()
    llm._cache.clear()
    second = client.post("/analyze", json={**SCAM, "text": SCAM["text"] + " Hurry."}).json()
    assert first["source_of_verdict"] == "rules+llm"
    assert second["source_of_verdict"] == "rules" and second["severity"] == first["severity"]
    assert fake.calls == 1


def test_vision_past_the_cap_returns_vision_unavailable(client, monkeypatch):
    calls = []
    monkeypatch.setattr(image_vision, "config", lambda: ("k", "m"))
    monkeypatch.setattr(image_vision, "describe", lambda d, c: calls.append(1) or {"description": "A chat.", "text": ""})
    monkeypatch.setenv("VISION_DAILY_CAP", "1")
    h = {"content-type": "image/png"}
    assert client.post("/image/analyze?ai=0", content=PNG, headers=h).json()["vision_available"] is True
    over = client.post("/image/analyze?ai=0", content=PNG, headers=h).json()
    assert over["vision_available"] is False and over["severity"] == "none"
    assert len(calls) == 1
