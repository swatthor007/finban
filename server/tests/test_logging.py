import logging

import pytest
from fastapi.testclient import TestClient

from app import main
from app.auth import verify
from app.main import app

SECRET_TEXT = "Dear customer your SBI account is blocked. Update KYC now"
SENDER = "Secret Sender <sbi.support@gmail.com>"
LINK = "http://sbi-kyc-update.top/zzz-private-path"


@pytest.fixture
def client():
    app.dependency_overrides[verify] = lambda: "firebase-uid-ABC123"
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_logs_contain_no_message_data(client, caplog):
    caplog.set_level(logging.INFO, logger="guard")
    r = client.post("/analyze", json={"source": "sms", "text": SECRET_TEXT, "sender": SENDER, "links": [LINK]})
    assert r.status_code == 200
    out = "\n".join(rec.getMessage() for rec in caplog.records)
    for secret in (SECRET_TEXT, "SBI account", "Secret Sender", "sbi.support", "gmail.com", "zzz-private-path",
                   "sbi-kyc-update", "firebase-uid-ABC123"):
        assert secret not in out, secret
    assert f"uid={main.uid_hash('firebase-uid-ABC123')}" in out
    assert "severity=high" in out and "stages=patterns,sender,email,links" in out and "req=" in out and "ms=" in out


def test_link_endpoint_query_not_logged(client, caplog):
    caplog.set_level(logging.INFO, logger="guard")
    client.get("/health?url=http://secret.example/path")
    out = "\n".join(rec.getMessage() for rec in caplog.records)
    assert "secret.example" not in out and "path=/health" in out


def test_uid_hash_stable_and_salted(monkeypatch):
    a = main.uid_hash("u1")
    assert a == main.uid_hash("u1") and a != main.uid_hash("u2") and len(a) == 12 and "u1" not in a
    monkeypatch.setattr(main, "LOG_SALT", "other")
    assert main.uid_hash("u1") != a
