"""UTS #39 style skeletons and edit-distance-1 neighbours for lookalike domains.

`confusable_homoglyphs` ships the Unicode confusables table but no skeleton function, so we derive
source-char -> ASCII from it, then apply an explicit ASCII fold. Both sides of a comparison go through
the same function, so the fold only has to be consistent, not linguistically perfect.
"""
from __future__ import annotations

import json
import os
import unicodedata
from functools import lru_cache

import confusable_homoglyphs

_ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789"
# Classic look-alike swaps inside ASCII: i/l/1/| -> l, 0 -> o, then rn -> m, vv -> w.
_ASCII_FOLD = str.maketrans({"1": "l", "i": "l", "|": "l", "0": "o"})


@lru_cache(maxsize=1)
def _to_ascii() -> dict:
    path = os.path.join(os.path.dirname(confusable_homoglyphs.__file__), "confusables.json")
    with open(path, encoding="utf-8") as f:
        table = json.load(f)
    out = {}
    for ch, alts in table.items():
        if len(ch) != 1 or ch.isascii():
            continue
        cands = [a["c"].lower() for a in alts if len(a["c"]) == 1 and a["c"].lower() in _ALNUM]
        if cands:
            out[ch] = cands[0]
    return out


def skeleton(s: str) -> str:
    """Lowercase, NFD, map non-ASCII look-alikes to ASCII, drop combining marks, then ASCII-fold."""
    m = _to_ascii()
    out = []
    for ch in unicodedata.normalize("NFD", s.lower()):
        if unicodedata.combining(ch):
            continue
        out.append(m.get(ch, ch))
    t = "".join(out).translate(_ASCII_FOLD)
    return t.replace("rn", "m").replace("vv", "w")


_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789-."


def edits1(s: str) -> set:
    """All strings one deletion / insertion / substitution / adjacent transposition away (excluding s)."""
    splits = [(s[:i], s[i:]) for i in range(len(s) + 1)]
    out = {a + b[1:] for a, b in splits if b}
    out |= {a + c + b for a, b in splits for c in _ALPHABET}
    out |= {a + c + b[1:] for a, b in splits if b for c in _ALPHABET}
    out |= {a + b[1] + b[0] + b[2:] for a, b in splits if len(b) > 1}
    out.discard(s)
    return out
