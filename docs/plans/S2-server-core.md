# S2 Server core
**Goal:** `/analyze` returns rules-only verdicts matching the testset; auth enforced.
**Exists:** auth, rules engine, `/analyze`, domain pipeline (`server/app/domains.py`, `confusables.py`), response templates (`templates.py`), eval; 190 tests pass + 4 strict xfail (see CONTEXT.md "S2 state").
**Milestones:** [x] rework to S1 contract [x] domain check pipeline + email [x] confusables/UTS #39 skeleton check [x] pattern templates + audio_url (audio_url only when the file exists; files come in S6) [x] logging (uid hash, no text) [x] `scripts/eval.py` first version [x] tune near-miss header and lookalike checks [ ] open: weak-cue false alarms (4/20 on genuine), Tamil text, data verification batch
**Exit:** `pytest` green; eval prints scams caught / false alarms. (Met; eval: 13/13 scams caught, 4/20 false alarms, 29/33 exact. All 4 false alarms are single-cue `pattern-weak` hits, kept in `hard_negatives.jsonl`.)

## Domain check order (URL hosts and email addresses share it)
Results combine as max severity, so order mostly changes cost and where a check can stop the rest.
0. Normalise: lowercase, strip port/trailing dot, drop URL user-info (`sbi.co.in@evil.com` goes to `evil.com`), decode punycode, registrable domain via PSL. Email: domain after last `@`, plus display name (and Reply-To if visible).
1. Exact official match (`bank_domains.json`, or `gov.in`/`nic.in` at a dot boundary) -> no domain warning, skip network.
2. Structure flags: bare IP, user-info trick, `.apk`, punycode/mixed script, skeleton equal to an official domain.
3. Brand claims: brand alias in host/subdomain but registrable domain not that brand's; email display name names a brand but domain differs (esp. `free_mail.json`).
4. Weak signals: risky TLD, shortener, hyphen/digit patterns; `common_domains.json` only lowers suspicion if nothing above fired and no brand is claimed.
5. Network, parallel with a time limit, only if still undecided: expand shorteners (<= 3 hops, re-run 0-4 on the final URL), then RDAP age, Safe Browsing, 24 h cache. Skip if local checks already say `high` (except shortener expansion).
6. Combine: max severity; age/TLD only escalate alongside other evidence; unknown never downgrades.
Android runs steps 0-4 offline. Email: only what is on screen; no SPF/DKIM, so say "this address doesn't match", never "this email is fake". Check order-independence of verdicts by running the testset in different orders; add email samples to the testset.

## Where the AI fits (S6)
Feed the LLM the structured results of steps 0-5 (flags, brand claim, age, expansion) plus redacted text, so it decides with the whole picture. Constraints from the SPEC still apply: redact first, severity = max(rules, LLM), the LLM can never lower a rules `high`, 3 s timeout falls back to the rules verdict.
