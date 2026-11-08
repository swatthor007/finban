# Default context (keep short; update as things change)

## Hard rules (from SPEC)
- App warns, person decides: never block/click/type/send/file in other apps. Every action is a user-tapped button.
- Never say "safe"/"real". Best: "No warning signs found."
- Bottom sheets, not pop-ups. Tamil title + English subtitle.
- No secrets in APK. `google-services.json`, `.env`, service-account JSON are gitignored.
- Caretakers see only who/when/pattern/trigger. Never text, numbers, screenshots, amounts.
- Don't request READ_SMS, READ_CALL_LOG, SYSTEM_ALERT_WINDOW. Don't set `isAccessibilityTool`.
- Names: app is **Finban** (user-facing strings, replaces `AppName`); package `devr.shellshock.finban` (replaces `com.yourteam.guard`). SPEC.md still has the old placeholders; this file wins. Harness code in `experiment/` is `com.yourteam.guard.harness`: rename the package when copying it in phase A.

## Tech
Android: Kotlin, Compose, Room, minSdk 29. Server: FastAPI, Pydantic, firebase-admin. Firebase Spark, no Cloud Functions. Versions: reuse `experiment/gradle/libs.versions.toml`.

## Environment
- macOS; no `timeout` command; system Python is 3.9 (spec wants 3.11; server code runs on both; venv at `server/.venv`).
- Firestore rules tests: `cd firebase && tests/node_modules/.bin/firebase emulators:exec --config firebase.json --only firestore --project demo-guard "cd tests && node --test rules.test.mjs"` (works, 8 pass).
- Server tests: `cd server && .venv/bin/python -m pytest -q` (190 pass, 4 strict xfail).
- Eval: `server/.venv/bin/python -I scripts/eval.py` (in-process, no network; `--shuffle N`, `--json`, `--min-caught`, `--max-false-alarms`).

## State (2026-11-04)
Done: FINDINGS (emulator-only, no phone data); Sprint 1 realigned API.md, Pydantic models (`server/app/models.py`), pattern schema, testset schema, Firestore rules to `docs/SPEC.md`. Shared example payloads in `data/contract/`; contract tests in `server/tests/test_contract.py`.
Never done: any real-phone testing. Watchlist packages are harness defaults, unverified. Kotlin data classes exist only as a spec section in API.md (S4 creates them).
Patterns: Tamil text and `source` are `TODO(hackathon)`; only English cues populated (`text_cues.en`). Server `/analyze` is rules-only with placeholder Tamil (S2 adds real English reason templates).

## Contract decisions (S1)
- Link doc ids are deterministic `{elder_uid}_{caretaker_uid}`, so rules can `exists()`-check them on alert create. S3 pairing must create links this way.
- Open: SPEC says elder or caretaker can mark an alert resolved, but rules section lets only caretakers update. Rules follow the latter; revisit in S5.
- `/analyze` response has no `links` field; link detail comes from `GET /link`.

