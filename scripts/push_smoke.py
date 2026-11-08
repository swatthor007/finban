"""Phase A exit check: send an /alert-shaped FCM push to a phone that registered its token.

Usage (needs a service-account JSON, outside git; talks to real Firebase):
  FIREBASE_CREDENTIALS=/path/to/sa.json server/.venv/bin/python -I scripts/push_smoke.py <uid>
Find <uid> in Firebase console -> Firestore -> users (one doc per phone, role + fcm_tokens).
Uses the server's own FcmSender, so channel id, priority and data keys are exactly what /alert sends.
The HTTP /alert path (elder token, links) is exercised in phase H.
"""
import sys

sys.path.insert(0, "server")
from app.firestore_store import FirestoreStore  # noqa: E402
from app.push import FcmSender  # noqa: E402


def main(uid: str) -> int:
    user = FirestoreStore().get_user(uid)
    if not user:
        print("no such user doc"); return 1
    tokens = user.get("fcm_tokens") or []
    print(f"role={user.get('role')} tokens={len(tokens)}")
    if not tokens:
        print("no fcm_tokens: open the app, choose a role, then retry"); return 1
    ok, bad = FcmSender().send(
        tokens,
        title="Scam alert (test)",
        body="TODO(hackathon): sample. Someone may be on a scam call. Call them now.",
        data={"type": "scam_alert", "alert_id": "smoke-test"},
    )
    print(f"sent={ok} invalid_tokens={len(bad)}")
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]) if len(sys.argv) == 2 else print(__doc__) or 1)
