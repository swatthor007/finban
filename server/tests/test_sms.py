import json
from pathlib import Path

import pytest

from app import rules

DATA = Path(__file__).resolve().parents[2] / "data"
FIX = json.loads((DATA / "testset" / "rules_fixtures.json").read_text())


@pytest.mark.parametrize("f", FIX["sender_type"], ids=lambda f: str(f["in"]))
def test_sender_type(f):
    info = rules.classify_sender(f["in"])
    for k, v in f.items():
        if k != "in":
            assert info[k] == v, (k, info)


def test_prefix_tables_cover_suffixes():
    assert set(rules._prefixes()["suffix"]) == set("SPTG")


def test_bank_header_data_is_registered_to_banks():
    for b in rules.banks():
        for h in b["headers"]:
            ent = rules.header_entity(h)
            assert ent is not None, h
            assert ent["category"] in ("bank", "payments"), (h, ent)


def test_registered_lookalike_owner_is_not_trusted():
    # INCTAX is registered, but to a non-government company: category must not say government.
    ent = rules.header_entity("INCTAX")
    assert ent and ent["category"] == "other"


def test_near_miss_and_real_headers():
    near = lambda s: any(r["id"] == "sender.near-miss-header" for r in rules.analyze("hi", s, [])["reasons"])
    assert near("VM-SBIBNQ")            # one edit from SBIBNK, unregistered
    assert not near("VM-SBIBNK")        # the real one
    assert not near("VM-SBIINB")        # another real SBI header
    assert not near("AX-HDFCBK-S")


def test_promo_header_only_careful_with_account_talk():
    assert rules.analyze("Your account will be blocked", "AX-HDFCBK-P", [])["severity"] == "careful"
    assert rules.analyze("Big sale this weekend", "AX-HDFCBK-P", [])["severity"] == "none"


def test_near_miss_false_alarm_rate_on_registered_headers():
    """Registered non-bank headers should rarely look like a core bank header."""
    db = rules._header_db()
    core = rules.known_headers()
    sample = [h for h in sorted(db["headers"]) if len(h) == 6 and h.isalpha()][::40]
    flagged = [h for h in sample
               if rules.header_entity(h)["category"] != "bank"
               and any(0 < rules.edit_distance(h, k) <= 2 for k in core)]
    assert len(flagged) / len(sample) < 0.05, flagged


def test_domain_lists_loaded_from_data():
    assert "bit.ly" in rules.shorteners() and "top" in rules.risky_tlds() and "ly" not in rules.risky_tlds()
    assert rules.link_flags("http://bit.ly/x")[1] == ["shortener"]


def test_common_domains():
    assert rules.is_common("sbi.co.in") and rules.is_common("irctc.co.in") and rules.is_common("uidai.gov.in")
    for hosting in ("blogspot.com", "github.io", "amazonaws.com", "wordpress.com"):
        assert not rules.is_common(hosting), hosting
    assert not rules.is_common("sbi-kyc-update.top")



def test_near_miss_header_rule():
    assert rules.near_miss_header("SBIBNQ") == "SBIBNK"      # substitution, unregistered
    assert rules.near_miss_header("SBIBKN") == "SBIBNK"      # adjacent transposition
    assert rules.near_miss_header("HDFCB") == "HDFCBK"       # deletion
    assert rules.near_miss_header("SBIBNK") is None          # the real header
    assert rules.near_miss_header("AUBANK") is None          # registered to some entity: not a near miss
    assert rules.near_miss_header("ZZZZZZ") is None


def test_near_miss_no_false_alarm_on_registered_headers():
    db = rules._header_db()
    assert [h for h in db["headers"] if rules.near_miss_header(h)] == []
