### WO-M.1: Implement the remediation loop node (apply → compile → retest → suite → rescan → classify → retry)
Wave: 9   Depends on: WO-G.10, WO-G.11, WO-K.15, WO-X.2, WO-K.6   Blocks: WO-M.2, WO-R.4, WO-E.6
Milestone: Oral Jan 25
Traces: FR-13, FR-14, NFR-5 · J, K, Q
Suggested human owner: Elodie Abdo (reviewer: Brant Dimitrievski)
Size: L

Goal (2–3 sentences): `RemediationLoopNode` (the frozen `remediate` node, after `score`) takes each `confirmed` record in a run with a usable sandbox and `--enable-fixes` ≠ `false`, and loops until `fixed` or `--max-fix-attempts` (default 3). Each attempt gets a patch from `RemediationAgent`, applies it to a fresh scratch copy, and verifies it offline in the sandbox and with the rescanner. Every attempt is recorded in `fix_attempts[]`; the final classification is `remediation_status`.

Context to read first (exact paths only): `docs/PLAN.md` Part 5 §A11, ADR-07 and "Re-plan 2026-11-18 (remediation)"; contracts `finding/Finding.java` (`FixAttempt`, `RemediationStatus`), `pipeline/RunContext.java` (`Rescanner`), `pipeline/PipelineGraph.java`; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/{RemediationAgent,PatchChecker,UnifiedDiff}.java`; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/evidence/TestEvidenceNode.java`; `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/MavenWorkspace.java`

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/RemediationLoopNode.java`, `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/SuiteBaseline.java`, `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/FixClassifier.java`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/remediation/{RemediationLoopNode,RemediationLoopSandboxIT,SuiteBaseline,FixClassifier}*.java`, `secpipe-agents/src/test/resources/llm-fixtures/remediation-loop/**`, `fixtures/remediation/**`.

Files you may read but not modify: the G.10 remediation files, evidence, testgen, sandbox, core detect.

Interfaces consumed: `RemediationAgent`, `PatchChecker`, `UnifiedDiff`, `SandboxRunner`, `MavenWorkspace`, `ArtifactPromoter`, `RunContext.Rescanner`   Interfaces produced: `RemediationLoopNode`; patches at `<outDir>/patches/<fingerprint-short>-attempt<N>.patch`.

Implementation notes:
- **Baseline once per run** (`SuiteBaseline`): passing project test ids + the detect fingerprint set, kept in the work directory (not run metadata). Prefetch once per run (X.2); every attempt runs offline (`NETWORK_NONE`) through the existing sandbox path only.
- **Per attempt:** 1 fresh NIO copy of the original source (no attempt sees another's edits); 2 `PatchChecker` + `UnifiedDiff` dry-run then apply, else `refused`; 3 offline compile, else `build_broken`; 4 regression test must pass, else `still_present`; 5 full suite ⊇ baseline, else `tests_broken`; 6 `ctx.rescanner().rescan(copy, touchedFiles)` (WO-K.15): original fingerprint still present → `still_present`, a new fingerprint → `newly_introduced_findings` plus a new `needs_validation` record (blocker `introduced by candidate fix <patch>; not validated`); 7 all pass → `fixed` and the patch is promoted. Failing step + trimmed log go into the next attempt.
- No usable sandbox, or a record not `confirmed` → `remediation_status=not_attempted` (attempt outcome `sandbox_unavailable` where an attempt was planned).
- Every agent call counts against the LLM budget; every sandbox run (compile, regression, suite) counts against `--max-sandbox-runs`. Budget exhausted → the record keeps its status and the run notes it. Never touch the user's working tree; never push.
- Fixture `fixtures/remediation/sqli-fixable`: a copy of sqli-min with a small JUnit suite, plus `expected-remediation.json` (CWE-89 → PreparedStatement, `fixed` in ≤ 2 attempts).

Tests to write: unit with fake sandbox + fake rescanner + fake LLM: fixed first try; fixed on retry; exhausted; broken build; tests broken; introduced finding; refused patch; no sandbox → not_attempted; target directory byte-identical before/after. `@Tag("sandbox")` `RemediationLoopSandboxIT` on `fixtures/remediation/sqli-fixable`.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='RemediationLoop*Test,FixClassifier*Test,SuiteBaseline*Test'` → ≥ 10 tests, 0 failures
- [ ] A test asserts the original target directory is byte-identical before and after the node runs
- [ ] [DOCKER] `./mvnw -q -B -Psandbox -pl secpipe-agents -am verify -Dtest='RemediationLoopSandboxIT'` → matches `expected-remediation.json` and the patch file exists (paste the log)
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Session split (L-size; same branch, one PR): **Session A** — `SuiteBaseline` and `FixClassifier` with unit tests, and the `sqli-fixable` fixture. Commit and push; stop. **Session B** (fresh session, same kickoff + "continue WO-M.1 part B") — `RemediationLoopNode` (attempt loop, rescan, newly-introduced records, patches, budgets) and the sandbox IT. Then run all acceptance commands and open the PR.

Out of scope: test adequacy (M.2); GitHub delivery (`--enable-fixes pr`, stretch); dependency upgrades.

Handoff note for next agent: M.2 inserts the adequacy call after step 6 in `RemediationLoopNode` (M.2 owns that edit in wave 10).
