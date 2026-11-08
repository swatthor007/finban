"""Domain check pipeline for URL hosts and email addresses (see docs/plans/S2-server-core.md).

Step 0: normalise (this file, `host_info` / `email_info`).
Step 1: exact official match (`is_official`).
Later steps (structure, brand claims, weak signals) are added on top; no network here.
"""
from __future__ import annotations

import ipaddress
from functools import lru_cache
from urllib.parse import urlparse

import tldextract

from . import rules

_extract = tldextract.TLDExtract(suffix_list_urls=(), cache_dir=None)

# Suffixes missing from the bundled Public Suffix List that behave like one: registrations under
# them belong to different owners, so `evil.bank.in` must not share a registrable domain with
# `sbi.bank.in`. TODO(hackathon): check the live PSL / NIXI rules and add fin.in etc. if confirmed.
EXTRA_SUFFIXES = ("bank.in",)
# Any host at or under these suffixes counts as official (government rule, no list needed).
GOV_SUFFIXES = tuple(rules._load("allowlists/gov_domains.json")["official_suffixes"])


def _to_ascii(host: str) -> str:
    try:
        return host.encode("idna").decode("ascii")
    except UnicodeError:
        return host


def _to_unicode(host: str) -> str:
    out = []
    for label in host.split("."):
        if label.startswith("xn--"):
            try:
                label = label.encode("ascii").decode("idna")
            except UnicodeError:
                pass
        out.append(label)
    return ".".join(out)


def host_info(url_or_host: str) -> dict:
    """Step 0 for a URL or bare host. Never raises.

    Returns {host (ascii/punycode), unicode_host, registrable (reg domain or host), suffix, subdomain,
    is_ip, userinfo (bool: `a@b` trick present), path}. The host is what the browser would connect
    to: user-info is dropped, port and trailing dot stripped, everything lowercased."""
    raw = (url_or_host or "").strip()
    info = {"host": "", "unicode_host": "", "registrable": "", "suffix": "", "subdomain": "",
            "is_ip": False, "userinfo": False, "path": ""}
    try:
        parsed = urlparse(raw if "://" in raw else "http://" + raw)
        host = (parsed.hostname or "").lower().rstrip(".")
        info["userinfo"] = "@" in parsed.netloc
        info["path"] = parsed.path
    except ValueError:
        return info
    if not host:
        return info
    try:
        ipaddress.ip_address(host)
        info.update(host=host, unicode_host=host, registrable=host, is_ip=True)
        return info
    except ValueError:
        pass
    host = _to_ascii(host)
    info["host"], info["unicode_host"] = host, _to_unicode(host)
    suffix = next((s for s in EXTRA_SUFFIXES if host.endswith("." + s)), None)
    if suffix:
        labels = host[: -len(suffix) - 1].split(".")
        info.update(suffix=suffix, registrable=labels[-1] + "." + suffix, subdomain=".".join(labels[:-1]))
        return info
    ext = _extract(host)
    info.update(suffix=ext.suffix, subdomain=ext.subdomain,
                registrable=ext.top_domain_under_public_suffix or host)
    return info


def email_info(address: str, display_name: str | None = None) -> dict:
    """Step 0 for an email: domain after the last `@` normalised like a host, plus the display name."""
    addr = (address or "").strip().strip("<>").lower()
    domain = addr.rsplit("@", 1)[1] if "@" in addr else ""
    info = host_info(domain) if domain else host_info("")
    info.update(local=addr.rsplit("@", 1)[0] if "@" in addr else addr, display_name=(display_name or "").strip())
    return info


@lru_cache(maxsize=1)
def official_set() -> frozenset:
    """Registrable domains we treat as official (exact match). Government domains use `GOV_SUFFIXES`."""
    return frozenset(d.lower() for d in rules.official_domains())


def is_gov(info: dict) -> bool:
    s = info["suffix"] or info["host"]
    return s in GOV_SUFFIXES


def is_official(info: dict) -> bool:
    """Step 1. Exact registrable-domain match (never `endswith`), or any *.gov.in / nic.in host."""
    if not info["host"] or info["is_ip"]:
        return False
    return info["registrable"] in official_set() or is_gov(info)


# ---------------------------------------------------------------------------------------------
# Steps 2-4: independent checks. Each returns a set of flag names and never looks at other checks'
# results, so running them in any order gives the same verdict (tested). Combining is separate.
# ---------------------------------------------------------------------------------------------
import re
import unicodedata
from typing import Callable, Iterable

