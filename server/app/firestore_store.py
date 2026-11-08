"""Firestore-backed Store (firebase-admin). Works against the emulator when FIRESTORE_EMULATOR_HOST is set."""
from __future__ import annotations

import os
from datetime import datetime
from typing import Optional

from firebase_admin import firestore
from google.cloud.firestore_v1 import ArrayRemove, transactional

from . import auth
from .store import MAX_CARETAKERS, StoreError, check_code, link_id, now


class FirestoreStore:
    def __init__(self):
        if os.environ.get("FIRESTORE_EMULATOR_HOST"):
            # Emulator needs no credentials; firebase_admin would look for application-default ones.
            from google.auth.credentials import AnonymousCredentials
            from google.cloud import firestore as gcf
            self.db = gcf.Client(project=os.environ.get("GOOGLE_CLOUD_PROJECT", "demo-guard"),
                                 credentials=AnonymousCredentials())
        else:
            auth.init_app()
            self.db = firestore.client()

    def get_user(self, uid):
        d = self.db.collection("users").document(uid).get()
        return d.to_dict() if d.exists else None

    def create_code(self, code, elder_uid, expires_at):
        ref = self.db.collection("pair_codes").document(code)

        @transactional
        def run(tx):
            snap = ref.get(transaction=tx)
            if snap.exists:
                old = snap.to_dict()
                if not old.get("used") and old["expires_at"] > now():
                    return False
            tx.set(ref, {"elder_uid": elder_uid, "expires_at": expires_at, "used": False})
            return True
        return run(self.db.transaction())

    def claim_code(self, code, caretaker_uid, at):
        code_ref = self.db.collection("pair_codes").document(code)
        links = self.db.collection("links")

        @transactional
        def run(tx):
            snap = code_ref.get(transaction=tx)
            doc = check_code(snap.to_dict() if snap.exists else None, caretaker_uid, at)
            elder = doc["elder_uid"]
            lid = link_id(elder, caretaker_uid)
            link_ref = links.document(lid)
            if not link_ref.get(transaction=tx).exists:
                existing = list(links.where("elder_uid", "==", elder).get(transaction=tx))
                if len(existing) >= MAX_CARETAKERS:
                    raise StoreError("elder_full", 409)
                tx.set(link_ref, {"elder_uid": elder, "caretaker_uid": caretaker_uid, "created_at": at})
            tx.update(code_ref, {"used": True})
            return lid, elder
        return run(self.db.transaction())

    def get_link(self, lid):
        d = self.db.collection("links").document(lid).get()
        return d.to_dict() if d.exists else None

    def delete_link(self, lid):
        ref = self.db.collection("links").document(lid)
        snap = ref.get()
        if not snap.exists:
            return
        l = snap.to_dict()
        ref.delete()
        # Unlinked caretaker loses access to that elder's alert history.
        # Single-field query + Python filter: equality + array-contains would need a composite index.
        batch = self.db.batch()
        for a in self.db.collection("alerts").where("elder_uid", "==", l["elder_uid"]).stream():
            if l["caretaker_uid"] in a.to_dict().get("caretaker_uids", []):
                batch.update(a.reference, {"caretaker_uids": ArrayRemove([l["caretaker_uid"]])})
        batch.commit()

    def get_alert(self, alert_id):
        d = self.db.collection("alerts").document(alert_id).get()
        return d.to_dict() if d.exists else None

    def linked_caretakers(self, elder_uid, caretaker_uids):
        out = []
        for u in caretaker_uids:
            if u not in out and self.db.collection("links").document(link_id(elder_uid, u)).get().exists:
                out.append(u)
        return out

    def mark_pushed(self, alert_id, at: datetime):
        self.db.collection("alerts").document(alert_id).update({"pushed_at": at})

    def remove_tokens(self, uid, tokens):
        self.db.collection("users").document(uid).update({"fcm_tokens": ArrayRemove(list(tokens))})
