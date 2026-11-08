from __future__ import annotations
"""Rules engine. Mirror of the Android engine; both are tested on data/testset."""
import json
import re
from functools import lru_cache
from pathlib import Path

DATA = Path(__file__).resolve().parents[2] / "data"
HEADER_RE = re.compile(r"^([A-Z])([A-Z])-([A-Z0-9]{6})(?:-([PSTG]))?$")
MOBILE_RE = re.compile(r"^(\+91|91|0)?[6-9]\d{9}$")

ACCOUNT_WORDS = ["account", "a/c", "kyc", "blocked", "suspend", "bank"]
SEV_ORDER = {"none": 0, "careful": 1, "high": 2}


def parse_header(sender: str | None) -> dict:
    """Parse a TRAI-style sender header. `sms_type` comes from the -S/-P/-T/-G suffix:
    service | promotional | transactional | government | unspecified (no suffix)."""
    m = HEADER_RE.match(sender or "")
    if not m:
        return {"valid": False}
    pre = _prefixes()
    suffix = m[4]
    return {"valid": True, "prefix": m[1] + m[2], "header": m[3], "suffix": suffix,
            "sms_type": pre["suffix"].get(suffix, "unspecified") if suffix else "unspecified",
            "operator": pre["operators"].get(m[1]), "circle": pre["circles"].get(m[2])}


def classify_sender(sender: str | None) -> dict:
    """What kind of sender is this? Returns at least {format, sms_type}.
    format: header | mobile_number | shortcode | other | none
    sms_type: transactional | promotional | service | government | unspecified   (header)
              person_number (mobile) | shortcode | unknown_format | unknown (none)
    Header senders also get registered / entity / entity_category from the TRAI list."""
    if not sender:
        return {"format": "none", "sms_type": "unknown"}
    hdr = parse_header(sender)
    if hdr["valid"]:
        info = dict(hdr, format="header", registered=False, entity=None, entity_category=None)
        ent = header_entity(hdr["header"])
        if ent:
            info.update(registered=True, entity=ent["name"], entity_category=ent["category"])
        return info
    if is_mobile(sender):
        return {"format": "mobile_number", "sms_type": "person_number"}
    if re.fullmatch(r"\d{3,6}", sender.strip()):
        return {"format": "shortcode", "sms_type": "shortcode"}
    return {"format": "other", "sms_type": "unknown_format"}


def is_mobile(sender: str | None) -> bool:
    return bool(sender and MOBILE_RE.match(sender.replace(" ", "")))


def edit_distance(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def edit_distance_osa(a: str, b: str) -> int:
    """Edit distance where swapping two adjacent characters counts as one edit."""
    d = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i in range(len(a) + 1):
        d[i][0] = i
    for j in range(len(b) + 1):
        d[0][j] = j
    for i in range(1, len(a) + 1):
        for j in range(1, len(b) + 1):
            c = a[i - 1] != b[j - 1]
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + c)
            if i > 1 and j > 1 and a[i - 1] == b[j - 2] and a[i - 2] == b[j - 1]:
                d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
    return d[-1][-1]


def near_miss_header(header: str) -> str | None:
    """Core bank/payment header this one is exactly one edit away from, if the header itself is not
    registered to anyone in the TRAI list. Measured on the 2020 list: 0 hits among 21.7k registered
    non-bank headers (distance<=2 against core flagged 1.2% of them); catches every unregistered
    one-letter variant of a core header. Caveat: headers registered after 2020 look unregistered."""
    if header in known_headers() or header_entity(header):
        return None
    return next((k for k in known_headers() if edit_distance_osa(header, k) == 1), None)


def _load(name: str):
    return json.loads((DATA / name).read_text())


@lru_cache(maxsize=1)
def _header_db() -> dict:
    return _load("allowlists/sms_headers.json")


@lru_cache(maxsize=1)
def _prefixes() -> dict:
    return _load("allowlists/sms_prefixes.json")


@lru_cache(maxsize=1)
def banks() -> list[dict]:
    return _load("allowlists/bank_domains.json")["banks"]


def header_entity(header: str) -> dict | None:
    """Registered entity {name, category} for a 6-char header in the TRAI list, else None."""
    db = _header_db()
    i = db["headers"].get(header)
    return db["entities"][i] if i is not None else None


def known_headers() -> list[str]:
    """Core bank/payment headers we protect against lookalikes (from bank_domains.json)."""
    return [h for b in banks() for h in b["headers"]]


def patterns() -> list[dict]:
    return [json.loads(p.read_text()) for p in sorted((DATA / "patterns").glob("*.json")) if p.name != "version.json"]