FLAG_LEVEL = {
    "brand_mismatch_free_mail": "high", "brand_mismatch": "careful",
    "bare_ip": "high", "user_info": "high", "apk": "high",
    # File downloads from sites we do not know: programs/scripts are high, archives (which hide programs) careful.
    "executable_download": "high", "archive_download": "careful",
    # Punycode alone is only careful: genuine Tamil/Hindi IDN domains exist. Mixing scripts or matching
    # an official skeleton is what makes it high.
    "punycode": "careful", "non_ascii": "careful",
    "mixed_script": "high", "confusable": "high", "brand_in_domain": "high",
    "lookalike_official": "high", "lookalike": "careful", "shortener": "careful", "risky_tld": "careful",
    "many_hyphens": "careful", "temporary_host": "careful",
    # Network step (domain_age.py), added in rules.analyze, never by the local host checks.
    "very_new_domain": "high", "new_domain": "careful",
    # Page content (page_fetch.py), also added in rules.analyze.
    "page_brand_mismatch": "high", "page_download": "high", "asks_secret_details": "careful", "form_posts_elsewhere": "careful",
}
SEV_RANK = {"none": 0, "careful": 1, "high": 2}
# Canonical order: most severe first, then name. Output never depends on check order.
FLAG_ORDER = sorted(FLAG_LEVEL, key=lambda f: (-SEV_RANK[FLAG_LEVEL[f]], f))
# Weak flags that a popular domain with no other evidence may shed (a shortener never sheds: it hides the target).
SHEDDABLE = {"risky_tld", "many_hyphens", "archive_download"}  # a popular host (github.com releases, a bank PDF zip) may serve a zip
# Words scammers glue to a short brand alias: sbikyc, hdfc-update, sbibank ...
SCAM_WORDS = ("kyc", "bank", "update", "login", "secure", "care", "support", "refund", "pay", "online",
              "net", "help", "verify", "reward", "offer", "alert", "card", "connect", "india")


def _peel(token: str) -> str:
    """Strip scam words and trailing digits from both ends: 'onlinehdfcbank' -> 'hdfc', 'sbi2' -> 'sbi'."""
    prev = None
    while prev != token:
        prev = token
        token = token.rstrip("0123456789")
        for w in SCAM_WORDS:
            if token.startswith(w) and len(token) > len(w):
                token = token[len(w):]
            if token.endswith(w) and len(token) > len(w):
                token = token[: -len(w)]
    return token


def _alias_forms(alias: str) -> tuple[str, bool]:
    compact = re.sub(r"[^a-z0-9]", "", alias.lower())
    return compact, len(compact) > 4


def claimed_brands(text: str) -> list[dict]:
    """Brands whose alias a piece of text (host labels or an email display name) appears to claim.
    Aliases of 4 chars or fewer must equal a token once scam words and digits are peeled off both ends
    ('sbikyc', 'securesbi', 'onlinehdfcbank'); longer aliases may appear anywhere. Avoids 'bobby.com' / 'taxis.com' false hits."""
    from . import rules
    text = (text or "").lower()
    tokens = [t for t in re.split(r"[^a-z0-9]+", text) if t]
    compact = "".join(tokens)
    out = []
    for b in rules.brands():
        for alias in b["aliases"]:
            a, anywhere = _alias_forms(alias)
            if not a:
                continue
            if anywhere:
                hit = a in compact
            else:
                hit = any(_peel(t) == a for t in tokens)
            if hit:
                out.append(b)
                break
    return out


def _host_text(info: dict) -> str:
    """Subdomain + the registrable domain's name part (not the suffix): where brands get faked."""
    reg, suf = info["registrable"], info["suffix"]
    name = reg[: -len(suf) - 1] if suf and reg.endswith("." + suf) else reg
    return (info["subdomain"] + "." + name).strip(".")


def _brand_ok(brand: dict, info: dict) -> bool:
    return (info["registrable"] in brand["regs"] or info["registrable"] in brand.get("neutral_regs", ())
            or (brand["category"] == "gov" and is_gov(info)))


# --- structure (step 2) ---
def chk_ip(info):
    return {"bare_ip"} if info["is_ip"] else set()


def chk_user_info(info):
    return {"user_info"} if info["userinfo"] else set()


