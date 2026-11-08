import os
from typing import Optional
from fastapi import Header, HTTPException

_initialised = False


def init_app() -> None:
    """Initialise firebase_admin once. Credentials from FIREBASE_CREDENTIALS (service-account JSON path, never
    committed); otherwise application-default / emulator env (FIRESTORE_EMULATOR_HOST, GOOGLE_CLOUD_PROJECT)."""
    global _initialised
    if _initialised:
        return
    import firebase_admin
    from firebase_admin import credentials
    cred_path = os.environ.get("FIREBASE_CREDENTIALS")
    opts = {"projectId": os.environ["GOOGLE_CLOUD_PROJECT"]} if os.environ.get("GOOGLE_CLOUD_PROJECT") else None
    firebase_admin.initialize_app(credentials.Certificate(cred_path) if cred_path else None, opts)
    _initialised = True


def verify(authorization: Optional[str] = Header(default=None)) -> str:
    """FastAPI dependency: returns uid or raises 401. Tests override this."""
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "unauthenticated")
    try:
        from firebase_admin import auth
        init_app()
        return auth.verify_id_token(authorization[7:])["uid"]
    except Exception:
        raise HTTPException(401, "unauthenticated")
