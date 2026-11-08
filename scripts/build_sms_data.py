#!/usr/bin/env python3
"""Build data/allowlists/sms_headers.json from TRAI's header list (data/sms/*.xlsx).

Stdlib only (the xlsx is read as a zip). Re-run after replacing the xlsx:
    python3 scripts/build_sms_data.py [path/to/List_SMS_Headers.xlsx]

Entity categories are a keyword HEURISTIC on the registered entity name, not an
official classification. A header being registered does not mean the sender is who
the header looks like (e.g. INCTAX is registered to an unrelated company), so use
`category` only as a hint and prefer curated lists (bank_domains.json) for brands.
"""
import json
import re
import sys
import zipfile
from pathlib import Path
from xml.sax.saxutils import unescape

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "data" / "sms" / "List_SMS_Headers_16062020_0.xlsx"
OUT = ROOT / "data" / "allowlists" / "sms_headers.json"

# Order matters: first match wins.
CATEGORY_RULES = [
    ("insurance", r"INSURANCE|ASSURANCE"),
    ("finance", r"SECURITIES|MUTUAL FUND|ASSET MANAGEMENT|\bCARDS?\b|CREDILA|BROKING|FINANCE|FINANCIAL|FINCORP|CAPITAL|CREDIT|LOAN"),
    ("payments", r"\bNPCI\b|NATIONAL PAYMENTS|PAYTM|PHONEPE|PAYMENTS?\b|\bUPI\b"),
    ("bank", r"\bBANK\b|\bBANKING\b|BANK LTD|BANK LIMITED"),
    ("government", r"NATIONAL INFORMATICS|\bGOVT\b|GOVERNMENT|DEPARTMENT|MINISTRY|DIRECTORATE|OFFICE OF|MUNICIPAL|POLICE|COMMISSION|CORPORATION OF TAMIL NADU|\bNIC\b|UIDAI|PANCHAYAT"),
]


def categorise(name: str) -> str:
    for cat, pat in CATEGORY_RULES:
        if re.search(pat, name, re.I):
            return cat
    return "other"


def read_rows(path: Path):
    z = zipfile.ZipFile(path)
    ss = [unescape(re.sub(r"<[^>]+>", "", s))
          for s in re.findall(r"<si>(.*?)</si>", z.read("xl/sharedStrings.xml").decode(), re.S)]
    sheet = z.read("xl/worksheets/sheet1.xml").decode()
    for row in re.findall(r"<row .*?</row>", sheet, re.S):
        out = []
        for _ref, attr, body in re.findall(r'<c r="(\w+)"([^>]*?)(?:/>|>(.*?)</c>)', row, re.S):
            v = re.search(r"<v>(.*?)</v>", body or "")
            v = v.group(1) if v else ""
            out.append(ss[int(v)] if 't="s"' in attr and v else v)
        yield out


def main():
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else SRC
    rows = [r for r in read_rows(src)][1:]  # skip header row
    entities, index, headers = [], {}, {}
    for r in rows:
        if len(r) < 2 or not r[0].strip():
            continue
        h, name = r[0].strip().upper(), " ".join(r[1].split())
        if name not in index:
            index[name] = len(entities)
            entities.append({"name": name, "category": categorise(name)})
        headers[h] = index[name]
    doc = {
        "source": "TRAI List_SMS_Headers_16062020 (data/sms/)",
        "list_date": "2020-06-16",
        "note": "Stale (2020). Categories are a name-keyword heuristic. Registered != legitimate for the brand it resembles. TODO(hackathon): merge a newer TRAI download.",
        "entities": entities,
        "headers": dict(sorted(headers.items())),
    }
    OUT.write_text(json.dumps(doc, ensure_ascii=False, separators=(",", ":")) + "\n")
    cats = {}
    for e in entities:
        cats[e["category"]] = cats.get(e["category"], 0) + 1
    print(f"{len(headers)} headers, {len(entities)} entities, entity categories: {cats} -> {OUT}")


if __name__ == "__main__":
    main()
