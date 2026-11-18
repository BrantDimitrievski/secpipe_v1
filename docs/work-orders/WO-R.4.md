### WO-R.4: Report remediation results and write the verified-fix patch
Wave: 10   Depends on: WO-M.1, WO-R.1   Blocks: none
Milestone: Oral Jan 25
Traces: FR-13, FR-11 · Q, S
Suggested human owner: Abdul Babatunde (reviewer: Brant Dimitrievski)
Size: S

Goal (2–3 sentences): `report.md` gets a Remediation section (outcome, attempts, adequacy, patch link per finding), and SARIF results carry `fixes[]` only for `remediation_status=fixed`. `secpipe-fixes.patch` concatenates only `fixed` patches. The attempt history in `findings.json` comes from the frozen `fix_attempts[]` field (already serialised by `FindingCodec`); this order only checks it is present.

Context to read first (exact paths only): `secpipe-report/src/main/java/ca/carleton/secpipe/report/MarkdownWriter.java`; `secpipe-report/src/main/java/ca/carleton/secpipe/report/SarifWriter.java`; `secpipe-report/src/main/java/ca/carleton/secpipe/report/ReportBundle.java`; contracts `finding/Finding.java` (`FixAttempt`); `docs/PLAN.md` ADR-07

Files you own (create/modify — nothing else): `secpipe-report/src/main/java/ca/carleton/secpipe/report/MarkdownWriter.java`, `secpipe-report/src/main/java/ca/carleton/secpipe/report/SarifWriter.java`, `secpipe-report/src/main/java/ca/carleton/secpipe/report/ReportBundle.java`, `secpipe-report/src/main/java/ca/carleton/secpipe/report/patch/**`, `secpipe-report/src/test/java/ca/carleton/secpipe/report/patch/**`, `secpipe-report/src/test/java/ca/carleton/secpipe/report/RemediationReport*Test.java`, `secpipe-report/src/test/resources/ca/carleton/secpipe/report/remediation/**`.

Files you may read but not modify: agents remediation classes (`secpipe-report` must not depend on `secpipe-agents`; use a small local diff parser if needed).

Interfaces consumed: `ReportInputs` (records with `fix_attempts[].patch_artifact`)   Interfaces produced: Remediation section in `report.md`, SARIF `fixes[]`, `secpipe-fixes.patch`.

Implementation notes:
- No `suggestions.json` (GitHub review suggestions need the stretch Action).
- Output is deterministic: records sorted by fingerprint, attempts by number; no `secpipe-fixes.patch` when nothing is `fixed`.
- `not_attempted` records show the reason (e.g. sandbox unavailable, `--enable-fixes false`).

Tests to write: only fixed attempts reach the patch and SARIF `fixes[]`; Markdown section for fixed / still_present / not_attempted; byte-stable output; `fix_attempts` round-trips in `findings.json`.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-report -am test -Dtest='Patch*Test,RemediationReport*Test'` → ≥ 6 tests, 0 failures
- [ ] A test validates the SARIF with `fixes[]` against `secpipe-report/src/test/resources/ca/carleton/secpipe/report/sarif-2.1.0-subset.schema.json` (via `SarifSchema`)
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: PR comments and suggestions (Action, stretch); HTML report.

Handoff note for next agent: E.6 links each scorecard row to the patch listed in the Remediation section.