def patterns_version() -> str:
    return _load("patterns/version.json")["version"]


@lru_cache(maxsize=1)
def shorteners() -> frozenset:
    return frozenset(_load("allowlists/shorteners.json")["domains"])


@lru_cache(maxsize=1)
def tunnel_hosts() -> frozenset:
    return frozenset(_load("allowlists/tunnel_hosts.json")["domains"])


@lru_cache(maxsize=1)
def risky_tlds() -> frozenset:
    return frozenset(_load("allowlists/risky_tlds.json")["tlds"])


@lru_cache(maxsize=1)
def _common() -> frozenset:
    return frozenset(_load("allowlists/common_domains.json")["domains"])


def is_common(registrable_domain: str) -> bool:
    """In the Tranco-derived popular-domain list (hosting domains excluded). Popular != trustworthy."""
    return registrable_domain.lower() in _common()


@lru_cache(maxsize=1)
def brands() -> list[dict]:
    """Impersonation targets from brands.json. Bank entries inherit name, aliases and domains from
    bank_domains.json. Each result has `regs` (frozenset of official registrable domains)."""
    by_id = {b["id"]: b for b in banks()}
    out = []
    for b in _load("allowlists/brands.json")["brands"]:
        src = by_id[b["bank_id"]] if "bank_id" in b else {}
        doms = list(b.get("domains", [])) + list(src.get("domains", []))
        out.append({**b, "name_en": b.get("name_en") or src.get("name_en"),
                    "aliases": b.get("aliases") or src.get("aliases", []),
                    "regs": frozenset(d.lower() for d in doms)})
    return out


def official_domains() -> list[str]:
    return _load("allowlists/official_domains.json")["domains"] + sorted({d for b in brands() for d in b["regs"]})


@lru_cache(maxsize=1)
def free_mail() -> frozenset:
    return frozenset(_load("allowlists/free_mail.json")["domains"])


def link_flags(url: str) -> tuple[str, list[str]]:
    """(registrable domain, flags in canonical order). See domains.check_url for severity."""
    from . import domains  # local import: domains uses this module's data loaders
    r = domains.check_url(url)
    return r["registrable"], r["flags"]


URL_RE = re.compile(r"https?://[^\s]+")
# Bare address, as a browser's address bar shows it: lowercase host, optional path. Not part of an email or UPI id.
BARE_HOST_RE = re.compile(r"(?<![\w@./-])(?:[a-z0-9-]+\.)+[a-z]{2,}(?![\w@-])(?:/[^\s]*)?")


def extract_links(text: str) -> list[str]:
    """Full URLs, plus bare addresses whose ending is a real public suffix (so "rs.500" or "e.g" are not links)."""
    from . import domains
    found = URL_RE.findall(text)
    bare = (m.group(0).rstrip(".,)") for m in BARE_HOST_RE.finditer(URL_RE.sub(" ", text)))
    return found + [b for b in bare if domains.host_info(b)["suffix"]]


def _claims_brand(text: str) -> bool:
    from . import domains
    return bool(domains.claimed_brands(text))


MONEY_ASK_RE = re.compile(
    r"(?:rs\.?|inr|₹|rupees?)\s*\d|\d\s*(?:rs\b|rupees|/-)|\b[\w.\-]{2,}@[a-z]{2,}\b"
    r"|\b(?:send|transfer|give|pay)\b[^.!?\n]{0,20}\b(?:money|amount|cash|fee|payment)\b|\bupi\b|\bgpay\b|\bphonepe\b|\bpaytm\b", re.I)
PHONE_RE = re.compile(r"(?<!\d)(?:\+91[\s-]?|0)?[6-9]\d{9}(?!\d)")
SECRET_ASK_RE = re.compile(r"\b(?:cvv|cvc|upi pin|atm pin|card pin)\b", re.I)
# Banks remind people never to share these: that is advice, not a request.
NEVER_SHARE_RE = re.compile(r"\b(?:never|not|don'?t)\b[^.!?\n]{0,40}\b(?:share|give|tell|ask|disclose|reveal)", re.I)


def has_money_ask(text: str) -> bool:
    """An amount, a UPI id (name@bank), or a 'send/pay money' phrase. Used by `require_any` entries `"@money"` and by the weak-cue rule."""
    return bool(MONEY_ASK_RE.search(text))


def _required_ok(p: dict, lowered: str, text: str) -> bool:
    """Optional pattern field `require_any`: at least one entry must be present or the pattern does not count at all.
    Entries are lowercase substrings, or the token "@money" (has_money_ask), so innocent texts that share a few cues stay quiet."""
    req = p.get("require_any")
    if not req:
        return True
    return any(has_money_ask(text) if r == "@money" else r.lower() in lowered for r in req)


