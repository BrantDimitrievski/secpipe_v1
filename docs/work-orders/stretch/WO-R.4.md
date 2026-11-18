### WO-R.4: Render patches and GitHub review suggestions from verified fixes
Wave: 9   Depends on: WO-M.1, WO-R.1   Blocks: WO-H.5
Milestone: Oral Jan 25
Traces: FR-13, FR-11 · Q, S
Suggested human owner: Abdul Babatunde (reviewer: Brant Dimitrievski)
Size: S

Goal (2–3 sentences): `PatchRenderer` writes `secpipe-fixes.patch`, concatenating **only** fixes with
`remediation_status=fixed`. It also writes `suggestions.json`: per changed hunk ≤ 30 lines, a GitHub review
suggestion (path, start_line, line, body with a ```suggestion block). Unverified attempts are never offered.

Context to read first (exact paths only): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/UnifiedDiff.java`; `secpipe-report/src/main/java/ca/carleton/secpipe/report/ReportRenderer.java`;
`docs/PLAN.md` ADR-07.

Files you own (create/modify — nothing else): `secpipe-report/src/main/java/ca/carleton/secpipe/report/patch/**`,
`secpipe-report/src/main/resources/META-INF/services/ca.carleton.secpipe.report.ReportRenderer` (append one
line), `secpipe-report/src/test/java/ca/carleton/secpipe/report/patch/**`.

Files you may read but not modify: agents remediation classes (copy the diff parsing into a small local
parser; `secpipe-report` must not depend on `secpipe-agents`).

Interfaces consumed: `ReportInputs` (records with `fix_attempts.patch_artifact`)   Interfaces produced:
`secpipe-fixes.patch`, `suggestions.json`.

Implementation notes: Suggestions use the **new** side of each hunk mapped to the PR's head lines. If the
target file isn't in the PR diff, the suggestion goes to the patch only.

Tests to write: only fixed attempts included; the hunk → suggestion mapping; the > 30-line hunk is excluded
from suggestions.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-report -am test -Dtest='Patch*Test'` → ≥ 5 tests, 0 failures

Out of scope: posting (H.5).

Handoff note for next agent: `suggestions.json` is the H.5 input.
