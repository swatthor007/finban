#!/usr/bin/env bash
# Grant runtime permissions via adb.
. "$(dirname "$0")/env.sh"
for p in READ_PHONE_STATE READ_CONTACTS RECEIVE_SMS POST_NOTIFICATIONS; do
  "$ADB" shell pm grant "$PKG" "android.permission.$p" && echo "granted $p"
done
