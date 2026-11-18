### WO-G.10: Implement the remediation agent (smallest effective fix as a checked unified diff)
Wave: 8   Depends on: WO-G.7, WO-G.1   Blocks: WO-M.1
Milestone: Oral Jan 25
Traces: FR-11, FR-13 · Q
Suggested human owner: Brant Dimitrievski (reviewer: Elodie Abdo)
Size: M

Goal (2–3 sentences): `RemediationAgent` returns a unified diff (≤ 5 files) that fixes one `confirmed` record at the last trusted decision point; the agent writes nothing itself. `PatchChecker` statically validates the diff and `UnifiedDiff` (pure Java, no `patch`/git binary) dry-runs and applies it to a scratch copy. On retry the agent sees the previous patches with the failing step and trimmed logs.

Context to read first (exact paths only): `docs/PLAN.md` Part 5 §A10 and "Re-plan 2026-11-18 (remediation)"; contracts `schemas/agent/remediation-result.schema.json`, `agent/AgentOutputs.java` (`RemediationResult`); `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/runner/AgentRunner.java`; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/prompt/DefaultPromptRenderer.java`; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/testgen/TestGenAgent.java`

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/RemediationAgent.java`, `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/RemediationContextBuilder.java`, `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/PatchChecker.java`, `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/UnifiedDiff.java`, `secpipe-agents/src/main/resources/prompts/remediation.v1.md`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/remediation/{RemediationAgent,RemediationContextBuilder,PatchChecker,UnifiedDiff}*Test.java`, `secpipe-agents/src/test/resources/llm-fixtures/remediation/**`.

Files you may read but not modify: runner, prompt, evidence, testgen.

Interfaces consumed: `AgentRunner`, `PromptRenderer`   Interfaces produced: `RemediationAgent.propose(record, regressionTest, previousAttempts)`, `PatchChecker.check(diff, repoRoot, regressionTestPath)`, `UnifiedDiff.dryRun(Path root, String diff)` / `UnifiedDiff.apply(Path root, String diff)`.

Implementation notes:
- Fresh agent per attempt through `AgentRunner` (fresh id, no history, no tools, repo text only via `PromptRenderer.untrusted(...)`, schema-validated and grounded output). Malformed output is re-run at most `AgentRunner.MAX_ATTEMPTS` (3) times, never repaired.
- Inputs: the confirmed record, the failing regression test source, the enclosing methods of the trace, and on retry the previous patches with failing step + trimmed log.
- Prompt `remediation.v1.md` (not `.txt`: `DefaultPromptRenderer.sha256` resolves `.md`). Constraints: smallest fix at the last trusted decision point, keep public API, no new dependencies, no catch-all suppression, do not edit the regression test.
- `PatchChecker` rejects: > 5 files, paths outside the repo (incl. `..`/absolute), the regression test, build files (`pom.xml`, `mvnw`, `.mvn/**`), files under `src/test/`, `@SuppressWarnings`, empty catch blocks. `UnifiedDiff` handles CRLF and multiple hunks, fuzz 0.

Tests to write: diff parse/dry-run/apply (CRLF, multi-hunk, mismatched context); every checker rejection; fake-LLM fixtures `valid`, `malformed`, `forbidden-path`, `test-file-edit`, `outage` (→ no patch, reason recorded); the valid fixture yields a PreparedStatement fix that applies cleanly to a copy of `fixtures/e2e/sqli-min`.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='RemediationAgent*Test,RemediationContextBuilder*Test,PatchChecker*Test,UnifiedDiff*Test'` → ≥ 12 tests, 0 failures
- [ ] A test asserts no API key appears in the rendered prompt or the outcome (`SecretLeakAssertions`)
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: running anything (M.1); dependency upgrades.

Handoff note for next agent: M.1 calls `RemediationAgent.propose` once per attempt and applies the result with `UnifiedDiff` only after `PatchChecker` passes; a checker rejection is outcome `refused`.
