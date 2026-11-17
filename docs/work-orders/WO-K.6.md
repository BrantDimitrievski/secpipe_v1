### WO-K.6: Implement the detect node — adapter discovery, parallel execution, ledger settlement
Wave: 3   Depends on: WO-P.2, WO-P.3, WO-K.7, WO-C.2   Blocks: WO-P.6, WO-P.7, WO-0.2, WO-M.1
Milestone: Prototype 2 Dec 5
Traces: FR-3, FR-5, NFR-2, NFR-3 · A, B, D
Suggested human owner: Brant Dimitrievski (reviewer: Abdul Babatunde)
Size: M

Goal (2–3 sentences): `DetectNode` discovers `AnalyzerAdapter`s via ServiceLoader and selects them by
config, profile, and availability. It runs them in parallel with per-tool timeouts, records `ToolRun`s,
adds candidates, and settles ledger units from coverage claims. It also provides `RunContext.Rescanner`
for the remediation loop.

Context to read first (exact paths only): contracts `spi/AnalyzerAdapter.java`, `pipeline/RunContext.java`;
MVP `src/main/java/ca/carleton/secpipe/tools/ToolRegistry.java`, `agents/AdapterRunner.java`;
contracts `ledger/LedgerUnit.java` (build `LedgerUnit.Update`s directly; `LedgerTransitions` from WO-K.5
lands in the same wave, so don't depend on it).

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/detect/**`,
`secpipe-core/src/test/java/ca/carleton/secpipe/core/detect/**`.

Files you may read but not modify: adapters, recon, ledger.

Interfaces consumed: `AnalyzerAdapter`, `RunContext`   Interfaces produced: `DetectNode`; `AdapterSelector.select(config,
profile, env) → List<Selection>` (used by doctor); `DefaultRescanner implements RunContext.Rescanner`.

Implementation notes:
- Thread pool sized `min(adapters, cores)`. A timeout cancels the future and records status `timeout`.
- An adapter exception → status `failed` with the message; the run continues.
- **Scope:** pass `diffScope` as `scopeFiles` (empty = all).
- **Settlement:** for each coverage claim (cwe, files), find the units whose CWE family matches and whose
  `starting_paths` ∩ files ≠ ∅. Units with candidates at a sink in those files → `candidate`, else
  `covered` with `local_checks = tool:ruleIds`.
- Candidates' `ledgerUnits` are filled with the matching unit ids.
- Requires-sandbox adapters receive `ctx.sandbox()`; if it's empty → skipped with reason "requires
  sandbox".

Tests to write: two fake adapters registered via test-scope `META-INF/services` (timing, failure, timeout);
selection rules (`--tools`, `--exclude-tools`, default per profile, opt-in); settlement produces
validator-clean updates; Rescanner returns candidates for a patched copy.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='DetectNode*Test,AdapterSelector*Test'` → ≥ 10 tests, 0 failures
- [ ] A test asserts a timed-out adapter yields `ToolRun.status=TIMEOUT` and the other adapter's candidates are still present

Out of scope: normalization (WO-K.8).

Handoff note for next agent: Candidates leave detect **un-fingerprinted**. Normalize is the only place
fingerprints are assigned.
