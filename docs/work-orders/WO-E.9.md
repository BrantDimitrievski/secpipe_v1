### WO-E.9: Add the portability CI matrix and the 12-check checklist
Wave: 12   Depends on: WO-H.6, WO-K.13   Blocks: WO-D.4
Milestone: Final Feb 20
Traces: NFR-6, NFR-1 · (evaluation §2.8 item 9)
Suggested human owner: Elaine Huang (reviewer: Abdul Babatunde)
Size: M

Goal (2–3 sentences): `.github/workflows/portability.yml` runs build, tests, and the no-AI fixture scan on
`ubuntu-latest`, `macos-latest`, and `windows-latest` (JDK 21), with paths containing spaces. The proposal's
12-check checklist is automated where possible (`scripts/eval/portability-checklist.{sh,ps1}`), with manual
steps recorded per OS in `docs/eval/portability.md`.

Context to read first (exact paths only): `.github/workflows/ci.yml`; proposal §3.5 checklist items (build, test execution, env
checks, scanning, report generation, generated tests, exit codes, error handling, paths with spaces, remediation verification —
expanded to 12 in `docs/eval/portability.md`).

Files you own (create/modify — nothing else): `.github/workflows/portability.yml`, `scripts/eval/portability-checklist.sh`,
`scripts/eval/portability-checklist.ps1`, `docs/eval/portability.md`.

Files you may read but not modify: everything else.

Interfaces consumed: the CLI   Interfaces produced: the matrix and the checklist results.

Implementation notes:
- Windows/macOS runners lack Linux Docker on hosted runners. Sandbox checks there are **manual on team
  laptops** (Docker Desktop), recorded in the doc.
- **The 12 checks:**
  1. JDK detection;
  2. build;
  3. unit tests;
  4. doctor;
  5. no-AI scan;
  6. report HTML opens;
  7. SARIF valid;
  8. exit codes;
  9. error hint on a bad path;
  10. path with spaces;
  11. generated test runs in the sandbox;
  12. remediation verification (sandbox).

Tests to write: the scripts themselves (each check prints PASS/FAIL).

Acceptance criteria (all must pass):
- [ ] `actionlint .github/workflows/portability.yml` → exit 0
- [ ] [HUMAN] The latest `portability` run on `main` → success on all 3 OSes (link in the PR)
- [ ] `docs/eval/portability.md` has a 12 × 3 table with PASS/FAIL/manual and dates

Out of scope: ARM Windows.

Handoff note for next agent: none.
