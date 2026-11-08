"""Page fetch: SSRF guard, limits, HTML facts, evaluation, wiring. No real network or DNS."""
import httpx
import pytest

from app import domains, domain_age, page_fetch, rules

PUBLIC = "93.184.216.34"


def resolver_for(mapping):
    def r(host, port):
        if host not in mapping:
            raise OSError("nxdomain")
        return mapping[host]
    return r


def client_for(handler):
    return httpx.Client(transport=httpx.MockTransport(handler), follow_redirects=False)


def page(html, ctype="text/html; charset=utf-8", status=200, headers=None):
    return httpx.Response(status, content=html.encode(), headers={"content-type": ctype, **(headers or {})})


LOGIN = """<html><head><title>SBI Online - Update KYC</title>
<meta property="og:site_name" content="State Bank of India"></head>
<body><h1>Update your KYC</h1><form action="https://collect.other.top/post">
<input type="text" name="account_number"><input type="password" name="pw"><input name="otp_code"></form></body></html>"""


# ---- SSRF guard ---------------------------------------------------------------------------------------------
@pytest.mark.parametrize("addr", ["127.0.0.1", "10.0.0.5", "192.168.1.1", "172.16.0.9", "169.254.169.254", "100.100.1.1",
                                  "0.0.0.0", "::1", "fe80::1", "fc00::1", "::ffff:127.0.0.1", "224.0.0.1"])
def test_non_public_addresses_blocked(addr):
    with pytest.raises(page_fetch.Blocked):
        page_fetch.check_target("http://evil.example/x", resolver_for({"evil.example": [addr]}))


def test_one_private_answer_among_public_blocks():
    with pytest.raises(page_fetch.Blocked):
        page_fetch.check_target("http://evil.example/", resolver_for({"evil.example": [PUBLIC, "10.0.0.1"]}))


@pytest.mark.parametrize("url", ["ftp://a.example/", "file:///etc/passwd", "http://a.example:22/", "http://a.example:8080/",
                                 "http://user:pw@a.example/", "http://a@b.example/", "http:///nohost", "javascript:alert(1)"])
def test_bad_scheme_port_userinfo_blocked(url):
    with pytest.raises(page_fetch.Blocked):
        page_fetch.check_target(url, resolver_for({"a.example": [PUBLIC], "b.example": [PUBLIC]}))


def test_literal_ip_urls_are_checked_too():
    with pytest.raises(page_fetch.Blocked):
        page_fetch.check_target("http://169.254.169.254/latest/meta-data/")
    with pytest.raises(page_fetch.Blocked):
        page_fetch.check_target("http://[::1]/")


def test_public_target_returns_pinned_ip():
    ip, host, port, path, https = page_fetch.check_target("https://Shop.Example/a?b=1", resolver_for({"shop.example": [PUBLIC]}))
    assert (ip, host, port, path, https) == (PUBLIC, "shop.example", 443, "/a?b=1", True)


def test_connection_goes_to_the_checked_ip_with_host_header():
    seen = []

    def handler(req):
        seen.append((req.url.host, req.headers["host"]))
        return page("<title>x</title>")
    info = page_fetch.fetch("https://shop.example/p", resolver_for({"shop.example": [PUBLIC]}), client_for(handler))
    assert info and seen == [(PUBLIC, "shop.example")]       # DNS rebinding cannot change where we connect


def test_dns_failure_is_none():
    assert page_fetch.fetch("http://nope.example/", resolver_for({}), client_for(lambda r: page(""))) is None


# ---- redirects ----------------------------------------------------------------------------------------------
def test_redirect_to_private_address_is_blocked():
    res = resolver_for({"a.example": [PUBLIC], "inner.example": ["10.0.0.7"]})
    calls = []

    def handler(req):
        calls.append(req.headers["host"])
        return httpx.Response(302, headers={"location": "http://inner.example/admin"})
    assert page_fetch.fetch("http://a.example/", res, client_for(handler)) is None
    assert calls == ["a.example"]                             # the internal host was never contacted


