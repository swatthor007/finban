#!/usr/bin/env bash
# Enable the accessibility service, preserving any other enabled services.
. "$(dirname "$0")/env.sh"
cur=$("$ADB" shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$cur" in null|"") new="$SVC" ;; *"$SVC"*) new="$cur" ;; *) new="$cur:$SVC" ;; esac
"$ADB" shell settings put secure enabled_accessibility_services "$new"
"$ADB" shell settings put secure accessibility_enabled 1
echo "enabled_accessibility_services=$("$ADB" shell settings get secure enabled_accessibility_services)"
