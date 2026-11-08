"""LLM explanation layer (OpenRouter). It only writes user-facing text. Severity, pattern, reasons and actions
are decided by rules/domain checks before this runs and are never read back from the model.

Input is findings only (generic template sentences + pattern name), never the message, sender or links.
Any failure (no key, timeout, bad JSON, banned wording) returns None and the caller keeps template text.
"""
from __future__ import annotations

import json
import logging
import os
import re
import threading
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeout
from typing import Optional, Protocol

log = logging.getLogger("guard")

TIMEOUT_S = 8.0     # background HTTP timeout: lets slow answers still fill the cache
DEADLINE_S = 2.2    # how long a request waits (client gives up at 3.5 s, docs/API.md); rules take ~ms
URL = "https://openrouter.ai/api/v1/chat/completions"

SYSTEM = """You write short warnings for an elderly person in Tamil Nadu who may be looking at a scam message.
You are given findings from an automatic checker. Explain them in plain, calm words a 70-year-old understands.
Rules:
- Use only the findings given. Do not invent facts, names, amounts, numbers, links or phone numbers.
- ONE short sentence per language (English at most 25 words, Tamil at most 30 words). Say what looks wrong and what to do (do not pay, do not share OTP/PIN, ask a trusted person or call 1930 if money was sent).
- Write the Tamil the way a caring son or daughter would explain it at home: simple, everyday, polite Tamil in short common words. No literary, formal or news-style Tamil. Use the words people really say: மெசேஜ், லிங்க், வெப்சைட், போன், ஆப், பணம், வங்கி, OTP, PIN, UPI. Say மோசடி for scam and நம்பிக்கையான ஒருவர் for a trusted person. Example: "இந்த மெசேஜ் மோசடி போல் தெரிகிறது, அதனால் பணம் அனுப்பாதீர்கள், நம்பிக்கையான ஒருவரிடம் கேளுங்கள்."
- Never say a message is "safe", "real", "genuine" or "legitimate" (in Tamil too: never பாதுகாப்பான, உண்மையான, நம்பகமான, அசல்). Never say an email is "fake"; say the address does not match.
- Do not tell them to click, install or reply.
Return only JSON: {"explanation_en": "...", "explanation_ta": "..."} (explanation_ta in Tamil script)."""

BANNED = re.compile(r"\b(safe|real|genuine|legit|legitimate|authentic|verified|guarantee[sd]?)\b|100\s*%", re.I)
BANNED_TA = re.compile("பாதுகாப்பான|உண்மையான|நம்பகமான|அசல்|உத்தரவாத")
URLISH = re.compile(r"https?://|www\.|\d{5,}", re.I)
TAMIL = re.compile(r"[஀-௿]")


class LlmClient(Protocol):
    def complete(self, system: str, user: str, timeout: float) -> str: ...


class OpenRouterClient:
    def __init__(self, api_key: str, model: str):
        self.api_key, self.model = api_key, model

    def complete(self, system, user, timeout):
        import httpx
        r = httpx.post(URL, timeout=timeout, headers={"Authorization": f"Bearer {self.api_key}"}, json={
            "model": self.model, "temperature": 0.2, "max_tokens": 500,
            "reasoning": {"enabled": False},  # thinking tokens add seconds and can leave content empty
            "response_format": {"type": "json_object"},
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]})
        r.raise_for_status()
        content = r.json()["choices"][0]["message"].get("content")
        if not content:
            raise ValueError("empty_content")
        return content


def get_llm() -> Optional[LlmClient]:
    """FastAPI dependency. None when no key (or LLM_DISABLED=1): the server then uses template text only."""
    key, model = os.environ.get("OPENROUTER_API_KEY"), os.environ.get("LLM_MODEL")
    if not key or not model or os.environ.get("LLM_DISABLED") == "1":
        return None
    return OpenRouterClient(key, model)


def findings(out: dict, pattern: Optional[dict]) -> dict:
    return {"severity": out["severity"],
            "scam_type": pattern["name_en"] if pattern else None,
            "findings": [r.text_en for r in out["reasons"]]}


def parse(raw: str) -> Optional[dict]:
    """Validate model output. Returns {"explanation_en": str, "explanation_ta": str} or None. Both languages must pass:
    the template text is used for both otherwise, so the two never disagree and the AI badge stays true."""
    raw = raw.strip()
    raw = re.sub(r"^```(?:json)?|```$", "", raw, flags=re.M).strip()
    try:
        j = json.loads(raw)
    except ValueError:
        return None
    if not isinstance(j, dict):
        return None
    en, ta = j.get("explanation_en"), j.get("explanation_ta")
    if not isinstance(en, str) or not 20 <= len(en) <= 400 or BANNED.search(en) or URLISH.search(en):
        return None
    if not (isinstance(ta, str) and 10 <= len(ta) <= 600 and TAMIL.search(ta)) or BANNED_TA.search(ta) or URLISH.search(ta):
        return None
    return {"explanation_en": en.strip(), "explanation_ta": ta.strip()}


_cache: dict = {}
_inflight: dict = {}
_lock = threading.Lock()
_pool = ThreadPoolExecutor(max_workers=4, thread_name_prefix="llm")


def _fetch(key: str, f: dict, client: LlmClient) -> Optional[dict]:
    """Runs in the pool. Always fills the cache on success, even if the request already gave up waiting,
    so a repeat of the same findings (or a pre-warmed demo) is instant."""
    try:
        res = parse(client.complete(SYSTEM, "Findings:\n" + json.dumps(f, ensure_ascii=False), TIMEOUT_S))
        if res is None:
            log.info("llm fallback reason=invalid_output")
            return None
        with _lock:
            if len(_cache) > 256:
                _cache.clear()
            _cache[key] = res
        return res
    except Exception as e:  # timeout, HTTP error, anything: fall back. Log the class only.
        log.info("llm fallback reason=%s", type(e).__name__)
        return None
    finally:
        with _lock:
            _inflight.pop(key, None)


def enrich(out: dict, pattern: Optional[dict], client: Optional[LlmClient], deadline: Optional[float] = None) -> bool:
    """Replace explanation text on `out` in place. Returns True if the LLM text was used.
    Waits at most `deadline` (default DEADLINE_S); a slower answer is cached for next time. Never touches severity,
    pattern_id, reasons or actions."""
    if client is None or out["severity"] == "none":
        return False
    f = findings(out, pattern)
    key = json.dumps(f, sort_keys=True)
    with _lock:
        res = _cache.get(key)
        fut = None if res else _inflight.get(key)
        if res is None and fut is None:
            fut = _inflight[key] = _pool.submit(_fetch, key, f, client)
    if res is None:
        try:
            res = fut.result(timeout=DEADLINE_S if deadline is None else max(0.0, deadline))
        except FutureTimeout:
            log.info("llm fallback reason=deadline")
            return False
    if res is None:
        return False
    out["explanation_en"], out["explanation_ta"] = res["explanation_en"], res["explanation_ta"]
    out["source_of_verdict"] = "rules+llm"
    return True
