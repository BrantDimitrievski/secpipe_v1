### WO-0.2: Turn on the end-to-end fixture scan as a required CI gate
Wave: 5   Depends on: WO-K.3, WO-K.4, WO-K.6, WO-K.8, WO-K.9, WO-K.11, WO-K.13, WO-G.4, WO-G.5, WO-R.1, WO-P.2, WO-L.1   Blocks: none
Milestone: Prototype 2 Dec 5
Traces: FR-1, FR-3, FR-4, FR-9, NFR-2 · A, F, I, P, S
Suggested human owner: Clinton Odinamadu (reviewer: Elaine Huang)
Size: S

Goal (2–3 sentences): From now on, every PR must pass a full `secpipe scan` of `fixtures/e2e/sqli-min`
with the fake LLM. The scan must produce validator-clean outputs and the exact expected verdict counts. This
is the always-on integration test from §3A.4.

Context to read first (exact paths only): `scripts/ci-fixture-scan.sh`, `.github/workflows/ci.yml`,
`fixtures/e2e/sqli-min/README.md`, `fixtures/e2e/sqli-min/expected.json`, `docs/PLAN.md` §4.3.

Files you own (create/modify — nothing else): `ci/fixture-scan.enabled`, `fixtures/e2e/sqli-min/llm/**`,
`fixtures/e2e/sqli-min/expected.json`, `secpipe-cli/src/test/java/ca/carleton/secpipe/cli/e2e/**`.

Files you may read but not modify: `scripts/ci-fixture-scan.sh`, `.github/workflows/ci.yml`, all module
sources.

Interfaces consumed: CLI contract, FakeLlmClient fixture format (WO-L.1)   Interfaces produced: required
e2e gate.

Implementation notes:
- `scripts/ci-fixture-scan.sh` (Part 13) checks for `ci/fixture-scan.enabled`. Creating that file flips the
  gate from "skipped" to required.
- Add a `fake_no_sandbox` key to `expected.json`:
  `{"confirmed":0,"needs_validation":1,"rejected":1,"terminal_state":"complete"}`. The script compares
  exactly these four keys.
- Write the fake-LLM fixture: the disprover returns a `confirmed` verdict for `LoginServlet.java`.
  `TagFilter.java` never reaches the disprover, because the deterministic refuter (WO-G.5) rejects it
  first. No other roles are needed (the hunter is skipped in `quick` when no units are planned).
- Expected without Docker (`--no-sandbox`): `LoginServlet` → `needs_validation`, with a blocker containing
  "sandbox" (the disprover could not refute it, but no regression test could run); `TagFilter` → `rejected`; validators pass; terminal state `complete`.
- Also add a JUnit e2e test (`-Pe2e`) running the same scan in-process via the picocli entry point.

Tests to write: `FixtureScanE2ETest` (in-process), asserting the counts, terminal state, a validator pass,
and that `secpipe.sarif` has 1 suppressed result.

Acceptance criteria (all must pass):
- [ ] `bash scripts/ci-fixture-scan.sh` → prints `fixture-scan: PASS (confirmed=0 needs_validation=1 rejected=1 complete)`
- [ ] `./mvnw -q -B -Pe2e -pl secpipe-cli -am verify` → exit 0
- [ ] `java -jar secpipe-cli/target/secpipe.jar validate findings secpipe-out-e2e/findings.json --root fixtures/e2e/sqli-min` → exit 0
- [ ] Breaking the fake disprover fixture (`sed -i 's/"confirmed"/"rejected"/' fixtures/e2e/sqli-min/llm/disprover-loginservlet.json`) makes `bash scripts/ci-fixture-scan.sh` exit non-zero (then revert)

Out of scope: Docker-backed evidence (WO-G.7 adds a `-Psandbox` variant); real providers.

Handoff note for next agent: When a later WO changes the expected verdicts on purpose (e.g. G.7 with Docker
in `-Psandbox`), it must own and update `expected.json` explicitly in its ownership list.
