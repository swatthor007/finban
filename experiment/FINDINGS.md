# Findings: what didn't work or behaved unexpectedly

Environment so far: Android Studio emulator `Medium_Phone`, API 37 google_apis (no Play Store). **No physical phone was available**, so everything below is emulator-only and OEM behaviour is untested.

## Behaved unexpectedly (and what we did)
1. **`uiautomator dump` unbinds accessibility services.** UiAutomation takes over the a11y channel; our service got `onUnbind` and a showing bottom sheet was removed. Test-tooling artifact only (don't use uiautomator/Appium-style tooling while testing; use screenshots + `input tap`). Our service reconnected afterwards on its own.
2. **Compose slide-in never ran** when the animation was launched with `LaunchedEffect(measuredHeight)`: the effect restarted mid-animation, the second run saw `offset == height` and skipped, so the sheet stayed off-screen (window present, focused, invisible). Fixed by keying on a boolean `measured`. Worth knowing if the production sheet misbehaves the same way.
3. **Ignoring our own package broke the rule.** Opening our app and returning to a watchlisted app was not a "foreground change" because our window events were dropped. Now our own app is tracked as foreground (never fires the rule), so returning to another app counts.
4. **Cellular audio mode is `RINGTONE → IN_CALL`, not `IN_COMMUNICATION`.** `MODE_IN_COMMUNICATION` is therefore a VoIP-only signal (good), but any app that opens a communication audio session also sets it (false positives: voice notes, video calls, recorders, stuck mode after a call). Each mode change logs the foreground package for triage.
5. **Call-screening callback arrives ~20 ms *before* the `RINGING` state callback**, so the number is available by the time the state listener fires. Holding the screening role did **not** change whether `TelephonyCallback` fired (emulator). Re-check on a phone (`call/state` payload records `screeningRoleHeld`).
6. **Without READ_CALL_LOG, the number only comes from the screening service** (`TelephonyCallback.CallStateListener` has no number on API 31+). Without the role there is no number at all.
7. **Notification listener also sees the SMS app's group *summary* notification** (empty title/text). Logged as `sms-app-summary`.
8. **Gesture nav insets on the emulator**: `navigationBars.bottom = 0`, `systemGestures.bottom = 84 px`. The sheet already pads for nav bars; confirm on 3-button-nav phones and OEM gesture pills.
9. Emulator's `adb emu gsm accept` requires the number argument.

## Not verified yet (needs a phone)
WhatsApp/UPI foreground package names and bottom-sheet behaviour over them (focus, touch, keyboard), VoIP `IN_COMMUNICATION` detection, real DLT senders, OEM messaging package, notification-content redaction (Android 15+), contacts "known" path, QS tile, notification action, export share sheet, "Speak Tamil" audio output (only the availability check was seen: `ta-IN LANG_COUNTRY_AVAILABLE`), restricted-settings flow, Advanced Protection mode, OEM battery killers.
