import hashlib
import logging
import os
import re
import time
import uuid
from typing import Optional

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from . import alerts, costguard, demo_pages, image_check, image_vision, link_check, llm, pairing, rules, templates
from .auth import verify
from .models import AnalyzeRequest, AnalyzeResponse, ImageAnalyzeResponse, ImageCheckResponse, LinkCheckRequest, LinkCheckResponse

log = logging.getLogger("guard")
if not log.handlers:
    _h = logging.StreamHandler()
    _h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s"))
    log.addHandler(_h)
    log.setLevel(logging.INFO)
# Salt for the uid hash in logs. Set LOG_SALT in production so hashes can't be matched to Firebase uids.
LOG_SALT = os.environ.get("LOG_SALT", "dev-salt")


def uid_hash(uid: str) -> str:
    """Stable short pseudonym for logs (same uid -> same value, not reversible without the salt)."""
    return hashlib.sha256((LOG_SALT + uid).encode()).hexdigest()[:12]

SERVER_BUDGET_S = 2.6   # client gives up at 3.5 s and spends ~0.3 s on its token + the network

app = FastAPI(title="Finban API")
@app.on_event("startup")
def _warm():
    from . import domain_age
    domain_age.prewarm()


app.include_router(pairing.router)
app.include_router(alerts.router)
app.include_router(demo_pages.router)


@app.exception_handler(HTTPException)
async def http_error(request: Request, exc: HTTPException):
    """docs/API.md error shape: {"error": "<code>"}."""
    return JSONResponse({"error": exc.detail}, status_code=exc.status_code)


@app.middleware("http")
async def timing(request: Request, call_next):
    t0 = time.time()
    rid = request.state.rid = uuid.uuid4().hex[:8]
    resp = await call_next(request)
    # never log message text
    log.info("req=%s path=%s status=%s ms=%d", rid, request.url.path, resp.status_code, (time.time() - t0) * 1000)
    return resp


@app.get("/health")
def health():
    return {"status": "ok", "version": "0.1.0", "demo_mode": os.environ.get("DEMO_MODE") == "1"}


@app.get("/debug/status")
def debug_status(uid: str = Depends(verify)):
    """For the app's developer screen: which optional services this server is configured for. Booleans and the model name only,
    never a key. Needs a signed-in caller so it is not a public map of the server's setup."""
    return {
        "version": "0.1.0",
        "demo_mode": os.environ.get("DEMO_MODE") == "1",
        "llm_configured": llm.get_llm() is not None,
        "llm_model": os.environ.get("LLM_MODEL") if llm.get_llm() is not None else None,
        "image_configured": bool(os.environ.get("SIGHTENGINE_USER") and os.environ.get("SIGHTENGINE_SECRET")) and os.environ.get("IMAGE_CHECK") != "0",
        "link_fetch": os.environ.get("LINK_FETCH", "1") != "0",
        "domain_age": os.environ.get("DOMAIN_AGE", "1") != "0",
        "firestore_emulator": bool(os.environ.get("FIRESTORE_EMULATOR_HOST")),
    }


@app.get("/patterns/version")
def patterns_version(uid: str = Depends(verify)):
    return {"version": rules.patterns_version()}


@app.post("/analyze", response_model=AnalyzeResponse)
def analyze(req: AnalyzeRequest, request: Request, uid: str = Depends(verify),
            client: Optional[llm.LlmClient] = Depends(llm.get_llm)):
    t0 = time.time()
    caller_unknown = req.context.caller_unknown if req.context else None
    r = rules.analyze(req.text, req.sender, req.links, caller_unknown)
    built = templates.build(r, 0)
    stages = list(r["stages"])
    # Whole answer must be out by ~2.6 s (client gives up at 3.5 s incl. its token fetch and mobile-network latency). Rules + domain age may have
    # used some of it already, so the LLM only gets what is left.
    left = min(llm.DEADLINE_S, SERVER_BUDGET_S - (time.time() - t0))
    if client is not None and built["severity"] != "none" and not costguard.ai.allow(costguard.client_ip(request)):
        client = None   # over the AI cap: template text
    if llm.enrich(built, next((p for p in rules.patterns() if p["id"] == r["pattern_id"]), None), client, deadline=left):
        stages.append("llm")
    built["latency_ms"] = int((time.time() - t0) * 1000)
    out = AnalyzeResponse(**built)
    # Never log text, sender, links, or the raw uid.
    log.info("analyze req=%s uid=%s source=%s severity=%s pattern=%s verdict=%s stages=%s ms=%d",
             getattr(request.state, "rid", "-"), uid_hash(uid), req.source, out.severity, out.pattern_id,
             out.source_of_verdict, ",".join(stages), out.latency_ms)
    return out


link_limiter = pairing.Limiter(10, 60)   # per uid, in memory: a link check contacts a third-party site


