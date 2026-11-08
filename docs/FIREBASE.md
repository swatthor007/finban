# Firebase setup

One Firebase project on the free Spark plan. No Cloud Functions: the FastAPI server does pairing and sends pushes with `firebase-admin`.

## 1. Create the project (needs you, browser)
1. console.firebase.google.com -> Add project (Analytics off is fine).
2. Build -> Authentication -> Sign-in method -> enable **Anonymous**.
3. Build -> Firestore Database -> create (production mode, a region near India, e.g. `asia-south1`).
4. Project settings -> Your apps -> add **one Android app** with package `devr.shellshock.finban`, nickname "Finban". It is a single APK with an elder/caretaker role choice, installed on both phones, so there is one registration and one `google-services.json` (put it in `android/app/`; gitignored, never commit).
5. Cloud Messaging needs no extra step (FCM v1 is on by default). Project settings -> Service accounts -> **Generate new private key**. Save the JSON outside the repo (or as `*service-account*.json`, which is gitignored).

## 2. Server config
Copy `.env.example` to `.env` and set:
- `FIREBASE_CREDENTIALS=/absolute/path/to/service-account.json`
- optionally `LOG_SALT=<random>` (uid hash salt in logs).

Run: `cd server && set -a && . ./.env && set +a && .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000`.

## 3. Deploy rules
`cd firebase && tests/node_modules/.bin/firebase deploy --only firestore:rules --project <project-id>` (needs `firebase login`).

## 4. Emulators and tests (no credentials, no network)
- Rules: `cd firebase && tests/node_modules/.bin/firebase emulators:exec --config firebase.json --only firestore --project demo-guard "cd tests && node --test rules.test.mjs"`
- Server store against the emulator: `cd firebase && tests/node_modules/.bin/firebase emulators:exec --config firebase.json --only firestore --project demo-guard "cd ../server && FIRESTORE_EMULATOR_HOST=127.0.0.1:8080 GOOGLE_CLOUD_PROJECT=demo-guard .venv/bin/python -m pytest -m emulator -q"`
- Unit tests (in-memory store, fake FCM): `cd server && .venv/bin/python -m pytest -q`

## 5. How pairing and alerts use Firebase
- Android signs in anonymously; the uid is the identity. Each app writes its own `users/{uid}` doc (`role`, `display_name`, `fcm_tokens`).
- Link doc ids are `{elder_uid}_{caretaker_uid}`; only the server writes `links` and `pair_codes`.
- Elder creates `alerts/{id}` (rules check each caretaker is linked), then calls `POST /alert`. The server re-checks, pushes to the caretakers' `fcm_tokens` on channel id `scam_alerts` (display name "Scam alerts"; the Android app creates that channel), prunes invalid tokens, and sets `pushed_at` so a replay does not push again.
- Push `data` holds only `type` and `alert_id`. Text shown is "[Name] may be on a scam call. Call them now." plus the pattern name. No message text, numbers or amounts.

## 6. Real-phone push checklist
- Both phones on mobile data; battery optimisation off for the app (OEM battery savers delay FCM); the "Scam alerts" channel set to high importance.
- Caretaker app registered an FCM token into `users/{uid}.fcm_tokens` and the elder is linked to it.
- Status: TODO(hackathon): needs the Android client (S4/S5, or a small test app) and the real project above.
