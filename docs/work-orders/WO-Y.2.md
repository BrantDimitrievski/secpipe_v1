### WO-Y.2: Harden secret handling and redact logs
Wave: 8   Depends on: WO-L.8, WO-K.1, WO-X.1   Blocks: WO-Y.3
Milestone: Oral Jan 25
Traces: NFR-5 · (§2.6, §2.7 item 7)
Suggested human owner: Abdul Babatunde (reviewer: Elodie Abdo)
Size: S

Goal (2–3 sentences): Add redaction to `DefaultRunLog` (using `Redactor`), plus a `SecretLeakAssertions` test
helper. A system test plants a fake key in the env and in a fixture file, runs a full fake-LLM scan with
the sandbox disabled and enabled, and greps **every** output file, log, prompt dump, and sandbox env file
for the key.

Context to read first (exact paths only): `secpipe-core/src/main/java/ca/carleton/secpipe/core/engine/DefaultRunLog.java`;
`secpipe-llm/src/main/java/ca/carleton/secpipe/llm/privacy/Redactor.java`; `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/EnvAllowlist.java`.

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/engine/DefaultRunLog.java`,
`secpipe-core/src/test/java/ca/carleton/secpipe/core/engine/DefaultRunLogTest.java`,
`secpipe-cli/src/test/java/ca/carleton/secpipe/cli/security/SecretLeakIT.java`,
`secpipe-validators/src/main/java/ca/carleton/secpipe/validators/testing/SecretLeakAssertions.java` (main
scope, no JUnit dependency: it throws `AssertionError`, so every module's tests can use it).

Files you may read but not modify: llm, sandbox.

Interfaces consumed: `Redactor`   Interfaces produced: `SecretLeakAssertions.assertNoSecret(Path dir, String secret)`.

Implementation notes: `secpipe-core` may not depend on `secpipe-llm`. Copy `Redactor`'s pattern list into
`core/engine` as a private table, with a test that keeps both lists equal (read the llm resource from the
test classpath).

Tests to write: log redaction; the full leak IT (fake LLM, `--print-prompts` and normal); the sandbox env file
check (`@Tag("sandbox")` variant).

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -Pe2e -pl secpipe-cli -am test -Dtest=SecretLeakIT` → 0 failures
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest=DefaultRunLogTest` → ≥ 4 tests, 0 failures

Out of scope: secret scanning of targets.

Handoff note for next agent: The `SecretLeakAssertions` helper is available to all later tests.
