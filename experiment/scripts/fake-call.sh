#!/usr/bin/env bash
# EMULATOR ONLY. Usage: fake-call.sh [number] [seconds-connected]
. "$(dirname "$0")/env.sh"
N="${1:-9876543210}"; S="${2:-5}"
"$ADB" emu gsm call "$N"; sleep 2; "$ADB" emu gsm accept "$N"; sleep "$S"; "$ADB" emu gsm cancel "$N"
