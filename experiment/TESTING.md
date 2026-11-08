# Test checklist

Fill one Result column per device (✅ / ❌ / n/a + note). Log lines are visible in the in-app timeline and `adb logcat -s GuardH`.
Device A: ________  Android: ____   Device B: ________  Android: ____

Setup first: `scripts/install.sh`, then enable the service (Diagnostics row 1, or `scripts/enable-a11y.sh`), grant permissions.

| # | Capability | How to trigger | Expected log (type/tag) | Result A | Result B | Notes |
|---|---|---|---|---|---|---|
| 1 | Accessibility service | Enable in Settings; check Diagnostics | `service/connected`; row shows "enabled, connected" | | | |
| 1b | Restricted-settings guide (sideload, 13+) | Install APK from browser/file manager, follow in-app guide | toggle becomes usable | | | |
| 2 | Foreground detection | Open Settings, Chrome, a keyboard-using app | `foreground/app` with pkg + class; no lines for keyboard/systemui/own app | | | |
| 2b | Watchlist package ids | Open GPay, PhonePe, Paytm, BHIM, WhatsApp, AnyDesk, TeamViewer QS | `foreground/watched … [WATCHLISTED]`; check the "Packages seen" list matches the defaults; fix ids | | | |
| 3 | Capture via bubble | Tap bubble over any app | `capture/done source=bubble …`, viewer shows text | | | |
| 3b | Capture via notification action | Pull shade, tap "Capture" | `capture/done source=notification` | | | |
| 3c | Capture via QS tile | Add "Guard capture" tile, tap it | `capture/tile` then `capture/done source=tile` (shade may be what's read, see notes) | | | |
| 3d | Extractors | Open a screen with a URL, +91 number, `name@ybl`, ₹ amount | `capture/extracted URL=…; PHONE=…; UPI=…; AMOUNT=…`, highlighted in viewer | | | |
| 3e | Empty / redacted | Capture a banking app, a webview/canvas app, a secure (FLAG_SECURE) screen | `capture/empty` or `capture/redacted` naming the pkg | | | |
| 3f | Export capture | "Export capture" | share sheet with `capture-<ts>.json` | | | |
| 4 | Bubble | Drag it; release near centre; switch apps; toggle switch off/on | snaps to nearest edge; survives app switches; `overlay/bubble shown/hidden` | | | |
| 5 | Bottom sheet | "Sheet in 5s", switch to WhatsApp, then a UPI app | slides up; scrim dim; drag handle; `overlay/sheet shown`, `sheet-focus`, `sheet-insets` | | | |
| 5b | Sheet dismissals | Back gesture; tap scrim; drag down; tap button | `sheet removed (reason=back/scrim/drag/button)` | | | |
| 5c | Focus / keyboard | Open sheet while a text field + keyboard is up in another app | note whether keyboard hides, typing lost, touch passes through | | | |
| 6 | Cellular state | Real call in/out | `call/state IDLE -> RINGING -> OFFHOOK -> IDLE` | | | |
| 6b | Call screening role | Request role; receive a call | `call/screen incoming number=… known=… verification=…`; call rings normally | | | |
| 6c | Role effect on state listener | Compare `call/state` payload `screeningRoleHeld` true vs false | same transitions either way? | | | |
| 6d | Unknown vs known caller | Call from a saved contact and from an unsaved number | `known=true` / `known=false` | | | |
| 6e | VoIP | WhatsApp voice call (answer it) | `voip/mode … -> IN_COMMUNICATION <-- VoIP candidate`, then back on hang-up | | | |
| 6f | VoIP false positives | Send a WhatsApp voice note; end a call and watch for stuck mode | note any `IN_COMMUNICATION` without a call | | | |
| 7 | SMS_RECEIVED | Send SMS from a DLT sender + from a mobile number | `sms/receiver from="XX-HEADER-S" -> DLT header=…`, `PLAIN_MOBILE` | | | |
| 7b | Notification listener | Enable listener; same SMS | `notification/sms-app pkg=… title=… -> <parsed>`; note OEM SMS package id and add it in the SMS-apps list | | | |
| 7c | OTP redaction (Android 15+) | Receive an OTP SMS | `[EMPTY/REDACTED TEXT]`? | | | |
| 8 | Trigger rule | Call from unknown number, then open a watchlisted app | `rule/fire …` + sheet with 3 questions; answers logged as `rule/answer` | | | |
| 8b | Rule negatives | No call; known caller; within cooldown; call ended > N min ago | `rule/skip … -> <reason>` | | | |
| 9 | Tamil TTS | Open app; tap "Speak Tamil" | `tts/check ta-IN availability=…`; audible Tamil, `tts/speak done` | | | |
| 9b | Asset audio | "Play sample_ta.mp3" | `tts/asset playing …` and audible | | | |
| 9c | Audio during a call | Speak/play while a call is active | audible to user? note audio mode logged | | | |
| 10 | Diagnostics | Toggle each setting off/on | rows flip ❌/✅ after returning to the app; every button opens the right page | | | |
| 11 | Log export | "Export log" | share sheet with `log-<ts>.json` | | | |

Emulator shortcuts: `scripts/fake-call.sh`, `scripts/fake-sms.sh`, `scripts/grant-role.sh`. Don't use `uiautomator dump` while testing (it unbinds the a11y service).
