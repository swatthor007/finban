"""POST /alert: verify ownership, then push to linked caretakers. See docs/API.md."""
from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException

from . import rules
from .auth import verify
from .models import AlertRequest, AlertResult
from .push import PushSender, get_sender
from .store import Store, get_store, now

log = logging.getLogger("guard")
router = APIRouter()


def push_text(elder_name: str, pattern_id, lang: str = "en"):
    """Title/body in the caretaker's language. Only who + pattern name: never message text, numbers or amounts."""
    pat = next((p for p in rules.patterns() if p["id"] == pattern_id), None) if pattern_id else None
    if lang == "ta":
        who = elder_name or "உங்கள் குடும்பத்தினர்"
        body = f"{who} மோசடி அழைப்பில் இருக்கலாம். உடனே போன் செய்யுங்கள்."
        return "மோசடி எச்சரிக்கை", f"{body} {pat['name_ta']}" if pat else body
    who = elder_name or "Your family member"
    body = f"{who} may be on a scam call. Call them now."
    return "Scam alert", f"{body} {pat['name_en']}" if pat else body


@router.post("/alert", response_model=AlertResult)
def alert(req: AlertRequest, uid: str = Depends(verify), store: Store = Depends(get_store),
          sender: PushSender = Depends(get_sender)):
    a = store.get_alert(req.alert_id)
    if a is None:
        raise HTTPException(404, "alert_not_found")
    if a.get("elder_uid") != uid:
        raise HTTPException(403, "forbidden")
    if a.get("pushed_at"):  # replay: don't spam caretakers
        return AlertResult(sent=0, pruned=0)
    elder_name = (store.get_user(uid) or {}).get("display_name") or ""
    sent = pruned = 0
    for cuid in store.linked_caretakers(uid, a.get("caretaker_uids", [])):
        user = store.get_user(cuid) or {}
        tokens = user.get("fcm_tokens", [])
        title, body = push_text(elder_name, a.get("pattern_id"), user.get("lang", "en"))
        ok, bad = sender.send(tokens, title, body, {"type": "scam_alert", "alert_id": req.alert_id})
        sent += ok
        if bad:
            store.remove_tokens(cuid, bad)
            pruned += len(bad)
    if sent > 0:
        store.mark_pushed(req.alert_id, now())
    log.info("alert sent=%d pruned=%d", sent, pruned)
    return AlertResult(sent=sent, pruned=pruned)
