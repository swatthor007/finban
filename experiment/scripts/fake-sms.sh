#!/usr/bin/env bash
# EMULATOR ONLY. Usage: fake-sms.sh [sender] [text]
. "$(dirname "$0")/env.sh"
"$ADB" emu sms send "${1:-AX-HDFCBK-S}" "${2:-Rs 5,000 debited. Call 9876543210 or visit hdfc-secure.xyz}"
