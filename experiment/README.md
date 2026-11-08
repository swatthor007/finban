# Guard Harness – Android capability test harness

Throwaway app that proves, on a real phone, the system capabilities an accessibility-based anti-scam assistant needs.
**Observe-only.** It never clicks, types, blocks, rejects or otherwise acts inside other apps. It only shows its own overlays.
No network, no `INTERNET` permission, no backend. Placeholder application id: `com.yourteam.guard.harness`.

Requested permissions: `READ_PHONE_STATE`, `READ_CONTACTS`, `RECEIVE_SMS`, `POST_NOTIFICATIONS`.
Deliberately **not** requested: `READ_SMS`, `READ_CALL_LOG`, `SYSTEM_ALERT_WINDOW` (`scripts/check-manifest.sh` verifies the built APK).

## Requirements
JDK 17+ (built with 21), Android SDK with platform 37 + build-tools, `adb` from the SDK's `platform-tools`.
`scripts/env.sh` finds the SDK (`~/Android/Sdk`, or `~/Library/Android/sdk` on macOS) and always uses the **SDK's** adb, because distro adb packages are often older than the device tooling expects.
Gradle comes from the wrapper (9.5.0). Stack: AGP 9.3.3, Kotlin 2.4.20, Compose BOM 2026.09, Room 2.8.5; minSdk 29, compile/target 37.

## Build, install, enable
```bash
scripts/install.sh              # build + install -r + launch (ANDROID_SERIAL=<id> if several devices)
scripts/grant.sh                # runtime permissions via pm grant
scripts/enable-a11y.sh          # appends our service to enabled_accessibility_services (keeps other services)
scripts/enable-notif-listener.sh
scripts/restricted.sh           # appops ACCESS_RESTRICTED_SETTINGS allow
scripts/grant-role.sh           # call-screening role via `cmd role` (or use the in-app "Request role" button)
scripts/check-manifest.sh       # no forbidden permissions in the built APK
```
Emulator-only helpers: `scripts/emu.sh` (boot AVD), `scripts/fake-call.sh [number] [secs]`, `scripts/fake-sms.sh [sender] [text]`.

What was verified on the emulator (API 37): install, `pm grant`, enabling the a11y service via `settings put`, `appops set ACCESS_RESTRICTED_SETTINGS allow` (accepted, returns `allow`), `cmd notification allow_listener`, `cmd role add-role-holder`. **Not verifiable on an emulator:** whether the restricted-settings app-op actually lifts the sideload block (an `adb install` does not trigger the block in the first place; it only applies to APKs installed via browser/file manager/other non-store installers). Test on a phone.

### Enabling by hand on a phone (Android 13–16)
1. **Accessibility:** Settings → Accessibility → Installed/Downloaded apps → *Guard Harness (test)* → On.
   If the toggle is greyed out with "Restricted setting" (sideloaded APK, Android 13+): Settings → Apps → Guard Harness → ⋮ → **Allow restricted settings** (PIN/biometric), then retry. On Android 13 the ⋮ entry only appears after you have first tried to enable the service once. Some OEMs bury "Installed apps" under *Accessibility → Installed services*.
2. **Notification listener:** Diagnostics → "Open settings" → Notification access → on. Same restricted-settings rule applies (Android 13+; the app-op is the same one).
3. **Call screening:** Diagnostics → "Request role" (system dialog), or Settings → Apps → Default apps → Caller ID & spam / Call screening app.
4. **Runtime permissions:** Diagnostics → "Request".
5. **Android 16+ Advanced Protection Mode** (if the user enabled it) can switch off accessibility services that are not declared as accessibility tools. We intentionally do not set `isAccessibilityTool`; if the service turns itself off on such a phone, that is why.

### Wireless debugging + scrcpy
```bash
# Phone: Settings → Developer options → Wireless debugging → on → "Pair device with pairing code"
adb pair <ip>:<pair-port>            # enter the 6-digit code
adb connect <ip>:<connect-port>      # the port on the main Wireless-debugging screen (differs from the pair port)
adb devices
scrcpy                                # mirror + control (add --serial <ip:port> if several devices)
scrcpy --no-control                   # view only
```
Linux: `sudo apt install scrcpy`; macOS: `brew install scrcpy`. Over USB just plug in and accept the RSA prompt.
Watch logs live: `adb logcat -s GuardH`.

## App tour
Home = diagnostics + timeline. Checklist rows with deep-links: accessibility, Tamil TTS, notification listener, call-screening role, runtime permissions. Sections: screen capture (bubble switch, capture viewer, export), TTS, trigger rule (N / cooldown), SMS-app package list, watchlist + "packages seen on this device", then the event timeline (filter chips, export, clear).
Everything is also logged to Logcat tag `GuardH`; the Room-backed log (newest ~5 000 events) survives process death.
Exports are written to the app's private `files/exports/` and shared through the share sheet (FileProvider).

## Design notes
* **Event log = Room**, not a RAM ring buffer: events arrive from five different entry points and the process can die between a call and the moment you open the app.
* **Settings = SharedPreferences** (not DataStore): the a11y event path needs synchronous reads; throwaway code.
* **Overlays**: bubble = non-focusable `TYPE_ACCESSIBILITY_OVERLAY`; bottom sheet = separate *focusable* `TYPE_ACCESSIBILITY_OVERLAY` (focus is needed to receive BACK). `ComposeView` with a hand-rolled `OverlayOwner` (Lifecycle + ViewModelStore + SavedStateRegistry set via the ViewTree helpers) works; no fallback to Views was needed.
* **Trigger rule caller semantics**: cellular call → unknown unless the screening service saw the number *and* it is in contacts (no screening info counts as unknown and is flagged in the log); VoIP-only → no number exists, treated as unknown.

## Known quirks / gotchas found so far (emulator, API 37)
See `FINDINGS.md`. OEM-specific quirks will be added there after phone testing.
