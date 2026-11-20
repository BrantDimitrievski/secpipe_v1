### WO-R.6: Remediation report follow-ups (stop note, sandbox_unavailable fixture, one verified-patch rule, official SARIF schema)
Wave: 13   Depends on: WO-R.4, WO-M.2   Blocks: none
Milestone: Oral Jan 25
Traces: FR-13 · Q, S
Suggested human owner: Elaine Huang (reviewer: Brant Dimitrievski)
Size: S

Goal (2–3 sentences): Close the minor findings from the WO-R.4 review. The Markdown Remediation section must show why a record's loop stopped, a `sandbox_unavailable` outcome must have a golden fixture, and the three outputs that list "verified" fixes (report.md, SARIF `fixes[]`, `secpipe-fixes.patch`) must use one shared rule. SARIF tests switch from the hand-written subset schema to the official OASIS SARIF 2.1.0 schema.

Context to read first (exact paths only): `secpipe-report/src/main/java/ca/carleton/secpipe/report/MarkdownWriter.java`; `secpipe-report/src/main/java/ca/carleton/secpipe/report/SarifWriter.java`; `secpipe-report/src/main/java/ca/carleton/secpipe/report/patch/FixedPatches.java`; `secpipe-report/src/test/java/ca/carleton/secpipe/report/SarifSchema.java`; `secpipe-report/src/test/java/ca/carleton/secpipe/report/RemediationReportTest.java`; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/RemediationLoopNode.java` (the run notes it writes, read only); `docs/work-orders/WO-R.4.md`.

Files you own (create/modify — nothing else): `secpipe-report/src/main/java/ca/carleton/secpipe/report/**`, `secpipe-report/src/test/**`, `THIRD_PARTY_NOTICES.md` (one entry for the vendored SARIF schema), `docs/work-orders/WO-R.6.md` (this file, verbatim).

Files you may read but not modify: everything else, in particular `secpipe-agents/**` (the loop's note texts are its contract with this module) and `secpipe-contracts/**`.

Interfaces consumed: `Finding.FixAttempt`, `RunMetadata.notes`, the run-note prefixes written by `RemediationLoopNode`   Interfaces produced: one package-private "verified fix" predicate used by all three writers.

Implementation notes:
- Stop note: `RemediationLoopNode` writes run notes `remediation of <fp> stopped at attempt <n>: <failing step>` and `remediation of <fp> stopped: <reason>`. For each confirmed record the Remediation section prints a `- Stopped: <text after "stopped">` line when such a note exists. Match on the exact fingerprint prefix; never on a substring of another fingerprint.
- `sandbox_unavailable`: add a golden case where the last attempt's outcome is `sandbox_unavailable` (status `not_attempted` or `still_present` as the loop writes it). Check it renders "sandbox unavailable" honestly, never "fixed", and that no patch from it reaches SARIF `fixes[]` or `secpipe-fixes.patch`.
- One rule: a fix is "verified" only when the record is `confirmed`, `remediation_status=fixed`, the **last** attempt's outcome is `fixed`, and its patch artifact exists under `outDir/patches/`. Put this in one place (e.g. `patch/VerifiedFix.java` or inside `FixedPatches`) and call it from `MarkdownWriter`, `SarifWriter` and the patch writer. Delete the duplicated checks. Add a test where `remediation_status=fixed` but the last attempt is `still_present`: no writer calls it verified.
- Official schema: copy `sarif-2.1/schema/sarif-schema-2.1.0.json` from `oasis-tcs/sarif-spec` commit `1742a253eb654bf8a4ed7c60eaef7412b2c23553` (sha256 `c3b4bb2d6093897483348925aaa73af03b3e3f4bd4ca38cef26dcb4212a2682e`; a copy is at `/home/claude/ref/sarif-schema-2.1.0.json` in the coordinator workspace, or `git clone --depth 1 https://github.com/oasis-tcs/sarif-spec`) to `secpipe-report/src/test/resources/ca/carleton/secpipe/report/sarif-schema-2.1.0.json`, byte-identical. It is draft-04: use networknt's draft-4 support, already on the test classpath. Remove the subset schema. All existing golden SARIF files must validate. If one does not, fix the writer, not the schema, and note it in the PR. Add the THIRD_PARTY_NOTICES entry (OASIS, source URL, commit, used only in tests).

Tests to write: stop note rendered for both note shapes, and absent for a record with no note; `sandbox_unavailable` golden; the "fixed status, last attempt not fixed" case for all three writers; every golden `.sarif` validates against the official schema; a test that the vendored schema's sha256 equals the pinned value.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-report -am test` → 0 failures, ≥ 5 new tests
- [ ] `sha256sum secpipe-report/src/test/resources/ca/carleton/secpipe/report/sarif-schema-2.1.0.json` → `c3b4bb2d6093897483348925aaa73af03b3e3f4bd4ca38cef26dcb4212a2682e`
- [ ] `python3 -B scripts/check-plan.py` → `errors=0`
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` and `sandbox` green)

Out of scope: changing run-note texts (agents); new report sections; HTML report; scorecard logic (WO-M.4).

Handoff note for next agent: WO-D.6 (demo kit) quotes the Remediation section; the "Stopped:" line is what the audience sees when a fix fails.
