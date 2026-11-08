import pytest

from app import domains, rules


@pytest.mark.parametrize("raw,host,reg", [
    ("https://WWW.OnlineSBI.sbi:8443/x?y=1", "www.onlinesbi.sbi", "onlinesbi.sbi"),
    ("sbi.co.in.", "sbi.co.in", "sbi.co.in"),
    ("http://sbi.co.in@evil.com/login", "evil.com", "evil.com"),
    ("https://xn--bcher-kva.example/", "xn--bcher-kva.example", "xn--bcher-kva.example"),
    ("http://sbi.bank.in/a", "sbi.bank.in", "sbi.bank.in"),
    ("http://evil.bank.in/a", "evil.bank.in", "evil.bank.in"),
    ("http://a.b.gov.in", "a.b.gov.in", "b.gov.in"),
])
def test_host_info(raw, host, reg):
    i = domains.host_info(raw)
    assert (i["host"], i["registrable"]) == (host, reg)


def test_userinfo_trick_recorded():
    assert domains.host_info("http://sbi.co.in@evil.com")["userinfo"] is True
    assert domains.host_info("http://sbi.co.in")["userinfo"] is False


def test_unicode_host_roundtrip():
    i = domains.host_info("http://bücher.example")
    assert i["host"] == "xn--bcher-kva.example"
    assert i["unicode_host"] == "bücher.example"


@pytest.mark.parametrize("bad", ["", "http://", "http://[bad", "   "])
def test_host_info_never_raises(bad):
    assert domains.host_info(bad)["host"] == ""


def test_email_info():
    i = domains.email_info("SBI Support <alerts@Gmail.com>".split("<")[1], "SBI Support")
    assert i["registrable"] == "gmail.com" and i["local"] == "alerts" and i["display_name"] == "SBI Support"
    assert domains.email_info("no-at-sign")["host"] == ""


@pytest.mark.parametrize("url", [
    "https://www.onlinesbi.sbi", "https://retail.onlinesbi.sbi/x", "https://sbi.co.in",
    "https://uidai.gov.in", "https://a.b.gov.in", "https://www.nic.in", "https://x.nic.in",
    "https://sbi.bank.in",
])
def test_official(url):
    assert domains.is_official(domains.host_info(url))


@pytest.mark.parametrize("url", [
    "https://sbi.co.in.evil.com",     # official name as a subdomain of another site
    "https://evilsbi.co.in",          # endswith("sbi.co.in") would have matched
    "https://notonlinesbi.sbi",
    "https://gov.in.evil.com",
    "https://evil-gov.in",
    "https://evil.bank.in",           # shares suffix, not registrable domain, with sbi.bank.in
    "http://sbi.co.in@evil.com",      # user-info trick: real host is evil.com
    "http://192.168.1.5",
    "",
])
def test_not_official(url):
    assert not domains.is_official(domains.host_info(url))


def test_link_flags_official_has_no_domain_flags():
    assert rules.link_flags("https://sbi.co.in")[1] == []
    assert rules.link_flags("https://uidai.gov.in/x")[1] == []
    assert "brand_in_domain" in rules.link_flags("https://securesbi.co.in")[1]
    # apk is a path property: still reported on an official host
    assert rules.link_flags("https://sbi.co.in/app.apk")[1] == ["apk"]


@pytest.mark.parametrize("host,flag", [
    ("http://sbi-kyc-update.top/login", "brand_in_domain"),
    ("http://sbi.co.in.evil.com", "brand_in_domain"),
    ("http://onlinehdfcbank.xyz", "brand_in_domain"),
    ("http://sbikyc.in", "brand_in_domain"),
    ("http://192.168.1.5/login", "bare_ip"),
    ("http://sbi.co.in@evil.com", "user_info"),
    ("http://xn--sbi-kyc.top/a", "punycode"),
    ("http://bit.ly/3xYz", "shortener"),
    ("http://win-big-now.top", "risky_tld"),
    ("http://a-b-c-d.com", "many_hyphens"),
])
def test_flags_fire(host, flag):
    assert flag in domains.check_url(host)["flags"]


