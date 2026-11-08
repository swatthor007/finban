"""Limited, defensive page fetch for a link the person asked us to look at (on demand, via link_check.py).

What it does: GET the page (no JavaScript, no rendering, no cookies kept), read <=200 KB of HTML, and extract
metadata + a few structural facts (title, site name, headings, forms, sensitive input fields, where forms post,
redirect chain, downloads). `evaluate()` turns that into warning flags. It can ONLY add warnings: a clean-looking
page proves nothing (scam pages cloak by IP/user agent), so nothing here ever lowers a severity or says "safe".

Safety (SSRF + abuse):
- http/https only, ports 80/443 only, no user-info.
- DNS is resolved HERE; every address must be globally routable (no loopback/private/link-local/metadata/etc).
  The connection is pinned to the checked IP (Host header + SNI carry the name), so DNS rebinding cannot swap it.
- Redirects are followed by hand (<=3), each hop re-validated and re-pinned.
- Hard caps: total time, bytes read, HTML only; anything else is recorded (content type) and not read.
Privacy: the fetch reveals this server's IP to the link's owner (and tells them the link was opened). Hence it only
runs when the PERSON taps "Look at this link more closely" (POST /link/check), never automatically.
Page text is never logged or stored.
"""
from __future__ import annotations

import ipaddress
import os
import re
import socket
import threading
import time
from html.parser import HTMLParser
from typing import Callable, Optional
from urllib.parse import urljoin, urlparse

MAX_BYTES = 200_000
MAX_HOPS = 3
PER_PHASE_TIMEOUT = 1.2
TOTAL_BUDGET = 2.0
ALLOWED_PORTS = {80, 443}
CACHE_TTL = 600
# A plain mobile browser: scam pages often show the real page only to phones. TODO(hackathon): revisit.
USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

_lock = threading.Lock()
_cache: dict[str, tuple[float, Optional[dict]]] = {}


def enabled() -> bool:
    """Kill switch: LINK_FETCH=0 disables the on-demand link check entirely."""
    return os.environ.get("LINK_FETCH", "1") != "0"


# ---------------------------------------------------------------------------------------------- SSRF guard
class Blocked(Exception):
    pass


Resolver = Callable[[str, int], list]


def _default_resolver(host: str, port: int) -> list:
    return [ai[4][0] for ai in socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)]


def check_target(url: str, resolver: Resolver = _default_resolver) -> tuple[str, str, int, str, bool]:
    """Validate a URL and return (ip, host, port, path_and_query, is_https). Raises Blocked."""
    p = urlparse(url)
    if p.scheme not in ("http", "https"):
        raise Blocked("scheme")
    if p.username or p.password or "@" in p.netloc:
        raise Blocked("userinfo")
    host = (p.hostname or "").lower().rstrip(".")
    if not host:
        raise Blocked("host")
    port = p.port or (443 if p.scheme == "https" else 80)
    if port not in ALLOWED_PORTS:
        raise Blocked("port")
    try:
        addrs = [host] if _is_ip(host) else resolver(host, port)
    except OSError:
        raise Blocked("dns")
    if not addrs:
        raise Blocked("dns")
    for a in addrs:                      # ALL answers must be public, not just the first
        ip = ipaddress.ip_address(a.split("%")[0])
        if getattr(ip, "ipv4_mapped", None):
            ip = ip.ipv4_mapped
        if not ip.is_global or ip.is_multicast:
            raise Blocked("non-public address")
    path = (p.path or "/") + (("?" + p.query) if p.query else "")
    return addrs[0], host, port, path, p.scheme == "https"


def _is_ip(host: str) -> bool:
    try:
        ipaddress.ip_address(host)
        return True
    except ValueError:
        return False