def test_redirect_chain_followed_and_recorded():
    res = resolver_for({"short.example": [PUBLIC], "land.example": [PUBLIC]})

    def handler(req):
        if req.headers["host"] == "short.example":
            return httpx.Response(301, headers={"location": "https://land.example/welcome"})
        return page("<title>Landing</title>")
    info = page_fetch.fetch("http://short.example/x", res, client_for(handler))
    assert info["final_url"] == "https://land.example/welcome" and info["hops"] == 1 and info["title"] == "Landing"


def test_too_many_redirects_is_none():
    res = resolver_for({"loop.example": [PUBLIC]})
    assert page_fetch.fetch("http://loop.example/", res, client_for(lambda r: httpx.Response(302, headers={"location": "/again"}))) is None


def test_meta_refresh_only_page_is_followed():
    res = resolver_for({"a.example": [PUBLIC], "b.example": [PUBLIC]})

    def handler(req):
        if req.headers["host"] == "a.example":
            return page('<meta http-equiv="refresh" content="0; url=https://b.example/real">')
        return page("<title>B</title>")
    assert page_fetch.fetch("http://a.example/", res, client_for(handler))["final_url"] == "https://b.example/real"


# ---- limits -------------------------------------------------------------------------------------------------
def test_body_is_capped():
    big = "<title>Big</title>" + "x" * 5_000_000
    info = page_fetch.fetch("http://a.example/", resolver_for({"a.example": [PUBLIC]}), client_for(lambda r: page(big)))
    assert info["title"].startswith("Big")                   # parsed from the first 200 KB; the rest was never read


def test_non_html_is_not_parsed_and_downloads_are_noted():
    res = resolver_for({"a.example": [PUBLIC]})
    apk = page_fetch.fetch("http://a.example/x", res, client_for(
        lambda r: httpx.Response(200, content=b"PK", headers={"content-type": "application/vnd.android.package-archive"})))
    assert apk["download"] is True and apk["forms"] == []
    att = page_fetch.fetch("http://a.example/x", res, client_for(
        lambda r: page("<title>t</title>", ctype="text/html", headers={"content-disposition": "attachment; filename=a.exe"})))
    assert att["download"] is True


def test_http_error_page_is_not_parsed():
    info = page_fetch.fetch("http://a.example/", resolver_for({"a.example": [PUBLIC]}), client_for(lambda r: page(LOGIN, status=404)))
    assert info["status"] == 404 and info["forms"] == []


def test_timeout_is_none():
    def handler(req):
        raise httpx.ReadTimeout("slow")
    assert page_fetch.fetch("http://a.example/", resolver_for({"a.example": [PUBLIC]}), client_for(handler)) is None


# ---- HTML facts ---------------------------------------------------------------------------------------------
def test_parse_extracts_metadata_forms_and_ignores_script_text():
    facts = page_fetch.parse_html(LOGIN + "<script>var title='Fake';</script>")
    assert facts["title"] == "SBI Online - Update KYC"
    assert facts["meta"]["og:site_name"] == "State Bank of India"
    assert facts["h1"] == ["Update your KYC"]
    assert facts["forms"][0]["action"] == "https://collect.other.top/post"
    assert {"password", "otp", "id_number"} <= {k for k in facts["forms"][0]["fields"] if k}


def test_parse_survives_garbage():
    assert page_fetch.parse_html("<<<>><form><input type=")["forms"] is not None


# ---- evaluation ---------------------------------------------------------------------------------------------
def evaluate(info, initial="evil.top"):
    return page_fetch.evaluate(
        info, initial,
        official_check=lambda x: domains.is_official(domains.host_info(x)),
        claimed=domains.claimed_brands,
        brand_ok=lambda b, x: domains._brand_ok(b, domains.host_info(x)),
        registrable_of=lambda u: domains.host_info(u)["registrable"])


def fetched(html, final="https://sbi-kyc.evil.top/login"):
    facts = page_fetch.parse_html(html)
    return {"final_url": final, "status": 200, "content_type": "text/html", "hops": 0, "chain": [final], "download": False, **facts}


