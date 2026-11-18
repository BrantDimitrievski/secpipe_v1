### WO-G.7: Implement the test-evidence node — run generated tests in the sandbox and decide the verdict
Wave: 6   Depends on: WO-G.4, WO-G.6, WO-X.1, WO-X.2   Blocks: WO-D.1
Milestone: Prototype 2 Dec 5
Traces: FR-6, FR-12, NFR-5 · F, J, K
Suggested human owner: Elodie Abdo (reviewer: Elaine Huang)
Size: M

Goal (2–3 sentences): `TestEvidenceNode` prefetches the target, runs each generated test offline in the sandbox, and promotes the JUnit report. A test that fails on the vulnerable code confirms the finding; anything else (pass, compile error, refused control, prefetch incomplete) leaves it `needs_validation` with the reason.

Context to read first (exact paths only): contracts `spi/SandboxRunner.java`, `findings/TestEvidence.java`; `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/MavenWorkspace.java`; `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/ArtifactPromoter.java`; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/testgen/TestGenAgent.java`

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/evidence/**`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/evidence/**`, `secpipe-agents/src/test/resources/llm-fixtures/evidence/**`, `fixtures/e2e/sqli-min/expected-sandbox.json`.

Files you may read but not modify: sandbox, testgen, disprover.

Interfaces consumed: `SandboxRunner`, `MavenWorkspace`, `ArtifactPromoter`, `TestGenAgent`   Interfaces produced: `TestEvidenceNode`; `TestEvidenceId.of(fingerprint, phase, attempt)`.

Implementation notes:
- All target code runs through `SandboxRunner.run(...)` with `NETWORK_NONE`; no sandbox → `needs_validation` blocker "sandbox unavailable" (keeps the K.13 e2e unchanged).
- Exit 137 / resource limit → `needs_validation` ("resource limit"), never `confirmed`.
- Only a failing assertion inside the generated test confirms; compile errors and timeouts never do.

Tests to write: unit tests with a fake `SandboxRunner` (fail → confirmed, pass → needs_validation, compile error, refused, prefetch-incomplete); `[DOCKER]` `@Tag("sandbox")` test on `fixtures/e2e/sqli-min`.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='TestEvidence*Test'` → ≥ 8 tests, 0 failures
- [ ] [DOCKER] `./mvnw -q -B -Psandbox -pl secpipe-agents -am verify` → the sqli-min finding is `confirmed` as in `expected-sandbox.json`
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: remediation (stretch).

Handoff note for next agent: With the sandbox, survivors become `confirmed`; D.1's demo script shows this path.
