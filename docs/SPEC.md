# SPEC (verbatim original prompt, source of truth)

> Saved 2026-11-04. If any other doc disagrees with this file, this file wins, except where `docs/FINDINGS.md` explicitly overrides it (Step 0 rule).

# Scaffold the hackathon project: anti-scam assistant (Android + server + Firebase)

## Context
We're building an Android assistant that helps people, mainly elderly users in Tamil Nadu, avoid scams. The person invokes it (floating bubble, share-to-check, or opening the app) to check what's on screen: an SMS, WhatsApp chat, link, image or call. It explains the risk in Tamil (English below), can pause them with three questions when an unknown call is followed by opening a payment app, alerts a caretaker they linked, and guides them to report via Chakshu (attempt, no loss) or the 1930 helpline (money already sent).

There are two demonstrable ends, running the same APK in different modes:
- **Elder phone:** gets checked, warned and paused.
- **Caretaker phone:** gets a push alert, opens the alert, calls the elder, and taps "I'm calling now", which updates the elder's screen live.

Core rules for every line of code:
- **The app warns, the person decides.** Never block, reject, click, type, send or file anything in another app or on the user's behalf. Every action is a button the person taps.
- **Never say "safe" or "real."** Best result: "No warning signs found."
- **Bottom sheets, not pop-ups,** for anything shown over other apps.
- **No secrets in the APK.** API keys and the Firebase service account live only on our server. (`google-services.json` is a public client config, but keep it out of git anyway.)
- **Caretakers see risk signals only:** who, when, which pattern, which trigger. Never message text, sender numbers, screenshots or amounts.

The app name isn't chosen yet: use `AppName` in user-facing strings and `com.yourteam.guard` as the package (both easy to rename later).

Hackathon rule check: the team must confirm whether pre-written code is allowed. Build this as a clean scaffold (structure, contracts, wiring, stubs, tests) that is quick to fill in during the event.

## Step 0: read the experiment results first (mandatory)
`../experiment/` holds a test harness we ran on the real demo phone. Before writing any code:
1. Read its `README.md`, `TESTING.md` (with our filled-in results), any summary or notes files, exported logs (`*.json`), and the source of the parts that worked: accessibility service, overlay/bottom sheet, call detection, SMS capture, foreground-app detection, `scripts/`.
2. Write `docs/FINDINGS.md` summarising, per capability: worked / partly / failed, on which device and Android version, and what we'll do about it. Include the exact package names, SMS sender formats and audio-mode behaviour we actually observed.
3. Let the findings override this prompt. Specifically:
   - Bottom sheet: if Compose inside the accessibility overlay was stable, use it; otherwise use Android Views for overlays (Compose stays for in-app screens).
   - Call screening role: if it broke the call-state listener or didn't work on the device, drop it and use contacts + call state + audio mode instead.
   - SMS: keep whichever capture method (`SMS_RECEIVED` vs notification listener) gave reliable sender + text; keep the other only if it adds something.
   - Watchlist package names: use the observed ones.
   - Restricted-settings flow and adb scripts: reuse what worked; note what didn't.
