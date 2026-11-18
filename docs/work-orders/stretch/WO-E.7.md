### WO-E.7: Measure run-to-run variance and incremental coverage gain
Wave: 13   Depends on: WO-E.2, WO-K.12, WO-G.3, WO-H.4   Blocks: WO-D.4
Milestone: Final Feb 20
Traces: NFR-4, NFR-2 · M, L, (evaluation §2.8 item 7)
Suggested human owner: Abdul Babatunde (reviewer: Clinton Odinamadu)
Size: S

Goal (2–3 sentences): Run the same input 5× per AI arm and report the mean/stdev of TPR, FPR, and verdict
flips per fingerprint. Measure the incremental gain: run 1 vs run 2 with `--prior-run` (carried-forward
count, new coverage units, time saved) and coverage growth per critic wave.

Context to read first (exact paths only): `scripts/eval/ablation.sh`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/incremental/CarryForward.java`.

Files you own (create/modify — nothing else): `scripts/eval/variance.sh`, `secpipe-eval/src/main/java/ca/carleton/secpipe/eval/variance/**`,
`secpipe-eval/src/test/java/ca/carleton/secpipe/eval/variance/**`, `docs/eval/variance.md`.

Files you may read but not modify: everything else.

Interfaces consumed: scorecards, run metadata   Interfaces produced: `variance.md`.

Implementation notes: Use the 400-case sample. Report "flip rate" = the fraction of fingerprints whose
verdict differs across runs.

Tests to write: statistics on fixture scorecards; flip-rate computation.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-eval -am test -Dtest='Variance*Test'` → ≥ 3 tests, 0 failures
- [ ] `docs/eval/variance.md` has a 5-run table per arm and an incremental table (run1 vs run2)

Out of scope: seed control in providers beyond `temperature=0`.

Handoff note for next agent: none.
