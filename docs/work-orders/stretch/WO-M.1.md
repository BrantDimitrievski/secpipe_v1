### WO-M.1: Implement the remediation loop node (apply → retest → rescan → classify → retry)
Wave: 8   Depends on: WO-G.10, WO-G.7, WO-K.6, WO-X.2   Blocks: WO-M.2, WO-R.4, WO-E.6, WO-H.5
Milestone: Oral Jan 25
Traces: FR-13, FR-14, NFR-5 · J, K, Q
Suggested human owner: Elodie Abdo (reviewer: Brant Dimitrievski)
Size: L

Goal (2–3 sentences): For each `confirmed` record (priority order, `--enable-fixes` ≠ false, budget
permitting), `RemediationLoopNode` gets a patch, applies it to a fresh scratch copy, and runs the
regression test (must pass) and the full test suite (no new failures vs baseline) in the sandbox. It then
rescans the patched copy via `RunContext.Rescanner`: the original fingerprint must be gone, with no new
fingerprints. The record is classified fixed / still present / newly introduced, retrying up to
`max-fix-attempts`.

Context to read first (exact paths only): `docs/PLAN.md` Part 5 §A11, ADR-07; contracts `finding/Finding.java`
(`FixAttempt`), `pipeline/RunContext.java` (`Rescanner`); `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/{RemediationAgent,PatchChecker,UnifiedDiff}.java`;
`secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/MavenWorkspace.java`.

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/RemediationLoopNode.java`,
`secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/SuiteBaseline.java`, `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/FixClassifier.java`,
`secpipe-agents/src/test/java/ca/carleton/secpipe/agents/remediation/{RemediationLoopNode,SuiteBaseline,FixClassifier}*Test.java`,
`fixtures/remediation/**`.

Files you may read but not modify: the other remediation files (G.10), evidence, sandbox.

Interfaces consumed: `RemediationAgent`, `SandboxRunner`, `MavenWorkspace`, `Rescanner`   Interfaces produced:
`RemediationLoopNode`; patches written to `<out>/patches/<fingerprint-short>-attempt<N>.patch`.

Implementation notes:
- **Suite baseline:** run the project's full test suite once on the unpatched copy (sandbox, offline) and
  record the passing test ids. "suite_passed" = every baseline-passing test still passes.
- **Newly introduced:** any fingerprint in the rescan that wasn't in the pre-fix candidates for the touched
  files. These become new records with `remediation_status=newly_introduced` (`needs_validation`, blocker
  "introduced by candidate fix <patch>; not validated").
- **Status per attempt:**
  - `fixed` only if the regression test passes AND the suite passed AND the rescan is clean AND there are
    no new fingerprints;
  - otherwise `still_present` / `newly_introduced_findings` / `tests_broken` / `build_broken` / `refused`.
- **Retry:** the next attempt includes the previous patch, its outcome, and the trimmed failing output.
- The record's `remediation_status` is the final classification; `fix_attempts` holds every attempt.
- **Never** modify the user's working tree; never push.

Tests to write:
- unit with fake sandbox + fake rescanner: fixed on the first attempt; still present then fixed on the
  second; introduces a new finding; breaks the suite; max attempts reached;
- `@Tag("sandbox")` IT on `fixtures/remediation/sqli-fixable` (the team writes a copy of the sqli-min
  fixture with a JUnit suite) → `fixed`.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='RemediationLoop*Test,FixClassifier*Test,SuiteBaseline*Test'` → ≥ 10 tests, 0 failures
- [ ] [DOCKER] `./mvnw -q -B -Psandbox -pl secpipe-agents -am verify -Dtest='RemediationLoopSandboxIT'` → record `remediation_status=fixed`, `fix_attempts[-1]` all-true flags, the patch file exists (paste the log)
- [ ] A test asserts the original target directory is byte-identical before and after the node runs

Session split (L-size; same branch, one PR): **Session A** — `SuiteBaseline` and `FixClassifier` with unit tests. Commit and push; stop. **Session B** (fresh session, same kickoff + "continue <WO-ID> part B") — `RemediationLoopNode` (attempt loop, rescan, newly-introduced records, patches) and the sandbox IT. Then run all acceptance commands and open the PR.

Out of scope: adequacy (M.2), delivery to GitHub (H.5).

Handoff note for next agent: M.2 adds the adequacy object to each attempt via `AdequacyCheck`, called from
this node (M.2 owns that call-site edit in wave 9).
