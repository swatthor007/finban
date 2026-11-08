# Master plan (index)

Status: `[ ]` todo, `[~]` in progress, `[x]` done. One conversation per sprint (or part of one). Plan the implementation at the start of each; sprint files are intentionally thin.

| # | Sprint | Milestone (demoable) | Status |
|---|---|---|---|
| 0 | [Foundation](S0-foundation.md) | Findings written, repo laid out | [~] done except review |
| 1 | [Contract & data](S1-contract-data.md) | API.md, models, rules, data match SPEC | [x] |
| 2 | [Server core](S2-server-core.md) | `/analyze` rules-only passes testset | [x] code done; open: weak-cue false alarms, Tamil text, data verification (see CONTEXT.md) |
| 3 | [Firebase pairing + alerts](S3-firebase.md) | Push reaches a real phone | [~] server + emulator tests done; open: real-phone push (needs Firebase project, service account, Android test client) |
| 4 | [Android elder skeleton](S4-android-elder.md) | Bubble -> `/analyze` -> sheet on phone | [ ] |
| 5 | [Caretaker mode + live reply](S5-caretaker.md) | "I'm calling now" updates elder sheet | [ ] |
| 6 | [Intelligence](S6-intelligence.md) | LLM, links/RDAP, images, TTS | [ ] |
| 7 | [Trigger & report flows](S7-trigger-report.md) | Pause sheet, 1930, Chakshu, demo mode | [ ] |
| 8 | [Polish & demo](S8-polish-demo.md) | Docs, eval number, rehearsal | [ ] |

Order notes: S2 and S3 can run in parallel with S4 once S1 is done. S4 needs the UI artifact (see `AGENTS.md`).
Reference: `docs/SPEC.md` (full spec), `docs/FINDINGS.md`, `docs/plans/CONTEXT.md`.

**Prototype re-scope (2026-11-04):** under 20 h total, proof of concept. S4-S8 are superseded by [PROTOTYPE-PLAN.md](PROTOTYPE-PLAN.md) (phases A-I, T). Sprint files S4-S8 stay as reference only.

## Real-phone plan (2026-11-05): 23 steps, status
| Phase | Steps | Status |
|---|---|---|
| 0 Save + permanent URL | 1-5 | [x] 1-4 done; 5: server up on the permanent domain, `/health` OK through ngrok from the laptop. **Still to check: from a phone on mobile data and the emulator** |
| 1 Server protection + detection | 6-10 | [x] 6 cost guard, 7 family-emergency, 8 weak-cue rule + 42-message testset, 9 spoofed-sender rule, 10 evidence table done; **10 needs the user to confirm `docs/DOMAIN-EVIDENCE.md`** (verified stays false) |
| 2 Real-phone pass | 11 | [ ] needs phones |
| 3 Call + payment warning | 12-15 | [x] code + emulator check done (unknown call -> pause sheet over a debug "payment app", 3 questions, Yes reaches the alert step); **not yet seen: alert arriving at a paired caretaker, real GPay, WhatsApp call** (phones) |
| 4 Experience | 16-19 | [ ] |
| 5 Release + Tamil | 20-21 | [~] 21 Tamil done (app, server, AI; needs a Tamil speaker's read); 20 release open |
| 6 Final pass + DEMO.md | 22-23 | [ ] |
