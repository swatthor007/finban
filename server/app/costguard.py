"""Cost guard for the paid calls (vision model, AI explanation). In memory, per process.

Anonymous Firebase uids are free to mint, so a per-uid limit alone does not protect the OpenRouter bill. Two extra guards:
a global daily cap per kind, and a per-IP hourly cap (ngrok puts the caller's address in X-Forwarded-For).
Past a cap the caller gets the free fallback (vision -> on-phone OCR, AI -> template text), never an error.
Env (read on every call, so tests and the operator can change them): VISION_DAILY_CAP (300), AI_DAILY_CAP (1500),
VISION_IP_PER_HOUR (20), AI_IP_PER_HOUR (60). 0 means nothing is allowed; to loosen a cap, set it high.
"""
import logging
import os
import threading
import time

from fastapi import Request

from .pairing import Limiter

log = logging.getLogger("guard")
DEFAULTS = {"VISION_DAILY_CAP": 300, "AI_DAILY_CAP": 1500, "VISION_IP_PER_HOUR": 20, "AI_IP_PER_HOUR": 60}


def _env(name: str) -> int:
    try:
        return int(os.environ.get(name, DEFAULTS[name]))
    except ValueError:
        return DEFAULTS[name]


def client_ip(request: Request) -> str:
    """The address ngrok saw. The LAST X-Forwarded-For entry is the one our proxy appended (earlier ones can be forged by the caller)."""
    xff = request.headers.get("x-forwarded-for", "")
    parts = [p.strip() for p in xff.split(",") if p.strip()]
    if parts:
        return parts[-1]
    return request.client.host if request.client else "unknown"


class _Kind:
    def __init__(self, name: str, daily_env: str, ip_env: str):
        self.name, self.daily_env, self.ip_env = name, daily_env, ip_env
        self.day, self.count = "", 0
        self.lock = threading.Lock()
        self.ip = Limiter(1, 3600)  # limit is replaced on every call from env

    def allow(self, ip: str) -> bool:
        daily, per_ip = _env(self.daily_env), _env(self.ip_env)
        today = time.strftime("%Y-%m-%d")
        with self.lock:
            if today != self.day:
                self.day, self.count = today, 0
            if self.count >= daily:
                log.info("costguard %s refused: daily cap", self.name)
                return False
            self.ip.limit = per_ip
            if not self.ip.allow(ip):
                log.info("costguard %s refused: per-ip cap", self.name)
                return False
            self.count += 1
            return True

    def reset(self) -> None:
        with self.lock:
            self.day, self.count = "", 0
            self.ip.reset()


vision = _Kind("vision", "VISION_DAILY_CAP", "VISION_IP_PER_HOUR")
ai = _Kind("ai", "AI_DAILY_CAP", "AI_IP_PER_HOUR")


def reset_all() -> None:
    vision.reset()
    ai.reset()
