#!/usr/bin/env bash
# Enable the notification listener (needed for SMS-notification capture).
. "$(dirname "$0")/env.sh"
"$ADB" shell cmd notification allow_listener "$PKG/$PKG.sms.GuardNotificationListener" && echo ok
"$ADB" shell cmd notification get_allowed_listeners 2>/dev/null | head -3
