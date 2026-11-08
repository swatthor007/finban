"""Domain age via RDAP (step 5 of the link pipeline).

Privacy: only the registrable domain (e.g. `yuyaa.xyz`) is sent, to the RDAP server of that TLD's registry,
found through IANA's bootstrap file. No message text, sender or full URL leaves the server.
Honesty: any failure, timeout or missing data gives `None` ("unknown"), never "old" or "clean".
Disable with DOMAIN_AGE=0 (tests do).
"""
from __future__ import annotations

import os
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor, wait
from datetime import datetime, timezone
from typing import Optional

BOOTSTRAP_URL = "https://data.iana.org/rdap/dns.json"
PER_REQUEST_TIMEOUT = 1.5     # seconds, each registry call (real .xyz lookup: bootstrap 0.3 s + RDAP 0.9 s)
TOTAL_BUDGET = 1.5            # seconds for all links of one message (client allows 3.5 s in total)
MAX_DOMAINS = 3               # lookups per message
OK_TTL = 24 * 3600
FAIL_TTL = 300                # retry unknown results after 5 minutes

# Thresholds (days). Tunable: TODO(hackathon): sanity-check against real scam/legit examples.
VERY_NEW_DAYS = 14            # high
NEW_DAYS = 90                 # careful

_lock = threading.Lock()
_cache: dict[str, tuple[float, Optional[int]]] = {}
_bootstrap: dict = {"at": 0.0, "map": {}}


def enabled() -> bool:
    return os.environ.get("DOMAIN_AGE", "1") != "0"


def flag_for(age_days: Optional[int]) -> Optional[str]:
    """Flag name for an age, or None (unknown or old enough)."""
    if age_days is None or age_days < 0:
        return None
    if age_days <= VERY_NEW_DAYS:
        return "very_new_domain"
    if age_days <= NEW_DAYS:
        return "new_domain"
    return None


def _client():
    import httpx
    return httpx.Client(timeout=PER_REQUEST_TIMEOUT, follow_redirects=True,
                        headers={"Accept": "application/rdap+json, application/json"})


def _rdap_base(tld: str, client) -> Optional[str]:
    now = time.time()
    with _lock:
        fresh = bool(_bootstrap["map"]) and now - _bootstrap["at"] < OK_TTL
        table = _bootstrap["map"]
    if not fresh:
        data = client.get(BOOTSTRAP_URL).json()
        table = {}
        for tlds, urls in data.get("services", []):
            for t in tlds:
                if urls:
                    table[t.lower()] = urls[0]
        with _lock:
            _bootstrap["map"], _bootstrap["at"] = table, now
    base = table.get(tld.lower())
    return base if base is None or base.endswith("/") else base + "/"


_FRAC = re.compile(r"\.(\d+)")


def _normalise(stamp: str) -> str:
    """Registries send '2026-06-30T07:09:21.0Z'; Python 3.9 needs 'Z' as +00:00 and exactly 6 fractional digits."""
    return _FRAC.sub(lambda m: "." + m.group(1)[:6].ljust(6, "0"), stamp.replace("Z", "+00:00"))


def _registration_age(doc: dict, now: datetime) -> Optional[int]:
    for ev in doc.get("events", []) or []:
        if ev.get("eventAction") == "registration" and ev.get("eventDate"):
            try:
                d = datetime.fromisoformat(_normalise(ev["eventDate"]))
            except ValueError:
                return None
            if d.tzinfo is None:
                d = d.replace(tzinfo=timezone.utc)
            return max(0, (now - d).days)
    return None


def _lookup(registrable: str, client=None) -> Optional[int]:
    try:
        c = client or _client()
        try:
            base = _rdap_base(registrable.rsplit(".", 1)[-1], c)
            if not base:
                return None
            r = c.get(f"{base}domain/{registrable}")
            if r.status_code != 200:
                return None
            return _registration_age(r.json(), datetime.now(timezone.utc))
        finally:
            if client is None:
                c.close()
    except Exception:  # network, JSON, anything: unknown
        return None


def age_days(registrable: str, client=None) -> Optional[int]:
    """Cached lookup. Unknown (None) is cached only briefly."""
    key = registrable.lower()
    now = time.time()
    with _lock:
        hit = _cache.get(key)
        if hit and now < hit[0]:
            return hit[1]
    age = _lookup(key, client)
    with _lock:
        _cache[key] = (now + (OK_TTL if age is not None else FAIL_TTL), age)
    return age


def ages_for(domains: list[str]) -> dict[str, Optional[int]]:
    """Look up several domains in parallel within TOTAL_BUDGET. Anything not back in time is None."""
    uniq = list(dict.fromkeys(d.lower() for d in domains))[:MAX_DOMAINS]
    out: dict[str, Optional[int]] = {d: None for d in uniq}
    if not uniq or not enabled():
        return out
    ex = ThreadPoolExecutor(max_workers=len(uniq))
    try:
        futs = {ex.submit(age_days, d): d for d in uniq}
        done, _ = wait(futs, timeout=TOTAL_BUDGET)
        for f in done:
            out[futs[f]] = f.result()
    finally:
        ex.shutdown(wait=False, cancel_futures=True)  # stragglers finish in the background and fill the cache
    return out


def prewarm() -> None:
    """Load the IANA bootstrap table in the background so the first real lookup skips that round trip."""
    if not enabled():
        return
    def run():
        try:
            c = _client()
            try:
                _rdap_base("com", c)
            finally:
                c.close()
        except Exception:
            pass
    threading.Thread(target=run, daemon=True).start()
