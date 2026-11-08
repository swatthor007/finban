# Prototype plan (v2, < 20 h total) — supersedes S4–S8 scope once agreed

Proof of concept, not a product. Showcase five things: **AI detection, SMS detection, bubble, domain checks, caretaker alerts + push.** Everything else is cut or stubbed and listed honestly in the final report.

Status of what exists: server `/analyze` (rules + domain pipeline), pairing, `/alert` + FCM, Firestore rules + tests (S1–S3, done, emulator-tested only). The experiment harness already has working a11y capture, bubble + sheet overlay, SMS receiver + notification listener, TTS, call monitor: **Android work is mostly copy + wire**, not greenfield.

## Status (2026-11-04, end of day 1)
| Phase | State |
|---|---|
| A | Code done; emulator verified (anon auth, role, `users/{uid}` + FCM token). **Exit not met:** no push seen on a phone yet (`scripts/push_smoke.py` is ready, service-account key now exists). Firebase project `finban-61e40` fully set up (Firestore asia-south1, rules, anonymous auth) via MCP. |
| B | **Done, verified on the emulator against the live server**: share -> `/analyze` -> sheet (high/careful/none/failed), shared-text quote card, 3.5 s timeout path. |
| E | **Done, beyond plan**: domain reasons in the sheet, domain age via RDAP, direct-download flags, on-demand "Look at this link more closely" (`POST /link/check`, server proxy, page content checks). |
| F | Server + app badge built; **not seen live** (no OpenRouter key set, so every reply is `rules`). |
| UI shell | **Built, seen on emulator**: onboarding (6 screens, glass shield hero), elder Home, Settings/Privacy/Trusted, "I already paid", warning illustration on high-risk sheet; plain back-stack nav (`ui/nav/Nav.kt`, no Navigation Compose). Caretaker home + alert detail written, **not yet run**. |
| D (bubble) | **Built, seen on the emulator over Chrome**: tap -> Siri-style grain wave from the bubble -> screen text read (`a11y/ScreenReader.kt`, own app/keyboard/system bar/password fields excluded) -> `/analyze` -> result sheet in the overlay (`overlay/ScreenCheck.kt`, `ScanOverlay.kt`). Not run on a real phone, not tested on WhatsApp/SMS apps yet. |
| J (image) | Built: photo picker + on-device OCR + `/image` (SightEngine) verified on the emulator with a real AI image. |
| C, G (wiring), H, T, I | Not started. Call + payment questionnaire (S7) is out of the prototype plan: see chat decision to bring it back. |
Not verified anywhere: real phones (Android 12 and 13), FCM delivery, a11y bubble, Tamil.

## Two tracks in parallel
- **Android (you):** one conversation per phase below.
- **Server (Claude, separate conversations):** LLM adapter, redactor, domain-age check, demo mode, seed script. Android never waits on it: contract is already `docs/API.md`.

