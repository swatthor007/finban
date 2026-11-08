import re

import pytest
from fastapi.testclient import TestClient

from app import rules, templates
from app.auth import verify
from app.llm import BANNED, BANNED_TA, TAMIL
from app.main import app


@pytest.fixture
def client():
    app.dependency_overrides[verify] = lambda: "uid-1"
    yield TestClient(app)
    app.dependency_overrides.clear()


def post(client, text, sender=None, links=()):
    r = client.post("/analyze", json={"source": "sms", "text": text, "sender": sender, "links": list(links)})
    assert r.status_code == 200, r.text
    return r.json()


def test_pattern_response(client):
    j = post(client, "This is CBI. A parcel in your name has drugs. You are under digital arrest. Stay on video call.")
    assert j["severity"] == "high" and j["pattern_id"] == "digital-arrest"
    assert j["reasons"][0]["id"] == "pattern.digital-arrest"
    assert j["reasons"][0]["text_en"] == next(p for p in rules.patterns() if p["id"] == "digital-arrest")["explanation_en"]
    assert j["actions"] == ["alert_caretaker", "report_chakshu", "call_1930", "listen"]


def test_none_response_has_no_actions(client):
    j = post(client, "Your A/c XX1234 debited Rs 500. Not you? Call bank helpline.", "VM-SBIBNK")
    assert j["severity"] == "none" and j["actions"] == [] and j["reasons"] == []
    assert j["title_en"] == "No warning signs found."


def test_reasons_capped_at_three_most_severe_first(client):
    j = post(client, "Your account is blocked, update KYC now click here",
             "VM-SBIBNQ", ["http://sbi-kyc-update.top/x", "http://bit.ly/x"])
    assert len(j["reasons"]) == 3
    levels = [r["level"] for r in j["reasons"]]
    assert levels == sorted(levels, key=lambda l: -templates.SEV_RANK[l])


def test_every_reason_has_both_languages(client):
    j = post(client, "Update KYC now", "VM-SBIBNQ", ["http://sbi-kyc-update.top/x"])
    assert j["reasons"]
    for r in j["reasons"]:
        assert r["text_en"] != r["id"] and TAMIL.search(r["text_ta"])
    assert TAMIL.search(j["title_ta"]) and TAMIL.search(j["explanation_ta"])


@pytest.mark.parametrize("en,ta", [(templates.LINK_TEXT, templates.LINK_TEXT_TA), (templates.SENDER_TEXT, templates.SENDER_TEXT_TA),
                                   (templates.EMAIL_TEXT, templates.EMAIL_TEXT_TA)])
def test_every_english_text_has_a_tamil_twin(en, ta):
    assert set(en) == set(ta) and all(TAMIL.search(t) for t in ta.values())


def test_no_tamil_placeholders_or_banned_words_left():
    texts = [t for d in (templates.LINK_TEXT_TA, templates.SENDER_TEXT_TA, templates.EMAIL_TEXT_TA, templates.EXPLANATION_TA) for t in d.values()]
    texts += [t[0] for t in templates.TITLES.values()]
    for p in rules.patterns():
        texts += [p["name_ta"], p["title_ta"], p["explanation_ta"]]
    assert all(TAMIL.search(t) and "TODO" not in t and not BANNED_TA.search(t) for t in texts)


def test_two_links_with_the_same_problem_are_one_reason(client):
    j = post(client, "Update KYC now", None, ["http://sbi-kyc-update.top/x", "http://sbi-kyc-update.top/y"])
    ids = [r["id"] for r in j["reasons"]]
    assert len(ids) == len(set(ids))


def test_llm_input_has_no_banned_word():
    """The model echoes findings; a banned word in our own text gets its whole answer thrown away."""
    assert not [p["id"] for p in rules.patterns() if BANNED.search(p["explanation_en"])]


def test_a_lower_verdict_does_not_use_the_scam_title():
    pat = rules.patterns()[0]
    out = templates.build({"severity": "careful", "pattern_id": pat["id"], "reasons": []}, 1)
    assert out["title_ta"] == templates.TITLES["careful"][0]


@pytest.mark.parametrize("flag", sorted(templates.LINK_TEXT))
def test_link_flag_texts_exist(flag):
    assert templates.reason_text_en("link." + flag) == templates.LINK_TEXT[flag]


def test_email_wording():
    t = templates.reason_text_en("email.brand_mismatch_free_mail")
    assert "free email service" in t
    assert "doesn't match" in templates.reason_text_en("email.brand_mismatch")
    assert "doesn't match" in templates.reason_text_en("email.confusable")


def test_wording_rules_no_safe_real_fake_email():
    texts = list(templates.LINK_TEXT.values()) + list(templates.EMAIL_TEXT.values()) + \
        list(templates.SENDER_TEXT.values()) + [templates.EMAIL_DEFAULT]
    for t in texts:
        assert not re.search(r"\b(safe|real|genuine)\b", t, re.I), t
        assert "email is fake" not in t.lower()
    assert templates.TITLES["none"][1] == "No warning signs found."


def test_audio_url_only_when_file_exists(tmp_path, monkeypatch):
    monkeypatch.setattr(templates, "AUDIO_DIR", tmp_path)
    assert templates.audio_url_for("digital-arrest") is None
    (tmp_path / "digital-arrest.mp3").write_bytes(b"x")
    assert templates.audio_url_for("digital-arrest") == "/audio/digital-arrest.mp3"
    assert templates.audio_url_for(None) is None
