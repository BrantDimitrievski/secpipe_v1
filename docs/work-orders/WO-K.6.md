### WO-K.6: Implement the detect node — adapter discovery, parallel execution, ledger settlement
Wave: 3   Depends on: WO-P.2, WO-C.2   Blocks: WO-P.6, WO-K.13, WO-K.15, WO-M.1
Milestone: Prototype 2 Dec 5
Traces: FR-3, NFR-2, NFR-3 · A, B, D
Suggested human owner: Brant Dimitrievski (reviewer: Abdul Babatunde)
Size: M

Goal (2–3 sentences): `DetectNode` discovers `AnalyzerAdapter`s via ServiceLoader, selects them by `--tools`/`--exclude-tools` and availability, and runs them in parallel with per-tool timeouts. It records `ToolRun`s, adds un-fingerprinted candidates, and settles ledger units from coverage claims.

Context to read first (exact paths only): contracts `spi/AnalyzerAdapter.java`, `pipeline/RunContext.java`, `ledger/LedgerUnit.java`; MVP `src/main/java/ca/carleton/secpipe/tools/ToolRegistry.java`, `src/main/java/ca/carleton/secpipe/agents/AdapterRunner.java`

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/detect/**`, `secpipe-core/src/test/java/ca/carleton/secpipe/core/detect/**`.

Files you may read but not modify: adapters, recon, ledger.

Interfaces consumed: `AnalyzerAdapter`, `RunContext`   Interfaces produced: `DetectNode`; `AdapterSelector.select(config, env) → List<Selection>`.

Implementation notes:
- Thread pool `min(adapters, cores)`. A timeout cancels the future and records status `timeout`; an exception → `failed`; the run continues.
- Settlement: for each coverage claim (cwe, files), units whose family matches and whose paths intersect → `candidate` if a candidate sits there, else `covered`.
- Build `LedgerUnit.Update`s directly (`LedgerTransitions` lands in the same wave).
- Requires-sandbox adapters with no `ctx.sandbox()` are skipped with reason "requires sandbox".
- Dropped: Rescanner (remediation is stretch), profiles, opt-in tool classes.

Tests to write: two fake adapters via test-scope `META-INF/services` (timing, failure, timeout); selection flags; settlement yields valid updates.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='DetectNode*Test,AdapterSelector*Test'` → ≥ 8 tests, 0 failures
- [ ] A test asserts a timed-out adapter yields `ToolRun.status=TIMEOUT` and the other adapter's candidates are still present
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: normalization (K.8), SARIF ingest (K.7, stretch).

Handoff note for next agent: Candidates leave detect **un-fingerprinted**. Normalize is the only place fingerprints are assigned.
