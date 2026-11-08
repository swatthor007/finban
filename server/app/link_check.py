"""On-demand deep check of ONE link (POST /link/check): local address checks + domain age + page content.

The person taps a button, so the fetch is their choice. The server is the proxy: the phone never contacts the site.
Only adds warnings, never says a link is fine: an unreachable or clean-looking page proves nothing.
"""
from __future__ import annotations

import time
from concurrent.futures import ThreadPoolExecutor
from typing import Optional

from . import domain_age, domains, page_fetch, rules, templates

SEV_RANK = {"none": 0, "careful": 1, "high": 2}
BUDGET_S = 3.2          # whole check; the app waits up to 5 s


def assess(url: str, page: Optional[dict], age: Optional[int]) -> dict:
    """Pure: combine address checks, age and page facts into {severity, flags[(flag, level)], ...}. No network."""
    local = domains.check_url(url)
    reg = local["registrable"]
    levels: dict[str, str] = {f: domains.FLAG_LEVEL[f] for f in local["flags"]}
    age_flag = domain_age.flag_for(age)
    if age_flag:
        levels[age_flag] = domains.FLAG_LEVEL[age_flag]

    redirected, final_reg = False, reg
    if page:
        ev = page_fetch.evaluate(
            page, reg,
            official_check=lambda x: domains.is_official(domains.host_info(x)),
            claimed=domains.claimed_brands,
            brand_ok=lambda b, x: domains._brand_ok(b, domains.host_info(x)),
            registrable_of=lambda u: domains.host_info(u)["registrable"])
        redirected, final_reg = ev["redirected"], ev["final_registrable"]
        if redirected:                      # a redirector/shortener: judge where it really leads as well
            for f in domains.check_url(page["final_url"])["flags"]:
                levels.setdefault(f, domains.FLAG_LEVEL[f])
        page_flags = {f: domains.FLAG_LEVEL[f] for f in ev["flags"]}
        if "asks_secret_details" in page_flags and ("page_brand_mismatch" in page_flags or age_flag or redirected
                                                    or local["severity"] == "high"):
            # Asking for secrets is only "careful" alone (many genuine sites have logins). High when the page also fakes
            # a brand, the site is brand new, the link was a redirect, or the address already looked high-risk.
            page_flags["asks_secret_details"] = "high"
        levels.update(page_flags)

    ordered = sorted(levels.items(), key=lambda kv: (-SEV_RANK[kv[1]], kv[0]))
    sev = max((lv for _, lv in ordered), key=SEV_RANK.__getitem__, default="none")
    return {"severity": sev, "flags": ordered, "registrable": reg, "final_registrable": final_reg,
            "redirected": redirected, "age_days": age, "page_reached": page is not None}


def check(url: str, resolver=page_fetch._default_resolver, client=None) -> dict:
    """Does the network work (page + age in parallel, time-boxed) and returns the API response dict."""
    t0 = time.time()
    reg = domains.host_info(url)["registrable"]
    page: Optional[dict] = None
    age: Optional[int] = None
    if page_fetch.enabled():
        with ThreadPoolExecutor(max_workers=2) as ex:
            f_page = ex.submit(page_fetch.fetch, url, resolver, client)
            f_age = ex.submit(domain_age.age_days, reg) if reg and not domains.host_info(url)["is_ip"] and domain_age.enabled() else None
            page = f_page.result(timeout=BUDGET_S)
            if f_age:
                try:
                    age = f_age.result(timeout=max(0.1, BUDGET_S - (time.time() - t0)))
                except Exception:
                    age = None
    a = assess(url, page, age)
    reasons = [{"id": "link." + f, "level": lv, "text_ta": templates.reason_text_ta("link." + f), "text_en": templates.reason_text_en("link." + f)}
               for f, lv in a["flags"]][:3]
    if not a["page_reached"] and not reasons:
        ta, en = templates.PAGE_UNREACHABLE
        reasons.append({"id": "link.page_unreachable", "level": "none", "text_ta": ta, "text_en": en})
    return {"severity": a["severity"], "reasons": reasons,
            "checked": {"registrable_domain": a["registrable"], "final_domain": a["final_registrable"], "redirected": a["redirected"],
                        "age_days": a["age_days"], "page_reached": a["page_reached"]},
            "latency_ms": int((time.time() - t0) * 1000)}
