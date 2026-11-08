from fastapi.testclient import TestClient

from app.auth import verify
from app.main import app


def test_debug_status_reports_booleans_never_keys(monkeypatch):
    app.dependency_overrides[verify] = lambda: "u"
    try:
        c = TestClient(app)
        r = c.get("/debug/status").json()
        assert r["llm_configured"] is False and r["llm_model"] is None and r["image_configured"] is False
        monkeypatch.setenv("OPENROUTER_API_KEY", "sk-secret-123")
        monkeypatch.setenv("LLM_MODEL", "some/model")
        monkeypatch.setenv("SIGHTENGINE_USER", "u")
        monkeypatch.setenv("SIGHTENGINE_SECRET", "s")
        r = c.get("/debug/status")
        assert r.json()["llm_configured"] and r.json()["llm_model"] == "some/model" and r.json()["image_configured"]
        assert "sk-secret-123" not in r.text
    finally:
        app.dependency_overrides.clear()


def test_debug_status_needs_sign_in():
    assert TestClient(app).get("/debug/status").status_code in (401, 403)
