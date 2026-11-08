#!/usr/bin/env python3
"""Build data/allowlists/common_domains.json from the Tranco top-1M list.

    python3 scripts/build_common_domains.py [--download] [--csv top-1m.csv] [--top 20000]

Needs `tldextract` (use server/.venv). Keeps the top N ICANN registrable domains plus
every `.in` domain ranked in the top 100k. Domains under PSL *private* suffixes
(blogspot.com, github.io, pages.dev ...) and a manual EXTRA_HOSTING set are dropped: anyone can host there, so
"popular" says nothing about a particular subdomain.

Popular != trustworthy and != who the brand is. This list only answers "is this a
well-known domain"; brand identity stays in bank_domains.json / brands.json.
"""
import argparse
import csv
import datetime
import json
import urllib.request
import zipfile
from pathlib import Path

import tldextract

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "data" / "allowlists" / "common_domains.json"
URL = "https://tranco-list.eu/top-1m.csv.zip"
# Hosting / user-content domains not covered by the PSL private section: anyone can publish
# under them, so they must never count as "common". Extend as needed.
EXTRA_HOSTING = {
    "amazonaws.com", "wordpress.com", "weebly.com", "wix.com", "windows.net", "googleusercontent.com",
    "sharepoint.com", "medium.com", "tumblr.com", "squarespace.com", "ipfs.io", "b-cdn.net", "fastly.net",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default="top-1m.csv")
    ap.add_argument("--download", action="store_true")
    ap.add_argument("--top", type=int, default=20000)
    ap.add_argument("--in-top", type=int, default=100000)
    a = ap.parse_args()
    csv_path = Path(a.csv)
    if a.download:
        zpath = csv_path.with_suffix(".csv.zip")
        urllib.request.urlretrieve(URL, zpath)
        zipfile.ZipFile(zpath).extract("top-1m.csv", csv_path.parent)
    icann = tldextract.TLDExtract(suffix_list_urls=(), cache_dir=None)
    priv = tldextract.TLDExtract(suffix_list_urls=(), cache_dir=None, include_psl_private_domains=True)
    keep, dropped_private = [], 0
    for rank, dom in csv.reader(open(csv_path)):
        rank = int(rank)
        if rank > max(a.top, a.in_top):
            break
        is_in = dom.endswith(".in")
        if rank > a.top and not is_in:
            continue
        e_i, e_p = icann(dom), priv(dom)
        if e_i.suffix != e_p.suffix or not e_p.domain or dom in EXTRA_HOSTING:
            dropped_private += 1
            continue
        keep.append(dom)
    doc = {
        "source": "Tranco top-1M (tranco-list.eu)",
        "downloaded": datetime.date.today().isoformat(),
        "note": f"Top {a.top} ICANN domains + .in domains in top {a.in_top}; PSL-private hosting domains removed. Popular != trustworthy. Rank = position in this array + 1 within kept domains, not Tranco rank.",
        "domains": keep,
    }
    OUT.write_text(json.dumps(doc, separators=(",", ":")) + "\n")
    print(f"kept {len(keep)}, dropped {dropped_private} private-suffix domains -> {OUT}")


if __name__ == "__main__":
    main()
