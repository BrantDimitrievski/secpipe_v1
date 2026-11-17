### WO-K.5: Implement the ledger store state machine and persistence
Wave: 3   Depends on: WO-V.2, WO-K.1   Blocks: WO-K.11, WO-G.2, WO-K.12
Milestone: Prototype 2 Dec 5
Traces: FR-15 · B, L, M
Suggested human owner: Abdul Babatunde (reviewer: Elodie Abdo)
Size: M

Goal (2–3 sentences): Replace the minimal `LedgerStore` with the full state machine. It provides
transition helpers (`assign`, `settle`, `complete`, `block`, `reassign`, `defer`), append-only attempt
archiving on reassignment, sorted persistence to `<out>/coverage-ledger.json`, and a coverage summary
(counts per status, per CWE) for reports and FR-15.

Context to read first (exact paths only): `secpipe-core/src/main/java/ca/carleton/secpipe/core/ledger/LedgerStore.java`
(K.1 version), `secpipe-core/src/main/java/ca/carleton/secpipe/core/engine/DeltaApplier.java`, contracts
`ledger/LedgerUnit.java`, `secpipe-validators/src/main/java/ca/carleton/secpipe/validators/LedgerValidator.java`, `docs/PLAN.md` §4.1 L1–L6.

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/ledger/**`,
`secpipe-core/src/test/java/ca/carleton/secpipe/core/ledger/**`.

Files you may read but not modify: `core/engine/**`.

Interfaces consumed: `LedgerUnit.Update`, `LedgerValidator`   Interfaces produced: `LedgerStore.apply(List<LedgerUnit>,
List<LedgerUnit.Update>) → List<LedgerUnit>` (signature unchanged from K.1), `LedgerTransitions` static
builders for `Update`s, `CoverageSummary.of(ledger)`.

Implementation notes:
- `reassign` archives the current attempt with `archived_reason`, resets the unit to `planned` with
  `agent_id=null`, and clears the arrays.
- `settle(unit, tool, reviewedPaths, ruleIds, fingerprints)` → `covered` if no fingerprints, else
  `candidate`, and adds the tool to `settled_by`.
- Merging two settlements on the same unit unions everything.
- `apply` must call `LedgerValidator.validateTransition` and throw `DeltaRejectedException` with the errors.

Tests to write: each helper produces a validator-clean unit; reassignment archives; double settlement
unions; an invalid transition throws; the summary counts.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='LedgerStore*Test,LedgerTransitions*Test,CoverageSummary*Test'` → ≥ 12 tests, 0 failures
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='PlainGraphRunner*Test'` still passes (no engine regression)

Out of scope: prior-run carry-forward (WO-K.12).

Handoff note for next agent: Nodes build `Update`s with `LedgerTransitions`. Never hand-craft status
strings.
