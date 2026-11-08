"""FirestoreStore against the Firestore emulator. Skipped unless FIRESTORE_EMULATOR_HOST is set:
cd firebase && tests/node_modules/.bin/firebase emulators:exec --config firebase.json --only firestore \
  --project demo-guard "cd ../server && FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 GOOGLE_CLOUD_PROJECT=demo-guard \
  .venv/bin/python -m pytest -m emulator -q"
"""
import os
import uuid
from datetime import timedelta

import pytest

from app.store import StoreError, now

pytestmark = [pytest.mark.emulator,
              pytest.mark.skipif(not os.environ.get("FIRESTORE_EMULATOR_HOST"), reason="needs Firestore emulator")]


@pytest.fixture(scope="module")
def store():
    from app.firestore_store import FirestoreStore
    return FirestoreStore()


@pytest.fixture
def ids():
    s = uuid.uuid4().hex[:8]
    return f"e{s}", [f"c{i}{s}" for i in range(4)]


def code_for(store, elder, expires=None):
    code = f"{uuid.uuid4().int % 1_000_000:06d}"
    assert store.create_code(code, elder, expires or now() + timedelta(minutes=10))
    return code


def test_claim_flow_and_single_use(store, ids):
    e, cs = ids
    code = code_for(store, e)
    assert not store.create_code(code, "other", now() + timedelta(minutes=10))  # live code is not overwritten
    assert store.claim_code(code, cs[0], now()) == (f"{e}_{cs[0]}", e)
    assert store.get_link(f"{e}_{cs[0]}")["caretaker_uid"] == cs[0]
    with pytest.raises(StoreError) as x:
        store.claim_code(code, cs[1], now())
    assert x.value.code == "code_used"


def test_invalid_expired_self(store, ids):
    e, cs = ids
    with pytest.raises(StoreError) as x:
        store.claim_code("000000", cs[0], now())
    assert x.value.code == "code_invalid"
    old = code_for(store, e, now() - timedelta(seconds=1))
    with pytest.raises(StoreError) as x:
        store.claim_code(old, cs[0], now())
    assert x.value.code == "code_expired"
    with pytest.raises(StoreError) as x:
        store.claim_code(code_for(store, e), e, now())
    assert x.value.code == "code_invalid"


def test_elder_full_does_not_burn_code(store, ids):
    e, cs = ids
    for c in cs[:3]:
        store.claim_code(code_for(store, e), c, now())
    code = code_for(store, e)
    with pytest.raises(StoreError) as x:
        store.claim_code(code, cs[3], now())
    assert x.value.code == "elder_full"
    assert not store.db.collection("pair_codes").document(code).get().to_dict()["used"]


def test_unlink_strips_alerts_and_linked_caretakers(store, ids):
    e, cs = ids
    for c in cs[:2]:
        store.claim_code(code_for(store, e), c, now())
    assert store.linked_caretakers(e, [cs[0], cs[1], cs[2], cs[0]]) == [cs[0], cs[1]]
    aid = uuid.uuid4().hex
    store.db.collection("alerts").document(aid).set({"elder_uid": e, "caretaker_uids": [cs[0], cs[1]]})
    store.delete_link(f"{e}_{cs[0]}")
    assert store.get_link(f"{e}_{cs[0]}") is None
    assert store.get_alert(aid)["caretaker_uids"] == [cs[1]]


def test_tokens_and_pushed(store, ids):
    e, cs = ids
    store.db.collection("users").document(cs[0]).set({"fcm_tokens": ["a", "b"]})
    store.remove_tokens(cs[0], ["b"])
    assert store.get_user(cs[0])["fcm_tokens"] == ["a"]
    aid = uuid.uuid4().hex
    store.db.collection("alerts").document(aid).set({"elder_uid": e, "caretaker_uids": []})
    store.mark_pushed(aid, now())
    assert store.get_alert(aid)["pushed_at"]
