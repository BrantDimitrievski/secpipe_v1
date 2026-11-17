### WO-V.2: Implement the ledger validator (invariants L1–L6, transitions)
Wave: 2   Depends on: WO-C.2   Blocks: WO-K.4, WO-K.5, WO-K.13, WO-K.11
Milestone: Prototype 2 Dec 5
Traces: FR-15 · B, I, P
Suggested human owner: Abdul Babatunde (reviewer: Elodie Abdo)
Size: M

Goal (2–3 sentences): `LedgerValidator` enforces the ledger schema, the status invariants, id
recomputation, sort order, the append-only attempts rule, and allowed transitions between two ledger
versions. These are adapted from Cloudflare's `validate-coverage-ledger.cjs` rules. The engine runs it after
every ledger update.

Context to read first (exact paths only): `docs/PLAN.md` §4.1 (L1–L6); `secpipe-contracts/src/main/resources/schemas/ledger.schema.json`;
`secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/ledger/LedgerUnit.java`; `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/ids/CoverageIds.java`.

Files you own (create/modify — nothing else): `secpipe-validators/src/main/java/ca/carleton/secpipe/validators/LedgerValidator.java`,
`secpipe-validators/src/test/java/ca/carleton/secpipe/validators/LedgerValidatorTest.java`,
`secpipe-validators/src/test/resources/ca/carleton/secpipe/validators/ledger/**`.

Files you may read but not modify: `SchemaRegistry.java`, `ValidationResult.java`, `ValidationError.java`
(WO-C.1).

Interfaces consumed: `CoverageIds`, `LedgerCodec`   Interfaces produced: `LedgerValidator.validate(List<LedgerUnit>)`,
`validateTransition(List<LedgerUnit> prev, List<LedgerUnit> next)`, `validateTerminal(List<LedgerUnit>)` (L6).

Implementation notes:
- Limits: 5 MiB, 10,000 units, 1,000 entries per nested array.
- The transition check is per unit by `coverage_id`. A removed unit is an error. A new unit must be
  `planned`, or a settled status with `settled_by`.

Tests to write: each invariant row (8 statuses × pass/fail); each allowed and disallowed transition;
attempts prefix; id mismatch; unsorted input; duplicate semantic tuple.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-validators -am test -Dtest=LedgerValidatorTest` → ≥ 30 tests, 0 failures
- [ ] `examples/ledger-valid.json` fails **only** on L1 (its id is a placeholder), and the test asserts exactly that error code

Out of scope: applying updates (WO-K.5).

Handoff note for next agent: `validateTransition` is what makes "the ledger is the coverage claim"
trustworthy. Never bypass it.
