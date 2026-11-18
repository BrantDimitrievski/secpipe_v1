### WO-K.13: Build the CLI, setup, and pipeline assembly; make the e2e fixture scan a CI test
Wave: 6   Depends on: WO-K.1, WO-C.4, WO-V.1, WO-K.4, WO-K.5, WO-K.6, WO-K.8, WO-K.9, WO-G.5, WO-G.4, WO-R.1, WO-L.1   Blocks: WO-D.1, WO-K.15
Milestone: Prototype 2 Dec 5
Traces: FR-1, FR-4, NFR-1, NFR-2, NFR-6 · A, P, S
Suggested human owner: Abdul Babatunde (reviewer: Clinton Odinamadu)
Size: M

Goal (2–3 sentences): `secpipe scan` (picocli) resolves config and environment (setup, folded from K.3), assembles the real pipeline through `PipelineAssembly`, and writes `findings.json`, `coverage-ledger.json`, `run-metadata.json`, SARIF and `report.md`. The e2e fixture scan (folded from 0.2) becomes a normal test run by `./mvnw verify`. When it produces the same report as the demo slice, delete `cli/demo/**`.

Context to read first (exact paths only): `docs/PLAN.md` §4.3 (frozen flags and exit codes); contracts `config/ConfigResolver.java`, `pipeline/PipelineGraph.java`; `secpipe-cli/src/main/java/ca/carleton/secpipe/cli/demo/DemoScan.java`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/engine/PlainGraphRunner.java`; `scripts/ci-fixture-scan.sh`

Files you own (create/modify — nothing else): `secpipe-cli/**`, `secpipe-core/src/main/java/ca/carleton/secpipe/core/setup/**`, `secpipe-core/src/test/java/ca/carleton/secpipe/core/setup/**`, `fixtures/e2e/sqli-min/expected.json`, `fixtures/e2e/sqli-min/llm/**`, `ci/fixture-scan.enabled`.

Files you may read but not modify: all modules.

Interfaces consumed: everything public   Interfaces produced: the `secpipe` CLI (`scan`, `--help`, `--version`); `PipelineAssembly.build(config) → (GraphRunner, RunContext, initial state)`; `SetupNode`.

Implementation notes:
- Implement only the §4.3 flags that the lean pipeline uses; flag names and exit codes stay exactly as frozen.
- Wire `EngineValidators(FindingsValidator, LedgerInvariants::check)`, `LlmClients.assemble(...)`, and the sandbox (absent → evidence stays `needs_validation`).
- E2E test (`cli/e2e/**`): scan `fixtures/e2e/sqli-min` with `FakeLlmClient`, no Docker, compare `findings.json` to `expected.json` (fingerprints + statuses).
- Create `ci/fixture-scan.enabled` so `scripts/ci-fixture-scan.sh` runs; errors follow the one-line problem + `hint:` style.
- Delete `cli/demo/**` and its tests in the same PR once the e2e report matches the demo's `report.md` findings.

Tests to write: `ScanCommandTest` (flags, exit codes), `SetupNodeTest` (config + env resolution), `E2eFixtureScanTest`, a `SecretLeakAssertions` check on all outputs.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-cli -am test -Dtest='ScanCommand*Test,SetupNode*Test,E2eFixtureScan*Test'` → ≥ 10 tests, 0 failures
- [ ] `bash scripts/ci-fixture-scan.sh` → green (not "skipped")
- [ ] `git ls-files secpipe-cli/src | grep -c /demo/` → `0`
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: `doctor`, `diff`, install scripts, Action wiring (stretch).

Handoff note for next agent: G.7 lands in the same wave: with no Docker, the evidence node must leave the e2e `findings.json` unchanged (survivors stay `needs_validation`).
