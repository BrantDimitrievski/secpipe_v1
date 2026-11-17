### WO-E.1: Automate the OWASP Benchmark v1.2 scorecard
Wave: 2   Depends on: WO-C.1, WO-P.0   Blocks: WO-E.2, WO-E.5, WO-E.8, WO-E.3
Milestone: Prototype 2 Dec 5
Traces: NFR-4 · (evaluation §2.8 item 1)
Suggested human owner: Abdul Babatunde (reviewer: Elaine Huang)
Size: M

Goal (2–3 sentences): `secpipe-eval` gets `BenchmarkScorecard`, which scores a `findings.json` (or any
SARIF) against `expectedresults-1.2.csv`. It reports per-CWE TP/FP/TN/FN, TPR, FPR, and score for the
supported CWEs, plus the averages and deltas vs the Oct-2026 baseline (TPR 82.4, FPR 46.0, score 23.8) and
NFR-4. `scripts/eval/benchmark.sh` fetches the Benchmark at a pinned commit (never vendored, GPL) and runs
SecPipe on it.

Context to read first (exact paths only): `docs/PLAN.md` §2.8 item 1, ADR-05 (counting rule); MVP `eval/score_samples.py`,
`eval/sarif_util.py`; contracts `json/ContractJson.java`.

Files you own (create/modify — nothing else): `secpipe-eval/src/main/java/ca/carleton/secpipe/eval/benchmark/**`,
`secpipe-eval/src/test/java/ca/carleton/secpipe/eval/benchmark/**`, `secpipe-eval/src/test/resources/ca/carleton/secpipe/eval/benchmark/**`,
`scripts/eval/benchmark.sh`, `docs/eval/baseline-2026-10.json`.

Files you may read but not modify: contracts.

Interfaces consumed: `ContractJson`   Interfaces produced:
`java -cp secpipe-eval.jar ca.carleton.secpipe.eval.benchmark.BenchmarkScorecard --expected CSV --findings FILE
[--sarif FILE] --cwes 22,78,89,327,328 --out DIR`, which writes `scorecard.json`, `scorecard.md`, and
`per-case.csv`.

Implementation notes:
- **Matching rule:** same test file (`BenchmarkTestNNNNN.java`) + matching CWE family.
  - `confirmed` or `needs_validation` = "reported"; `rejected` = "not reported".
  - SARIF mode: suppressed results = not reported.
- **Benchmark CWE ids:** pathtraver 22, cmdi 78, sqli 89, crypto 327, hash 328.
- `benchmark.sh` clones `OWASP-Benchmark/BenchmarkJava` at the pinned commit into `~/.secpipe/eval/benchmark`
  (outside the repo), then runs `secpipe scan` with the given flags.
- The scan **must not** read `expectedresults*.csv`: the script moves it out of the scanned tree first,
  and a test asserts the deny-path includes it.
- **Determinism:** sorted outputs.
- **`scorecard.json` shape (consumed by `wave-integration.yml`):** `{"per_cwe": {"CWE-89": {"tp", "fp", "tn",
  "fn", "tpr", "fpr", "score"}, …}, "average": {"tpr", "fpr", "score"}, "baseline_delta": {…}}`, with rates
  in percent (0–100, one decimal). `docs/eval/baseline-2026-10.json` uses the same `average` shape.
- `benchmark.sh` flags: `--no-ai`, `--provider P`, `--max-agent-calls N`, `--sample FILE`, `--out DIR`,
  `--dry-run`.

Tests to write: a hand-made mini expected CSV (10 rows) and findings → exact counts; the SARIF path;
suppressed handling; family matching (CWE-23 counts for 22); baseline delta computation.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-eval -am test -Dtest='BenchmarkScorecard*Test'` → ≥ 8 tests, 0 failures
- [ ] `bash scripts/eval/benchmark.sh --no-ai --dry-run` → prints the pinned commit, the scan command, and the deny-path for `expectedresults-1.2.csv`
- [ ] [HUMAN] (end of wave 3) `bash scripts/eval/benchmark.sh --no-ai` → writes `scorecard.md`, whose per-CWE table has 5 rows plus an average row

Out of scope: running AI arms (E.2).

Handoff note for next agent: `scorecard.json` is the input for the ablation and tool-comparison tables and
for `wave-integration.yml`.
