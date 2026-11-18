### WO-K.5: Implement the ledger store state machine and ledger invariants
Wave: 3   Depends on: WO-K.1   Blocks: WO-K.9, WO-K.13
Milestone: Prototype 2 Dec 5
Traces: FR-15 · B, L, M
Suggested human owner: Abdul Babatunde (reviewer: Elodie Abdo)
Size: M

Goal (2–3 sentences): `LedgerStore` applies `LedgerUnit.Update`s through an explicit state machine and persists `coverage-ledger.json`. It also provides `LedgerInvariants`, the essential checks from the old WO-V.2 (§4.1 L1–L6, with L3 as the legal transitions), which K.13 injects into the engine as the ledger validator.

Context to read first (exact paths only): contracts `ledger/*.java`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/ledger/LedgerStore.java`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/engine/EngineValidators.java`; `docs/work-orders/stretch/WO-V.2.md` (invariants only); `secpipe-contracts/src/main/resources/schemas/coverage-ledger.schema.json`

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/ledger/**`, `secpipe-core/src/test/java/ca/carleton/secpipe/core/ledger/**`, `secpipe-core/src/test/resources/ca/carleton/secpipe/core/ledger/**`.

Files you may read but not modify: `core/engine/**`, contracts, validators.

Interfaces consumed: `LedgerUnit.Update`, `ValidationResult`   Interfaces produced: `LedgerStore.apply(List<LedgerUnit>, List<LedgerUnit.Update>) → List<LedgerUnit>` (signature unchanged from K.1); `LedgerTransitions` static builders; `LedgerInvariants.check(before, after) → ValidationResult`; `CoverageSummary.of(ledger)`.

Implementation notes:
- Transitions: exactly the allowed-transition table of `docs/PLAN.md` §4.1 rule **L3**, using the contract's `LedgerUnit.Status` names (`planned, in_progress, covered, candidate, blocked, deferred, out_of_scope, not_applicable`); anything else is rejected with code `L3`. There is no `unexamined` status; recon seeds units as `planned`. (Corrected in the WO-K.5 PR, approved by Brant after draft PR #112 was blocked on the old `unexamined → …` rule.)
- Empty list fields in an update keep the existing list, except where L2 requires the field to be empty for the new status (e.g. `covered` has no `result_fingerprints`); there they clear it.
- `LedgerInvariants` matches the `EngineValidators.ledgerTransition` `BiFunction` shape so K.13 can wire it directly.
- Invariants are tests, not a separate module: one test per L1–L6 rule, positive and negative.
- Persistence writes sorted by `coverage_id`, validated against the ledger schema.

Tests to write: `LedgerStoreTest` (every legal and illegal transition); `LedgerInvariantsTest` (L1–L6, one pass and one fail each); round-trip of `coverage-ledger.json`.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='Ledger*Test,CoverageSummary*Test'` → ≥ 14 tests, 0 failures
- [ ] A test asserts an illegal transition returns a failing `ValidationResult` naming the rule
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: incremental carry-forward (K.12, stretch), a standalone `LedgerValidator` in `secpipe-validators`.

Handoff note for next agent: K.13 wires `LedgerInvariants::check` into `EngineValidators`. There is no `secpipe-validators` ledger validator in the lean plan.
