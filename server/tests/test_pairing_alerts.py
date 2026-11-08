from datetime import timedelta

import pytest
from fastapi.testclient import TestClient

from app import pairing
from app.auth import verify
from app.main import app
from app.push import FakeSender, get_sender
from app.store import MemoryStore, get_store, now


class Ctx:
    def __init__(self):
        self.uid = "elder1"
        self.store = MemoryStore()
        self.sender = FakeSender()
        self.store.users["elder1"] = {"role": "elder", "display_name": "Lakshmi", "fcm_tokens": []}
        for c in ("c1", "c2", "c3", "c4"):
            self.store.users[c] = {"role": "caretaker", "display_name": c, "fcm_tokens": [f"tok-{c}"]}
        self.http = TestClient(app)

    def as_(self, uid):
        self.uid = uid
        return self.http

    def code(self, elder="elder1"):
        return self.as_(elder).post("/pair/code").json()["code"]

    def claim(self, uid, code):
        return self.as_(uid).post("/pair/claim", json={"code": code})

    def pair(self, caretaker):
        return self.claim(caretaker, self.code())


@pytest.fixture
def ctx():
    c = Ctx()
    app.dependency_overrides[verify] = lambda: c.uid
    app.dependency_overrides[get_store] = lambda: c.store
    app.dependency_overrides[get_sender] = lambda: c.sender
    pairing.claim_limiter.reset()
    pairing.code_limiter.reset()
    yield c
    app.dependency_overrides.clear()


def test_error_body_shape():
    app.dependency_overrides.clear()
    r = TestClient(app).post("/pair/code")
    assert r.status_code == 401 and r.json() == {"error": "unauthenticated"}


def test_code_format_and_expiry(ctx):
    j = ctx.as_("elder1").post("/pair/code").json()
    assert len(j["code"]) == 6 and j["code"].isdigit()
    doc = ctx.store.codes[j["code"]]
    assert doc["elder_uid"] == "elder1" and doc["used"] is False
    assert timedelta(minutes=9) < doc["expires_at"] - now() <= timedelta(minutes=10)


def test_claim_creates_deterministic_link(ctx):
    r = ctx.pair("c1")
    assert r.status_code == 200
    assert r.json() == {"link_id": "elder1_c1", "elder_name": "Lakshmi"}
    assert ctx.store.links["elder1_c1"]["caretaker_uid"] == "c1"


def test_code_single_use(ctx):
    code = ctx.code()
    assert ctx.claim("c1", code).status_code == 200
    r = ctx.claim("c2", code)
    assert r.status_code == 409 and r.json() == {"error": "code_used"}
    assert "elder1_c2" not in ctx.store.links


def test_code_expired(ctx):
    code = ctx.code()
    ctx.store.codes[code]["expires_at"] = now() - timedelta(seconds=1)
    r = ctx.claim("c1", code)
    assert r.status_code == 410 and r.json() == {"error": "code_expired"}
    assert not ctx.store.links


@pytest.mark.parametrize("bad", ["000000", "12345", "abcdef", "", "1234567"])
def test_code_invalid(ctx, bad):
    r = ctx.claim("c1", bad)
    assert r.status_code == 404 and r.json() == {"error": "code_invalid"}


def test_claim_own_code_is_invalid(ctx):
    code = ctx.code()
    assert ctx.claim("elder1", code).status_code == 404
    assert not ctx.store.links and ctx.store.codes[code]["used"] is False


def test_max_three_caretakers(ctx):
    for c in ("c1", "c2", "c3"):
        assert ctx.pair(c).status_code == 200
    code = ctx.code()
    r = ctx.claim("c4", code)
    assert r.status_code == 409 and r.json() == {"error": "elder_full"}
    assert ctx.store.codes[code]["used"] is False  # failed claim doesn't burn the code
    assert "elder1_c4" not in ctx.store.links


def test_reclaim_existing_link_is_idempotent(ctx):
    ctx.pair("c1")
    assert ctx.pair("c1").json()["link_id"] == "elder1_c1"
    assert len(ctx.store.links) == 1


def test_claim_rate_limit_counts_every_attempt(ctx):
    codes = [ctx.claim("c1", "000000").status_code for _ in range(5)]
    assert codes == [404] * 5
    r = ctx.claim("c1", ctx.code())  # even a valid code is refused now
    assert r.status_code == 429 and r.json() == {"error": "rate_limited"}
    assert ctx.claim("c2", "000000").status_code == 404  # per uid


def test_code_rate_limit(ctx):
    for _ in range(5):
        assert ctx.as_("elder1").post("/pair/code").status_code == 200
    assert ctx.as_("elder1").post("/pair/code").status_code == 429


def test_code_collision_retries(ctx, monkeypatch):
    first = ctx.code()
    seq = iter([int(first), int(first), 424242])
    monkeypatch.setattr(pairing.secrets, "randbelow", lambda n: next(seq))
    assert ctx.as_("elder1").post("/pair/code").json()["code"] == "424242"


