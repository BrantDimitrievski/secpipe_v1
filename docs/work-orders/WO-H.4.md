### WO-H.4: Persist and restore run state for incremental Action scans
Wave: 10   Depends on: WO-K.12, WO-H.1   Blocks: WO-E.7
Milestone: Oral Jan 25
Traces: NFR-2 · M
Suggested human owner: Brant Dimitrievski (reviewer: Clinton Odinamadu)
Size: S

Goal (2–3 sentences): Make `.secpipe-state/last` (findings, ledger, metadata, file hashes) round-trip
through `actions/cache` per branch, with a fallback to the base branch's latest state. `run-scan.sh` passes
it as `--prior-run` only after `secpipe validate` succeeds on it (untrusted cache contents).

Context to read first (exact paths only): `action.yml` (cache steps); `scripts/action/run-scan.sh`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/incremental/CarryForward.java`.

Files you own (create/modify — nothing else): `scripts/action/state.sh`, `scripts/action/test/state.test.sh`.

Files you may read but not modify: `run-scan.sh` (H.3 owns it in wave 9; in this wave you may add **one**
line sourcing `state.sh` — listed here as owned: `scripts/action/run-scan.sh`, the single-line change).

Interfaces consumed: `--prior-run`   Interfaces produced: state layout v1.

Implementation notes: Validate the prior files (`secpipe validate findings|ledger|run`); on failure, ignore
them with a note. Cap the state size at 20 MiB.

Tests to write: valid state passed; invalid state ignored; size cap.

Acceptance criteria (all must pass):
- [ ] `bash scripts/action/test/state.test.sh` → `ALL PASS`
- [ ] The self-test (H.6) second run shows `prior_run.carried_forward ≥ 1` in `run-metadata.json` (verified when H.6 merges; note it in the PR)

Out of scope: cross-repo state.

Handoff note for next agent: E.7 uses two consecutive Action runs to measure the incremental gain.
