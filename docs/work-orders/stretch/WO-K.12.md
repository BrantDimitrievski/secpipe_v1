### WO-K.12: Implement incremental runs (prior-run carry-forward) and `secpipe diff`
Wave: 9   Depends on: WO-K.3, WO-K.5, WO-K.8, WO-G.4   Blocks: WO-H.4, WO-E.7
Milestone: Oral Jan 25
Traces: FR-14, NFR-2 · M, B
Suggested human owner: Brant Dimitrievski (reviewer: Elodie Abdo)
Size: M

Goal (2–3 sentences): Implement feature M. Unchanged-source prior `confirmed` records are carried forward
and re-verified. Changed source triggers revalidation units. Prior `rejected` records suppress only the
unchanged claim, never coverage. Prior `needs_validation`/`deferred`/`blocked` become current work. Also
add `BaselineDiff` (fixed / still present / newly introduced) for `secpipe diff`, redesigned from the MVP.

Context to read first (exact paths only): Cloudflare reference text in `docs/PLAN.md` §2.2-M;
`secpipe-core/src/main/java/ca/carleton/secpipe/core/setup/PriorRunLoader.java`, `core/recon/ReconNode.java`,
`core/ledger/LedgerTransitions.java`; MVP `src/main/java/ca/carleton/secpipe/core/BaselineDiff.java`.

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/incremental/**`,
`secpipe-core/src/test/java/ca/carleton/secpipe/core/incremental/**`,
`secpipe-core/src/main/java/ca/carleton/secpipe/core/recon/ReconNode.java` (add the `CarryForward.plan`
call after seeding), `secpipe-core/src/main/java/ca/carleton/secpipe/core/normalize/NormalizeNode.java` (add
the unchanged-rejection suppression). No other WO in wave 9 touches these.

Files you may read but not modify: setup, ledger, the rest of recon and normalize.

Interfaces consumed: `PipelineState.PriorRun`   Interfaces produced: `CarryForward.plan(prior, currentHashes,
currentLedger) → CarryForwardPlan` (unit `prior_status` updates + carried records as `needs_validation`
"carried forward; awaiting re-verification" + suppression set); `BaselineDiff.compare(baselineFindings,
currentFindings)`.

Implementation notes:
- **"Unchanged source"** = the SHA-256 of every file in the record's trace and evidence is equal.
- **Carried `confirmed`:** link it to a current planned unit with `prior_confirmed_same_source`. The
  disprover re-verifies it (it is put into candidates with origin `carried_forward`), and test evidence
  re-runs if budget allows.
- **Suppressed rejected claims:** normalize drops a candidate whose fingerprint matches an unchanged
  rejected record. It is re-recorded as `rejected` with reason "unchanged since run X: <prior reason>".
- **MVP baseline import:** `BaselineDiff` accepts the MVP `report.json` by recomputing fingerprints from
  `file` + `snippet` (best effort; documented).

Tests to write: each prior status path; a changed file flips to revalidation; rejected suppression only
when unchanged; the diff categories; `secpipe diff` CLI-level test via `BaselineDiff` (the CLI wiring
reads it).

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='CarryForward*Test,BaselineDiff*Test'` → ≥ 12 tests, 0 failures
- [ ] A test with prior = run A and current = A + one modified file asserts exactly the records touching that file get `prior_confirmed_changed_source` units

Out of scope: Action cache plumbing (WO-H.4).

Handoff note for next agent: `metadata.prior_run` counts come from `CarryForwardPlan`; the report shows
them in the coverage summary.
