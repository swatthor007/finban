"""Persistence for pairing + alerts. `Store` is the interface; `MemoryStore` is for tests/demo,
`FirestoreStore` (firestore_store.py) is the real one. Collections follow docs/API.md.

The claim logic that must be atomic lives inside each store (`claim_code`); the pure checks are shared
in `check_code`."""
from __future__ import annotations

import threading
from datetime import datetime, timezone
from typing import Optional, Protocol

MAX_CARETAKERS = 3


class StoreError(Exception):
    """Carries an API error code (docs/API.md) and its HTTP status."""

    def __init__(self, code: str, status: int):
        super().__init__(code)
        self.code, self.status = code, status


def link_id(elder_uid: str, caretaker_uid: str) -> str:
    return f"{elder_uid}_{caretaker_uid}"


def now() -> datetime:
    return datetime.now(timezone.utc)


def check_code(doc: Optional[dict], caretaker_uid: str, at: datetime) -> dict:
    """Shared claim checks on a pair_codes doc. Returns the doc or raises StoreError."""
    if doc is None or doc.get("elder_uid") == caretaker_uid:  # claiming your own code reads as invalid
        raise StoreError("code_invalid", 404)
    if doc.get("used"):
        raise StoreError("code_used", 409)
    if doc["expires_at"] <= at:
        raise StoreError("code_expired", 410)
    return doc


class Store(Protocol):
    def get_user(self, uid: str) -> Optional[dict]: ...
    def create_code(self, code: str, elder_uid: str, expires_at: datetime) -> bool:
        """Create pair_codes/{code}. False if the code is taken by a live (unexpired, unused) code."""
    def claim_code(self, code: str, caretaker_uid: str, at: datetime) -> tuple[str, str]:
        """Atomically validate + consume the code and create the link. Returns (link_id, elder_uid).
        Raises StoreError code_invalid/code_used/code_expired/elder_full."""
    def get_link(self, lid: str) -> Optional[dict]: ...
    def delete_link(self, lid: str) -> None:
        """Delete the link and strip the caretaker from that elder's alerts."""
    def get_alert(self, alert_id: str) -> Optional[dict]: ...
    def linked_caretakers(self, elder_uid: str, caretaker_uids: list[str]) -> list[str]:
        """Subset of caretaker_uids that have a link doc with the elder (order kept, no duplicates)."""
    def mark_pushed(self, alert_id: str, at: datetime) -> None: ...
    def remove_tokens(self, uid: str, tokens: list[str]) -> None: ...


class MemoryStore:
    def __init__(self):
        self.users: dict[str, dict] = {}
        self.codes: dict[str, dict] = {}
        self.links: dict[str, dict] = {}
        self.alerts: dict[str, dict] = {}
        self._lock = threading.Lock()

    def get_user(self, uid):
        return self.users.get(uid)

    def create_code(self, code, elder_uid, expires_at):
        with self._lock:
            old = self.codes.get(code)
            if old and not old["used"] and old["expires_at"] > now():
                return False
            self.codes[code] = {"elder_uid": elder_uid, "expires_at": expires_at, "used": False}
            return True

    def claim_code(self, code, caretaker_uid, at):
        with self._lock:
            doc = check_code(self.codes.get(code), caretaker_uid, at)
            elder = doc["elder_uid"]
            lid = link_id(elder, caretaker_uid)
            if lid not in self.links:
                if sum(1 for l in self.links.values() if l["elder_uid"] == elder) >= MAX_CARETAKERS:
                    raise StoreError("elder_full", 409)
                self.links[lid] = {"elder_uid": elder, "caretaker_uid": caretaker_uid, "created_at": at}
            doc["used"] = True
            return lid, elder

    def get_link(self, lid):
        return self.links.get(lid)

    def delete_link(self, lid):
        with self._lock:
            l = self.links.pop(lid, None)
            if l:
                for a in self.alerts.values():
                    if a["elder_uid"] == l["elder_uid"] and l["caretaker_uid"] in a["caretaker_uids"]:
                        a["caretaker_uids"] = [u for u in a["caretaker_uids"] if u != l["caretaker_uid"]]

    def get_alert(self, alert_id):
        return self.alerts.get(alert_id)

    def linked_caretakers(self, elder_uid, caretaker_uids):
        out = []
        for u in caretaker_uids:
            if u not in out and link_id(elder_uid, u) in self.links:
                out.append(u)
        return out

    def mark_pushed(self, alert_id, at):
        self.alerts[alert_id]["pushed_at"] = at

    def remove_tokens(self, uid, tokens):
        u = self.users.get(uid)
        if u:
            u["fcm_tokens"] = [t for t in u.get("fcm_tokens", []) if t not in tokens]


_store: Optional[Store] = None


def get_store() -> Store:
    """FastAPI dependency. Tests override it. TODO(hackathon): FIREBASE_MEMORY_STORE=1 for a no-Firebase demo."""
    global _store
    if _store is None:
        from .firestore_store import FirestoreStore
        _store = FirestoreStore()
    return _store
