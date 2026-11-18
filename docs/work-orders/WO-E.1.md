### WO-E.1: Automate the OWASP Benchmark v1.2 scorecard
Wave: 2   Depends on: WO-C.1, WO-P.0   Blocks: none
Milestone: Prototype 2 Dec 5
Traces: NFR-4 · (evaluation §2.8 item 1)
Suggested human owner: Abdul Babatunde (reviewer: Elaine Huang)
Size: M

Goal (2–3 sentences): `BenchmarkScorecard` scores a `findings.json` (or SARIF) against `expectedresults-1.2.csv`, reporting per-CWE TP/FP/TN/FN, TPR, FPR and score for CWEs 22, 78, 89, 327, 328, with averages and deltas vs the Oct-2026 baseline (TPR 82.4, FPR 46.0, score 23.8). `scripts/eval/benchmark.sh` fetches the Benchmark at a pinned commit (never vendored, GPL) and runs `secpipe scan` on it.

Context to read first (exact paths only): `docs/PLAN.md` §2.8 item 1 and ADR-05 (counting rule); MVP `eval/score_samples.py`; contracts `ContractJson.java`, `findings/Finding.java`

Files you own (create/modify — nothing else): `secpipe-eval/src/main/java/ca/carleton/secpipe/eval/benchmark/**`, `secpipe-eval/src/test/java/ca/carleton/secpipe/eval/benchmark/**`, `secpipe-eval/src/test/resources/ca/carleton/secpipe/eval/benchmark/**`, `scripts/eval/benchmark.sh`, `docs/eval/baseline-2026-10.json`.

Files you may read but not modify: contracts.

Interfaces consumed: `ContractJson`   Interfaces produced: `BenchmarkScorecard --expected CSV --findings FILE [--sarif FILE] --out DIR` → `scorecard.json`, `scorecard.md`, `per-case.csv`.

Implementation notes:
- Match = same `BenchmarkTestNNNNN.java` + matching CWE family; `confirmed`/`needs_validation` = reported, `rejected` = not reported; SARIF suppressed = not reported.
- `benchmark.sh` clones into `~/.secpipe/eval/benchmark` and moves `expectedresults*.csv` out of the scanned tree before scanning.
- `scorecard.json`: `{per_cwe, average, baseline_delta}`, rates in percent with one decimal, sorted keys.
- Flags kept: `--no-ai`, `--provider P`, `--out DIR`, `--dry-run`.

Tests to write: 10-row mini CSV + findings → exact counts; SARIF path; suppressed handling; family matching (CWE-23 → 22); baseline delta.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-eval -am test -Dtest='BenchmarkScorecard*Test'` → ≥ 8 tests, 0 failures
- [ ] `bash scripts/eval/benchmark.sh --no-ai --dry-run` → prints the pinned commit, the scan command, and the deny-path for `expectedresults-1.2.csv`
- [ ] [HUMAN] (after K.13 merges) `bash scripts/eval/benchmark.sh --no-ai` → `scorecard.md` has 5 per-CWE rows plus an average row
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: ablation and AI arms (E.2, stretch).

Handoff note for next agent: The headline number for the report is `scorecard.json.average` from the `--no-ai` and one AI run.
