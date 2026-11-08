# S1 Contract & data
**Goal:** Everything shared matches `docs/SPEC.md` exactly, so server and Android can proceed independently.
**Known drift to fix** (early drafts differed from the spec):
- `docs/API.md` + server `models.py`: `/analyze` request (`source, app_package, sender, text, links, image_id, context, locale`) and response (`title_ta/en, reasons[{id,level,text_ta,text_en}], explanation_ta, audio_url, actions[], source_of_verdict, latency_ms`); severity is `high|careful|none`; `/pair/claim` returns `{link_id, elder_name}`.
- Firestore model per SPEC (`users.fcm_tokens[]`, `display_name`, `alerts.severity`, links without `members`); rules: link readable by its two members, alert create must match existing links, `fcm_tokens` owner-only.
- `data/patterns/*.json` schema per SPEC (name_ta/en, text_cues{ta,en,tanglish}, screen_cues, checks, title_ta, explanations, caretaker summaries, what_to_do, source, dates).
- `data/testset/messages.jsonl` schema `{sender,text,expect,pattern_id,lang,source}`.
**Milestones:** [ ] API.md rewritten [ ] Pydantic models + Kotlin data classes spec [ ] rules + tests updated [ ] patterns/testset migrated [ ] contract tests on server
**Exit:** server tests + rules tests green against the new contract.
