import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("guard_eval", ROOT / "scripts" / "eval.py")
ev = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ev)

ROWS = [
    {"sender": None, "text": "This is CBI. You are under digital arrest. Do not tell anyone. Stay on video call.",
     "expect": "high", "pattern_id": "digital-arrest"},
    {"sender": None, "text": "I sent Rs 5000 by mistake, please send it back.", "expect": "high", "pattern_id": "task-job"},
    {"sender": None, "text": "Pongal holiday plan. Call me when free.", "expect": "none", "pattern_id": None},
    {"sender": None, "text": "Click here to track your order. Pay Rs 20 at delivery.", "expect": "none", "pattern_id": None},
]


def test_summarise_counts():
    s = ev.summarise(ev.run(ROWS))
    assert (s["scams"], s["caught"]) == (2, 2)                     # both high
    assert (s["genuine"], s["false_alarms"]) == (2, 1)             # weak cue plus an amount -> careful
    assert s["exact"] == 2                                          # first + third; second has wrong pattern
    assert s["per_pattern"]["task-job"] == {"n": 1, "severity_miss": 0, "pattern_miss": 1, "got": ["refund-trick-1"]}
    assert s["per_pattern"]["digital-arrest"]["pattern_miss"] == 0


def test_default_testsets_include_hard_negatives(capsys):
    import sys
    old = sys.argv
    try:
        sys.argv = ["eval.py", "--json"]
        ev.main()
        import json as j
        out = j.loads(capsys.readouterr().out)
        assert out["total"] >= 33 and out["scams"] >= 13
    finally:
        sys.argv = old


def test_cli_exit_codes_and_output(tmp_path, capsys):
    f = tmp_path / "t.jsonl"
    f.write_text("\n".join(json.dumps(r) for r in ROWS))
    import sys
    old = sys.argv
    try:
        sys.argv = ["eval.py", "--testset", str(f)]
        assert ev.main() == 0
        out = capsys.readouterr().out
        assert "scams caught:  2/2 (100%)" in out and "false alarms:  1/2 (50%)" in out
        sys.argv = ["eval.py", "--testset", str(f), "--max-false-alarms", "0"]
        assert ev.main() == 1
        sys.argv = ["eval.py", "--testset", str(f), "--shuffle", "3", "--min-caught", "100"]
        assert ev.main() == 0
    finally:
        sys.argv = old
