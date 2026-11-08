#!/usr/bin/env bash
# Grant the call-screening role via adb (works on the emulator; on phones prefer the in-app "Request role" dialog).
. "$(dirname "$0")/env.sh"
"$ADB" shell cmd role add-role-holder android.app.role.CALL_SCREENING "$PKG" 0
"$ADB" shell cmd role get-role-holders android.app.role.CALL_SCREENING
