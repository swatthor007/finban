"""The demo pages must trigger the real checks, and must never be able to work as a real scam."""
import re
from html.parser import HTMLParser

import httpx
import pytest
from fastapi.testclient import TestClient

from app import demo_pages, domains, link_check, main, page_fetch, rules

BASE = "https://demo-test.ngrok-free.dev"
PAGES = demo_pages.manifest()["pages"]
SLUGS = [p["slug"] for p in PAGES]


class _Text(HTMLParser):
    """What the bubble would read: visible text, no script or style."""
    def __init__(self):
        super().__init__()
        self.parts, self.skip = [], 0

    def handle_starttag(self, tag, attrs):
        self.skip += tag in ("script", "style", "title")

    def handle_endtag(self, tag):
        self.skip -= tag in ("script", "style", "title")

    def handle_data(self, data):
        if not self.skip:
            self.parts.append(data)


def visible_text(html: str) -> str:
    t = _Text()
    t.feed(html)
    return " ".join(" ".join(t.parts).split())


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("DEMO_PAGES", "1")
    return TestClient(main.app)


def test_off_unless_enabled(monkeypatch):
    monkeypatch.delenv("DEMO_PAGES", raising=False)
    c = TestClient(main.app)
    assert c.get("/demo").status_code == 404
    assert c.get("/demo/bank-login").status_code == 404
    assert c.get(f"/demo/files/{demo_pages.APK_NAME}").status_code == 404


@pytest.mark.parametrize("path", ["/demo"] + [f"/demo/{s}" for s in SLUGS])
def test_every_page_is_marked_and_locked_down(client, path):
    r = client.get(path)
    assert r.status_code == 200
    assert demo_pages.BANNER in r.text
    assert "form-action 'none'" in r.headers["content-security-policy"]
    assert "connect-src" not in r.headers["content-security-policy"]    # default-src 'none' covers it
    assert "noindex" in r.headers["x-robots-tag"]
    assert "noindex" in r.text


def test_unknown_page_is_404_and_paths_do_not_escape(client):
    assert client.get("/demo/nope").status_code == 404
    assert client.get("/demo/..%2Fpages").status_code == 404


@pytest.mark.parametrize("slug", SLUGS)
def test_forms_cannot_send_anywhere(client, slug):
    assert client.post(f"/demo/{slug}", data={"password": "x"}).status_code == 405
    html = client.get(f"/demo/{slug}").text
    for action in re.findall(r'<form[^>]*action="([^"]*)"', html):
        assert domains.host_info(action)["host"].endswith(".invalid")


BANK_PAGES = ["bank-login", "kyc-update", "card-verify"]


def claimed_by(html: str) -> set:
    """What a page says it is (title + heading + header bar). A field label like "Aadhaar number" asks for something, it does not claim to be UIDAI."""
    facts = page_fetch.parse_html(html)
    header = re.findall(r"<header>(.*?)</header>", html)
    return {b["id"] for b in domains.claimed_brands(" ".join([facts["title"], *facts["h1"], *header]))}


@pytest.mark.parametrize("slug", SLUGS)
def test_only_the_bank_pages_name_a_real_brand(client, slug):
    assert claimed_by(client.get(f"/demo/{slug}").text) == ({"sbi"} if slug in BANK_PAGES else set())


@pytest.mark.parametrize("slug", BANK_PAGES)
def test_bank_pages_are_only_a_picture_of_a_form(client, slug):
    html = client.get(f"/demo/{slug}").text
    assert "<form" not in html and "<script" not in html and "<img" not in html and "<a " not in html
    inputs = re.findall(r"<input[^>]*>", html)
    assert inputs and all("readonly" in i and "name=" not in i for i in inputs)
    assert 'type="button"' in html and 'type="submit"' not in html


@pytest.mark.parametrize("p", PAGES, ids=SLUGS)
def test_bubble_result_matches_the_list(client, p):
    """Same shape as a real scan: Chrome shows the address with no https:// and the app may not spot it, so it only arrives inside the text."""
    address = f"{BASE.split('://')[1]}/demo/{p['slug']}"
    page_text = visible_text(client.get(f"/demo/{p['slug']}").text)
    r = rules.analyze(f"{address} {page_text}", None, [], None)
    assert r["severity"] == p["bubble"], r["reasons"]