def test_fake_bank_login_page_flags():
    ev = evaluate(fetched(LOGIN))
    assert set(ev["flags"]) == {"page_brand_mismatch", "asks_secret_details", "form_posts_elsewhere"}
    assert ev["flags"][0] == "page_brand_mismatch"           # most severe first
    assert "password" in ev["secrets"]


def test_unreachable_page_adds_nothing():
    assert evaluate(None)["flags"] == []


def test_plain_page_adds_nothing():
    assert evaluate(fetched("<title>My bakery</title><h1>Fresh bread</h1>"))["flags"] == []


def test_real_bank_site_login_is_not_flagged():
    ev = evaluate(fetched(LOGIN.replace("https://collect.other.top/post", "/post"), final="https://www.onlinesbi.sbi/login"), initial="onlinesbi.sbi")
    assert ev["flags"] == []


def test_redirect_is_reported():
    ev = evaluate(fetched("<title>x</title>", final="https://land.example/a"), initial="short.example")
    assert ev["redirected"] and ev["final_registrable"] == "land.example"


# ---- link_check.assess (pure) -------------------------------------------------------------------------------
from app import link_check  # noqa: E402


def test_fake_bank_page_is_high():
    url = "https://sbi-kyc.evil.top/login"
    a = link_check.assess(url, fetched(LOGIN), None)
    flags = dict(a["flags"])
    assert a["severity"] == "high" and flags["page_brand_mismatch"] == "high" and flags["asks_secret_details"] == "high"


def test_secrets_page_alone_on_a_plain_domain_is_careful():
    url = "https://checkout.example-store.com/pay"
    page = fetched("<title>Checkout</title><form action='/p'><input type=password></form>", final=url)
    a = link_check.assess(url, page, None)
    assert dict(a["flags"]) == {"asks_secret_details": "careful"} and a["severity"] == "careful"


def test_secrets_page_on_brand_new_domain_is_high():
    url = "https://checkout.example-store.com/pay"
    page = fetched("<title>Checkout</title><form action='/p'><input type=password></form>", final=url)
    a = link_check.assess(url, page, 3)
    assert dict(a["flags"])["asks_secret_details"] == "high" and dict(a["flags"])["very_new_domain"] == "high"


def test_shortener_destination_is_judged():
    a = link_check.assess("https://bit.ly/3xYz", fetched("<title>x</title>", final="http://203.0.113.9/login"), None)
    assert a["redirected"] and dict(a["flags"])["bare_ip"] == "high" and a["severity"] == "high"


def test_unreachable_page_never_lowers_or_clears():
    a = link_check.assess("https://yuyaa.xyz/x", None, None)
    assert a["page_reached"] is False and a["severity"] == "careful"      # still the .xyz warning from the address
    b = link_check.assess("https://example-bakery.com/", None, None)
    assert b["severity"] == "none" and b["page_reached"] is False


def test_clean_page_adds_nothing():
    a = link_check.assess("https://example-bakery.com/", fetched("<title>Bread</title>", final="https://example-bakery.com/"), 4000)
    assert a["flags"] == [] and a["severity"] == "none"


# ---- analyze never fetches pages ----------------------------------------------------------------------------
def test_analyze_never_fetches_a_page(monkeypatch):
    monkeypatch.setattr(page_fetch, "fetch", lambda *a, **k: pytest.fail("analyze must not fetch pages"))
    rules.analyze("Update KYC now https://sbi-kyc.evil.top/login and https://bit.ly/3xYz", None, [])


# ---- link_check.check + endpoint (fake DNS/HTTP) -------------------------------------------------------------
def test_check_returns_reasons_and_summary():
    res = resolver_for({"sbi-kyc.evil.top": [PUBLIC]})
    out = link_check.check("https://sbi-kyc.evil.top/login", res, client_for(lambda r: page(LOGIN)))
    assert out["severity"] == "high" and len(out["reasons"]) <= 3
    assert {r["id"] for r in out["reasons"]} >= {"link.page_brand_mismatch", "link.asks_secret_details"} and out["checked"]["page_reached"] is True


def test_check_blocked_target_is_unreachable_not_clean():
    out = link_check.check("http://169.254.169.254/latest/meta-data/")
    assert out["checked"]["page_reached"] is False and out["severity"] == "high"   # a bare IP is already high


