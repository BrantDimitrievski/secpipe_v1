### WO-D.1: Write the user guide and demo script
Wave: 7   Depends on: WO-K.13, WO-L.2, WO-G.7   Blocks: none
Milestone: Prototype 2 Dec 5
Traces: NFR-1, NFR-5, NFR-6, FR-2, FR-7, FR-13, FR-14 · Q (FR-7, FR-13, FR-14 and Q appear only in the Limitations section: not implemented in the lean plan; see `docs/PLAN.md` "Re-plan 2026-11-17 (lean)")
Suggested human owner: Elaine Huang (reviewer: Abdul Babatunde)
Size: S

Goal (2–3 sentences): A short user guide: install (JDK 21 + `secpipe.jar`), `secpipe scan` with its outputs, providers and their env vars, Docker for evidence, and a Limitations section. Fold in the demo kit (old D.3) as one scripted demo on `fixtures/e2e/sqli-min`.

Context to read first (exact paths only): `docs/PLAN.md` §4.3; `secpipe-cli/src/main/java/ca/carleton/secpipe/cli/ScanCommand.java`; `README.md`; `docs/work-orders/stretch/WO-D.3.md`

Files you own (create/modify — nothing else): `README.md`, `docs/user/**`, `scripts/demo/**`, `scripts/docs/check-snippets.sh`.

Files you may read but not modify: everything else.

Interfaces consumed: the CLI `--help`   Interfaces produced: `docs/user/guide.md`, `scripts/demo/demo.sh`.

Implementation notes:
- Every command is copy-pasteable; `check-snippets.sh` runs the no-AI ones on the fixture.
- Limitations section names what the lean plan does not ship: GitHub Action, remediation/fixes (FR-13, FR-14, Q), dependency CVE scanning (FR-7), HTML report.
- `demo.sh`: no-AI scan → fake/real provider scan → Docker evidence (skips with a message if Docker is absent).

Tests to write: `scripts/docs/check-snippets.sh` (shell, run in CI by `verify` is not required).

Acceptance criteria (all must pass):
- [ ] `bash scripts/docs/check-snippets.sh` → exit 0
- [ ] `bash scripts/demo/demo.sh --no-docker` → writes `report.md` with at least one finding
- [ ] [HUMAN] a team member who did not write it follows the guide from a clean machine
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: Action docs, architecture docs, release (stretch).

Handoff note for next agent: Update the Limitations section whenever a stretch order lands.
