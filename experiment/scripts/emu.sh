#!/usr/bin/env bash
# Boot the Medium_Phone AVD in the background.
. "$(dirname "$0")/env.sh"
nohup "$ANDROID_HOME/emulator/emulator" -avd "${1:-Medium_Phone}" -no-snapshot -netdelay none >/tmp/emulator.log 2>&1 &
"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
echo booted
