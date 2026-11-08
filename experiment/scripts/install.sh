#!/usr/bin/env bash
# Build debug APK, install on the connected device (use ANDROID_SERIAL=... if several), launch.
set -euo pipefail
. "$(dirname "$0")/env.sh"
"$ROOT/gradlew" -p "$ROOT" :app:assembleDebug --console=plain -q
"$ADB" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
"$ADB" shell am start -n "$PKG/.ui.MainActivity"