@pytest.mark.parametrize("slug,expected", [("card-verify", "careful"), ("parcel-fee", "careful"), ("kyc-update", "high"), ("lucky-draw", "high")])
def test_still_fires_with_the_address_bar_scrolled_away(client, slug, expected):
    r = rules.analyze(visible_text(client.get(f"/demo/{slug}").text), None, [], None)
    assert r["severity"] == expected, r["reasons"]


@pytest.mark.parametrize("text,links", [
    ("sbikyc.shop/login", ["sbikyc.shop/login"]),
    ("open a-b.ngrok-free.dev/x now", ["a-b.ngrok-free.dev/x"]),
    ("see www.example.com.", ["www.example.com"]),
    ("https://a.example/x and b.example.com", ["https://a.example/x", "b.example.com"]),
    ("Paid rs.500 at 10.30 e.g today", []),
    ("mail first.last@gmail.com or pay name@ybl", []),
    ("Hello.World and report.pdf", []),
])
def test_bare_addresses_are_links(text, links):
    assert rules.extract_links(text) == links


@pytest.mark.parametrize("text", [
    "Never share your OTP, CVV or UPI PIN with anyone. - Your bank",
    "Do not share your ATM PIN. Bank staff will never ask for it.",
    "Your CVV should not be shared with anyone.",
])
def test_bank_advice_about_secrets_stays_quiet(text):
    assert rules.analyze(text, None, [], None)["severity"] == "none"


def test_secret_ask_on_a_known_site_stays_quiet():
    assert rules.analyze("amazon.in Enter card CVV to pay", None, [], None)["severity"] == "none"


def test_secret_ask_on_a_flagged_address_is_high():
    assert rules.analyze("x.ngrok-free.dev Enter your CVV", None, [], None)["severity"] == "high"


@pytest.mark.parametrize("p", PAGES, ids=SLUGS)
def test_closer_look_matches_the_list(client, p):
    url = f"{BASE}/demo/{p['slug']}"
    facts = page_fetch.parse_html(client.get(f"/demo/{p['slug']}").text)
    info = {"final_url": url, "status": 200, "content_type": "text/html", "hops": 0, "chain": [url], "download": False, **facts}
    a = link_check.assess(url, info, None)
    assert a["severity"] == p["link_check"], a["flags"]


@pytest.mark.parametrize("s", demo_pages.manifest()["samples"], ids=lambda s: s["id"])
def test_sample_text_result_matches_the_list(s):
    text = demo_pages.sample_text(s, BASE)
    links = re.findall(r"https?://\S+", text)
    r = rules.analyze(text, "+91" + s["sender"], links, None)
    assert r["severity"] == s["expect"], r["reasons"]


def test_list_only_promises_the_closer_look_when_the_app_offers_it():
    pages = {p["slug"]: p for p in PAGES}
    assert "closer look" in demo_pages.result_line(pages["bank-login"])
    assert "closer look" not in demo_pages.result_line(pages["lucky-draw"])


def test_fake_file_is_flagged_and_is_not_an_app(client):
    r = client.get(f"/demo/files/{demo_pages.APK_NAME}")
    assert r.headers["content-type"] == "application/vnd.android.package-archive"
    assert "attachment" in r.headers["content-disposition"]
    assert not r.content.startswith(b"PK")      # an APK is a zip
    assert len(r.content) < 100
    assert "apk" in domains.check_url(f"{BASE}/demo/files/{demo_pages.APK_NAME}")["flags"]


@pytest.mark.parametrize("url", ["https://a-b.ngrok-free.dev/x", "https://x.trycloudflare.com", "https://abc.loca.lt/login"])
def test_tunnel_address_is_careful(url):
    r = domains.check_url(url)
    assert r["flags"] == ["temporary_host"] and r["severity"] == "careful"


def test_ngrok_warning_page_is_skipped_only_for_ngrok():
    seen = {}

    def handler(request):
        seen[request.headers["host"]] = request.headers.get("ngrok-skip-browser-warning")
        return httpx.Response(200, headers={"content-type": "text/html"}, text="<title>x</title>")

    client = httpx.Client(transport=httpx.MockTransport(handler))
    resolver = lambda host, port: ["93.184.216.34"]
    page_fetch.fetch("https://a.ngrok-free.dev/x", resolver=resolver, client=client)
    page_fetch.fetch("https://example.com/x", resolver=resolver, client=client)
    assert seen == {"a.ngrok-free.dev": "1", "example.com": None}
