#!/usr/bin/env python3
"""Run data/testset/messages.jsonl through /analyze (in-process, rules only, no network) and print:
scams caught, false alarms on genuine messages, exact matches, per-pattern misses.

    server/.venv/bin/python scripts/eval.py [--testset PATH ...] [--shuffle SEED] [--json]
        [--min-caught PCT] [--max-false-alarms N]   (non-zero exit when violated)

Default testsets: messages.jsonl plus hard_negatives.jsonl (genuine messages containing scam-like words).
`expect` is the message label: high = scam, none = genuine, careful = borderline.
caught       = expect high and verdict high
false alarm  = expect none and verdict not none
exact        = verdict equals expect and, when the label has a pattern_id, so does the pattern
"""
from __future__ import annotations

import argparse
import os
import json
import random
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
os.environ["DOMAIN_AGE"] = "0"  # eval stays offline: no RDAP lookups

import logging  # noqa: E402

from fastapi.testclient import TestClient  # noqa: E402

from app.auth import verify  # noqa: E402
from app.main import app  # noqa: E402


logging.getLogger("guard").setLevel(logging.WARNING)  # keep the per-request log out of the report


def load(path: Path) -> list[dict]:
    return [json.loads(l) for l in path.read_text().splitlines() if l.strip()]


def run(msgs: list[dict]) -> list[dict]:
    app.dependency_overrides[verify] = lambda: "eval-user"
    client = TestClient(app)
    out = []
    for m in msgs:
        body = {"source": "sms", "text": m["text"], "sender": m.get("sender"), "links": m.get("links", [])}
        r = client.post("/analyze", json=body)
        r.raise_for_status()
        j = r.json()
        out.append({**m, "got": j["severity"], "got_pattern": j["pattern_id"],
                    "reasons": [x["id"] for x in j["reasons"]]})
    app.dependency_overrides.clear()
    return out


def summarise(rows: list[dict]) -> dict:
    scams = [r for r in rows if r["expect"] == "high"]
    genuine = [r for r in rows if r["expect"] == "none"]
    caught = [r for r in scams if r["got"] == "high"]
    false_alarms = [r for r in genuine if r["got"] != "none"]
    exact = [r for r in rows if r["got"] == r["expect"] and (not r["pattern_id"] or r["got_pattern"] == r["pattern_id"])]
    per_pattern = defaultdict(lambda: {"n": 0, "severity_miss": 0, "pattern_miss": 0, "got": []})
    for r in scams:
        if not r["pattern_id"]:
            continue
        p = per_pattern[r["pattern_id"]]
        p["n"] += 1
        p["severity_miss"] += r["got"] != "high"
        if r["got_pattern"] != r["pattern_id"]:
            p["pattern_miss"] += 1
            p["got"].append(r["got_pattern"])
    return {"total": len(rows), "scams": len(scams), "caught": len(caught), "genuine": len(genuine),
            "false_alarms": len(false_alarms), "exact": len(exact),
            "missed_scams": [r for r in scams if r["got"] != "high"], "false_alarm_rows": false_alarms,
            "per_pattern": dict(per_pattern)}


def pct(a: int, b: int) -> str:
    return f"{100 * a / b:.0f}%" if b else "n/a"


def show(s: dict) -> None:
    print(f"messages: {s['total']}  (scams {s['scams']}, genuine {s['genuine']})")
    print(f"scams caught:  {s['caught']}/{s['scams']} ({pct(s['caught'], s['scams'])})")
    print(f"false alarms:  {s['false_alarms']}/{s['genuine']} ({pct(s['false_alarms'], s['genuine'])})")
    print(f"exact matches: {s['exact']}/{s['total']} ({pct(s['exact'], s['total'])})")
    print("per-pattern misses (scam samples with a labelled pattern):")
    for pid, p in sorted(s["per_pattern"].items()):
        flag = "ok " if not (p["severity_miss"] or p["pattern_miss"]) else "MISS"
        wrong = f"  got {p['got']}" if p["got"] else ""
        print(f"  {flag} {pid}: n={p['n']} not-high={p['severity_miss']} wrong-pattern={p['pattern_miss']}{wrong}")
    for title, rows in (("missed scams", s["missed_scams"]), ("false alarms", s["false_alarm_rows"])):
        if rows:
            print(f"{title}:")
            for r in rows:
                print(f"  [{r['got']}] {r['reasons']} <- {r['sender']!s:.40} | {r['text'][:70]}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--testset", type=Path, nargs="+",
                    default=[ROOT / "data/testset/messages.jsonl", ROOT / "data/testset/hard_negatives.jsonl"])
    ap.add_argument("--shuffle", type=int, help="shuffle message order with this seed (verdicts must not change)")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--min-caught", type=float)
    ap.add_argument("--max-false-alarms", type=int)
    a = ap.parse_args()
    msgs = [m for p in a.testset for m in load(p)]
    if a.shuffle is not None:
        random.Random(a.shuffle).shuffle(msgs)
    s = summarise(run(msgs))
    if a.json:
        print(json.dumps(s, ensure_ascii=False, indent=2))
    else:
        show(s)
    bad = False
    if a.min_caught is not None and s["scams"] and 100 * s["caught"] / s["scams"] < a.min_caught:
        print(f"FAIL: caught below {a.min_caught}%", file=sys.stderr)
        bad = True
    if a.max_false_alarms is not None and s["false_alarms"] > a.max_false_alarms:
        print(f"FAIL: more than {a.max_false_alarms} false alarms", file=sys.stderr)
        bad = True
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