# ---------------------------------------------------------------------------------------------- HTML facts
_L = r"(?<![a-z])"   # "word" edges that treat _ and digits as separators: otp_code, input-pin2 ...
_R = r"(?![a-z])"
SECRET_KINDS = (
    ("password", re.compile(r"passw|passcode", re.I)),
    ("otp", re.compile(_L + r"otp" + _R + r"|one.?time|verification.?code|sms.?code", re.I)),
    ("pin_cvv", re.compile(_L + r"(pin|cvv|cvc)" + _R + r"|security.?code", re.I)),
    ("card", re.compile(r"card.?(number|no" + _R + r")|cc-?number|debit|credit.?card|expiry", re.I)),
    ("id_number", re.compile(r"aadhaa?r|" + _L + r"(pan|ifsc|upi)" + _R + r"|account.?(no" + _R + r"|number)", re.I)),
)


class _Facts(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.title, self.meta, self.h1, self.forms = "", {}, [], []
        self.refresh: Optional[str] = None
        self.text_len = 0
        self._tag_stack: list[str] = []
        self._form: Optional[dict] = None

    def handle_starttag(self, tag, attrs):
        a = {k: (v or "") for k, v in attrs}
        self._tag_stack.append(tag)
        if tag == "meta":
            key = (a.get("name") or a.get("property") or a.get("http-equiv") or "").lower()
            if key and a.get("content"):
                self.meta.setdefault(key, a["content"].strip()[:300])
            if key == "refresh":
                m = re.search(r"url\s*=\s*['\"]?([^'\";]+)", a.get("content", ""), re.I)
                if m:
                    self.refresh = m.group(1).strip()
        elif tag == "form":
            self._form = {"action": a.get("action", ""), "fields": []}
            self.forms.append(self._form)
        elif tag in ("input", "textarea", "select") and self._form is not None:
            blob = " ".join(a.get(k, "") for k in ("type", "name", "id", "placeholder", "autocomplete", "aria-label"))
            blob = re.sub(r"(?<=[a-z])(?=[A-Z])", " ", blob)   # txtCVV -> "txt CVV"
            kind = "password" if a.get("type", "").lower() == "password" else None
            kind = kind or next((n for n, rx in SECRET_KINDS if rx.search(blob)), None)
            self._form["fields"].append(kind)

    def handle_endtag(self, tag):
        if tag == "form":
            self._form = None
        while self._tag_stack and self._tag_stack.pop() != tag:
            pass

    def handle_data(self, data):
        cur = self._tag_stack[-1] if self._tag_stack else ""
        if cur in ("script", "style", "noscript"):
            return
        d = data.strip()
        if not d:
            return
        self.text_len += len(d)
        if cur == "title" and len(self.title) < 200:
            self.title += d
        elif cur == "h1" and len(self.h1) < 3:
            self.h1.append(d[:150])


def parse_html(html: str) -> dict:
    f = _Facts()
    try:
        f.feed(html)
    except Exception:       # malformed markup: keep whatever was parsed
        pass
    return {"title": f.title[:200], "meta": f.meta, "h1": f.h1, "forms": f.forms,
            "refresh": f.refresh, "text_len": f.text_len}


# ---------------------------------------------------------------------------------------------- fetching
def _client():
    import httpx
    return httpx.Client(timeout=PER_PHASE_TIMEOUT, follow_redirects=False,
                        headers={"User-Agent": USER_AGENT, "Accept": "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5",
                                 "Accept-Language": "en-IN,en;q=0.8,ta;q=0.5"})


def _read_capped(resp) -> bytes:
    buf = bytearray()
    for chunk in resp.iter_bytes():
        buf += chunk
        if len(buf) >= MAX_BYTES:
            break
    return bytes(buf[:MAX_BYTES])


def fetch(url: str, resolver: Resolver = _default_resolver, client=None) -> Optional[dict]:
    """Returns a facts dict, or None if blocked/unreachable (unknown is not 'clean'). Never raises."""
    own = client is None
    deadline = time.monotonic() + TOTAL_BUDGET
    chain: list[str] = []
    try:
        c = client or _client()
        try:
            cur = url
            for hop in range(MAX_HOPS + 1):
                if time.monotonic() > deadline:
                    return None
                ip, host, port, path, https = check_target(cur, resolver)
                chain.append(cur)
                netloc_ip = f"[{ip}]" if ":" in ip else ip
                scheme = "https" if https else "http"
                default = 443 if https else 80
                target = f"{scheme}://{netloc_ip}{'' if port == default else ':' + str(port)}{path}"
                hdr = {"Host": host if port == default else f"{host}:{port}"}
                if host.endswith(NGROK_SUFFIXES):
                    hdr["ngrok-skip-browser-warning"] = "1"
                with c.stream("GET", target, headers=hdr, extensions={"sni_hostname": host}) as r:
                    loc = r.headers.get("location")
                    if r.status_code in (301, 302, 303, 307, 308) and loc:
                        cur = urljoin(cur, loc)
                        continue
                    ctype = r.headers.get("content-type", "").split(";")[0].strip().lower()
                    disp = r.headers.get("content-disposition", "").lower()
                    info = {"final_url": cur, "status": r.status_code, "content_type": ctype, "hops": hop,
                            "chain": chain, "download": "attachment" in disp or ctype in DOWNLOAD_TYPES,
                            "title": "", "meta": {}, "h1": [], "forms": [], "text_len": 0}
                    if ctype in ("text/html", "application/xhtml+xml") and r.status_code < 400:
                        html = _read_capped(r).decode(r.encoding or "utf-8", errors="replace")
                        info.update(parse_html(html))
                        if info.get("refresh") and info["text_len"] < 200 and hop < MAX_HOPS:
                            cur = urljoin(cur, info["refresh"])   # tiny page that only redirects
                            continue
                    return info
            return None   # too many redirects
        finally:
            if own:
                c.close()
    except Exception:     # Blocked, timeouts, TLS errors, anything: unknown
        return None


# ngrok answers browser-like clients with its own warning page instead of the site. Scam pages hide behind this too, so ask for the site itself.
NGROK_SUFFIXES = (".ngrok-free.dev", ".ngrok-free.app", ".ngrok.app", ".ngrok.dev", ".ngrok.io")

DOWNLOAD_TYPES = {"application/vnd.android.package-archive", "application/octet-stream", "application/x-msdownload",
                  "application/zip", "application/x-apk"}


def fetch_cached(url: str) -> Optional[dict]:
    now = time.time()
    with _lock:
        hit = _cache.get(url)
        if hit and now < hit[0]:
            return hit[1]
    info = fetch(url)
    with _lock:
        if len(_cache) > 256:
            _cache.clear()
        _cache[url] = (now + (CACHE_TTL if info else 60), info)
    return info


# ---------------------------------------------------------------------------------------------- evaluation
FLAG_LEVEL = {"page_brand_mismatch": "high", "page_download": "high", "asks_secret_details": "careful",
              "form_posts_elsewhere": "careful"}


def evaluate(info: Optional[dict], initial_registrable: str, official_check: Callable[[str], bool],
             claimed: Callable[[str], list], brand_ok: Callable[[dict, str], bool], registrable_of: Callable[[str], str]) -> dict:
    """Pure function of the page facts. Returns {flags: [...], secrets: [...], final_registrable, redirected}.
    The callables come from domains.py (kept as arguments so this module stays testable on its own)."""
    out = {"flags": [], "secrets": [], "final_registrable": initial_registrable, "redirected": False}
    if not info:
        return out
    final = registrable_of(info["final_url"])
    out["final_registrable"], out["redirected"] = final, bool(final and final != initial_registrable)
    flags = set()
    if info.get("download"):
        flags.add("page_download")
    secrets = sorted({k for form in info["forms"] for k in form["fields"] if k})
    out["secrets"] = secrets
    on_known_site = official_check(final)
    if secrets and not on_known_site:
        flags.add("asks_secret_details")
    for form in info["forms"]:
        if form["action"].lower().startswith(("http://", "https://", "//")) and any(form["fields"]):
            target = registrable_of(urljoin(info["final_url"], form["action"]))
            if target and target != final:
                flags.add("form_posts_elsewhere")
    headline = " ".join([info.get("title", ""), info["meta"].get("og:site_name", ""), info["meta"].get("og:title", "")] + info.get("h1", []))
    if not on_known_site and any(not brand_ok(b, final) for b in claimed(headline)):
        flags.add("page_brand_mismatch")
    out["flags"] = sorted(flags, key=lambda f: (-{"high": 2, "careful": 1}[FLAG_LEVEL[f]], f))
    return out