@pytest.mark.parametrize("host", [
    "https://bobby.com", "https://taxis.com", "https://www.google.com",
    "https://uidai.gov.in", "https://sbi.co.in", "https://retail.onlinesbi.sbi",
])
def test_no_flags(host):
    assert domains.check_url(host)["flags"] == []


def test_common_domain_sheds_only_weak_flags(monkeypatch):
    monkeypatch.setattr(rules, "_common", lambda: frozenset({"popular.xyz", "bit.ly"}))
    assert domains.check_url("https://popular.xyz")["flags"] == []          # weak flag only, popular -> shed
    assert domains.check_url("https://unpopular.xyz")["flags"] == ["risky_tld"]
    assert "shortener" in domains.check_url("http://bit.ly/x")["flags"]     # never shed
    assert "brand_in_domain" in domains.check_url("https://sbikyc.xyz")["flags"]


def test_severity_levels():
    assert domains.check_url("http://sbi-kyc-update.top/login")["severity"] == "high"
    assert domains.check_url("http://bit.ly/x")["severity"] == "careful"
    assert domains.check_url("https://sbi.co.in")["severity"] == "none"
    assert domains.check_url("https://sbi.co.in/a.apk")["severity"] == "high"


def test_check_order_independent():
    import itertools, random
    urls = ["http://sbi-kyc-update.top/login", "http://bit.ly/x", "http://192.168.1.5/a", "https://sbi.co.in",
            "http://sbi.co.in@evil.com", "http://xn--sbi-kyc.top/a", "https://x.com/app.apk", "http://a-b-c-d.xyz"]
    base = {u: domains.check_url(u) for u in urls}
    rng = random.Random(7)
    for _ in range(25):
        hc, pc = list(domains.HOST_CHECKS), list(domains.PATH_CHECKS)
        rng.shuffle(hc); rng.shuffle(pc)
        order = urls[:]; rng.shuffle(order)
        for u in order:
            assert domains.check_url(u, host_checks=hc, path_checks=pc) == base[u]


@pytest.mark.parametrize("url,flag", [
    ("http://sbі.co.in", "confusable"),             # Cyrillic і
    ("http://sbl.co.in", "confusable"),             # l for i
    ("http://hdfcbank.co", "lookalike_official"),   # official name, other suffix
    ("http://hdfcbanks.com", "lookalike_official"), # one edit from official
    ("http://hdfcbnak.com", "lookalike_official"),  # transposition
    ("http://icicibank.co.in", "lookalike_official"),
])
def test_lookalike_flags(url, flag):
    r = domains.check_url(url)
    assert flag in r["flags"] and r["severity"] == "high"


def test_lookalike_of_popular(monkeypatch):
    monkeypatch.setattr(rules, "_common", lambda: frozenset({"wikipedia.org", "shopmart.com", "shopmart.in"}))
    assert "lookalike" in domains.check_url("http://wikipedla.org")["flags"]
    assert "lookalike" not in domains.check_url("http://wikipedia.org")["flags"]
    assert domains.check_url("http://shopmart.in")["flags"] == []   # popular itself, not a lookalike
    assert "lookalike" not in domains.check_url("http://shopmart.net")["flags"]  # other suffix: not an edit
    assert "lookalike" not in domains.check_url("http://abc.org")["flags"]       # name too short


def test_skeleton():
    from app import confusables as c
    assert c.skeleton("sbі.co.in") == c.skeleton("sbi.co.in") == c.skeleton("SB1.co.in")
    assert c.skeleton("rnicrosoft.com") == c.skeleton("microsoft.com")
    assert c.skeleton("hdfcbank.com") != c.skeleton("icicibank.com")
    assert "wikipedia.org" in c.edits1("wikipedla.org")


