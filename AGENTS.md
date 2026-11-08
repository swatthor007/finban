# AGENTS.md — read this first in every conversation

Hackathon project: **Finban**, an Android financial helper / anti-scam assistant for elderly users in Tamil Nadu (+ caretaker mode), with a FastAPI server and Firebase pairing/alerts. Package `devr.shellshock.finban` (replaces the SPEC placeholder `com.yourteam.guard`).

## Orient in 3 steps
1. Read `docs/plans/CONTEXT.md` (rules, state, gotchas). Short.
2. Read `docs/plans/00-MASTER-PLAN.md` (sprint index and status). Open only the sprint file you're working on.
3. Details live in `docs/SPEC.md` (original full spec, source of truth), `docs/FINDINGS.md` (emulator results, overrides spec), `docs/API.md` (contract).

## Working rules
- Each conversation **plans its own sprint's implementation** before coding. Sprint files give goal + milestones only.
- Small steps, build/run after each, commit each. Stubs get `TODO(hackathon):`.
- When you finish or pause, update the sprint's status in `00-MASTER-PLAN.md` and add anything non-obvious to `CONTEXT.md`.
- Never modify `experiment/` (read-only harness). Copy code out of it.
- Never run long/downloading commands without need; ask if unsure.

## UI design artifact
UI guidelines: https://claude.ai/artifact/L9xwbdETZsB1WRQNtB7QTT#page-dcb81aee8d83
Read it (Artifact tool, `action: read`) **only in Sprint 4+ when doing Android UI**, then map tokens into the single theme file. If it conflicts with hard rules (body >= 18sp, 56dp targets, 3 severity colours with icons, bottom sheets not pop-ups), flag it to the user.

## Layout
`android/ server/ firebase/ data/ docs/ scripts/ experiment/` (harness, read-only; `scam-prevent.zip` is a copy of it).