@app.post("/link/check", response_model=LinkCheckResponse)
def check_link(req: LinkCheckRequest, request: Request, uid: str = Depends(verify)):
    """The person tapped "Look at this link more closely". The server fetches the page (never the phone). The URL is in the
    body, not the query string, so it is not in access logs; it is never logged."""
    if not link_limiter.allow(uid):
        raise HTTPException(429, "rate_limited")
    url = req.url.strip()
    if "://" not in url:
        url = "http://" + url
    if not url.lower().startswith(("http://", "https://")):
        raise HTTPException(422, "bad_url")
    out = link_check.check(url)
    log.info("link_check req=%s uid=%s severity=%s reached=%s redirected=%s ms=%d", getattr(request.state, "rid", "-"), uid_hash(uid),
             out["severity"], out["checked"]["page_reached"], out["checked"]["redirected"], out["latency_ms"])
    return LinkCheckResponse(**out)


image_limiter = pairing.Limiter(10, 60)   # per uid: each call sends the photo to a third party


@app.post("/image", response_model=ImageCheckResponse)
async def check_image(request: Request, uid: str = Depends(verify)):
    """Raw image bytes as the body (Content-Type image/jpeg|png|webp), no multipart. In memory only, never logged."""
    if not image_limiter.allow(uid):
        raise HTTPException(429, "rate_limited")
    ctype = (request.headers.get("content-type") or "").split(";")[0].strip().lower()
    if ctype not in ("image/jpeg", "image/png", "image/webp"):
        raise HTTPException(415, "unsupported_type")
    data = await request.body()
    if not data or len(data) > image_check.MAX_BYTES:
        raise HTTPException(413, "too_large")
    import asyncio
    verdict = await asyncio.get_running_loop().run_in_executor(None, image_check.check, data, ctype)
    log.info("image_check req=%s uid=%s verdict=%s bytes=%d", getattr(request.state, "rid", "-"), uid_hash(uid), verdict, len(data))
    return ImageCheckResponse(verdict=verdict)


@app.post("/image/analyze", response_model=ImageAnalyzeResponse)
async def analyze_image(request: Request, uid: str = Depends(verify), client: Optional[llm.LlmClient] = Depends(llm.get_llm)):
    """A photo or a screenshot: a vision model says what it shows and reads its text; the text is then judged by the same rules as
    a typed message (severity is never the model's). Also runs the AI-photo check. Raw image bytes in, held in memory only."""
    if not image_limiter.allow(uid):
        raise HTTPException(429, "rate_limited")
    ctype = (request.headers.get("content-type") or "").split(";")[0].strip().lower()
    if ctype not in ("image/jpeg", "image/png", "image/webp"):
        raise HTTPException(415, "unsupported_type")
    data = await request.body()
    if not data or len(data) > image_check.MAX_BYTES:
        raise HTTPException(413, "too_large")
    import asyncio
    t0 = time.time()
    loop = asyncio.get_running_loop()
    ip = costguard.client_ip(request)
    vis_allowed = image_vision.config() is None or costguard.vision.allow(ip)   # only a call that would be paid for counts
    vis_f = loop.run_in_executor(None, image_vision.describe, data, ctype) if vis_allowed else loop.run_in_executor(None, lambda: None)
    # ?ai=0 (screenshots) skips the paid AI-photo check: a screenshot of a screen is never "an AI-made photo".
    if request.query_params.get("ai") == "0":
        vis, ai = await vis_f, "unknown"
    else:
        vis, ai = await asyncio.gather(vis_f, loop.run_in_executor(None, image_check.check, data, ctype))
    text = (vis or {}).get("text", "")
    links = [m.group(0).rstrip(".,)") for m in re.finditer(r"https?://\S+", text)][:10]
    r = rules.analyze(text, None, links, None)
    built = templates.build(r, 0)
    if client is not None and built["severity"] != "none" and not costguard.ai.allow(ip):
        client = None
    llm.enrich(built, next((p for p in rules.patterns() if p["id"] == r["pattern_id"]), None), client, deadline=2.0)
    built["latency_ms"] = int((time.time() - t0) * 1000)
    out = ImageAnalyzeResponse(**built, description=(vis or {}).get("description"), text_found=bool(text), text=text or None,
                               vision_available=vis is not None, ai_image=ai)
    log.info("image_analyze req=%s uid=%s vision=%s text=%s severity=%s ai=%s bytes=%d ms=%d", getattr(request.state, "rid", "-"), uid_hash(uid),
             vis is not None, bool(text), out.severity, ai, len(data), out.latency_ms)
    return out


# Pre-generated explanation audio (no user data in it). TODO(hackathon): S6 generates the files.
if templates.AUDIO_DIR.is_dir():
    app.mount("/audio", StaticFiles(directory=templates.AUDIO_DIR), name="audio")
