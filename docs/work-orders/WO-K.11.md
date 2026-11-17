### WO-K.11: Implement finalize — validators, terminal state, metadata freeze
Wave: 4   Depends on: WO-V.1, WO-V.2, WO-K.5, WO-K.10   Blocks: WO-0.2, WO-R.2
Milestone: Prototype 2 Dec 5
Traces: FR-4, NFR-2 · P, I, O
Suggested human owner: Abdul Babatunde (reviewer: Brant Dimitrievski)
Size: S

Goal (2–3 sentences): `FinalizeNode` runs the findings and ledger validators plus `validateTerminal`, and
counts records and units. It sets `terminal_state` to `complete` only if everything passes and every
candidate has a final record. Otherwise it sets `incomplete` with an **exact** reason (a concatenation of
every cause), and builds the final `RunMetadata`.

Context to read first (exact paths only): `docs/PLAN.md` §2.2-P, §4.1 L6; contracts `run/RunMetadata.java`,
`pipeline/PipelineState.java`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/budget/CallBudget.java`.

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/finalize/**`,
`secpipe-core/src/test/java/ca/carleton/secpipe/core/finalize/**`.

Files you may read but not modify: validators, ledger, budget.

Interfaces consumed: validators, `CoverageSummary`, `CallBudget.snapshot`   Interfaces produced:
`FinalizeNode`, `RunMetadataBuilder` (used by report).

Implementation notes:
- **Incomplete causes, in order:**
  - prior `incompleteReason`s from nodes;
  - `budget exhausted after <n> calls; <k> candidates validated in fingerprint order; <m> units
    unassigned`;
  - `unassigned ledger units: n`;
  - `candidates without final disposition: n`;
  - validator failures (first 3 errors).
- **Every candidate must have a record:** if one is missing, emit a `needs_validation` with blocker
  "pipeline ended before validation (reason: …)". Never drop candidates (feature O).

Tests to write: a clean state → complete; each cause → incomplete with the exact text; a candidate without
a record gets a needs_validation; the metadata validates against `run-metadata.schema.json`.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='FinalizeNode*Test'` → ≥ 8 tests, 0 failures
- [ ] A test asserts `RunMetadata` with `COMPLETE` never has `incompleteReason`, and its JSON validates against the schema (`allOf` rules)

Out of scope: writing files (report).

Handoff note for next agent: The report header text for incomplete runs is `incomplete_reason` verbatim.
