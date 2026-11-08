"""Domain-age step. No real network: registry answers are faked with httpx.MockTransport."""
import time
from datetime import datetime, timedelta, timezone

import httpx
import pytest

from app import domain_age, rules, templates

BOOT = {"services": [[["xyz", "com"], ["https://rdap.example/"]], [["in"], ["https://rdap.in.example"]]]}


def _iso(days_ago: int) -> str:
    return (datetime.now(timezone.utc) - timedelta(days=days_ago)).strftime("%Y-%m-%dT%H:%M:%SZ")


def make_client(domain_doc=None, status=200, calls=None):
    def handler(req: httpx.Request):
        if calls is not None:
            calls.append(str(req.url))
        if req.url.host == "data.iana.org":
            return httpx.Response(200, json=BOOT)
        return httpx.Response(status, json=domain_doc or {})
    return httpx.Client(transport=httpx.MockTransport(handler))


@pytest.mark.parametrize("age,flag", [(0, "very_new_domain"), (14, "very_new_domain"), (15, "new_domain"),
                                      (90, "new_domain"), (91, None), (4000, None), (None, None), (-1, None)])
def test_flag_thresholds(age, flag):
    assert domain_age.flag_for(age) == flag


def test_reads_registration_event():
    doc = {"events": [{"eventAction": "last changed", "eventDate": _iso(1)}, {"eventAction": "registration", "eventDate": _iso(3)}]}
    assert domain_age.age_days("yuyaa.xyz", make_client(doc)) == 3


def test_queries_registry_with_domain_only():
    calls = []
    domain_age.age_days("yuyaa.xyz", make_client({"events": []}, calls=calls))
    assert calls[-1] == "https://rdap.example/domain/yuyaa.xyz"      # nothing but the registrable domain
    domain_age.age_days("shop.in", make_client({"events": []}, calls=calls))
    assert calls[-1] == "https://rdap.in.example/domain/shop.in"      # base without trailing slash is fixed


@pytest.mark.parametrize("client", [
    make_client({}, status=404),
    make_client({"events": []}),                                                              # no registration event
    make_client({"events": [{"eventAction": "registration", "eventDate": "not a date"}]}),
])
def test_unknown_is_none_never_old(client):
    assert domain_age.age_days("yuyaa.xyz", client) is None


def test_unknown_tld_and_network_errors_are_none():
    assert domain_age.age_days("example.zzz", make_client({"events": []})) is None
    boom = httpx.Client(transport=httpx.MockTransport(lambda r: (_ for _ in ()).throw(httpx.ConnectError("down"))))
    assert domain_age.age_days("yuyaa.xyz", boom) is None


def test_cached_after_first_lookup():
    calls = []
    c = make_client({"events": [{"eventAction": "registration", "eventDate": _iso(5)}]}, calls=calls)
    assert domain_age.age_days("yuyaa.xyz", c) == 5
    n = len(calls)
    assert domain_age.age_days("yuyaa.xyz", c) == 5
    assert len(calls) == n


def test_disabled_by_default_in_tests(monkeypatch):
    monkeypatch.setattr(domain_age, "age_days", lambda d, client=None: pytest.fail("must not look up"))
    assert domain_age.ages_for(["yuyaa.xyz"]) == {"yuyaa.xyz": None}


def test_slow_registry_hits_budget(monkeypatch):
    monkeypatch.setenv("DOMAIN_AGE", "1")
    monkeypatch.setattr(domain_age, "TOTAL_BUDGET", 0.2)
    monkeypatch.setattr(domain_age, "age_days", lambda d, client=None: (time.sleep(2), 3)[1])
    t0 = time.time()
    assert domain_age.ages_for(["yuyaa.xyz"]) == {"yuyaa.xyz": None}
    assert time.time() - t0 < 1


def test_lookups_capped(monkeypatch):
    monkeypatch.setenv("DOMAIN_AGE", "1")
    seen = []
    monkeypatch.setattr(domain_age, "age_days", lambda d, client=None: seen.append(d) or 1)
    domain_age.ages_for([f"a{i}.xyz" for i in range(10)])
    assert len(seen) == domain_age.MAX_DOMAINS


# ---- wired into rules.analyze -------------------------------------------------------------------------------

def _ages(monkeypatch, mapping):
    monkeypatch.setattr(domain_age, "ages_for", lambda ds: {d.lower(): mapping.get(d.lower()) for d in ds})


def test_very_new_domain_makes_a_bare_link_high(monkeypatch):
    _ages(monkeypatch, {"yuyaa.xyz": 3})
    r = rules.analyze("https://uiwe.yuyaa.xyz/bskvKSvuAQ/e0a4d8d6f1557e91f76ec4e3f6?p=w", None, [])
    assert r["severity"] == "high"
    assert "link.very_new_domain" in [x["id"] for x in r["reasons"]]
    assert r["links"][0]["age_days"] == 3 and "very_new_domain" in r["links"][0]["flags"]


def test_new_domain_is_careful(monkeypatch):
    _ages(monkeypatch, {"example-shop.com": 60})
    assert rules.analyze("see https://example-shop.com/offer", None, [])["severity"] == "careful"


def test_unknown_age_changes_nothing(monkeypatch):
    _ages(monkeypatch, {})
    r = rules.analyze("https://uiwe.yuyaa.xyz/x", None, [])
    assert r["links"][0]["age_days"] is None
    assert not any(x["id"].endswith("_domain") for x in r["reasons"])


def test_old_domain_adds_no_flag(monkeypatch):
    _ages(monkeypatch, {"yuyaa.xyz": 2000})
    assert "very_new_domain" not in rules.analyze("https://yuyaa.xyz/x", None, [])["links"][0]["flags"]


def test_official_popular_and_ip_links_are_never_looked_up(monkeypatch):
    def fail(ds):
        pytest.fail(f"looked up {ds}")
    monkeypatch.setattr(domain_age, "ages_for", fail)
    rules.analyze("https://www.onlinesbi.sbi/login https://www.google.com/x http://203.0.113.9/a", None, [])


def test_reason_text_has_no_numbers_to_get_wrong():
    for f in ("very_new_domain", "new_domain"):
        assert templates.reason_text_en("link." + f) == templates.LINK_TEXT[f]


@pytest.mark.parametrize("stamp", ["2026-06-30T07:09:21.0Z", "2026-06-30T07:09:21Z", "2026-06-30T07:09:21.123456Z",
                                   "2026-06-30T07:09:21.5+00:00", "2026-06-30T07:09:21"])
def test_real_registry_date_formats_parse(stamp):
    doc = {"events": [{"eventAction": "registration", "eventDate": stamp}]}
    assert domain_age.age_days("yuyaa.xyz", make_client(doc)) is not None
