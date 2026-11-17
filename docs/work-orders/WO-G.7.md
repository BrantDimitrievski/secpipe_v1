### WO-G.7: Implement the test-evidence node — run generated tests in the sandbox and decide the verdict
Wave: 5   Depends on: WO-G.4, WO-G.6, WO-X.1, WO-X.2, WO-X.3   Blocks: WO-G.8, WO-G.10, WO-M.1, WO-E.6
Milestone: Prototype 2 Dec 5
Traces: FR-6, FR-12, NFR-5 · F, J, K
Suggested human owner: Elodie Abdo (reviewer: Elaine Huang)
Size: L

Goal (2–3 sentences): `TestEvidenceNode` turns provisional records into final verdicts. For each one
(priority order, `max-sandbox-runs`), it generates a test (G.6), compiles and runs it in the sandbox
against the offline-built target, three times. A consistent failure with the expected assertion →
`confirmed`, with `observed_result` citing the `te-…` id. Pass, error, flaky, or blocked → `needs_validation`
with the exact blocker.

Context to read first (exact paths only): `docs/PLAN.md` ADR-03, ADR-05, §4.1 F3; `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/{DockerSandboxRunner,MavenWorkspace,ArtifactPromoter}.java`;
`secpipe-agents/src/main/java/ca/carleton/secpipe/agents/testgen/TestGenAgent.java`; contracts `pipeline/PipelineState.java` (`survivorDrafts`).

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/evidence/**`,
`secpipe-agents/src/test/java/ca/carleton/secpipe/agents/evidence/**`, `secpipe-agents/src/test/resources/llm-fixtures/evidence/**`,
`fixtures/e2e/sqli-min/expected-sandbox.json` (new; the Docker-backed expectations).

Files you may read but not modify: sandbox, testgen, disprover.

Interfaces consumed: `SandboxRunner`, `MavenWorkspace` (X.2), `ArtifactPromoter` (X.3), `TestGenAgent`   Interfaces produced:
`TestEvidenceNode`, `TestEvidenceId.of(fingerprint, phase, attempt)` (`te-<12 hex>`).

Implementation notes:
- **Per record:**
  1. `MavenWorkspace.prepare(target)` (prefetch once per run, cached).
  2. Write the test to scratch `src/test/java/secpipe/generated/...`.
  3. Run the sandbox command `mvn -o -q compile dependency:build-classpath -Dmdep.outputFile=/scratch/cp.txt`.
  4. Compile the test with `javac -cp $(cat cp.txt):/opt/junit/junit-platform-console-standalone.jar`.
  5. Run `java -jar /opt/junit/...standalone.jar --class-path … --select-class … --reports-dir
     /scratch/reports`.
  6. Promote `reports/TEST-*.xml` (≤ 1 MiB).
- **Decision:**
  - all 3 runs fail with an `AssertionFailedError` mentioning the payload or expected marker →
    `failed_as_expected` → **confirmed**;
  - mixed results → `flaky`;
  - compile errors (after 1 regenerate) → `compile_error`;
  - pass → `passed` (the disprover's survivor isn't demonstrated → needs_validation "regression test did
    not reproduce").
- **No sandbox / refused** → needs_validation with the blocker `"sandbox unavailable: <missing controls>"`.
- The **confirmed record** is assembled from the provisional draft plus `execution` (payloads from
  TestGenResult, instructions = run command summary, observed_result = `"<te-id>: <assertion message
  excerpt>"`) plus `remediation.strategy` from the candidate's `smallestFix` or the knowledge-base
  default.

Tests to write: (unit, no Docker) decision table using a fake `SandboxRunner` returning recorded JUnit XML;
blocker texts; budget for sandbox runs. (`@Tag("sandbox")`) a real run on `fixtures/e2e/sqli-min`
LoginServlet with a fake testgen fixture → confirmed.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='TestEvidence*Test'` → ≥ 10 tests, 0 failures (no Docker)
- [ ] [DOCKER] `./mvnw -q -B -Psandbox -pl secpipe-agents -am verify -Dtest='TestEvidenceSandboxIT'` → LoginServlet record is `confirmed` with `test_evidence[0].outcome=failed_as_expected` (run on a machine with Docker; paste the log)
- [ ] A test asserts the FindingsValidator F3 passes on every confirmed record the node produces

Session split (L-size; same branch, one PR): **Session A** — Decision logic with a fake `SandboxRunner` and recorded JUnit XML; confirmed-record assembly; blocker texts. Commit and push; stop. **Session B** (fresh session, same kickoff + "continue <WO-ID> part B") — Real sandbox wiring (`MavenWorkspace`, promotion, `javac`/console runner) and `TestEvidenceSandboxIT`. Then run all acceptance commands and open the PR.

Out of scope: fixes (M.1).

Handoff note for next agent: The regression test file is promoted to `<out>/generated-tests/` and reused by
the remediation loop as the "must now pass" test.
