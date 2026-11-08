#!/bin/sh
# Share a text to Finban on the connected emulator/phone, like the system share sheet would.
# Usage: scripts/share-text.sh 'Any text, with spaces, "quotes" and apostrophes'
# Run it with sh/bash (not pasted into fish).
[ -n "$1" ] || { echo "usage: $0 'text to share'"; exit 1; }
esc=$(printf %s "$1" | sed "s/'/'\\\\''/g")
adb shell "am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '$esc' -n devr.shellshock.finban/.ui.share.ShareReceiverActivity"
