import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app import rules
from app.auth import verify
from app.main import app

DATA = Path(__file__).resolve().parents[2] / "data" / "testset"
FIX = json.loads((DATA / "rules_fixtures.json").read_text())
MSGS = [json.loads(l) for l in (DATA / "messages.jsonl").read_text().splitlines()]


@pytest.mark.parametrize("f", FIX["sender_header"])
def test_header(f):
    p = rules.parse_header(f["in"])
    assert p["valid"] == f["valid"]
    if f["valid"]:
        assert (p["prefix"], p["header"], p["suffix"]) == (f["prefix"], f["header"], f["suffix"])


def test_bank_from_mobile():
    for f in FIX["bank_from_mobile"]:
        assert rules.analyze(f["text"], f["sender"], [])["severity"] == f["severity"]


def test_near_miss():
    for f in FIX["near_miss_header"]:
        assert rules.analyze("hello", f["sender"], [])["severity"] == f["severity"]


@pytest.mark.parametrize("f", FIX["link_flags"])
def test_link_flags(f):
    _, flags = rules.link_flags(f["url"])
    assert set(f["flags"]) <= set(flags)
    if not f["flags"]:
        assert flags == []


@pytest.mark.parametrize("m", MSGS, ids=lambda m: m["text"][:30])
def test_testset(m):
    r = rules.analyze(m["text"], m["sender"], [])
    assert r["severity"] == m["expect"], r
    if m["pattern_id"]:
        assert r["pattern_id"] == m["pattern_id"]


HARD = [json.loads(l) for l in (DATA / "hard_negatives.jsonl").read_text().splitlines() if l.strip()]


@pytest.mark.parametrize("m", HARD, ids=lambda m: m["text"][:30])
def test_hard_negatives(m):
    assert rules.analyze(m["text"], m["sender"], [])["severity"] == m["expect"]


def _verdicts(msgs):
    return {m["text"] + "|" + str(m["sender"]): rules.analyze(m["text"], m["sender"], [])["severity"] for m in msgs}


def test_testset_verdicts_independent_of_order():
    """Same verdicts whatever the message order and whatever order the domain checks run in."""
    import random
    from app import domains
    base = _verdicts(MSGS)
    rng = random.Random(11)
    orig_h, orig_p, orig_e = list(domains.HOST_CHECKS), list(domains.PATH_CHECKS), list(domains.EMAIL_CHECKS)
    try:
        for _ in range(10):
            msgs = MSGS[:]
            rng.shuffle(msgs)
            for lst in (domains.HOST_CHECKS, domains.PATH_CHECKS, domains.EMAIL_CHECKS):
                rng.shuffle(lst)
            assert _verdicts(msgs) == base
    finally:  # restore: other tests must not depend on shuffled order
        domains.HOST_CHECKS[:], domains.PATH_CHECKS[:], domains.EMAIL_CHECKS[:] = orig_h, orig_p, orig_e


def test_auth_required():
    c = TestClient(app)
    assert c.post("/analyze", json={"text": "x", "source": "sms"}).status_code == 401
    assert c.get("/health").status_code == 200




def test_bank_header_with_link_outside_that_banks_domains_is_high():
    r = rules.analyze("Dear customer, view your statement: https://secure-statement.example.org/a", "VM-SBIBNK", [])
    assert r["severity"] == "high" and "sender.bank-link-mismatch" in [x["id"] for x in r["reasons"]]
    own = rules.analyze("Dear customer, view your statement: https://sbi.co.in/a", "VM-SBIBNK", [])
    assert "sender.bank-link-mismatch" not in [x["id"] for x in own["reasons"]]
    other = rules.analyze("Dear customer, view your statement: https://secure-statement.example.org/a", "VM-NOTBNK", [])
    assert "sender.bank-link-mismatch" not in [x["id"] for x in other["reasons"]]
