"""POST /image: is this photo probably made by a computer? (SightEngine `genai` model.)

The photo is held in memory for the request only: never written to disk, never logged. It does leave this server for
SightEngine, which the app tells the person. Severity is NOT touched here: the answer is an extra note in the sheet
("probably made by a computer" / "we couldn't tell"), never "real" or "fake". No key set -> "unknown".
"""
from __future__ import annotations

import os
from typing import Literal

import httpx

MAX_BYTES = 4 * 1024 * 1024
LIKELY = 0.85      # TODO(hackathon): tune on real examples
UNLIKELY = 0.15
Verdict = Literal["likely_ai", "unlikely_ai", "unknown"]


def verdict_for(score) -> Verdict:
    """Pure: map SightEngine's ai_generated probability to a verdict. The middle band and any missing value are 'unknown'."""
    if not isinstance(score, (int, float)):
        return "unknown"
    if score >= LIKELY:
        return "likely_ai"
    if score <= UNLIKELY:
        return "unlikely_ai"
    return "unknown"


def check(data: bytes, content_type: str, client: httpx.Client | None = None) -> Verdict:
    user, secret = os.environ.get("SIGHTENGINE_USER"), os.environ.get("SIGHTENGINE_SECRET")
    if not user or not secret or os.environ.get("IMAGE_CHECK") == "0":
        return "unknown"
    own = client is None
    client = client or httpx.Client(timeout=4.0)
    try:
        r = client.post(
            "https://api.sightengine.com/1.0/check.json",
            data={"models": "genai", "api_user": user, "api_secret": secret},
            files={"media": ("photo", data, content_type)},
        )
        if r.status_code != 200:
            return "unknown"
        return verdict_for(((r.json().get("type") or {}).get("ai_generated")))
    except Exception:
        return "unknown"
    finally:
        if own:
            client.close()
