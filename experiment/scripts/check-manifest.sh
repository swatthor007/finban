#!/usr/bin/env bash
# Fails if the BUILT apk requests a permission we promised not to use (catches transitive additions).
. "$(dirname "$0")/env.sh"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
AAPT=$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)
perms=$("$AAPT" dump permissions "$APK")
echo "$perms"
if echo "$perms" | grep -E "READ_SMS|READ_CALL_LOG|SYSTEM_ALERT_WINDOW|android.permission.INTERNET"; then echo "FORBIDDEN PERMISSION PRESENT"; exit 1; fi
echo "OK: no forbidden permissions"