## Phases (hours are guesses; budget ~20 h with F/J, so cut early if A-D slip; J is the first thing to drop)
| # | Phase | Owner | h | Demoable result |
|---|---|---|---|---|
| A | Firebase project + creds; Android project, harness code copied, anon auth, FCM token saved to `users/{uid}`, `scam_alerts` channel | you (+ me for console steps) | 2.5 | Test push from `/alert` lands on a real phone (**S3 exit**) |
| B | Share-to-check + `/analyze` client + bottom sheet (severity, 2-3 reasons, Tamil/English title) | you | 3 | Share a scam SMS text, correct sheet |
| C | **SMS detection**: `SMS_RECEIVED` + notification listener -> `/analyze` -> heads-up notification or sheet | you | 2 | Incoming scam SMS flagged automatically |
| D | **Bubble**: a11y capture of the foreground chat/SMS app, tap bubble -> `/analyze` -> sheet | you | 3.5 | Bubble over WhatsApp shows verdict |
| E | **Domain checks** surfaced: links from text/a11y sent in `links`, reasons shown ("address looks like SBI but isn't") | you (UI) / server already done | 1 | Lookalike link flagged in sheet |
| F | **AI explanation** via OpenRouter (OpenAI-compatible API over `httpx`; `OPENROUTER_API_KEY`, `LLM_MODEL`, a cheap fast model). **Severity stays deterministic (rules + domain checks); the LLM only writes the user-facing explanation** (`explanation_en`/`explanation_ta`, reason wording) from the structured findings (pattern id, reason ids, flags, severity), **not the raw message**. Timeout 3 s -> template text; output validated; can never change severity. Sheet shows an "AI-assisted" badge when `source_of_verdict` is `rules+llm` | me (server), you (badge) | 2 | Plain-language explanation tailored to the case |
| G | **Caretaker mode**: role pick, enter code, home list, alert detail, FCM tap -> `seen`, "I'm calling now" -> `calling`; elder sheet listens and updates | you | 4 | Two-phone live reply |
| H | Elder buttons: "Tell my trusted person" (writes alert + `/alert`), "I already paid" -> 1930 dial button | you | 1.5 | Push on the caretaker phone from the elder |
| J | **Image check** (`POST /image`, SightEngine `genai` model, in-memory only, capped at `careful`, copy says "probably made by a computer" / "we couldn't tell"); share an image -> sheet | me (server), you (share + sheet) | 1.5 | AI-generated image flagged |
| T | **Tamil**: a friend translates, **built after the app is done** so the strings are final. I generate one translation sheet (CSV/JSON) of every `TODO(hackathon): Tamil` string (server reason/title templates, 12 pattern names/titles/explanations, caretaker summaries) plus Android `strings.xml`, and an import script that fills them back in. Excel sheet columns: `key`, `where it appears`, `English`, `Tamil reference` (my draft, unreviewed, to be overwritten), `Proper Tamil` (friend fills), `Thanglish` (romanised, for the friend to check pronunciation/meaning). Tamil copy shows in the sheet and push | friend + me (script) | 1.5 | Sheet and push in Tamil |
| I | `DEMO.md`, README, `seed_demo.py`, DEMO_MODE canned scenarios, rehearsal, final "done/stubbed/untested" | both | 2.5 | Full run-through |

Critical path is A -> B -> D -> G. C, E, F, H can slot in around them.

## Cut order if time runs out (cut from the top)
1. H's "I already paid" detail, TTS audio (Tamil text itself is not cut)
2. E extras (domain age via RDAP: now built in the server, 2026-11-04; cut it first if lookups prove flaky)
3. C auto-notification (keep share + bubble)
4. F (template text still demos). J goes before F.
Never cut: A, B, G, and one of {C, D}.

## Explicitly out for the prototype
Call + payment-app 3-question trigger, Chakshu flows, report packs, QR pairing / CAMERA, offline SMS fallback, Sarvam TTS, Safe Browsing, debug panel, manifest check script, full design-system theme (one small theme file only), local rules engine on the phone (on timeout show a generic "Be careful, couldn't check" sheet), S2 false-alarm tuning, data verification batch.

## Risks
- **Bubble / a11y on a real phone** is the least tested (emulator only). Budget 3.5 h, fallback is share-to-check.
- **OEM battery savers** delay FCM: both phones, battery optimisation off.
- **Phone reaches server**: Tailscale or same Wi-Fi; set URL in a build config, no debug panel.
- **LLM input is findings only** (pattern id, reason ids, flags, severity), not the message text, so nothing personal leaves the server. Still goes to OpenRouter and its upstream provider; disclose in the final report. Cheap models write weak Tamil: Tamil explanation comes from the translated templates (phase T) and the LLM Tamil is optional/second-best.
- **SightEngine**: uploaded images leave the server; in-memory only, disclose. The repo has no record of the earlier SightEngine test, so treat the integration as untested until one real call.
- **Data in lists is unverified** (bank/brand domains): demo with senders and domains we've verified by hand.

## Open questions for you
Answered: LLM = OpenRouter; two real phones available; Tamil is required (a friend translates, phase T; TTS audio stays cut).
Phones: Android 12 and 13. Android 13 needs the sideload "Allow restricted settings" step before the accessibility service can be enabled (12 doesn't), and 13 asks for the notification permission at runtime (12 doesn't): test both paths in phase A/D.
Still open: which OpenRouter model (cheap + fast + decent Tamil-free English classification is enough, since the LLM sees English-or-Tamil text but replies in our JSON schema); the .xlsx writer (needs `openpyxl`, a download: I'll ask first; fallback is a CSV that opens in Excel). Translations must land before the rehearsal in phase I.
