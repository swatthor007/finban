# FINDINGS (emulator-only baseline)

Source: `experiment/FINDINGS.md`, `README.md`. Device: Android Studio emulator `Medium_Phone`, API 37, google_apis. **No physical phone was tested.** Everything marked UNVERIFIED must be re-checked on the demo phone. Decision made 2026-11-04: proceed without phone data.

## Per capability
| Capability | Status | Notes | Action |
|---|---|---|---|
| Accessibility service (observe-only, no `isAccessibilityTool`) | Worked (emu) | Foreground tracking OK; own package must be tracked as foreground (never fires rule) | Copy `a11y/*` |
| Compose sheet in `TYPE_ACCESSIBILITY_OVERLAY` | Worked (emu) | Needs hand-rolled `OverlayOwner`; key slide-in `LaunchedEffect` on boolean `measured`, not height | Keep Compose; copy `overlay/*` |
| Bubble (non-focusable overlay) | Worked (emu) | Sheet is a separate focusable overlay so BACK works | Copy |
| Touch/keyboard/focus over other apps | UNVERIFIED | | Test on phone |
| Cellular call state | Worked (emu) | Audio mode `RINGTONE -> IN_CALL` | Copy `calls/*` |
| VoIP signal `MODE_IN_COMMUNICATION` | Partly | Also set by voice notes, recorders, stuck mode: false positives | Use only together with watchlisted foreground app |
| Call screening role | Partly | Callback ~20 ms before RINGING; only source of number (no READ_CALL_LOG). Role did not change `TelephonyCallback` | KEEP (needed for number); degrade to "unknown caller" without it |
| SMS capture | Partly | `SMS_RECEIVED` + notification listener both built; listener also sees empty group-summary notifications | Keep both; filter summaries |
| Gesture-nav insets | Emu only | `navigationBars.bottom=0`, `systemGestures.bottom=84px` | Pad sheet; check 3-button nav |
| Restricted-settings flow | UNVERIFIED | `appops set ACCESS_RESTRICTED_SETTINGS allow` accepted, effect unknown | Reuse scripts, document manual path |
| Tamil TTS | Availability only | `ta-IN LANG_COUNTRY_AVAILABLE`; audio never heard | Bundled clips + Android TTS fallback |
| Advanced Protection (Android 16) | UNVERIFIED | May disable non-tool a11y services | Document in DEMO.md |
| Real UPI/WhatsApp package names, DLT senders, OEM SMS package, Android 15+ OTP redaction, contacts path | UNVERIFIED | | Harness defaults used as watchlist |

## Test-tooling gotchas
`uiautomator dump` unbinds the a11y service (use screenshots + `input tap`). `adb emu gsm accept` needs a number.

## Decisions (spec vs findings)
1. Sheet = **Compose** (proven, no Views fallback needed).
2. Call-screening role = **keep**, optional; no role means caller treated as unknown.
3. SMS = SMS_RECEIVED primary, notification listener secondary; no READ_SMS.
4. Watchlist = harness defaults: `com.google.android.apps.nbu.paisa.user`, `com.phonepe.app`, `net.one97.paytm`, `in.org.npci.upiapp` (payment); `com.whatsapp` (calls); `com.anydesk.anydeskandroid`, `com.teamviewer.quicksupport.market` (remote access). TODO(hackathon): confirm on phone.
5. Permissions: harness set + INTERNET, POST_NOTIFICATIONS, CAMERA. Never READ_SMS / READ_CALL_LOG / SYSTEM_ALERT_WINDOW.
6. Reuse `experiment/scripts/*` and `gradle/libs.versions.toml` as-is.
