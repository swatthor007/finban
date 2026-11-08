#!/usr/bin/env python3
"""Evidence for the bank/brand domain lists: for each domain, does it answer over HTTPS, where does it end up, and which
organisation does its TLS certificate name. Writes a markdown table. This is evidence for a human to confirm, NOT verification:
verified stays false until the owner confirms against the bank's own materials.
    server/.venv/bin/python -I scripts/domain_evidence.py > docs/DOMAIN-EVIDENCE.md
"""
import json, socket, ssl, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

D = Path(__file__).resolve().parents[1] / "data" / "allowlists"
rows = []
for b in json.load(open(D / "bank_domains.json"))["banks"]:
    rows += [(b["name_en"], d, "bank_domains.json") for d in b["domains"]]
for b in json.load(open(D / "brands.json"))["brands"]:
    if "bank_id" not in b:
        rows += [(b["name_en"], d, "brands.json") for d in b.get("domains", [])]


def probe(row):
    name, dom, src = row
    out = {"name": name, "domain": dom, "src": src, "dns": "", "final": "", "status": "", "cert_org": "", "cert_cn": ""}
    try:
        socket.gethostbyname(dom)
        out["dns"] = "ok"
    except OSError:
        out["dns"] = "no A record"
    try:
        ctx = ssl.create_default_context()
        with socket.create_connection((dom, 443), timeout=6) as s, ctx.wrap_socket(s, server_hostname=dom) as t:
            c = t.getpeercert()
        subj = {k: v for rdn in c["subject"] for k, v in rdn}
        out["cert_org"], out["cert_cn"] = subj.get("organizationName", "(none, DV cert)"), subj.get("commonName", "")
    except Exception as e:
        out["cert_org"] = "cert error: " + e.__class__.__name__
    for host in (dom, "www." + dom):
        try:
            req = urllib.request.Request("https://" + host, headers={"User-Agent": "Mozilla/5.0 finban-domain-check"})
            with urllib.request.urlopen(req, timeout=8) as r:
                out["final"], out["status"] = r.geturl(), str(r.status)
            break
        except Exception as e:
            out["status"] = e.__class__.__name__
    return out


with ThreadPoolExecutor(8) as ex:
    res = list(ex.map(probe, rows))
print("# Domain evidence (machine-collected, NOT verification)\n")
print("| Brand | Domain | DNS | HTTPS final URL | Status | Certificate organisation |\n|---|---|---|---|---|---|")
for r in res:
    print(f"| {r['name']} | `{r['domain']}` | {r['dns']} | {r['final'] or '-'} | {r['status']} | {r['cert_org']} |")
