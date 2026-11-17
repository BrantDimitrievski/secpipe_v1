### WO-G.10: Implement the remediation agent (smallest effective fix)
Wave: 7   Depends on: WO-G.7   Blocks: WO-M.1
Milestone: Oral Jan 25
Traces: FR-11, FR-13 · Q
Suggested human owner: Brant Dimitrievski (reviewer: Elodie Abdo)
Size: M

Goal (2–3 sentences): `RemediationAgent` produces a unified diff (≤ 5 files) implementing the invariant
at the last trusted decision point for one confirmed record, using the failing regression test and any
previous failed attempts. `PatchChecker` statically validates the diff: it applies cleanly to a copy, only
touches files in the trace or the build file for dependency fixes, and has no test deletions or
suppressions.

Context to read first (exact paths only): `docs/PLAN.md` Part 5 §A10, §2.2-Q; contracts `schemas/agent/remediation-result.schema.json`;
`secpipe-agents/src/main/java/ca/carleton/secpipe/agents/runner/AgentRunner.java`; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/evidence/TestEvidenceNode.java`.

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/RemediationAgent.java`,
`secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/RemediationContextBuilder.java`, `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/PatchChecker.java`, `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/UnifiedDiff.java`,
`secpipe-agents/src/main/resources/prompts/remediation.v1.txt`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/remediation/{RemediationAgent,PatchChecker,UnifiedDiff}*Test.java`,
`secpipe-agents/src/test/resources/llm-fixtures/remediation/**`.

Files you may read but not modify: evidence, runner.

Interfaces consumed: `AgentRunner`   Interfaces produced: `RemediationAgent`, `PatchChecker.check(diff, allowedFiles,
copyRoot)`, `UnifiedDiff.apply(Path root, String diff)` (pure Java, no `patch` binary).

Implementation notes:
- **Forbidden in patches:** deleting or modifying files under `src/test/`; `@SuppressWarnings`;
  `catch (Exception e) {}`; removing the sink without preserving the functionality (heuristic: the method
  body must not shrink by > 80 % → flag).
- **Dependency fixes** change only the version element in `pom.xml` to `fixed_version`.
- `UnifiedDiff` must handle CRLF files (Windows targets).

Tests to write: diff parse/apply (incl. CRLF, multiple hunks, fuzz 0); checker rejections; fake agent →
PreparedStatement fix for LoginServlet applies cleanly to the fixture copy.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='RemediationAgent*Test,PatchChecker*Test,UnifiedDiff*Test'` → ≥ 12 tests, 0 failures

Out of scope: running anything (M.1).

Handoff note for next agent: M.1 calls `RemediationAgent` per attempt with the history of previous attempts.