def analyze(text: str, sender: str | None, links: list[str], caller_unknown: bool | None = None) -> dict:
    """Returns {severity, pattern_id, reasons: [{id, level}], links}. Reason ids are dotted codes."""
    sev, reasons = "none", []

    def bump(s: str, code: str):
        nonlocal sev
        reasons.append({"id": code, "level": s})
        if SEV_ORDER[s] > SEV_ORDER[sev]:
            sev = s

    lowered = text.lower()
    best, best_hits = None, 0
    for p in patterns():
        if not _required_ok(p, lowered, text):
            continue
        cues = [c.lower() for lang in p["text_cues"].values() for c in lang]
        hits = sum(1 for c in cues if c in lowered)
        if hits > best_hits:
            best, best_hits = p, hits
    pattern_id = None
    if best and best_hits >= 2:
        pattern_id = best["id"]
        bump("high", f"pattern.{best['id']}")
    weak = best["id"] if best and best_hits == 1 else None   # decided after links: one cue alone is not enough

    stages = ["patterns", "sender"]
    hdr = parse_header(sender)
    if hdr["valid"]:
        if hdr["suffix"] == "P" and any(w in lowered for w in ACCOUNT_WORDS):
            bump("careful", "sender.promo-header")
        if near_miss_header(hdr["header"]):
            bump("high", "sender.near-miss-header")
    elif is_mobile(sender) and (any(w in lowered for w in ("bank", "account", "kyc")) or _claims_brand(lowered)):
        bump("high", "sender.mobile-bank")

    from . import domains
    email_results = []
    stages_email = bool(domains.parse_email_sender(sender) or domains.find_emails(text))
    parsed = domains.parse_email_sender(sender)
    candidates = ([parsed] if parsed else []) + [("", a) for a in domains.find_emails(text)]
    for display, addr in dict.fromkeys(candidates):
        r = domains.check_email(addr, display)
        email_results.append({"address_domain": r["address_domain"], "flags": r["flags"]})
        if r["flags"]:
            bump(r["severity"], "email." + r["flags"][0])

    all_links = list(dict.fromkeys(list(links) + extract_links(text)))
    stages += (["email"] if stages_email else []) + (["links"] if all_links else [])
    link_results = []
    checked = [(url, domains.check_url(url)) for url in all_links]
    # Step 5 (network): domain age, only for sites we do not already know (not official, not popular, not a bare IP).
    # Page content is NOT fetched here: the person asks for that with a button (POST /link/check, see link_check.py).
    from . import domain_age
    to_age = [r["registrable"] for _, r in checked
              if r["registrable"] and not r["official"] and "bare_ip" not in r["flags"] and not is_common(r["registrable"])]
    ages = domain_age.ages_for(to_age) if to_age else {}
    for url, r in checked:
        age = ages.get(r["registrable"].lower()) if r["registrable"] else None
        age_flag = domain_age.flag_for(age)
        link_results.append({"url": url, "registrable_domain": r["registrable"], "age_days": age,
                             "flags": r["flags"] + ([age_flag] if age_flag else [])})
        if r["flags"]:
            bump(r["severity"], "link." + r["flags"][0])
        if age_flag:
            bump(domains.FLAG_LEVEL[age_flag], "link." + age_flag)
    # Spoofed sender (separate step, after link checks): a bank's header on a message whose link leaves that bank's own domains.
    # This lowers risk, it does not prove identity: the wording stays "does not match".
    bank = next((b for b in banks() if hdr["valid"] and hdr["header"] in b["headers"]), None)
    if bank:
        own = {d.lower() for d in bank["domains"]}
        if any(l["registrable_domain"] and l["registrable_domain"].lower() not in own for l in link_results):
            stages.append("sender-link")
            bump("high", "sender.bank-link-mismatch")
    if weak and (any(l["flags"] for l in link_results) or PHONE_RE.search(text) or has_money_ask(text)):
        bump("careful", f"pattern-weak.{weak}")
    # Asked for a CVV or PIN. Fine on a site we know (a real checkout), a warning anywhere else, high if the address also looks wrong.
    known_site = any(r["official"] or is_common(r["registrable"]) for _, r in checked if not r["flags"])
    if SECRET_ASK_RE.search(text) and not NEVER_SHARE_RE.search(text) and not known_site:
        stages.append("secret-ask")
        bump("high" if any(l["flags"] for l in link_results) else "careful", "text.asks-secret")
    return {"severity": sev, "pattern_id": pattern_id, "reasons": reasons, "links": link_results,
            "emails": email_results, "stages": stages}
