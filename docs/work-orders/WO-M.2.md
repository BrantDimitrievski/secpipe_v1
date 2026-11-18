### WO-M.2: Implement test adequacy (FR-15) with JaCoCo on changed lines
Wave: 11   Depends on: WO-M.1, WO-M.3, WO-X.1, WO-G.6   Blocks: WO-E.6
Milestone: Oral Jan 25
Traces: FR-15, FR-6 · B, Q
Suggested human owner: Elaine Huang (reviewer: Elodie Abdo)
Size: M

Goal (2–3 sentences): After a candidate fix passes steps 4–6, run the regression test on the patched copy with the JaCoCo agent and compute coverage of the patch's changed lines. Every changed line covered → adequate; otherwise one extra testgen call targets the uncovered lines and steps 4–6 rerun. Still not adequate → record the limitation in `fix_attempts[].adequacy`; the fix is not failed.

Context to read first (exact paths only): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/RemediationLoopNode.java`; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/testgen/TestGenAgent.java`; `sandbox-images/runner/Dockerfile`; contracts `finding/Finding.java` (`Adequacy`)

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/AdequacyCheck.java`, `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/JacocoXml.java`, `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/RemediationLoopNode.java` (insert the adequacy call), `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/remediation/{AdequacyCheck,JacocoXml}*Test.java`, `secpipe-agents/src/test/resources/ca/carleton/secpipe/agents/remediation/jacoco/**`, `sandbox-images/runner/**`. Also (ownership extended by coordinator 2026-11-18: WO-M.3 review follow-ups): `secpipe-agents/src/main/resources/prompts/testgen.v1.txt`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/remediation/RemediationAgentTestgenPromptTest.java`, the wave-table rows in `docs/PLAN.md`, and this file (the ownership note).

Files you may read but not modify: testgen, sandbox, the other remediation files.

Interfaces consumed: `SandboxRunner`, `TestGenAgent`, `UnifiedDiff`   Interfaces produced: `AdequacyCheck.measure(...) → Adequacy`.

Implementation notes:
- JaCoCo is not in the runner image: add pinned JaCoCo agent + CLI jars to `sandbox-images/runner/` with their sha256 files in the repo (same pattern as `junit-platform-console-standalone.sha256`), installed under `/opt/jacoco/`.
- Run with `-javaagent:/opt/jacoco/jacocoagent.jar=destfile=/scratch/jacoco.exec`, then `jacococli.jar report … --xml`, inside the sandbox (offline); promote the XML via `ArtifactPromoter`.
- Changed lines = added/modified lines on the new side of the diff, production files only.
- The extra testgen call counts against the LLM budget and its sandbox runs against `--max-sandbox-runs`.

Tests to write: JaCoCo XML parse; diff-line mapping; adequate / inadequate decisions; extra-testgen path (fake LLM, fake sandbox); limitation recorded without failing the attempt.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='AdequacyCheck*Test,JacocoXml*Test,RemediationLoop*Test'` → ≥ 8 new tests, 0 failures
- [ ] [DOCKER] `docker build sandbox-images/runner` succeeds and the jar sha256 checks pass
- [ ] [DOCKER] `./mvnw -q -B -Psandbox -pl secpipe-agents -am verify -Dtest='RemediationLoopSandboxIT'` → the fixed record has `adequacy.adequate=true` (paste the log)
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: mutation testing.

Handoff note for next agent: R.4 shows "adequacy limited" when `adequate=false`; E.6 tabulates it.
