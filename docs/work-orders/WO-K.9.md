### WO-K.9: Implement deterministic scoring and finalize (validators, terminal state, metadata freeze)
Wave: 4   Depends on: WO-P.2, WO-V.1, WO-K.5   Blocks: WO-K.13
Milestone: Prototype 2 Dec 5
Traces: FR-8, FR-10, FR-4, NFR-2 · H, P
Suggested human owner: Elodie Abdo (reviewer: Elaine Huang)
Size: M

Goal (2–3 sentences): `ScoringNode` sets severity from knowledge-base impact anchors with caps, and computes `SeveritySummary` (FR-10). `FinalizeNode` (folded from K.11) runs the findings validator and ledger invariants one last time, sets the terminal run state, and freezes `run-metadata.json`.

Context to read first (exact paths only): `docs/PLAN.md` Part 5 §A8; `docs/work-orders/stretch/WO-K.11.md`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/knowledge/`; `secpipe-validators/src/main/java/ca/carleton/secpipe/validators/FindingsValidator.java`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/ledger/LedgerInvariants.java`; contracts `pipeline/RunMetadata.java`

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/scoring/**`, `secpipe-core/src/test/java/ca/carleton/secpipe/core/scoring/**`, `secpipe-core/src/main/java/ca/carleton/secpipe/core/finalize/**`, `secpipe-core/src/test/java/ca/carleton/secpipe/core/finalize/**`.

Files you may read but not modify: knowledge, validators, ledger.

Interfaces consumed: `KnowledgeBase.impactClass`, `FindingsValidator`, `LedgerInvariants`   Interfaces produced: `ScoringNode`; `ScoringPolicy`; `SeveritySummary.of(records)`; `FinalizeNode` (`requiresLlm()==false`).

Implementation notes:
- Severity: impact anchor per family, capped to `medium` when status is `needs_validation`.
- Finalize: a validator failure becomes `incomplete_reason`, never a crash; the terminal state is written once.
- Use `RunContext.clock()` for the end time; output sorted by fingerprint.
- Dropped: confidence model tuning, budget summaries (budget lives in L.1 usage).

Tests to write: `ScoringPolicyTest` (anchor + cap per family), `SeveritySummaryTest`, `FinalizeNodeTest` (valid run, validator failure → incomplete).

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='Scoring*Test,SeveritySummary*Test,Finalize*Test'` → ≥ 10 tests, 0 failures
- [ ] A test asserts a findings-validator failure sets `incomplete_reason` and still writes `run-metadata.json`
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: HTML/PR comment rendering.

Handoff note for next agent: K.13 places scoring before finalize; finalize is the last node before report.
