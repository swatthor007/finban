import json

import pytest
from fastapi.testclient import TestClient

from app import llm
from app.auth import verify
from app.main import app

GOOD = {"explanation_en": "This message asks for money in a way scammers do. Do not pay; ask someone you trust.",
        "explanation_ta": "இந்த செய்தி பணம் கேட்கிறது. பணம் அனுப்ப வேண்டாம்."}
SCAM = {"source": "sms", "sender": "+919840012345",
        "text": "Digital arrest: police case on you. Pay now on video call or you will be arrested."}


class Fake:
    def __init__(self, reply=None, exc=None):
        self.reply, self.exc, self.calls = reply, exc, []

    def complete(self, system, user, timeout):
        self.calls.append(user)
        if self.exc:
            raise self.exc
        return self.reply if isinstance(self.reply, str) else json.dumps(self.reply)


def post(fake, body=SCAM):
    app.dependency_overrides[verify] = lambda: "u"
    app.dependency_overrides[llm.get_llm] = lambda: fake
    try:
        return TestClient(app).post("/analyze", json=body).json()
    finally:
        app.dependency_overrides.clear()


def test_llm_text_used_severity_untouched():
    base = post(None)
    j = post(Fake(GOOD))
    assert j["source_of_verdict"] == "rules+llm" and j["explanation_en"] == GOOD["explanation_en"]
    for k in ("severity", "pattern_id", "actions", "reasons", "title_en"):
        assert j[k] == base[k]
    assert base["source_of_verdict"] == "rules"


def test_ai_tamil_replaces_template_tamil():
    base = post(None)
    j = post(Fake(GOOD))
    assert base["explanation_ta"] != GOOD["explanation_ta"] and j["explanation_ta"] == GOOD["explanation_ta"]


@pytest.mark.parametrize("ta", [None, "", "not tamil text at all here", "இந்த செய்தி பாதுகாப்பானது, பணம் அனுப்பலாம்.",
                                "இந்த வங்கி மெசேஜ் உண்மையானது, பயப்பட வேண்டாம்."])
def test_bad_tamil_keeps_template_in_both_languages(ta):
    base = post(None)
    j = post(Fake({**GOOD, "explanation_ta": ta}))
    assert j == {**base, "latency_ms": j["latency_ms"]}


def test_input_has_findings_only_no_message_text():
    f = Fake(GOOD)
    post(f)
    sent = f.calls[0]
    for secret in ("9840012345", "you will be arrested", "police case on you", "Pay now"):
        assert secret not in sent
    assert "severity" in sent and "findings" in sent


@pytest.mark.parametrize("bad", [
    "not json", "[]", {"explanation_en": "short"}, {"explanation_en": 5},
    {"explanation_en": "This is a real bank message so you can trust it, please go ahead."},
    {"explanation_en": "This is safe to open, nothing wrong with it at all, go ahead."},
    {"explanation_en": "Visit https://evil.example now and send the money quickly please."},
    "```json\n{\"explanation_en\": \"x\"}\n```",
])
def test_invalid_output_falls_back_to_template(bad):
    base = post(None)
    j = post(Fake(bad))
    assert j == {**base, "latency_ms": j["latency_ms"]}


def test_fenced_json_accepted():
    j = post(Fake("```json\n" + json.dumps(GOOD) + "\n```"))
    assert j["source_of_verdict"] == "rules+llm"


@pytest.mark.parametrize("exc", [TimeoutError(), RuntimeError("boom")])
def test_client_errors_fall_back(exc):
    j = post(Fake(exc=exc))
    assert j["source_of_verdict"] == "rules" and j["severity"] == "high"


def test_severity_none_skips_llm():
    f = Fake(GOOD)
    j = post(f, {"source": "sms", "sender": "VM-SBIBNK", "text": "Your statement is ready."})
    assert j["severity"] == "none" and not f.calls and j["source_of_verdict"] == "rules"


def test_cache_avoids_second_call():
    f = Fake(GOOD)
    post(f)
    post(f)
    assert len(f.calls) == 1


def test_no_key_means_no_client(monkeypatch):
    assert llm.get_llm() is None
    monkeypatch.setenv("OPENROUTER_API_KEY", "k")
    monkeypatch.setenv("LLM_MODEL", "m")
    assert isinstance(llm.get_llm(), llm.OpenRouterClient)
    monkeypatch.setenv("LLM_DISABLED", "1")
    assert llm.get_llm() is None


def test_slow_answer_falls_back_then_serves_from_cache(monkeypatch):
    import time
    monkeypatch.setattr(llm, "DEADLINE_S", 0.05)

    class Slow(Fake):
        def complete(self, system, user, timeout):
            time.sleep(0.3)
            return super().complete(system, user, timeout)
    slow = Slow(GOOD)
    j = post(slow)
    assert j["source_of_verdict"] == "rules"  # didn't wait
    for _ in range(50):  # background job fills the cache
        if llm._cache:
            break
        time.sleep(0.05)
    j2 = post(slow)
    assert j2["source_of_verdict"] == "rules+llm" and len(slow.calls) == 1


def test_enrich_deadline_argument_overrides_default(monkeypatch):
    """Rules + domain age may use most of the budget; the caller passes what is left (0 = cache only)."""
    import time as _t
    from app import llm as L

    class Slow:
        def complete(self, system, user, timeout):
            _t.sleep(0.5)
            return '{"explanation_en": "x", "explanation_ta": ""}'
    out = {"severity": "high", "reasons": [], "actions": [], "explanation_en": "t", "explanation_ta": "வார்ப்புரு",
           "pattern_id": None, "source_of_verdict": "rules"}
    t0 = _t.time()
    assert L.enrich(out, None, Slow(), deadline=0.0) is False
    assert _t.time() - t0 < 0.3
