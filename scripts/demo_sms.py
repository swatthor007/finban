#!/usr/bin/env python3
"""Send a demo scam text to the running emulator (real phones: send it from another phone, the list is at /demo).

    scripts/demo_sms.py            # list the samples
    scripts/demo_sms.py kyc        # send one
    DEMO_BASE=http://10.0.2.2:8000 scripts/demo_sms.py kyc   # links to a local server instead of the tunnel
"""
import json
import os
import pathlib
import subprocess
import sys

BASE = os.environ.get("DEMO_BASE", "https://galileo-tiliaceous-causally.ngrok-free.dev")
samples = {s["id"]: s for s in json.loads((pathlib.Path(__file__).parents[1] / "data/demo/pages.json").read_text())["samples"]}

if len(sys.argv) < 2 or sys.argv[1] not in samples:
    for s in samples.values():
        print(f"{s['id']:10} {s['label']} (expect {s['expect']})")
    sys.exit(0 if len(sys.argv) < 2 else 1)
s = samples[sys.argv[1]]
subprocess.run(["adb", "emu", "sms", "send", s["sender"], s["text"].replace("{base}", BASE)], check=True)
