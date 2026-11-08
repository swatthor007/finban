import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.auth import verify
from app.main import app
from app.models import AnalyzeRequest, AnalyzeResponse

DATA = Path(__file__).resolve().parents[2] / "data"
RESP_KEYS = {"severity", "pattern_id", "title_ta", "title_en", "reasons", "explanation_ta", "explanation_en", "audio_url",
             "actions", "source_of_verdict", "latency_ms"}
PATTERN_KEYS = {"id", "name_ta", "name_en", "severity", "text_cues", "screen_cues", "checks", "title_ta",
                "explanation_ta", "explanation_en", "caretaker_summary_ta", "caretaker_summary_en",
                "what_to_do", "source", "source_date", "last_reviewed"}


@pytest.fixture
def client():
    app.dependency_overrides[verify] = lambda: "uid1"
    yield TestClient(app)
    app.dependency_overrides.clear()


def load(name):
    return json.loads((DATA / "contract" / name).read_text())


def test_fixtures_validate():
    AnalyzeRequest(**load("analyze_request.json"))
    r = AnalyzeResponse(**load("analyze_response.json"))
    assert set(r.model_dump()) == RESP_KEYS


def test_analyze_response_shape(client):
    r = client.post("/analyze", json=load("analyze_request.json"))
    assert r.status_code == 200
    j = r.json()
    assert set(j) == RESP_KEYS
    assert j["severity"] == "high" and j["pattern_id"] == "digital-arrest"
    assert j["source_of_verdict"] == "rules" and len(j["reasons"]) <= 3
    assert all(set(x) == {"id", "level", "text_ta", "text_en"} for x in j["reasons"])
    AnalyzeResponse(**j)


def test_none_severity_not_low(client):
    j = client.post("/analyze", json={"source": "sms", "sender": "VM-SBIBNK", "text": "Your statement is ready."}).json()
    assert j["severity"] == "none" and j["actions"] == []


def test_rejects_old_shape(client):
    assert client.post("/analyze", json={"text": "x", "channel": "sms"}).status_code == 422


@pytest.mark.parametrize("f", sorted((DATA / "patterns").glob("*.json")), ids=lambda f: f.stem)
def test_pattern_schema(f):
    if f.name == "version.json":
        return
    o = json.loads(f.read_text())
    assert set(o) - {"require_any"} == PATTERN_KEYS and PATTERN_KEYS <= set(o)
    assert all(isinstance(x, str) for x in o.get("require_any", []))
    assert o["severity"] in ("high", "careful", "none")
    assert set(o["text_cues"]) == {"ta", "en", "tanglish"}


def test_testset_schema():
    for line in (DATA / "testset" / "messages.jsonl").read_text().splitlines():
        m = json.loads(line)
        assert set(m) == {"sender", "text", "expect", "pattern_id", "lang", "source"}
        assert m["expect"] in ("high", "careful", "none")
