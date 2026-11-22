### WO-E.11: Run the OWASP Benchmark (AI off) in GitHub Actions and print the scorecard
Wave: 14   Depends on: WO-E.1, WO-D.5   Blocks: none
Milestone: Oral Jan 25
Traces: NFR-4 · E
Suggested human owner: Elaine Huang (reviewer: Abdul Babatunde)
Size: S

Goal (2–3 sentences): SecPipe has never been scored on the OWASP Benchmark; the only numbers are the MVP's. The coordinator's machine cannot build the jar or download CI artifacts, but it can read job logs. Add a workflow that runs `scripts/eval/benchmark.sh --no-ai` and prints the scorecard into the log. No API key is involved (CLAUDE.md §6 holds).

Context to read first (exact paths only): `scripts/eval/benchmark.sh`; `docs/work-orders/WO-E.1.md`; `secpipe-eval/src/main/java/ca/carleton/secpipe/eval/benchmark/BenchmarkScorecard.java`; `.github/workflows/ci.yml` (setup steps and style); `docs/eval/baseline-2026-10.json`.

Files you own (create/modify — nothing else): `.github/workflows/eval-benchmark.yml` (new), `scripts/eval/benchmark.sh`, `docs/work-orders/WO-E.11.md` (this file, verbatim).

Files you may read but not modify: everything else; `.github/workflows/ci.yml` and `wave-integration.yml` belong to WO-0.3 in this wave; `secpipe-eval/**`.

Interfaces consumed: `scripts/eval/benchmark.sh`, `BenchmarkScorecard`   Interfaces produced: workflow `eval-benchmark` whose log contains the scorecard between the lines `== benchmark scorecard ==` and `== end scorecard ==`.

Implementation notes:
- Triggers: `workflow_dispatch`, and `push` to branches matching `eval/**` (the coordinator cannot dispatch workflows, but can push a branch). Never `pull_request_target`. `permissions: contents: read`. `timeout-minutes: 120`. A `concurrency` group per ref.
- Steps: checkout; `actions/setup-java@v5` (Temurin 21, Maven cache); `bash scripts/eval/benchmark.sh --no-ai --out secpipe-eval-out/benchmark`; then print `== benchmark scorecard ==`, `cat secpipe-eval-out/benchmark/scorecard.md`, the scorecard JSON on one line, `== end scorecard ==`; print wall-clock seconds for the scan. Upload `scorecard.json`, `scorecard.md` and `per-case.csv` as an artifact (v5), `if: always()`. Do not upload the Benchmark sources.
- No secrets, no `env:` with keys. The job must fail with a clear message if the scan or scoring step fails; it must not fail because the score is low.
- `benchmark.sh`: change it only if the CI run needs it (for example a missing directory, or timing output). Keep `--dry-run` output and the pinned commit unchanged. If the scan crashes or runs out of memory or time on the Benchmark, capture the error in the PR and stop (§11): fixing the engine is not this order.
- To prove it: after your PR's `ci` is green, push the same commit to a branch named `eval/wo-e.11-proof` (`git push origin HEAD:refs/heads/eval/wo-e.11-proof`), wait for the `eval-benchmark` run on that commit (check-runs API), and paste the scorecard section from its log into the PR. Leave that branch in place for the coordinator.

Tests to write: none in Java. The proof run above is the test.

Acceptance criteria (all must pass):
- [ ] `./actionlint` in CI passes with the new workflow
- [ ] The `eval-benchmark` job on `eval/wo-e.11-proof` completes and its log contains `== benchmark scorecard ==` with TPR and FPR per CWE (22, 78, 89, 327, 328) and the average
- [ ] `bash scripts/eval/benchmark.sh --dry-run` output unchanged except for additions you document
- [ ] `python3 -B scripts/check-plan.py` → `errors=0`
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` and `sandbox` green)

Out of scope: AI-on Benchmark runs (need a key: `[HUMAN]`, Brant's machine); tuning rules to raise the score; writing `docs/eval/benchmark.md` (coordinator, from the log).

Handoff note for next agent: the coordinator records the AI-off numbers against NFR-4 (FPR ≤ 25 % at TPR ≥ 80 %) and re-plans if they miss.
