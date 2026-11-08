import httpx
from fastapi.testclient import TestClient

from app import image_check
from app.main import app, verify

import pytest


@pytest.fixture
def client():
    app.dependency_overrides[verify] = lambda: "uid-img"
    try:
        yield TestClient(app)
    finally:
        app.dependency_overrides.clear()


def test_verdict_bands():
    assert image_check.verdict_for(0.97) == "likely_ai"
    assert image_check.verdict_for(0.02) == "unlikely_ai"
    assert image_check.verdict_for(0.5) == "unknown"
    assert image_check.verdict_for(None) == "unknown"


def test_no_key_is_unknown(monkeypatch):
    monkeypatch.delenv("SIGHTENGINE_USER", raising=False)
    assert image_check.check(b"x", "image/png") == "unknown"


def test_sightengine_response_mapped(monkeypatch):
    monkeypatch.setenv("SIGHTENGINE_USER", "u"); monkeypatch.setenv("SIGHTENGINE_SECRET", "s")
    c = httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(200, json={"status": "success", "type": {"ai_generated": 0.93}})))
    assert image_check.check(b"x", "image/png", c) == "likely_ai"
    bad = httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(500)))
    assert image_check.check(b"x", "image/png", bad) == "unknown"


def test_endpoint_validates_type_and_size(monkeypatch, client):
    monkeypatch.delenv("SIGHTENGINE_USER", raising=False)
    assert client.post("/image", content=b"x", headers={"content-type": "text/plain"}).status_code == 415
    assert client.post("/image", content=b"", headers={"content-type": "image/png"}).status_code == 413
    big = b"0" * (image_check.MAX_BYTES + 1)
    assert client.post("/image", content=big, headers={"content-type": "image/png"}).status_code == 413
    r = client.post("/image", content=b"abc", headers={"content-type": "image/png"})
    assert r.status_code == 200 and r.json() == {"verdict": "unknown"}