def chk_punycode(info):
    flags = set()
    if info["host"].startswith("xn--") or ".xn--" in info["host"]:
        flags.add("punycode")
    if any(ord(c) > 127 for c in info["unicode_host"]):
        flags.add("non_ascii")
    return flags


def _scripts(label: str) -> set:
    return {unicodedata.name(c, "?").split()[0] for c in label if c.isalpha()}


def chk_mixed_script(info):
    return {"mixed_script"} if any(len(_scripts(l)) > 1 for l in info["unicode_host"].split(".")) else set()


# --- brand claims (step 3) ---
def chk_brand_in_host(info):
    if info["is_ip"]:
        return set()
    return {"brand_in_domain"} if any(not _brand_ok(b, info) for b in claimed_brands(_host_text(info))) else set()


# --- weak signals (step 4) ---
def chk_shortener(info):
    from . import rules
    return {"shortener"} if info["registrable"] in rules.shorteners() else set()


def chk_temporary_host(info):
    """A free tunnel address: the site runs on someone's own computer. Never sheds: popularity of the tunnel service says nothing about the site."""
    from . import rules
    return {"temporary_host"} if info["registrable"] in rules.tunnel_hosts() else set()


def chk_risky_tld(info):
    from . import rules
    return {"risky_tld"} if info["suffix"].split(".")[-1] in rules.risky_tlds() else set()


def chk_hyphens(info):
    name = _host_text(info).rsplit(".", 1)[-1]
    if name.startswith("xn--"):
        name = name[4:]
    return {"many_hyphens"} if name.count("-") >= 2 else set()


# --- lookalikes (steps 2/4): skeleton and 1-edit neighbours of official and popular domains ---
MIN_LOOKALIKE_NAME = 6  # shorter names collide with real domains too often


def _split_name(reg: str, suffix: str) -> tuple[str, str]:
    if suffix and reg.endswith("." + suffix):
        return reg[: -len(suffix) - 1], suffix
    return reg, ""


@lru_cache(maxsize=1)
def _official_names() -> dict:
    """name -> set of suffixes, for official registrable domains."""
    out: dict = {}
    for d in official_set():
        info = host_info(d)
        n, suf = _split_name(info["registrable"], info["suffix"])
        out.setdefault(n, set()).add(suf)
    return out


def _reg_unicode(info) -> str:
    """Registrable domain in unicode form (the last N labels of the unicode host)."""
    n = info["registrable"].count(".") + 1
    return ".".join(info["unicode_host"].split(".")[-n:])


def chk_confusable(info):
    """Skeleton equals an official domain's skeleton but the domain is not that domain."""
    from . import confusables
    if info["is_ip"] or not info["registrable"] or info["registrable"] in official_set():
        return set()
    return {"confusable"} if confusables.skeleton(_reg_unicode(info)) in _official_skeletons() else set()


@lru_cache(maxsize=1)
def _official_skeletons() -> frozenset:
    from . import confusables
    return frozenset(confusables.skeleton(d) for d in official_set())


def chk_lookalike(info):
    from . import confusables, rules
    reg = info["registrable"]
    if info["is_ip"] or not reg or reg in official_set():
        return set()
    name, suf = _split_name(reg, info["suffix"])
    if len(name) < MIN_LOOKALIKE_NAME:
        return set()
    flags = set()
    # official brand name under a different suffix, or an official name one edit away (same suffix)
    on = _official_names()
    if (name in on and suf not in on[name]) or any(n in on and suf in on[n] for n in confusables.edits1(name)):
        flags.add("lookalike_official")
    # one edit away from a popular domain with the same suffix; popular domains are never lookalikes themselves
    if not rules.is_common(reg) and any(rules.is_common(n + "." + suf if suf else n) for n in confusables.edits1(name)):
        flags.add("lookalike")
    return flags


EXECUTABLE_EXT = (".exe", ".msi", ".scr", ".bat", ".cmd", ".js", ".vbs", ".jar", ".ps1", ".lnk", ".iso", ".img", ".dmg", ".pkg", ".apks", ".xapk")
ARCHIVE_EXT = (".zip", ".rar", ".7z", ".tar", ".gz", ".tgz")


def chk_download(info):
    """A link that goes straight to a file. Host check: only for sites that are not official (see HOST_CHECKS)."""
    path = info["path"].lower()
    if path.endswith(EXECUTABLE_EXT):
        return {"executable_download"}
    if path.endswith(ARCHIVE_EXT):
        return {"archive_download"}
    return set()


