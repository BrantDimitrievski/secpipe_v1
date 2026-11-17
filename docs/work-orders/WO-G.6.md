### WO-G.6: Implement the regression-test generator agent
Wave: 4   Depends on: WO-G.1   Blocks: WO-G.7, WO-M.2
Milestone: Prototype 2 Dec 5
Traces: FR-6 · A, F, Q
Suggested human owner: Elaine Huang (reviewer: Clinton Odinamadu)
Size: M

Goal (2–3 sentences): `TestGenAgent` produces one JUnit 5 class (`secpipe.generated…*SecurityTest`) that
fails on the vulnerable code by asserting on the observable sink input or effect, and passes after a
correct fix. It is built from the provisional record, the sink method source, and the involved classes'
signatures. It includes static pre-checks (package, class name, no network/process/sleep APIs) before
anything runs.

Context to read first (exact paths only): `docs/PLAN.md` Part 5 §A8; contracts `schemas/agent/testgen-result.schema.json`,
`agent/AgentOutputs.java` (TestGenResult); `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/runner/AgentRunner.java`; MVP `src/main/resources/knowledge/test-templates.json`
(payload lists only).

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/testgen/**`,
`secpipe-agents/src/main/resources/prompts/testgen.v1.txt`, `secpipe-agents/src/main/resources/testgen/payloads.json`,
`secpipe-agents/src/test/java/ca/carleton/secpipe/agents/testgen/**`, `secpipe-agents/src/test/resources/llm-fixtures/testgen/**`.

Files you may read but not modify: runner, disprover.

Interfaces consumed: `AgentRunner`   Interfaces produced: `TestGenAgent`, `TestGenContextBuilder`,
`GeneratedTestChecker.check(source) → List<Violation>` (static rules).

Implementation notes:
- **Static rules:** package must start `secpipe.generated`; exactly one top-level class matching the
  name; imports may not include `java.net`, `java.lang.ProcessBuilder` (except when CWE-78 and used only
  as a type in assertions), `Thread.sleep`, `System.exit`, or file writes outside
  `@TempDir`/`System.getProperty("java.io.tmpdir")`. Size ≤ 200 lines.
- **Test seams by CWE** (the prompt guidance):
  - CWE-89: a JDBC `Connection` proxy (`java.lang.reflect.Proxy`) capturing the SQL string;
  - CWE-78: inject a recording `ProcessLauncher` if the code allows it; otherwise assert on the command
    string built (when accessible);
  - CWE-22: `@TempDir` canary outside the base dir;
  - CWE-327/328: assert on the algorithm name passed to the factory.
- Port the payload lists from the MVP templates.
- In mode `none`: not invoked (see Part 5).

Tests to write: static checker table; fake agent output → `TestGenResult` → checker passes; a bad output
(uses `java.net.URL`) → violation → the runner treats it as a grounding failure (retry); context-builder
caps.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='TestGen*Test,GeneratedTestChecker*Test'` → ≥ 10 tests, 0 failures

Out of scope: running tests (G.7).

Handoff note for next agent: G.7 compiles and runs `TestGenResult.source` in the sandbox. Checker violations
must never reach the sandbox.