def test_check_unreachable_unknown_domain_says_so():
    out = link_check.check("https://example-bakery.com/", resolver_for({}), client_for(lambda r: page("")))
    assert out["severity"] == "none" and out["reasons"][0]["id"] == "link.page_unreachable"
    assert "does not mean" in out["reasons"][0]["text_en"]


def test_kill_switch(monkeypatch):
    monkeypatch.setenv("LINK_FETCH", "0")
    monkeypatch.setattr(page_fetch, "fetch", lambda *a, **k: pytest.fail("fetched while disabled"))
    assert link_check.check("https://example-bakery.com/")["checked"]["page_reached"] is False


@pytest.fixture
def api(monkeypatch):
    from fastapi.testclient import TestClient
    from app import main
    from app.auth import verify
    main.app.dependency_overrides[verify] = lambda: "uid-1"
    main.link_limiter.hits.clear()
    yield TestClient(main.app), main
    main.app.dependency_overrides.clear()


def test_endpoint_happy_path_and_no_url_in_logs(api, monkeypatch, caplog):
    c, main = api
    monkeypatch.setattr(link_check, "check", lambda url: {"severity": "careful", "reasons": [], "latency_ms": 5,
                        "checked": {"registrable_domain": "x.top", "final_domain": "x.top", "redirected": False, "age_days": None, "page_reached": True}})
    with caplog.at_level("INFO", logger="guard"):
        r = c.post("/link/check", json={"url": "https://secret-token.example.top/abc123"})
    assert r.status_code == 200 and r.json()["severity"] == "careful"
    assert "secret-token" not in caplog.text and "abc123" not in caplog.text


def test_endpoint_adds_scheme_and_rejects_other_schemes(api, monkeypatch):
    c, main = api
    seen = []
    monkeypatch.setattr(link_check, "check", lambda url: seen.append(url) or {"severity": "none", "reasons": [], "latency_ms": 1,
                        "checked": {"registrable_domain": "", "final_domain": "", "redirected": False, "age_days": None, "page_reached": False}})
    assert c.post("/link/check", json={"url": "example-bakery.com/menu"}).status_code == 200 and seen == ["http://example-bakery.com/menu"]
    r = c.post("/link/check", json={"url": "file:///etc/passwd"})
    assert r.status_code == 422 and r.json() == {"error": "bad_url"}
    assert c.post("/link/check", json={"url": "x" * 3000}).status_code == 422


def test_endpoint_rate_limited(api, monkeypatch):
    c, main = api
    monkeypatch.setattr(link_check, "check", lambda url: {"severity": "none", "reasons": [], "latency_ms": 1,
                        "checked": {"registrable_domain": "", "final_domain": "", "redirected": False, "age_days": None, "page_reached": False}})
    codes = [c.post("/link/check", json={"url": "http://a.example/"}).status_code for _ in range(12)]
    assert codes[:10] == [200] * 10 and codes[10:] == [429, 429]


def test_endpoint_requires_auth():
    from fastapi.testclient import TestClient
    from app import main
    assert TestClient(main.app).post("/link/check", json={"url": "http://a.example/"}).status_code == 401


def test_reason_texts_exist():
    from app import templates
    for f in page_fetch.FLAG_LEVEL:
        assert f in templates.LINK_TEXT and "safe" not in templates.LINK_TEXT[f].lower()


@pytest.mark.parametrize("name,kind", [("otp_code", "otp"), ("input-pin2", "pin_cvv"), ("txtCVV", "pin_cvv"), ("card_number", "card"),
                                       ("pan_no", "id_number"), ("password1", "password")])
def test_secret_field_names(name, kind):
    f = page_fetch.parse_html(f'<form><input name="{name}"></form>')["forms"][0]["fields"]
    assert f == [kind]


@pytest.mark.parametrize("name", ["company", "spinner", "panel", "pinterest", "opt_in", "optional"])
def test_ordinary_field_names_are_not_secrets(name):
    assert page_fetch.parse_html(f'<form><input name="{name}"></form>')["forms"][0]["fields"] == [None]