def test_idn_alone_is_careful_not_high():
    r = domains.check_url("http://xn--12c1ezaww.com")
    assert r["flags"] == ["non_ascii", "punycode"] and r["severity"] == "careful"
    assert domains.check_url("http://sbі.co.in")["severity"] == "high"   # confusable + mixed script


@pytest.mark.parametrize("sender,expected", [
    ("SBI Support <sbi.support@gmail.com>", ("SBI Support", "sbi.support@gmail.com")),
    ('"SBI Support" <x@y.com>', ("SBI Support", "x@y.com")),
    ("<x@y.com>", ("", "x@y.com")),
    ("x@y.com", ("", "x@y.com")),
    ("VM-SBIBNK", None), ("+919876543210", None), (None, None), ("not an @ email", None),
])
def test_parse_email_sender(sender, expected):
    assert domains.parse_email_sender(sender) == expected


def test_find_emails_ignores_urls():
    assert domains.find_emails("write to a@b.com or http://sbi.co.in@evil.com/x") == ["a@b.com"]


@pytest.mark.parametrize("addr,name,flag,sev", [
    ("sbi.support@gmail.com", "SBI Support", "brand_mismatch_free_mail", "high"),
    ("help@gmail.com", "Income Tax Department", "brand_mismatch_free_mail", "high"),
    ("sbi.care@gmail.com", "", "brand_mismatch_free_mail", "high"),        # brand only in the local part
    ("alerts@mailer-news.com", "HDFC Bank", "brand_mismatch", "careful"),  # unknown domain: careful only
    ("noreply@jio-recharge.in", "Jio Care", "brand_in_domain", "high"),
    ("x@hdfcbank.co", "", "lookalike_official", "high"),
])
def test_email_flags(addr, name, flag, sev):
    r = domains.check_email(addr, name)
    assert flag in r["flags"] and r["severity"] == sev


@pytest.mark.parametrize("addr,name", [
    ("alerts@hdfcbank.com", "HDFC Bank"),
    ("noreply@uidai.gov.in", "UIDAI"),
    ("x@tn.gov.in", "Income Tax Department"),     # any *.gov.in counts for a government brand
    ("ravi@gmail.com", "Ravi Kumar"),             # free mail, no brand claimed
    ("billing@lakshmistores.in", "Lakshmi Stores"),
    ("order@amazon.in", "Amazon"),                 # brand not in our list: no claim, no flag
])
def test_email_no_flags(addr, name):
    assert domains.check_email(addr, name)["flags"] == []


def test_email_common_domain_never_cancels_brand_mismatch():
    assert domains.rules.is_common("gmail.com")  # popular ...
    assert domains.check_email("sbi@gmail.com", "SBI")["severity"] == "high"  # ... but still a mismatch


def test_email_check_order_independent():
    import random
    cases = [("sbi.support@gmail.com", "SBI Support"), ("alerts@mailer-news.com", "HDFC Bank"),
             ("x@hdfcbank.co", ""), ("alerts@hdfcbank.com", "HDFC Bank"), ("ravi@gmail.com", "Ravi")]
    base = [domains.check_email(a, n) for a, n in cases]
    rng = random.Random(5)
    for _ in range(25):
        chk = list(domains.EMAIL_CHECKS)
        rng.shuffle(chk)
        assert [domains.check_email(a, n, host_checks=chk) for a, n in cases] == base


# ---- file downloads from unknown sites -----------------------------------------------------------------------
@pytest.mark.parametrize("url,flag,sev", [
    ("https://maildeveloper.site/flomold.zip", "archive_download", "careful"),
    ("https://files-share.example.net/Doc.RAR?x=1", "archive_download", "careful"),
    ("https://files-share.example.net/setup.exe", "executable_download", "high"),
    ("https://files-share.example.net/invoice.scr", "executable_download", "high"),
    ("https://files-share.example.net/a.iso", "executable_download", "high"),
])
def test_file_downloads_from_unknown_sites(url, flag, sev):
    r = domains.check_url(url)
    assert flag in r["flags"] and r["severity"] == sev


