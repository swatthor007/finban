"""FCM push. `PushSender.send` returns the tokens FCM reports as permanently invalid (to prune)."""
from __future__ import annotations

from typing import Optional, Protocol

CHANNEL_ID = "scam_alerts"  # display name "Scam alerts"; the Android app creates the channel (S5)


class PushSender(Protocol):
    def send(self, tokens: list[str], title: str, body: str, data: dict) -> tuple[int, list[str]]:
        """Returns (sent_ok, invalid_tokens)."""


class FakeSender:
    def __init__(self, invalid: Optional[set] = None):
        self.invalid = invalid or set()
        self.calls: list[dict] = []

    def send(self, tokens, title, body, data):
        self.calls.append({"tokens": list(tokens), "title": title, "body": body, "data": dict(data)})
        bad = [t for t in tokens if t in self.invalid]
        return len(tokens) - len(bad), bad


class FcmSender:
    def send(self, tokens, title, body, data):
        from firebase_admin import messaging
        from . import auth
        auth.init_app()
        if not tokens:
            return 0, []
        msgs = [messaging.Message(
            token=t,
            notification=messaging.Notification(title=title, body=body),
            data=data,
            android=messaging.AndroidConfig(
                priority="high",
                notification=messaging.AndroidNotification(channel_id=CHANNEL_ID)),
        ) for t in tokens]
        resp = messaging.send_each(msgs)
        ok, bad = 0, []
        permanent = (messaging.UnregisteredError, messaging.SenderIdMismatchError)
        for t, r in zip(tokens, resp.responses):
            if r.success:
                ok += 1
            elif isinstance(r.exception, permanent) or type(r.exception).__name__ == "InvalidArgumentError":
                bad.append(t)
        return ok, bad


_sender: Optional[PushSender] = None


def get_sender() -> PushSender:
    """FastAPI dependency. Tests override it."""
    global _sender
    if _sender is None:
        _sender = FcmSender()
    return _sender
