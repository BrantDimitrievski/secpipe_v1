### WO-K.4: Implement recon (entry surfaces, sinks) and seed the coverage ledger
Wave: 3   Depends on: WO-K.1, WO-C.2   Blocks: WO-G.4, WO-G.5, WO-K.13
Milestone: Prototype 2 Dec 5
Traces: FR-3, FR-15 · B, C, N
Suggested human owner: Brant Dimitrievski (reviewer: Clinton Odinamadu)
Size: M

Goal (2–3 sentences): `ReconNode` finds entry surfaces (servlet/Spring handlers) and sinks for the five Benchmark CWE families (22, 78, 89, 327, 328) with a JavaParser walk and a sink catalogue. It seeds one `LedgerUnit` per (family, entry surface) using `CoverageIds`, and exposes `FileSourceIndex` so later nodes can read source lines.

Context to read first (exact paths only): contracts `ledger/LedgerUnit.java`, `ledger/CoverageIds.java`, `spi/SourceIndex.java`, `pipeline/RunContext.java`; `fixtures/mvp-samples/shop-samples/`; `docs/PLAN.md` Part 5 §A1

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/recon/**`, `secpipe-core/src/main/resources/recon/**`, `secpipe-core/src/test/java/ca/carleton/secpipe/core/recon/**`, `secpipe-core/src/test/resources/ca/carleton/secpipe/core/recon/**`.

Files you may read but not modify: contracts, `core/engine/**`.

Interfaces consumed: `CoverageIds`, `SourceIndex`, `RunContext`   Interfaces produced: `ReconNode`; `ReconFacts` (entry surfaces with `Location`, sinks by family) persisted as `<work>/recon-facts.json`; `ReconFacts.load(workDir)`; `FileSourceIndex implements SourceIndex`.

Implementation notes:
- Sink catalogue is JSON under `recon/`, one entry per (family, class, method, tainted-arg index).
- Build `LedgerUnit`s directly (state `unexamined`); output sorted by `coverage_id`.
- Files that do not parse are recorded as recon notes, never a crash.
- Deterministic: no LLM, no target-code execution.
- Dropped from the old K.4: boundary controls, auth hints, framework plug-ins (stretch).

Tests to write: `ReconNodeTest` on `shop-samples` (expected sinks per family); `FileSourceIndexTest`; seeding produces unique, sorted coverage ids.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='Recon*Test,FileSourceIndex*Test'` → ≥ 8 tests, 0 failures
- [ ] A test asserts `recon-facts.json` is byte-identical across two runs on `shop-samples`
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: hunter agents, architecture summary, non-Java languages.

Handoff note for next agent: G.4 and G.5 read `ReconFacts` and `FileSourceIndex`; K.13 puts `ReconNode` before detect.
