"""Scam look-alike pages for the live demo (GET /demo). Only on when DEMO_PAGES=1 (scripts/serve-public.sh sets it).

They exist so the bubble and "Look at this link more closely" have something to find. They must never work as a real scam:
- The bank pages name SBI so the brand check has something real to catch, but they are pictures of a form: no <form>, no script,
  read-only boxes, no logo. A test enforces this. Every other organisation is fictional.
- Nothing can leave any page: no POST route here, forms on the fictional pages point at the reserved `.invalid` domain, and the
  CSP sets form-action 'none' and allows no network calls. Typed text is never read.
- A "demo" banner on every page; noindex, so search engines leave them out.
The expected results in pages.json are enforced by tests/test_demo_pages.py, so the /demo list cannot drift from the real checks.
"""
from __future__ import annotations

import html
import json
import os
from functools import lru_cache

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import HTMLResponse, Response

from . import rules

router = APIRouter()
DIR = rules.DATA / "demo"
APK_NAME = "golden-star-prize.apk"
SEVERITY_LABEL = {"high": "High risk", "careful": "Be careful", "none": "No warning signs"}
HEADERS = {
    "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; form-action 'none'; base-uri 'none'",
    "X-Robots-Tag": "noindex, nofollow",
    "Referrer-Policy": "no-referrer",
    "Cache-Control": "no-store",
}
BANNER = "Finban demo page, not a real site. Never enter real details."

STYLE = """
:root{--brand:#1f4e8c;--tint:#eef3fa}.lottery{--brand:#8a2be2;--tint:#f6efff}.gov{--brand:#1c7a4d;--tint:#eaf6f0}.alert{--brand:#b3261e;--tint:#fdeeed}.market{--brand:#0b7285;--tint:#e8f6f9}
*{box-sizing:border-box}body{margin:0;background:var(--tint);color:#1c2430;font:17px/1.5 system-ui,sans-serif}
.demo-banner{background:#1c2430;color:#fff;font-size:14px;padding:8px 16px;text-align:center}
header{background:var(--brand);color:#fff;padding:18px 16px;font-weight:700;font-size:20px}
main{max-width:480px;margin:0 auto;padding:16px}.card{background:#fff;border-radius:14px;padding:20px;box-shadow:0 2px 10px #0000001a;margin-bottom:16px}
h1{font-size:24px;line-height:1.25;margin:0 0 10px}p{margin:0 0 12px}label{display:block;font-weight:600;margin:12px 0 4px}
input{width:100%;font:inherit;padding:12px;border:1px solid #b9c2cf;border-radius:8px}
button,.button{display:block;width:100%;margin-top:18px;padding:14px;border:0;border-radius:10px;background:var(--brand);color:#fff;font:inherit;font-weight:700;text-align:center;text-decoration:none}
.big{font-size:34px;font-weight:800;color:var(--brand);margin:8px 0}.small{font-size:14px;color:#5b6675}.notice{background:#fff4d6;border-radius:8px;padding:12px;margin-bottom:12px;font-weight:600}
.demo-done{display:none;background:#1c2430;color:#fff;border-radius:10px;padding:14px;margin-top:14px}
"""
SCRIPT = """document.querySelectorAll('form').forEach(function(f){f.addEventListener('submit',function(e){e.preventDefault();f.reset();
var n=document.getElementById('demo-done');if(n)n.style.display='block'})})"""


@lru_cache(maxsize=1)
def manifest() -> dict:
    return json.loads((DIR / "pages.json").read_text(encoding="utf-8"))


def page(slug: str) -> dict | None:
    return next((p for p in manifest()["pages"] if p["slug"] == slug), None)


@lru_cache(maxsize=32)
def render(slug: str) -> str:
    p = page(slug)
    body = (DIR / "pages" / f"{slug}.html").read_text(encoding="utf-8")
    interactive = ""
    if "<form" in body:     # pages with no form get no script at all
        interactive = f"""<main><div class="demo-done" id="demo-done">This was a demo. Nothing was sent or saved. A real page like this would have your details now.</div></main>
<script>{SCRIPT}</script>"""
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex,nofollow"><title>{html.escape(p['title'])}</title><style>{STYLE}</style></head>
<body class="{p['theme']}"><div class="demo-banner">{BANNER}</div>{body}{interactive}</body></html>"""


def base_url(request: Request) -> str:
    proto = request.headers.get("x-forwarded-proto") or request.url.scheme
    return f"{proto}://{request.headers.get('host') or request.url.netloc}"


def sample_text(sample: dict, base: str) -> str:
    return sample["text"].replace("{base}", base)


def result_line(p: dict) -> str:
    """The app offers the closer look only when the first result is not high (ResultSheet.kt), so the list only promises it then."""
    line = f"Bubble: {SEVERITY_LABEL[p['bubble']]}."
    return line if p["bubble"] == "high" else f"{line} Tap the closer look: {SEVERITY_LABEL[p['link_check']]}."


def render_index(base: str) -> str:
    m = manifest()
    groups = []
    for group in m["groups"]:
        items = []
        for p in (x for x in m["pages"] if x["group"] == group):
            items.append(f"""<div class="card"><h1>{html.escape(p['label'])}</h1><p>{html.escape(p['shows'])}</p>
<p class="small">{result_line(p)}</p>
<a class="button" href="/demo/{p['slug']}">Open page</a></div>""")
        groups.append(f"<h1>{html.escape(group)}</h1>" + "".join(items))
    texts = "".join(f"""<div class="card"><p><b>{html.escape(s['label'])}</b> ({SEVERITY_LABEL[s['expect']]})</p><p class="small">{html.escape(sample_text(s, base))}</p></div>"""
                    for s in m["samples"])
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex,nofollow"><title>Finban demo pages</title><style>{STYLE}</style></head>
<body><div class="demo-banner">{BANNER}</div><header>Finban demo pages</header><main>
<p>Open a page on the phone, then tap the Finban bubble. Tap "Look at this link more closely" on the result to go deeper.</p>
{''.join(groups)}<h1>Sample texts</h1><p>Send one from an unsaved number, or share it to Finban.</p>{texts}</main></body></html>"""


def _require_enabled() -> None:
    if os.environ.get("DEMO_PAGES") != "1":
        raise HTTPException(404, "not_found")


@router.get("/demo", include_in_schema=False)
def demo_index(request: Request):
    _require_enabled()
    return HTMLResponse(render_index(base_url(request)), headers=HEADERS)


@router.get(f"/demo/files/{APK_NAME}", include_in_schema=False)
def demo_apk():
    """Named and typed like an app so the download checks fire. It is one line of text and cannot be installed."""
    _require_enabled()
    return Response(b"Finban demo file. This is not an app.\n", media_type="application/vnd.android.package-archive",
                    headers={**HEADERS, "Content-Disposition": f'attachment; filename="{APK_NAME}"'})


@router.get("/demo/{slug}", include_in_schema=False)
def demo_page(slug: str):
    _require_enabled()
    if page(slug) is None:      # only slugs from pages.json ever reach a file path
        raise HTTPException(404, "not_found")
    return HTMLResponse(render(slug), headers=HEADERS)
