# S3 Firebase pairing + alerts
**Goal:** Elder gets a code, caretaker claims it, an alert pushes to a real phone.
**Needs from user:** Firebase project, service-account JSON, `google-services.json` (one app, one APK on both phones).
**Milestones:** [x] `docs/FIREBASE.md` [x] `/pair/code`, `/pair/claim` (expiry, single-use, rate limit, max 3 caretakers) [x] `DELETE /pair/{id}` [x] `/alert` ownership check + FCM (channel "Scam alerts", prune stale tokens) [x] server tests (unit + emulator) [ ] real-phone push (needs Firebase project + Android test client; `android/` is empty)
**Exit:** push received on a real phone.