4. Copy (don't import) proven code from the harness into the new project and clean it up. Don't modify `../experiment/`.
5. Stop and show me `docs/FINDINGS.md` plus the list of decisions it changed before continuing.

## Repo layout
```
/
├── android/            Kotlin app (elder + caretaker modes)
├── server/             Python FastAPI (analysis, pairing, push)
├── firebase/           firestore.rules, firestore.indexes.json, firebase.json, rules tests
├── data/
│   ├── patterns/       scam pattern DB (versioned JSON)
│   ├── allowlists/     SMS headers, official domains, payment-app packages
│   ├── testset/        labelled messages (JSONL)
│   └── audio/          generated Tamil clips per pattern
├── docs/               FINDINGS.md, API.md, DEMO.md, PRIVACY.md, FIREBASE.md
├── scripts/            build/install, adb setup, emulators, seed, audio, eval
└── README.md
```
One git repo, `main` protected. `.gitignore` covers `.env`, `google-services.json`, service-account JSON, build output and local caches. Commit `.example` versions with setup instructions.

## Shared contract (write first, in `docs/API.md`)
Both sides code against this so the Android and server people never wait on each other. All endpoints except `/health` require a Firebase ID token (`Authorization: Bearer <token>`), verified on the server with `firebase-admin`.

`POST /analyze`
```json
// request
{
  "source": "screen" | "share" | "sms" | "call_context",
  "app_package": "com.whatsapp",
  "sender": "+919840012345",
  "text": "…",                       // already capped at ~4 KB
  "links": ["https://…"],
  "image_id": null,                  // from POST /image
  "context": { "in_call": true, "caller_unknown": true, "call_type": "voip" },
  "locale": "ta-IN"
}
// response
{
  "severity": "high" | "careful" | "none",
  "pattern_id": "digital-arrest",
  "title_ta": "…", "title_en": "…",
  "reasons": [{ "id": "sender.mobile-bank", "level": "high", "text_ta": "…", "text_en": "…" }],
  "explanation_ta": "…",
  "audio_url": "/audio/digital-arrest.mp3",
  "actions": ["alert_caretaker", "report_chakshu", "call_1930", "listen"],
  "source_of_verdict": "rules" | "rules+llm" | "cache",
  "latency_ms": 840
}
```
Also:
- `POST /image` (multipart upload → `image_id`)
- `GET /link?url=` (link-only check)
- `POST /pair/code` (elder: create a 6-digit code) → `{code, expires_at}`
- `POST /pair/claim` (caretaker: `{code}`) → `{link_id, elder_name}`
- `DELETE /pair/{link_id}` (either side unlinks)
- `POST /alert` (`{alert_id}`; server verifies and sends the push)
- `GET /health`, `GET /patterns/version`

Kotlin data classes and Pydantic models are kept identical to this file. Add a contract test on each side.

## Android app (`android/`)
- Kotlin, Gradle KTS + version catalog, single `app` module with packages: `a11y`, `overlay`, `calls`, `sms`, `trigger`, `analyze` (rules + API client), `firebase` (auth, Firestore, messaging), `caretaker`, `ui` (Compose screens), `data` (Room), `tts`, `alerts`, `report`, `settings`.
- minSdk 29, latest stable target/compile SDK.
- **Accessibility service:** observe only; honest `android:description`; no `isAccessibilityTool`; foreground-app tracking and on-demand screen text capture (text, content descriptions, view IDs) with extraction of links, phone numbers, UPI IDs and amounts. Runs only in elder mode.
- **Floating bubble** via `TYPE_ACCESSIBILITY_OVERLAY`: draggable, edge-snapping.
- **Bottom sheet overlay** component: scrim, drag handle, severity strip, Tamil title + English subtitle, 2–3 reason rows, Listen button, full-width primary and secondary actions, text dismiss; half height by default, draggable to full. It must be able to update its content live (for the caretaker reply).
- **On-device rules engine** (mirrors the server rules, so the app still works offline):
  - SMS sender parse `^([A-Z])([A-Z])-([A-Z0-9]{6})(?:-([PSTG]))?$` plus whatever formats FINDINGS shows; bank text from a 10-digit/+91 number = high; near-miss header (edit distance ≤ 2 to the allowlist) = high; account warning with `-P` suffix = careful.
  - Links: punycode decode, mixed scripts, confusable skeleton vs official domains, brand-in-domain, shorteners, risky TLDs, bare IPs, `.apk`.
  - Pattern cues from `data/patterns/*.json` (bundled as assets).
  - If the server doesn't answer within 3.5 s, show the local verdict and its template explanation. Never show a spinner past that.
- **Call + payment trigger:** in call (cellular off-hook or `MODE_IN_COMMUNICATION`) or ended within 10 min, AND caller not in contacts, AND a watchlisted app comes to the foreground → pause sheet with three Yes/No questions, one per step. Cooldown 3 min. Both values in settings.
- **Share target** for text, links and images; result shown full screen in-app.
- **Room** for the event log, report packs and consent records; report packs auto-delete after filing or 30 days.
- **"I already paid" flow:** full-height sheet, huge "Call 1930" button (dial intent, person taps call), amount/UTR/time shown large with copy buttons, checklist; also alerts the caretaker with trigger `already_paid`.
- **Chakshu guide:** consent review (toggles per field) → step-by-step with copy buttons → done.
- **Tamil voice:** play bundled clips from `data/audio`; fall back to Android `TextToSpeech` `ta-IN`; server-generated audio only for LLM explanations.
- **Screens, elder mode:** onboarding (language, role choice, pairing, permissions incl. a visual "Allow restricted settings" guide), home (Check my screen / Check a message or photo / I already paid; linked caretakers with call buttons), result, settings, privacy page.
- **Screens, caretaker mode:** onboarding (language, role choice, enter/scan code, notification permission), home (linked elders, recent alerts with status chips), alert detail (name, time, pattern in Tamil + English, one line on what it usually means, big "Call [name]" dial button, "I'm calling now", "Mark resolved"), settings.
- **Debug panel** (7 taps on version, both modes): live event log, server URL, Firebase connection, FCM token present, last alert sent/received, force demo mode.
- **Design:** a Compose theme with placeholder tokens (colours incl. three severity colours with icons, type scale with Noto Sans Tamil, min body 18sp, 56dp tap targets, light + dark), kept in one file for the design team's final tokens. 3D illustration placeholders as named image slots (`ill_onboarding_phone`, `ill_call_handset`, `ill_caretaker_hands`, etc.) with a hatched placeholder drawable.
- **Permissions:** same as the harness unless FINDINGS says otherwise, plus `POST_NOTIFICATIONS` and `CAMERA` (QR scan, caretaker only). Don't request `READ_SMS`, `READ_CALL_LOG` or `SYSTEM_ALERT_WINDOW`.
- **Demo mode** toggle: uses cached server responses for the scripted demo inputs.

## Firebase (caretaker link)
**Setup**
- One Firebase project on the free Spark plan. No Cloud Functions; our FastAPI server does pairing and sends pushes with `firebase-admin`.
- Android: Firebase BoM with Auth (anonymous sign-in), Firestore and Cloud Messaging. Sign in anonymously on first launch; the uid is the user's identity.
- Server: `FIREBASE_CREDENTIALS` in `.env` points to the service-account JSON, never committed.
- `docs/FIREBASE.md`: step-by-step project setup, adding both phones' app, downloading configs, enabling anonymous auth, deploying rules, running emulators.

**Roles**
- First launch asks: "I want protection" (elder) or "I'm looking after someone" (caretaker). Stored in `users/{uid}.role` and locally; changeable in settings.
- A caretaker can follow several elders; an elder can have up to 3 caretakers.

**Pairing**
- Elder taps "Add a trusted person" → `POST /pair/code` → shows a 6-digit code (valid 10 minutes, single use) as large digits and a QR.
- Caretaker enters or scans it → `POST /pair/claim` → server validates, rate-limits attempts per uid, creates the link, marks the code used.
- Both sides show who they're linked to (display name each person entered), with an unlink button.

**Firestore data model**
```
users/{uid}            role, display_name, fcm_tokens[], lang, created_at
links/{linkId}         elder_uid, caretaker_uid, created_at
pair_codes/{code}      elder_uid, expires_at, used               (server-only)
alerts/{alertId}       elder_uid, caretaker_uids[], pattern_id, severity,
                       trigger ("pause_yes" | "already_paid" | "manual"),
                       created_at, status ("new" | "seen" | "calling" | "resolved"),
                       ack_by, ack_at
```

**Security rules (`firebase/firestore.rules`, with emulator tests)**
- Users read/write only their own `users` doc; `fcm_tokens` writable by the owner only.
- `alerts`: the elder creates their own (with `caretaker_uids` that must match existing links; the server re-checks before pushing); only listed caretakers can read the alert and update `status`, `ack_by`, `ack_at`, and nothing else; the elder can read their own; nobody else reads anything.
- `links` and `pair_codes`: server-only writes; a link is readable by its two members.
- Tests in `firebase/tests/` using the Firestore emulator cover every allow and deny case above.

**Flow**
1. Elder answers "Yes" in the pause sheet, taps "I already paid", or taps "Tell my trusted person" → app writes `alerts/{id}` and calls `POST /alert {alert_id}`.
2. Server verifies the caller owns the alert and the links exist, then sends a high-priority FCM push to each caretaker's tokens on notification channel "Scam alerts". Text: "[Name] may be on a scam call. Call them now." plus the pattern name in Tamil and English. Drop stale tokens the API reports as invalid.
3. Caretaker taps the notification → alert detail; opening it sets `status` to `seen`.
4. "I'm calling now" sets `status` to `calling` → the elder's app, listening to that alert doc, updates the open sheet: "[Caretaker] has seen this and is calling you." If the sheet was closed, show a small banner over the current app.
5. Elder or caretaker can mark it resolved.

**Offline fallback**
If Firebase or the server is unreachable within 5 s, show the elder a "Send SMS to [name]" button that opens their SMS app with a pre-filled message. The person taps send. Never send SMS silently.

## Server (`server/`)
- Python 3.11+, FastAPI, Uvicorn, Pydantic, `httpx` (async), `firebase-admin`, `tldextract` (Public Suffix List), a confusables library for UTS #39 skeletons, `pytest`. `uv` or plain `pip` + `requirements.txt`, your choice.
- Config via `.env` (`.env.example` committed): `LLM_PROVIDER`, `LLM_API_KEY`, `LLM_MODEL`, `SARVAM_API_KEY`, `SIGHTENGINE_USER`, `SIGHTENGINE_SECRET`, `SAFE_BROWSING_KEY`, `FIREBASE_CREDENTIALS`, `DEMO_MODE`, `CACHE_DIR`.
- **Auth middleware:** verify Firebase ID tokens on every endpoint except `/health`; reject with a clear 401.
- **Pipeline for `/analyze`:**
  1. Rules engine (same rules as Android, Python port, shared fixtures).
  2. Links: expand shorteners server-side (redirects, 3 s timeout, never on the phone); registrable domain via Public Suffix List; RDAP age via `https://rdap.org/domain/<reg>` with fallback to the TLD's server from IANA's bootstrap file; Safe Browsing lookup. Cache link results 24 h. Missing registration date = "unknown", never "old".
  3. If a pattern matched with high confidence, use its hand-written Tamil template and pre-generated audio. Otherwise, or for "careful"/"none" with suspicious signals, call the LLM.
  4. **Redact before the LLM:** replace phone numbers, account numbers, UPI IDs, OTP-like digit runs and names with placeholders. Nothing is stored.
  5. LLM returns strict JSON (severity, pattern_id, reasons, explanation_ta, confidence); validate with Pydantic; on failure or timeout (3 s) fall back to the rules verdict.
  6. Severity = the higher of rules and LLM; the LLM can never downgrade a rules "high".
- **LLM adapter** with one interface and two implementations (Anthropic and Gemini) behind `LLM_PROVIDER`. Prompt in `server/prompts/classify.md`, versioned.
- **Images:** `/image` keeps the upload in memory only; check C2PA locally if a library is easy to add, then Sightengine `models=genai`. Image results cap at "careful"; copy says "probably made by a computer" or "we couldn't tell", never "real".
- **TTS:** Sarvam Bulbul for LLM-written explanations; cache by text hash.
- **Safe Browsing wording:** hedged ("suspected"), credited to Google.
- **Pairing and alerts:** `/pair/*` and `/alert` as above, using `firebase-admin` for Firestore and FCM.
- **Demo cache:** `DEMO_MODE=1` answers scripted inputs from `server/demo_cache/*.json`; a script records them from live calls.
- Logging: request id, uid hash, latency, stages run; never message text.

## Data (`data/`)
- `patterns/*.json`, one file per pattern:
  `id, name_ta, name_en, severity, text_cues {ta, en, tanglish}, screen_cues, checks, title_ta, explanation_ta, explanation_en, caretaker_summary_ta, caretaker_summary_en, what_to_do, source, source_date, last_reviewed`.
  Start with: digital-arrest, fake-kyc, refund-trick (3 variants: fake credit / tainted money / PIN "refund"), otp-request, investment-group, task-job, reward-fee, fake-scheme-ad, customer-care-screen-share, sim-swap-signs. Leave `source` as a TODO where we haven't confirmed one; never invent a citation.
- `allowlists/sms_headers.json` (seed with headers seen in FINDINGS + a TODO to merge TRAI's list), `official_domains.json`, `payment_apps.json` (observed package names).
- `testset/messages.jsonl`: `{sender, text, expect, pattern_id, lang, source}`. Seed with ~10 obviously synthetic examples marked `"source":"synthetic"`; we add the real collected samples.
- `audio/`: empty, filled by `scripts/gen_audio.py` (Sarvam, one clip per pattern explanation, skips existing files).

## Tests and scripts
- Server `pytest`: each rule, the redactor, the RDAP parser (fixtures with and without registration events), LLM fallback on timeout and invalid JSON, auth middleware, pairing (expiry, reuse, rate limit), `/alert` ownership checks, contract tests.
- Firestore rules tests against the emulator.
- `scripts/eval.py`: runs the test set through `/analyze` and prints scams caught, false alarms on genuine messages, exact matches and per-pattern misses. This is the number for the pitch slide.
- Android: unit tests for the rules engine using the same fixtures as the server; one instrumented smoke test for the overlay if quick.
- `scripts/`: build + install to a named device (`ELDER_SERIAL`, `CARETAKER_SERIAL`), grant permissions and enable services over adb (from the harness, only commands that worked), start the server, start Firebase emulators, `seed_demo.py` (creates a demo elder, a demo caretaker and their link so pairing can be shown live or skipped), record the demo cache, generate audio, run eval.

## Networking
Both phones reach the server over Tailscale. Server URL is set in the debug panel (default from `BuildConfig`). Document the Tailscale setup and a same-Wi-Fi fallback in README. Firebase traffic goes over the normal internet, so both phones need data.

## Docs
- `docs/DEMO.md`: the scripted demo with two phones:
  1. Fake bank SMS with a lookalike link → bubble → high-risk sheet.
  2. WhatsApp "digital arrest" chat → bubble → high-risk sheet.
  3. Unknown WhatsApp call → elder opens a payment app → pause sheet → "Yes" → caretaker phone buzzes → caretaker taps "I'm calling now" → elder's sheet updates live.
  4. "I already paid" → 1930 screen; caretaker alerted with trigger `already_paid`.
  Include exact inputs, expected outputs, the offline fallback, and setup notes: both phones on mobile data, battery optimisation off for the app on both (OEM battery savers can delay FCM), caretaker notification channel set to high importance.
- `docs/PRIVACY.md`: what stays on the phone, what goes to our server, what (redacted) goes to the LLM, what caretakers can and can't see, retention, and the honest one-liner for the pitch.

## How to work
1. Step 0 (findings), then stop for my review.
2. Write `docs/API.md`, the Firestore data model, rules, and shared fixtures.
3. Server skeleton: auth, rules engine, `/analyze` returning rules-only verdicts, tests passing.
4. Firebase: project config, emulators, rules + tests, pairing and `/alert` with a push reaching a real phone.
5. Android skeleton: role choice, pairing, services, bubble, bottom sheet, rules engine, calling `/analyze`, end-to-end on the elder phone.
6. Caretaker mode: home, notification, alert detail, "I'm calling now" updating the elder's sheet.
7. Then the LLM, links/RDAP, TTS, images, trigger flow, report flows, demo mode.

Build and run after each step. Keep each step small and committed. Where something is stubbed, leave a clear `TODO(hackathon):` comment. At the end, list what's done, stubbed and untested.