def test_unlink_by_either_side(ctx):
    ctx.pair("c1")
    ctx.pair("c2")
    assert ctx.as_("c1").delete("/pair/elder1_c1").status_code == 204
    assert ctx.as_("elder1").delete("/pair/elder1_c2").status_code == 204
    assert not ctx.store.links


def test_unlink_forbidden_for_stranger_and_missing(ctx):
    ctx.pair("c1")
    r = ctx.as_("c2").delete("/pair/elder1_c1")
    assert r.status_code == 403 and r.json() == {"error": "forbidden"}
    assert "elder1_c1" in ctx.store.links
    assert ctx.as_("c2").delete("/pair/nope_nope").status_code == 403


def test_unlink_strips_caretaker_from_alerts(ctx):
    ctx.pair("c1")
    ctx.pair("c2")
    ctx.store.alerts["a1"] = {"elder_uid": "elder1", "caretaker_uids": ["c1", "c2"]}
    ctx.store.alerts["other"] = {"elder_uid": "elderX", "caretaker_uids": ["c1"]}
    ctx.as_("elder1").delete("/pair/elder1_c1")
    assert ctx.store.alerts["a1"]["caretaker_uids"] == ["c2"]
    assert ctx.store.alerts["other"]["caretaker_uids"] == ["c1"]


# ---- /alert ----

def alert(ctx, cuids=("c1",), pattern="digital-arrest", aid="a1"):
    ctx.store.alerts[aid] = {"elder_uid": "elder1", "caretaker_uids": list(cuids), "pattern_id": pattern,
                             "severity": "high", "trigger": "pause_yes", "status": "new"}
    return ctx.as_("elder1").post("/alert", json={"alert_id": aid})


def test_alert_pushes_to_linked_caretaker(ctx):
    ctx.pair("c1")
    r = alert(ctx)
    assert r.status_code == 200 and r.json() == {"sent": 1, "pruned": 0}
    call = ctx.sender.calls[0]
    assert call["tokens"] == ["tok-c1"]
    assert "Lakshmi may be on a scam call. Call them now." in call["body"]
    assert "Digital arrest" in call["body"]
    assert call["data"] == {"type": "scam_alert", "alert_id": "a1"}
    assert ctx.store.alerts["a1"]["pushed_at"]


def test_alert_null_pattern(ctx):
    ctx.pair("c1")
    assert alert(ctx, pattern=None).json()["sent"] == 1
    assert ctx.sender.calls[0]["body"] == "Lakshmi may be on a scam call. Call them now."


def test_alert_uses_each_caretakers_language(ctx):
    ctx.pair("c1")
    ctx.store.get_user("c1")["lang"] = "ta"
    alert(ctx)
    call = ctx.sender.calls[0]
    assert call["title"] == "மோசடி எச்சரிக்கை"
    assert call["body"].startswith("Lakshmi மோசடி அழைப்பில் இருக்கலாம்.") and "டிஜிட்டல் கைது மோசடி" in call["body"]


def test_alert_ownership(ctx):
    ctx.pair("c1")
    alert(ctx)
    assert ctx.as_("c1").post("/alert", json={"alert_id": "a1"}).status_code == 403
    assert ctx.as_("elder1").post("/alert", json={"alert_id": "missing"}).status_code == 404
    assert len(ctx.sender.calls) == 1  # only the owner's own call pushed


def test_alert_skips_unlinked_caretakers(ctx):
    ctx.pair("c1")  # c2 listed but never linked
    assert alert(ctx, cuids=("c1", "c2")).json()["sent"] == 1
    assert [c["tokens"] for c in ctx.sender.calls] == [["tok-c1"]]


def test_alert_prunes_invalid_tokens(ctx):
    ctx.pair("c1")
    ctx.store.users["c1"]["fcm_tokens"] = ["good", "stale"]
    ctx.sender.invalid = {"stale"}
    assert alert(ctx).json() == {"sent": 1, "pruned": 1}
    assert ctx.store.users["c1"]["fcm_tokens"] == ["good"]


def test_alert_replay_does_not_resend(ctx):
    ctx.pair("c1")
    alert(ctx)
    r = ctx.as_("elder1").post("/alert", json={"alert_id": "a1"})
    assert r.json() == {"sent": 0, "pruned": 0} and len(ctx.sender.calls) == 1


def test_alert_with_no_tokens_can_be_retried(ctx):
    ctx.pair("c1")
    ctx.store.users["c1"]["fcm_tokens"] = []
    assert alert(ctx).json()["sent"] == 0
    assert "pushed_at" not in ctx.store.alerts["a1"]


def test_push_carries_no_sensitive_fields(ctx):
    ctx.pair("c1")
    ctx.store.alerts["a1"] = {"elder_uid": "elder1", "caretaker_uids": ["c1"], "pattern_id": "otp-request",
                              "text": "OTP 482913 Rs 50000", "severity": "high", "trigger": "manual", "status": "new"}
    ctx.as_("elder1").post("/alert", json={"alert_id": "a1"})
    blob = str(ctx.sender.calls[0])
    assert "482913" not in blob and "50000" not in blob
