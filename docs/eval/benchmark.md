# OWASP Benchmark v1.2 scorecard

## AI off (analyzers + deterministic refuter), 2026-11-22

Run by the `eval-benchmark` workflow on branch `eval/benchmark-no-ai` at commit `ab2e09a`
(`bash scripts/eval/benchmark.sh --no-ai`; BenchmarkJava pinned at `a452b3a`). No LLM, no Docker. Scan and scoring
took 63 seconds on a GitHub runner (5,483 entry surfaces, 2,864 sink sites, 27,422 ledger units).

| CWE | TP | FP | TN | FN | TPR % | FPR % | Score |
|---|---|---|---|---|---|---|---|
| CWE-22 | 71 | 55 | 80 | 62 | 53.4 | 40.7 | 12.7 |
| CWE-78 | 113 | 110 | 15 | 13 | 89.7 | 88.0 | 1.7 |
| CWE-89 | 272 | 201 | 31 | 0 | 100.0 | 86.6 | 13.4 |
| CWE-327 | 130 | 0 | 116 | 0 | 100.0 | 0.0 | 100.0 |
| CWE-328 | 89 | 0 | 107 | 40 | 69.0 | 0.0 | 69.0 |
| **Average** | 675 | 366 | 349 | 115 | 82.4 | 43.1 | 39.3 |

Average rates are the unweighted mean of the per-CWE rates; score = TPR − FPR.

MVP baseline (Oct 2026, `baseline-2026-10.json`): TPR 82.4, FPR 46.0, score 23.8.

## Reading

- **NFR-4 (FPR ≤ 25 % at TPR ≥ 80 %) is not met with AI off:** TPR 82.4 %, FPR 43.1 %.
- TPR equals the MVP's: the detection rules are the ported ones. The deterministic refuter lowers FPR by 2.9 points.
- Almost all false positives are in CWE-78 (88.0 %) and CWE-89 (86.6 %). The refuter rejects few of the Benchmark's
  safe cases there; the Benchmark's collection and map tricks are what the stretch `CollectionTrickRule` targets.
- Missed true positives: CWE-22 (62 misses) and CWE-328 (40 misses) are detection gaps.
- The adversarial disprover and test evidence only run with AI on. That run needs an API key and is pending
  (`[HUMAN]`): `bash scripts/eval/benchmark.sh --provider anthropic`.

## How to re-run

Push any commit to a branch named `eval/<name>`; the `eval-benchmark` workflow prints the scorecard in its log
between `== benchmark scorecard ==` and `== end scorecard ==`.
