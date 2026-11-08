"""POST /pair/code, POST /pair/claim, DELETE /pair/{link_id}. See docs/API.md."""
from __future__ import annotations

import logging
import re
import secrets
import threading
import time
from collections import defaultdict, deque
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, Response

from .auth import verify
from .models import PairClaimRequest, PairClaimResponse, PairCode
from .store import Store, StoreError, get_store, now

log = logging.getLogger("guard")
router = APIRouter()

CODE_TTL = timedelta(minutes=10)


class Limiter:
    """Sliding window per key, in memory (per process).
    TODO(hackathon): anonymous uids are cheap to mint, so also throttle invalid claims globally."""

    def __init__(self, limit: int, window_s: int):
        self.limit, self.window = limit, window_s
        self.hits: dict[str, deque] = defaultdict(deque)
        self.lock = threading.Lock()

    def allow(self, key: str) -> bool:
        t = time.monotonic()
        with self.lock:
            q = self.hits[key]
            while q and t - q[0] > self.window:
                q.popleft()
            if len(q) >= self.limit:
                return False
            q.append(t)
            return True

    def reset(self) -> None:
        self.hits.clear()


claim_limiter = Limiter(5, 600)  # every attempt counts
code_limiter = Limiter(5, 600)


def _err(status: int, code: str) -> HTTPException:
    return HTTPException(status, code)


@router.post("/pair/code", response_model=PairCode)
def pair_code(uid: str = Depends(verify), store: Store = Depends(get_store)):
    if not code_limiter.allow(uid):
        raise _err(429, "rate_limited")
    expires = now() + CODE_TTL
    for _ in range(20):
        code = f"{secrets.randbelow(1_000_000):06d}"
        if store.create_code(code, uid, expires):
            return PairCode(code=code, expires_at=expires.isoformat())
    raise _err(503, "try_again")


@router.post("/pair/claim", response_model=PairClaimResponse)
def pair_claim(req: PairClaimRequest, uid: str = Depends(verify), store: Store = Depends(get_store)):
    if not claim_limiter.allow(uid):
        raise _err(429, "rate_limited")
    code = req.code.strip()
    if not re.fullmatch(r"\d{6}", code):
        raise _err(404, "code_invalid")
    try:
        lid, elder = store.claim_code(code, uid, now())
    except StoreError as e:
        raise _err(e.status, e.code)
    name = (store.get_user(elder) or {}).get("display_name") or ""
    log.info("pair claim ok")  # link ids contain uids: not logged
    return PairClaimResponse(link_id=lid, elder_name=name)


@router.delete("/pair/{link_id}", status_code=204)
def pair_delete(link_id: str, uid: str = Depends(verify), store: Store = Depends(get_store)):
    link = store.get_link(link_id)
    # Missing link and non-member both 403 so link existence isn't revealed.
    if not link or uid not in (link["elder_uid"], link["caretaker_uid"]):
        raise _err(403, "forbidden")
    store.delete_link(link_id)
    return Response(status_code=204)
