### WO-G.6: Implement the regression-test generator agent
Wave: 5   Depends on: WO-G.1   Blocks: WO-G.7, WO-G.11, WO-M.2
Milestone: Prototype 2 Dec 5
Traces: FR-6 · A, F
Suggested human owner: Elaine Huang (reviewer: Clinton Odinamadu)
Size: M

Goal (2–3 sentences): `TestGenAgent` writes one JUnit 5 test per survivor that fails while the vulnerability exists (exploit payload reaches the sink). `GeneratedTestChecker` statically rejects tests that use network, files outside scratch, reflection on SecPipe, or `System.exit`.

Context to read first (exact paths only): `docs/PLAN.md` Part 5 §A9; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/runner/AgentRunner.java`; contracts `agents/TestGenResult.java`; `fixtures/e2e/sqli-min/`

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/testgen/**`, `secpipe-agents/src/main/resources/prompts/testgen.v1.txt`, `secpipe-agents/src/main/resources/testgen/**`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/testgen/**`, `secpipe-agents/src/test/resources/llm-fixtures/testgen/**`.

Files you may read but not modify: runner, disprover.

Interfaces consumed: `AgentRunner`, survivor drafts   Interfaces produced: `TestGenAgent`, `TestGenContextBuilder`, `GeneratedTestChecker.check(source) → List<Violation>`.

Implementation notes:
- Payload hints per family come from `testgen/payloads.json`.
- A test that fails `GeneratedTestChecker` is treated as malformed: fresh re-run, never edited.
- The test must assert on observable sink behaviour (e.g. a recording stub), not on log text.

Tests to write: fake-LLM fixtures (valid test, forbidden API → re-run, malformed → dropped); `GeneratedTestCheckerTest` per rule.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='TestGen*Test,GeneratedTestChecker*Test'` → ≥ 8 tests, 0 failures
- [ ] A test asserts a generated test using `java.net` is rejected
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: running the tests (G.7), test adequacy (M.2, stretch).

Handoff note for next agent: G.7 compiles and runs the source in the sandbox.