# Host checks run only when step 1 did not already say "official".
HOST_CHECKS: list[Callable[[dict], set]] = [
    chk_ip, chk_user_info, chk_punycode, chk_mixed_script, chk_confusable, chk_lookalike, chk_brand_in_host,
    chk_shortener, chk_temporary_host, chk_risky_tld, chk_hyphens, chk_download,
]


def chk_apk(info):
    return {"apk"} if info["path"].lower().endswith(".apk") else set()


# Path checks run always: an .apk is dangerous wherever it is hosted.
PATH_CHECKS: list[Callable[[dict], set]] = [chk_apk]


def combine(flags: Iterable[str], info: dict, brand_claimed: bool) -> list[str]:
    """Step 6 for the local part: drop sheddable weak flags for a popular domain when nothing else
    fired and no brand is claimed. Returns flags in canonical order."""
    from . import rules
    flags = set(flags)
    if flags and flags <= SHEDDABLE and not brand_claimed and rules.is_common(info["registrable"]):
        flags = set()
    return [f for f in FLAG_ORDER if f in flags]


def severity_of(flags: Iterable[str]) -> str:
    return max((FLAG_LEVEL[f] for f in flags), key=SEV_RANK.__getitem__, default="none")


def check_host(info: dict, host_checks=None, path_checks=None) -> dict:
    """Steps 1-4 + local combine for an already-normalised host. Returns
    {flags (canonical order), severity, official, registrable}. Pass permuted check lists to test order independence."""
    official = is_official(info)
    flags: set = set()
    for chk in (PATH_CHECKS if path_checks is None else path_checks):
        flags |= chk(info)
    claimed = False
    if not official:
        for chk in (HOST_CHECKS if host_checks is None else host_checks):
            flags |= chk(info)
        claimed = "brand_in_domain" in flags
    out = combine(flags, info, claimed)
    return {"flags": out, "severity": severity_of(out), "official": official, "registrable": info["registrable"]}


def check_url(url: str, **kw) -> dict:
    return check_host(host_info(url), **kw)


# ---------------------------------------------------------------------------------------------
# Email (same pipeline: the sender domain goes through the host checks, plus a display-name check)
# ---------------------------------------------------------------------------------------------
EMAIL_RE = re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)+")
_NAME_ADDR_RE = re.compile(r"^\s*(?:\"?([^\"<]*?)\"?\s*)?<\s*([^<>\s]+@[^<>\s]+)\s*>\s*$")


def parse_email_sender(sender: str | None) -> tuple[str, str] | None:
    """`Name <a@b.c>` or `a@b.c` -> (display_name, address); None if the sender is not an email."""
    if not sender or "@" not in sender:
        return None
    m = _NAME_ADDR_RE.match(sender)
    if m:
        return (m.group(1) or "").strip(), m.group(2).strip()
    s = sender.strip()
    return ("", s) if EMAIL_RE.fullmatch(s) else None


def find_emails(text: str) -> list[str]:
    """Email addresses in free text. URLs are removed first: `http://sbi.co.in@evil.com` is a link with
    user-info (flagged as `user_info`), not an email address."""
    return list(dict.fromkeys(EMAIL_RE.findall(re.sub(r"https?://\S+", " ", text or ""))))


def chk_email_brand(info):
    """Display name or local part names a brand whose official domains don't include the sender's.
    From a free-mail provider this is the classic fake-bank-mail pattern (high); otherwise careful,
    because we cannot see SPF/DKIM: the wording is 'this address doesn't match', never 'fake'."""
    from . import rules
    claim = (info.get("display_name") or "") + " " + (info.get("local") or "")
    if not any(not _brand_ok(b, info) for b in claimed_brands(claim)):
        return set()
    return {"brand_mismatch_free_mail"} if info["registrable"] in rules.free_mail() else {"brand_mismatch"}


EMAIL_CHECKS: list[Callable[[dict], set]] = HOST_CHECKS + [chk_email_brand]


def check_email(address: str, display_name: str | None = None, host_checks=None) -> dict:
    """Same result shape as check_host. No path checks (an address has no path)."""
    info = email_info(address, display_name)
    r = check_host(info, host_checks=EMAIL_CHECKS if host_checks is None else host_checks, path_checks=[])
    r["address_domain"] = info["registrable"]
    return r
