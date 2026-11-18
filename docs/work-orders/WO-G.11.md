### WO-G.11: Tighten the test evidence (confirm only on the intended failure)
Wave: 8   Depends on: WO-G.7, WO-G.6   Blocks: WO-M.1
Milestone: Oral Jan 25
Traces: FR-12, FR-6 · F
Suggested human owner: Elodie Abdo (reviewer: Elaine Huang)
Size: S

Goal (2–3 sentences): Fix-up of G.7 so remediation starts from trustworthy evidence. Setup checks in generated tests become JUnit assumptions (testgen prompt guidance + `GeneratedTestChecker`), and a JUnit `<failure>` confirms only when its message matches testgen's `expected_failure`. Any other failure leaves the record `needs_validation` with an exact blocker.

Context to read first (exact paths only): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/evidence/TestEvidenceNode.java`; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/evidence/RegressionTestRun.java`; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/testgen/GeneratedTestChecker.java`; `secpipe-agents/src/main/resources/prompts/testgen.v1.txt`; contracts `schemas/agent/testgen-result.schema.json` (`expected_failure`)

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/evidence/**`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/evidence/**`, `secpipe-agents/src/test/resources/llm-fixtures/evidence/**`, `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/testgen/**`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/testgen/**`, `secpipe-agents/src/test/resources/llm-fixtures/testgen/**`, `secpipe-agents/src/main/resources/prompts/testgen.v1.txt`.

Files you may read but not modify: sandbox, runner, disprover, contracts.

Interfaces consumed: `SandboxRunner`, `TestGenAgent`   Interfaces produced: unchanged `TestEvidenceNode`; `GeneratedTestChecker` rule "setup checks use `Assumptions`".

Implementation notes:
- A failed assumption (JUnit `<skipped>`) is `needs_validation`, blocker `setup assumption failed: <message>`.
- `<failure>` whose message does not contain the `expected_failure` text → `needs_validation`, blocker `unexpected failure: <first line>`; `<error>` never confirms.
- The matching is deterministic string containment after whitespace normalisation; no LLM call.
- Changing `testgen.v1.txt` changes its prompt sha256; update the affected fixtures you own.

Tests to write: expected failure → confirmed; other failure → needs_validation with the exact blocker; assumption skipped; checker rejects `assert`/`if…fail` setup checks and accepts `Assumptions.assumeTrue`.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='TestEvidence*Test,GeneratedTestChecker*Test'` → ≥ 6 new tests, 0 failures
- [ ] [DOCKER] `./mvnw -q -B -Psandbox -pl secpipe-agents -am verify` → the sqli-min finding is still `confirmed` as in `fixtures/e2e/sqli-min/expected-sandbox.json`
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: remediation (G.10/M.1).

Handoff note for next agent: M.1's step 4 uses the same expected-failure match to decide that the regression test now passes for the right reason.
