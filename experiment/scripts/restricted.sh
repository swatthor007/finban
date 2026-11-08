#!/usr/bin/env bash
# Allow "restricted settings" (Android 13+ sideload restriction) for this app.
. "$(dirname "$0")/env.sh"
"$ADB" shell appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow && echo ok
"$ADB" shell appops get "$PKG" ACCESS_RESTRICTED_SETTINGS