## SMS data (added after S1)
- `data/sms/` = raw TRAI files (xlsx header list from 2020-06; the PDF named `official_domains.pdf` is really TRAI's header-structure note, it has no domains). `scripts/build_sms_data.py` rebuilds `data/allowlists/sms_headers.json` (stdlib only).
- `sms_prefixes.json` (operator/circle/suffix codes), `bank_domains.json` (starter; domains NOT verified, headers checked against TRAI list).
- `rules.classify_sender(sender)` returns `{format, sms_type, operator, circle, registered, entity, entity_category}`; `sms_type` = transactional|promotional|service|government|unspecified|person_number|shortcode|unknown_format|unknown.
- Near-miss header: only vs the ~22 core headers in `bank_domains.json` (distance <= 2 against all 1.4k bank headers flagged ~17% of unrelated headers). Currently flags ~1.2% of registered non-bank headers; some are real finance headers (e.g. AUBANK). Tune in S2.
- A registered header is not proof of identity (INCTAX is registered to an accountancy firm). Entity category is a name-keyword heuristic.
- `common_domains.json` = Tranco top-20k + `.in` top-100k (built by `scripts/build_common_domains.py`, downloaded 2026-11-04). Hosting/user-content domains removed (PSL private + manual `EXTRA_HOSTING`). `rules.is_common()`. Popular != trustworthy. Many real banks rank below 20k (canarabank.com 260k, iob.in 268k), so it can't replace `bank_domains.json`; unranked starter entries (`sbi.bank.in`, `tmb.in`, `kvb.co.in`) need manual verification.
- `shorteners.json`, `risky_tlds.json` moved out of `rules.py` (sources still TODO; TLD signal is weak, careful only; no `.ly`).

## S2 state (2026-11-04)
- **Pipeline** = `server/app/domains.py` (steps 0-4 + local combine; email via `check_email`), `confusables.py` (skeleton, edits1), `templates.py` (reason text, titles, actions, audio_url). `rules.analyze` calls them; `rules.link_flags(url)` kept as a thin wrapper. Network step 5 (shortener expansion, RDAP, Safe Browsing) is S6, not built.
- **Order independence**: each check returns a flag set and never reads another's result; `FLAG_ORDER` makes output canonical; tests shuffle `HOST_CHECKS/PATH_CHECKS/EMAIL_CHECKS` and the testset. New checks must follow this (no cross-reading).
- **Official match** is exact registrable domain (PSL via tldextract bundled snapshot, no network) or suffix `gov.in`/`nic.in` (`gov_domains.json`). `bank.in` is added as an extra suffix in `domains.EXTRA_SUFFIXES` because the bundled PSL treats `.in` as the suffix (`evil.bank.in` would otherwise share a registrable domain with `sbi.bank.in`).
- **Severity**: high = bare_ip, user_info, apk, mixed_script, confusable, lookalike_official, brand_in_domain, brand_mismatch_free_mail; careful = punycode/non_ascii alone (real Tamil IDN domains exist), lookalike (1-edit of a popular domain, same suffix), shortener, risky_tld, many_hyphens, brand_mismatch. `is_common()` only sheds risky_tld/many_hyphens when nothing else fired; it never cancels a brand claim, shortener or mismatch (gmail.com is popular).
- **Brand aliases**: <=4 chars (sbi, hdfc, axis) match only after peeling scam words/digits off a token (`sbikyc`, `onlinehdfcbank`, not `bobby`/`taxis`); longer aliases match anywhere.
- **Skeleton**: `confusable_homoglyphs` has no skeleton function; `confusables.skeleton` derives non-ASCII -> ASCII from its table then folds i/l/1/| -> l, 0 -> o, rn -> m, vv -> w. Lookalike needs name >= 6 chars (shorter collide with real domains). Probe: ~0.5% of popular domains get any flag, mostly brand words on unlisted domains (paytm.in, axisbank.co.in, tnebnet.org) which may be genuine until the lists are verified.
- **Near-miss header** now: unregistered in TRAI list AND one edit (adjacent swap counts) from a core header. 0 hits on 21.7k registered non-bank headers (old rule 1.2%); catches all 3,224 one-letter variants. Caveat: headers registered after the 2020 list look unregistered.
- **Email**: `sender` may be `Name <a@b>` or `a@b` (API.md updated); addresses in text are checked too (URLs stripped first). Copy says "this address doesn't match", never "fake".
- **audio_url** is returned only if `server/audio/{pattern_id}.mp3` exists (override `AUDIO_DIR`); `/audio` is mounted only if that dir exists. S6 generates the files.
- **Logs**: `analyze req=.. uid=<sha256(LOG_SALT+uid)[:12]> source severity pattern verdict stages ms`. Set `LOG_SALT` in production. Test asserts no text/sender/link/raw uid.
- **Eval (testset 33 msgs, synthetic)**: 13/13 scams caught, 4/20 false alarms, 29/33 exact. False alarms = single pattern cue -> `careful` (`pattern-weak`), kept in `data/testset/hard_negatives.jsonl` with a strict xfail in `test_rules.py`; decide in a later sprint whether one cue should warn at all. Testset is tiny and synthetic: the eval numbers are a harness check, not a pitch number yet.

### Needs your verification (batch)
- `bank_domains.json` (all `verified:false`): every `domains` entry; especially `tmb.in`, `kvb.co.in`, `sbi.bank.in`, `hdfc.bank.in`, `axis.bank.in`, `unionbankofindia.co.in`, `indianbank.in`, `bankofbaroda.in`.
- `brands.json` non-bank entries (all `verified:false`, `source:null`): gpay, uidai, incometax, epfo, indiapost, tneb, irctc, jio, airtel, bsnl domains. Until verified, a genuine site of these brands under an unlisted domain gets `brand_in_domain` (high).
- `gov_domains.json`: confirm the registration policy for `gov.in` / `nic.in` and cite it. `domains.EXTRA_SUFFIXES = ("bank.in",)`: confirm against the live PSL / NIXI rules.
- Source/date still TODO: `shorteners.json`, `risky_tlds.json`, `free_mail.json`.
- Tamil text is written (see "Tamil" below) but no native speaker has read it yet. `text_cues.ta` are still empty.

## S3 state (2026-11-04)
- **Layers**: `store.py` (`Store` protocol, `MemoryStore`, `get_store` dep), `firestore_store.py` (real; uses an anonymous-credential client when `FIRESTORE_EMULATOR_HOST` is set, else firebase_admin), `push.py` (`FcmSender`, `FakeSender`, `get_sender` dep), `pairing.py`, `alerts.py`. Tests override `verify`, `get_store`, `get_sender`. Atomic claim logic lives in each store; shared checks in `store.check_code`.
- **Errors** are now `{"error": code}` via an HTTPException handler (matches API.md; previously FastAPI's `detail`).
- **Limits**: claim/code limiters are in-memory per uid per process (5 / 10 min). Anonymous uids are cheap to mint, so a global invalid-claim throttle is still TODO. A failed claim does not burn the code.
- **`pushed_at`** is a server-written alert field; replay returns sent 0. Only set when >=1 push succeeded, so a retry after a token-less attempt works.
- **Unlink** strips the caretaker from that elder's alerts (single-field query + Python filter; equality + array-contains would need a composite index).
- **Push** never includes `name_ta` while it is a `TODO` placeholder. Channel id `scam_alerts`; the Android app must create it (S5). Data = `type`, `alert_id` only.
- **Not verified on real Firebase**: Firestore transactions, `send_each` and token pruning only ran against the emulator / fakes. FCM error mapping (`UnregisteredError`, `SenderIdMismatchError`, `InvalidArgumentError` by name) is untested against real FCM.
- Emulator store tests: see `docs/FIREBASE.md` section 4 (`pytest -m emulator`; skipped in the normal run: 217 pass, 5 skipped, 4 xfail).

## Gotchas (from FINDINGS)
Compose overlay: key slide-in on a boolean, not height. Track own package as foreground. `uiautomator dump` unbinds the a11y service. VoIP `MODE_IN_COMMUNICATION` has false positives. Call-screening role is the only source of caller number.

## Android state (phase A+B, 2026-11-04)
- **Layout**: `android/` is the Gradle project (`devr.shellshock.finban`). Server URL: `finban.serverUrl=http://<ip>:8000` in `android/local.properties` (gitignored, also holds `sdk.dir`); default `http://10.0.2.2:8000` (emulator). Debug and release both allow cleartext (TODO(hackathon): remove).
- **Logging**: harness `EventLog` (Room, stored SMS bodies) is replaced by `log/EventLog.kt`, logcat only. Never pass message text, senders, numbers or links to it.
- **Theme**: ONE file `ui/theme/FinbanTheme.kt` (tokens from the design artifact). Deviations: body/subtitle/buttons 18 sp (artifact 15-16), system font not Figtree, bubble must be 56x56 dp (artifact 46x56; phase D). Design artifact has no role choice, caretaker side, timeout sheet, AI badge/explanation; those are plain screens from the same tokens.
- **Server placeholders**: gone (see "Tamil" below). `realText()` in `net/Models.kt` still hides any string starting with `TODO`.
- **Result sheet** (`ui/sheet/ResultSheet.kt`) is a plain composable; phase B hosts it in a `ModalBottomSheet` (`ShareReceiverActivity`), phase D should host it in `BottomSheetController`.
- **Firebase gotcha**: `firebase_init` (MCP) only writes `firebase.json`; sign-in kept failing with `CONFIGURATION_NOT_FOUND` until `firebase_deploy only=auth` ran. Firestore reads through the MCP failed once with a socket error (retry).
- **Emulator tip**: `adb shell input keyevent 4` (BACK) with no keyboard open exits the app. For share tests use a single quoted `adb shell "am start ... --es android.intent.extra.TEXT 'a_b_c'"`.
- **Unverified**: anything on real phones (both Android 12 and 13), FCM delivery (the emulator FCM token path was only checked as far as "token saved"), live `/analyze` sheets, notification channel importance on the phones, restricted-settings flow (phase D).

## Domain age (added 2026-11-04, after phase B)
- `server/app/domain_age.py`: RDAP via IANA bootstrap, parallel, 1.5 s total budget, 24 h cache (failures 5 min), max 3 domains per message. Flags `very_new_domain` (<= 14 days, high) and `new_domain` (<= 90 days, careful), added in `rules.analyze` as a separate step (local checks in `domains.py` stay pure). Skipped for official, popular (`is_common`) and bare-IP hosts.
- Unknown age (timeout, unsupported TLD, no registration event) = no flag. Thresholds are guesses: TODO(hackathon) tune on real examples.
- Tests never touch the network (`conftest` sets `DOMAIN_AGE=0`; `scripts/eval.py` too). Server tests now: 262 pass, 5 skipped, 4 xfail.
- Not yet run against real registries. Email sender domains are not age-checked yet.
- This reverses the earlier "RDAP stays out of the prototype" line in PROTOTYPE-PLAN (user asked for it after the bare `.xyz` link test).

## Page fetch (added 2026-11-04)
- **Changed same day: page fetch is ON-DEMAND ONLY.** `/analyze` never fetches pages; the app shows a "Look at this link more closely" button on careful/none results with links and calls `POST /link/check` (`server/app/link_check.py`; kill switch `LINK_FETCH=0`; 10/min/uid; URL only in the body, never logged). The paragraphs below still describe the fetcher itself.
- `server/app/page_fetch.py`. SSRF guard: resolve DNS ourselves, every answer must be globally routable, ports 80/443, no user-info, IP-pinned connection (Host + SNI), manual redirects (<=3, each re-validated), 200 KB / 2 s caps, HTML only. Stdlib HTML parser, no rendering.
- Only fetches links that already look suspicious (local flags or message already warns), never official/popular/bare-IP hosts; shorteners are the exception (popular but where they lead matters). If a link redirects elsewhere, the destination also goes through the local host checks.
- Flags: `page_brand_mismatch` (title/og:site_name/h1 claims a protected brand the final domain doesn't own), `page_download`, `asks_secret_details` (careful; high with a fake brand / new domain / redirect / already-high address), `form_posts_elsewhere`.
- Only adds warnings. Cloaking means a clean page proves nothing; never used to lower severity or say "safe".
- Privacy: fetching tells the link's owner the link was opened and shows them this server's IP. Page text is not logged or stored (only a small summary stays in memory for the request). TODO(hackathon): decide whether the demo should run with LINK_FETCH=1.
- Tests are offline (fake DNS + MockTransport). Not yet run against a real page. Server tests: 332 pass, 5 skipped, 4 xfail.

## Photo check (added 2026-11-04)
- Home "Check a message or photo" -> system photo picker (no permission) -> `ShareReceiverActivity` (also an `image/*` share target). Text is read on the phone with ML Kit (Latin only; **Tamil text in photos is not read**, TODO) and sent to `/analyze`. In parallel the photo (re-encoded <= 1600 px JPEG, EXIF dropped) goes to `POST /image` (raw body) for the AI-generated note. Server: `server/app/image_check.py`; tests offline (MockTransport). SightEngine keys are not set, so the verdict is always `unknown` until `SIGHTENGINE_USER/SECRET` are exported. Not run against real SightEngine. The running dev server must be restarted to get `/image`.
- UI shell (2026-11-04): `ui/nav/Nav.kt` is a plain back-stack (no Navigation Compose). Recent checks (`data/RecentChecks.kt`) keep severity, title, time only.

## Bubble check (added 2026-11-04)
- Tap flow: `BubbleController` -> `CaptureBus.request` -> `FinbanAccessibilityService.capture` -> `ScreenCheck.start`. The scan window (`ScanOverlayController`) is `TYPE_ACCESSIBILITY_OVERLAY`, not touchable/focusable, so the app underneath is untouched; it plays at least one 1.8 s expansion. Wave origin = `BubbleController.centerPx()`. 3,200 grain circles per frame: untested on low-end phones, drop the count if it janks.
- `ScreenReader` is pure and unit-tested, except the top-to-bottom sort: `android.graphics.Rect` is a stub in JVM tests, so ordering is only verified on the emulator. Text goes to `/analyze` once, never logged or stored. Finban cannot read its own screen: Home's "Check my screen" only shows a hint to open another app first.
- Bubble toggle in Settings shows/hides the bubble live (`setBubble`). Bubble y can drift after taps in adb tests; harmless.

## Launcher icon (added 2026-11-05)
- Real logo in `logos/Finban main.png|svg` (1000x1000, shield + rupee mark on the brand pink #FF385C): wired as the launcher icon. Legacy PNGs generated with `sips` into `res/mipmap-<density>/ic_launcher[_round].png`; adaptive icon (`res/mipmap-anydpi-v26/ic_launcher.xml`) uses the same square as the foreground over a solid-pink background, since there is no separate transparent-background shield asset — any launcher mask crops into more of the same pink, not a seam. `AndroidManifest.xml` now sets `android:icon`/`android:roundIcon`.
- In-app top-bar mark (`AppIcons.Mark`) is unrelated and left as is: it reproduces the design artifact's own `i-mark` symbol, a different surface from the launcher icon.

## Launcher icon fix + type scale down (2026-11-05)
- Bug found: the adaptive icon's `<foreground>` pointed at `@mipmap/ic_launcher` -- the SAME resource name as the
  `mipmap-anydpi-v26/ic_launcher.xml` selector file itself. On API 26+ that inner reference re-resolves back to the
  same XML (self-reference), fails to inflate, and Android silently falls back to the generic placeholder icon --
  looked like "the icon never changed" even though the APK (`aapt2 dump badging`) showed the right path. Fixed by
  giving the foreground bitmap its own name, `ic_launcher_foreground.png`, per density. Icon caching on top of this:
  a plain `adb install -r` sometimes won't refresh a launcher's cached icon after a *first* install with no custom
  icon -- verify via a full `adb uninstall` + install, and check **Settings > App info**, not the home screen (the
  home screen in this AVD has no Finban tile pinned; our own floating bubble, same pink, is easy to mistake for one).
- Type scale brought down a step (still >= 18sp body per AGENTS.md): title 22, heading 20, body/subtitle/button/severity/code 18,
  caption 15, field label 16. The previous pass (body 20) read as too large on screen.

## Logo PNG used directly (2026-11-05)
- Dropped the SVG safe-zone/transparent-foreground work: `ui/components/AppLogo.kt` now just renders
  `res/drawable-nodpi/finban_logo.png` (copy of `logos/Finban main.png`) clipped to a rounded square, used wherever the
  small brand mark shows in-app (Welcome and Home top bars). Replaces the vector ring+dot `AppIcons.Mark` there;
  `AppIcons.Mark` itself is unused now but left in `AppIcons.kt` in case something still wants the design's literal symbol.

## SMS detection (phase C, 2026-11-05)
- `sms/SmsCheck.kt`: both `SmsReceiver` (SMS_RECEIVED, `goAsync`) and `FinbanNotificationListener` call it. De-duplicates (sender tail + body hash, 2 min), skips **saved contacts** (READ_CONTACTS added; no permission = treated as unsaved; a name-shaped notification title = saved contact). Everything else goes to `/analyze` (`source=sms`) as-is: **no bank-header allowlist, no OTP redaction (prototype decision)**.
- careful/high -> heads-up notification on `scam_alerts`; tap opens `ShareReceiverActivity` with the text (re-checks, so the link button works). Text rides in the PendingIntent extra until tapped (TODO(hackathon): keep the verdict in memory). `none` -> no notification.
- Verified on the emulator: unsaved-number scam SMS -> high notification -> sheet. Not verified: READ_CONTACTS runtime request (only granted via adb; onboarding doesn't ask), Android 13 notification permission, real phones.
- Known gap: a spoofed bank header looks the same as a real one; the TRAI allowlist idea was dropped for now.

## SMS popup, Home log, trusted-person button (2026-11-05)
- **Incoming risky SMS = bottom sheet** over the front app via the a11y overlay (`ScreenCheck.showResult`), with the message quoted. Falls back to the `scam_alerts` notification if the service isn't connected (reinstalling unbinds it until re-enabled) or another sheet is open. Log tag `Finban/sms: popup: shown=.. service=..` says which.
- **Home**: status card, primary action, two tiles, compact recent rows (pattern + sender, source, time) -> `CheckDetailScreen` with the full log. `RecentChecks` now stores only careful/high, up to 20, with source, sender/app, pattern, up to 6 reasons and the explanation, on the phone only. Never the message body. Prototype decision; caretakers still never see any of it.
- **Caretaker alert = a tap, not automatic** (keeps the "every action is a user-tapped button" rule): "Tell my trusted person" on high-risk sheets and the detail page -> `CaretakerAlert.send` (links query -> `alerts/{id}` with trigger `manual` -> `POST /alert`). No rules change needed. Not tested end to end: no caretaker is paired on the emulator, so only the "no trusted person linked" path was reachable.

## Permanent address + server protection (2026-11-05)
- Default `SERVER_URL` = `https://galileo-tiliaceous-causally.ngrok-free.dev` (build.gradle.kts); `finban.serverUrl` in local.properties overrides (now removed). `ServerConfig` drops a saved address containing `ngrok-free.app`, `127.0.0.1` or `10.0.2.2` once (unless the build default is itself one of those). `scripts/serve-public.sh` with no argument = server + ngrok on that domain (`NGROK_DOMAIN` overrides); ngrok 3.39 accepts `--url`. The laptop must stay on and online.
- **Cost guard** `server/app/costguard.py`: global daily caps + per-IP hourly caps for vision and AI explanation (env `VISION_DAILY_CAP` 300, `AI_DAILY_CAP` 1500, `VISION_IP_PER_HOUR` 20, `AI_IP_PER_HOUR` 60). Past the cap: vision returns `vision_available=false`, AI uses template text. IP = LAST `X-Forwarded-For` entry (the one ngrok appended). In memory, resets on restart. Set a credit limit on the OpenRouter key too. App Check skipped (Play Integrity does not suit sideloaded APKs).
- **Patterns**: optional `require_any` list (entries are lowercase substrings or the token `"@money"` = amount / UPI id / "send money" phrase); a pattern whose requirement is not met does not count at all. `family-emergency` uses it. **One weak cue alone no longer warns**: it gives `careful` only when a link has a flag, or the text has a phone number or money ask. The 4 strict xfails are gone.
- **Spoofed sender**: bank header (`bank_domains.json` headers) + a link whose registrable domain is not in that bank's `domains` = high, reason `sender.bank-link-mismatch`. Reduces risk, does not prove identity.
- **Domains moved**: banks now redirect to `<name>.bank.in` (icici, kotak, bankofbaroda, indianbank, ...). These were missing and would have flagged genuine bank links; added (verified=false). Evidence table: `docs/DOMAIN-EVIDENCE.md` (made by `scripts/domain_evidence.py`; no A record for `kvb.co.in`, `paytmbank.com`; some apexes only work with `www.`). Brand list still needs the user's confirmation.
- Testset: 42 messages (17 scams, 25 genuine), all synthetic: 17/17 caught, 0/25 false alarms. This is a harness check, not a pitch number. Server tests: 403 pass, 5 skipped.

## Payment pause (2026-11-05)
- `calls/TriggerRule.kt` (registered by the accessibility service) + `ui/sheet/PauseSheet.kt`. Payment apps = `TriggerRule.UPI_APPS` + `BANK_APPS` (package names from Juspay/Razorpay lists, WhatsApp excluded; TMB and City Union have no confirmed package yet); debug builds also honour `Session.debugPaymentApps`. Fires when a call is on or ended within `Session.callWindowMin` (10) and the caller is not a saved contact (no screening info = unknown, e.g. WhatsApp), cooldown `pauseCooldownMin` (3). A "known" answer only counts while off hook or within the window. First Yes -> `CaretakerAlert.send("high", null, "pause_yes")`. Skip/Close always available; it never touches the payment app. Paid screen has "Tell my trusted person" (`already_paid`).
- Call-screening role: manifest service registered, role requested on the permissions step. On the emulator grant with `adb shell cmd role add-role-holder android.app.role.CALL_SCREENING devr.shellshock.finban 0`; debug payment app without UI: edit `shared_prefs/finban.xml` via `run-as`.
- Emulator gotcha: `adb emu gsm accept` crashed/restarted the AVD once and wiped nothing but took minutes to boot. `gsm call 5551234` then `gsm accept 5551234` otherwise works.

## SMS follow-up fixes (2026-11-05)
- Phone report: first scam text from a number flagged, later ones did not. Two causes found on the emulator: (1) `SmsCheck` used the on-screen 3.5 s analyze timeout and rethrew `TimeoutCancellationException` (a `CancellationException`), so a slow `/analyze` dropped the text with no log; now 15 s and logged as `analyze failed: timeout`. (2) A second text while the first SMS sheet was still open got `popup shown=false` (notification only); now it replaces the open SMS sheet (`BottomSheetController.sheetOwner`; the pause sheet is never replaced).
- Still true: the same sender + same body within 2 minutes is ignored (`SmsCheck.DEDUPE_MS`), so re-sending an identical test text will not flag twice. Vary the text.
- Open, not changed: on the notification path (no RECEIVE_SMS) a business sender shown by name (e.g. "SBI" for VM-SBIBNK) is treated as a saved contact and skipped. Fix idea: only skip if a contact has that exact display name. If SMS permission is granted (broadcast path) all senders are checked.

## Manual AI-photo check on the bubble (2026-11-05)
- The bubble check now photographs the screen whenever `Session.pictureCheck` is on and the app is not a payment app (in memory only). The full shot goes to the vision call only when text is weak or a big picture is on screen (as before). `ScreenReader.bigPictureBounds` finds the biggest ImageView / `android.widget.Image` node; `ScreenShot.take(svc, bounds)` returns the full shot plus a full-resolution crop of that picture (JPEG q92, longest side 1280). No bounds = the whole screen is used for the check.
- Result sheets from the bubble show a button "Check if this picture was made by a computer". Tapping it sends ONLY the crop to `POST /image` (SightEngine `genai`), and the sheet shows a verdict (`likely_ai` -> careful, `unlikely_ai`, `unknown`). It is never automatic, so there is no SightEngine cost per tap. Descriptions of pictures are no longer shown anywhere in the sheet (the server still returns `description`; the app ignores it).
- Gotcha: `Bitmap.createBitmap` returns the SOURCE bitmap for a full-size crop; recycling it broke the full-screen encode.
- Not tested on a real phone. Many apps draw pictures on custom views, which the accessibility tree does not report as images, so the crop falls back to the whole screen there.

## Tamil (2026-11-05)
- **App language = `Session.lang`** (default `ta`), Compose state, separate from the phone's language. `data/AppLanguage.kt`: `ProvideLanguage` sits inside `FinbanTheme`, so Activities and overlay sheets switch at once with no restart. Outside Compose use `AppLanguage.context(ctx)`. Not `Locale.setDefault`: Android's relative-time formatter ignored it, so use `timeAgo()`.
- **Sheets**: Tamil mode = Tamil title + English subtitle; reasons and paragraph in Tamil only. English mode = English only. Pick text with `AnalyzeResponse.title/.explanation`, `Reason.text`.
- **Wording**: same word list in `values-ta/strings.xml` and `server/app/templates.py`: மெசேஜ், லிங்க், வெப்சைட், நம்பிக்கையான நபர், வட்ட பட்டன் (bubble). Written by Claude; needs a Tamil speaker's read. Severity "none" must stay the same words in `strings.xml` and `TITLES` (the sheet hides a title equal to the badge).
- **AI**: Tamil from the model replaces template Tamil. Both languages must pass `llm.parse` (`BANNED`, `BANNED_TA`) or both stay template, so the AI badge stays true. The model echoes our findings, so a banned word in our own text loses the whole answer ("Real police...", "Guaranteed returns" did): `test_llm_input_has_no_banned_word`. Haiku takes ~1.8 s, so with a link the first check of new findings usually misses the deadline and shows template text; the repeat is AI from the cache.
- **Caretaker push** uses the caretaker's `users/{uid}.lang`, kept in step by `Session.lang`.
- Long Tamil words do not wrap well in narrow tiles or one-line rows: keep tile text short and check Home on screen after wording changes.
- Seen on the emulator (test server, real AI): Settings switch, Home, high/careful/none sheets, AI text + badge, SMS sheet over another app, pause sheet first question, English mode. Not seen: caretaker screens and push in Tamil, onboarding, real phones. Tamil text in photos is still not read (ML Kit Latin).
- **Gotcha**: `scripts/serve-public.sh stop` ignores `PORT` for tunnels: it kills every ngrok/cloudflared and resets Tailscale Funnel. Never use it to stop a second test server.

## Demo scam pages (2026-11-05)
- `server/app/demo_pages.py` serves `data/demo/` at `/demo` only when `DEMO_PAGES=1` (`serve-public.sh` sets it). Guide: `docs/DEMO-PAGES.md`. Expected results live in `pages.json` and `tests/test_demo_pages.py` enforces them, so change both together.
- The three bank pages name SBI (user decision) but must stay pictures of a form: no `<form>`, script, logo or typing, enforced by a test. Other orgs are fictional. The pages share the API's ngrok address, so a phishing report could take the API down: run with `DEMO_PAGES=0` outside rehearsals and the demo.
- New flag `temporary_host` (careful) for free tunnels (`allowlists/tunnel_hosts.json`). Side effect: anything on our own tunnel is at least careful, so the "no warning" control in a demo must be a real site.
- ngrok serves a warning page to browser-like clients. `page_fetch` sends `ngrok-skip-browser-warning` for ngrok hosts only; people still tap "Visit Site" once per browser.
- The app hides the closer-look button when the first result is high (`ResultSheet.kt`).
- Bubble scans of Chrome send the address with no scheme. `rules.extract_links` now finds bare addresses on the server (real public suffix required); the phone's `Extractors` has its own fixed ending list, which decides whether the closer-look button appears.
- `text.asks-secret`: CVV / UPI PIN / ATM PIN asked for = careful, high on a flagged address. Skipped for "never share" advice and known sites.

## Picture type guess (2026-11-05)
- `ui/share/PictureKind.kt` (on the phone, unit-tested) guesses payment / Aadhaar / PAN / Voter / other ID from words read off a picture. A note only: it never changes severity, and the sheet always says it may be wrong. Tamil strings are unreviewed drafts.
- A 12-digit run alone is not an Aadhaar (UPI references look the same), and a money ask alone is not a payment. When the guess is an ID, the text is not saved to Recent checks.
- Bubble: if the guess is set and a picture is on screen, the AI-photo check runs automatically (replaces "never automatic" above, so each such scan costs one SightEngine call). Payment apps are never photographed, so they never get it.

## Launcher icon v2 (2026-11-05)
- New `logos/Finban main.png` (2000x2000, opaque `#D23A55`, rupee bleeding off the left and bottom). Launcher only: `drawable-nodpi/finban_logo.png` (in-app mark) is still the old logo.
- Adaptive foregrounds are now true 108dp sizes (108-432 px; the old 48dp files were being stretched). The whole logo sits in the visible 72dp, with its left and bottom edges extended into the hidden margin so the rupee still bleeds off if a launcher shows more.