def test_pdf_and_pages_are_not_download_flags():
    for u in ("https://files-share.example.net/report.pdf", "https://files-share.example.net/zip", "https://files-share.example.net/a.zip.html"):
        assert not {"archive_download", "executable_download"} & set(domains.check_url(u)["flags"])


def test_popular_host_may_serve_an_archive_but_not_an_exe():
    assert "archive_download" not in domains.check_url("https://github.com/org/repo/releases/download/v1/app.zip")["flags"]
    assert "executable_download" in domains.check_url("https://github.com/org/repo/releases/download/v1/app.exe")["flags"]


def test_official_site_download_is_not_flagged():
    assert domains.check_url("https://www.onlinesbi.sbi/forms/kyc.zip")["flags"] == []


def test_download_check_is_order_independent():
    a = domains.check_host(domains.host_info("https://x.example.top/setup.exe"), host_checks=list(domains.HOST_CHECKS))
    b = domains.check_host(domains.host_info("https://x.example.top/setup.exe"), host_checks=list(reversed(domains.HOST_CHECKS)))
    assert a == b


# ---- shopping / delivery brands (data added 2026-11-04, domains unverified) -----------------------------------
@pytest.mark.parametrize("url", [
    "https://amazon-gift-offer.xyz/claim", "https://amazon.in.verify-account.top/login", "https://flipkart-sale.in/x",
    "https://myntra-refund.online/a", "https://dtdc-parcel-track.top/pay", "https://swiggy-offers.xyz", "https://bluedart.delivery-fee.top",
])
def test_shopping_and_delivery_lookalikes_are_high(url):
    r = domains.check_url(url)
    assert "brand_in_domain" in r["flags"] and r["severity"] == "high"


@pytest.mark.parametrize("url", ["https://www.amazon.in/dp/B0123", "https://amzn.to/3abc", "https://www.flipkart.com/offers",
                                 "https://www.myntra.com/", "https://www.dtdc.in/tracking", "https://bluedart.com/track"])
def test_official_shopping_and_delivery_sites_are_not_flagged(url):
    assert domains.check_url(url)["flags"] == []


def test_amazonaws_is_not_an_impersonation_but_still_checked():
    r = domains.check_url("https://mybucket.s3.amazonaws.com/index.html")
    assert "brand_in_domain" not in r["flags"]
    r = domains.check_url("https://mybucket.s3.amazonaws.com/setup.exe")      # other checks still run
    assert "executable_download" in r["flags"]


def test_short_aliases_need_a_whole_token():
    assert "brand_in_domain" in domains.check_url("https://ajio-offers.top")["flags"]
    assert "brand_in_domain" not in domains.check_url("https://adjourn.top")["flags"]


def test_email_from_a_shopping_brand_domain_is_fine_and_free_mail_claim_is_not():
    assert domains.check_email("order@amazon.in", "Amazon")["flags"] == []
    assert "brand_mismatch_free_mail" in domains.check_email("amazon.support@gmail.com", "Amazon Support")["flags"]


def test_new_shopping_brands_do_not_flag_popular_domains():
    """Regression guard: adding a brand alias must not turn popular sites that merely contain it (amazon.de, media-amazon.com,
    nykaafashion.com ...) into 'brand_in_domain'. Fix by listing them in the brand's neutral_regs."""
    new = {"amazon", "flipkart", "myntra", "meesho", "ajio", "nykaa", "bigbasket", "blinkit", "swiggy", "zomato", "dtdc", "bluedart", "delhivery"}
    hits = [(b["id"], d) for d in sorted(rules._common()) for info in [domains.host_info(d)]
            for b in domains.claimed_brands(domains._host_text(info)) if b["id"] in new and not domains._brand_ok(b, info)]
    assert hits == []


@pytest.mark.parametrize("url", ["https://www.amazon.de/dp/1", "https://m.media-amazon.com/images/a.jpg", "https://amzn.eu/d/abc"])
def test_genuine_amazon_family_links_are_not_flagged(url):
    assert "brand_in_domain" not in domains.check_url(url)["flags"]
