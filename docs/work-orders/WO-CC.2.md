### WO-CC.2: Add the SourceIndex, Location and ReconFacts contracts
Wave: 3   Depends on: WO-C.2   Blocks: WO-K.4, WO-G.1, WO-G.4, WO-G.5
Milestone: Prototype 2 Dec 5
Traces: FR-3, FR-5 · B, C
Suggested human owner: Brant Dimitrievski (reviewer: Abdul Babatunde)
Size: S

Goal (2–3 sentences): The lean re-plan moved WO-V.3 (which defined `SourceIndex`) to stretch, but WO-K.4,
WO-G.1, WO-G.4 and WO-G.5 still consume `SourceIndex`, `Location` and `ReconFacts`, and `secpipe-agents` cannot
depend on `secpipe-core`. Add these as frozen contracts so recon (core) can produce them and agents can read them.

Context to read first (exact paths only): `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/spi/AnalyzerAdapter.java`;
`contracts/ledger/LedgerUnit.java`; `contracts/finding/Finding.java` (existing location/trace shapes);
`contracts/json/ContractJson.java`; `docs/work-orders/WO-K.4.md`; `docs/work-orders/WO-G.4.md`.

Files you own (create/modify — nothing else):
- `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/spi/SourceIndex.java`;
- `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/recon/{Location,ReconFacts,package-info}.java`;
- `secpipe-contracts/src/main/resources/schemas/recon-facts.schema.json`;
- `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/json/ReconFactsCodec.java` and its test + examples under `secpipe-contracts/src/test/**/recon/**`;
- `docs/work-orders/WO-CC.2.md` (this file, verbatim) and `docs/work-orders/WO-K.4.md` (text fixes only, see notes);
- `docs/PLAN.md` ("Migration notes" appendix entry only).

Interfaces consumed: none   Interfaces produced: `SourceIndex`, `Location`, `ReconFacts`, `ReconFactsCodec`, `recon-facts.schema.json`.

Implementation notes:
- `SourceIndex` (read-only, repo-relative POSIX paths, no `..`): `List<String> files()` (sorted), `boolean exists(String path)`, `Optional<List<String>> lines(String path)`, `Optional<String> line(String path, int oneBasedLine)`. Never throws on a missing file; rejects absolute or escaping paths with `IllegalArgumentException`.
- `Location` record: `String file`, `int line` (≥ 1), `Optional<String> scope` (`Class#method(ParamTypes)`); compact constructor validates.
- `ReconFacts` record: `List<Location> entrySurfaces`, `Map<String, List<Location>> sinksByFamily` (key = CWE family root id, e.g. `CWE-89`); immutable copies, sorted (file, line, scope) for byte-stable output.
- `ReconFactsCodec`: strict read/write via `ContractJson.mapper()`; sorted keys and lists; validates against `recon-facts.schema.json` on read. Reuse existing codec patterns (strict, no repair).
- `WO-K.4.md` fixes: seed ledger units as `planned` (not `unexamined`); `CoverageIds` path is `contracts/ids/CoverageIds.java`; recon is PLAN §A2; consume `SourceIndex`/`ReconFacts`/`Location` from contracts and implement `FileSourceIndex implements SourceIndex` in `core/recon`; Depends on adds WO-CC.2.

Tests to write: `ReconFactsCodecTest` (round trip byte-stable, strict rejects unknown/null, schema-valid examples); `LocationTest` (validation).

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-contracts -am test -Dtest='ReconFacts*Test,Location*Test'` → ≥ 6 tests, 0 failures
- [ ] `git grep -n unexamined -- docs/work-orders/WO-K.4.md` → no output
- [ ] `python3 -B scripts/check-plan.py` → `errors=0`
- [ ] `./mvnw -q -B verify` → exit 0 (CI green)

Out of scope: `FileSourceIndex` implementation (WO-K.4); grounding logic (WO-G.1).

Handoff note for next agent: K.4 implements `FileSourceIndex` and writes `recon-facts.json` with `ReconFactsCodec`; agents read `ReconFacts` via the codec.
