import json

import pytest
from fastapi.testclient import TestClient

from app import image_vision
from app.auth import verify
from app.main import app

PNG = b"\x89PNG\r\n\x1a\n" + b"0" * 64


@pytest.fixture
def client():
    app.dependency_overrides[verify] = lambda: "u"
    yield TestClient(app)
    app.dependency_overrides.clear()


def post(client):
    return client.post("/image/analyze", content=PNG, headers={"content-type": "image/png"})


def test_parse_accepts_the_asked_json_and_drops_banned_wording():
    ok = image_vision.parse(json.dumps({"description": "A chat on a phone.", "text": "hello"}))
    assert ok == {"description": "A chat on a phone.", "text": "hello"}
    assert image_vision.parse(json.dumps({"description": "This looks safe and real.", "text": "x"}))["description"] is None
    assert image_vision.parse("not json") is None
    assert image_vision.parse("[1]") is None


def test_text_read_from_the_image_is_judged_by_the_rules_not_the_model(client, monkeypatch):
    monkeypatch.setattr(image_vision, "describe", lambda data, ct: {
        "description": "A text message on a phone screen.",
        "text": "Congratulations! You won Rs 50,00,000. Pay Rs 499 processing fee to claim.prize@ybl and share your Aadhaar."})
    r = post(client).json()
    assert r["vision_available"] and r["text_found"]
    assert r["severity"] == "high" and r["pattern_id"] == "reward-fee"
    assert r["description"] == "A text message on a phone screen."


def test_plain_picture_with_no_text_has_no_warning(client, monkeypatch):
    monkeypatch.setattr(image_vision, "describe", lambda data, ct: {"description": "Two people standing together.", "text": ""})
    r = post(client).json()
    assert r["severity"] == "none" and not r["text_found"] and r["description"]


def test_no_vision_model_means_the_app_falls_back(client, monkeypatch):
    monkeypatch.setattr(image_vision, "describe", lambda data, ct: None)
    r = post(client).json()
    assert r["vision_available"] is False and r["description"] is None and r["severity"] == "none"


def test_rejects_bad_type_and_size(client):
    assert client.post("/image/analyze", content=b"x", headers={"content-type": "text/plain"}).status_code == 415
    assert client.post("/image/analyze", content=b"", headers={"content-type": "image/png"}).status_code == 413


def test_screenshots_skip_the_paid_ai_photo_check(client, monkeypatch):
    from app import image_check
    calls = []
    monkeypatch.setattr(image_vision, "describe", lambda data, ct: {"description": "A phone screen.", "text": ""})
    monkeypatch.setattr(image_check, "check", lambda data, ct: calls.append(1) or "likely_ai")
    assert client.post("/image/analyze?ai=0", content=PNG, headers={"content-type": "image/png"}).json()["ai_image"] == "unknown"
    assert calls == []
    assert post(client).json()["ai_image"] == "likely_ai" and calls == [1]
