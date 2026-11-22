### WO-0.3: CI prints how many tests ran
Wave: 14   Depends on: WO-0.1, WO-D.5   Blocks: none
Milestone: Oral Jan 25
Traces: NFR-6 · Q
Suggested human owner: Clinton Odinamadu (reviewer: Brant Dimitrievski)
Size: S

Goal (2–3 sentences): CI runs Maven with `-q`, so the logs never show which tests ran. Reviewers cannot confirm that a PR's new tests executed. Add a summary step to both CI jobs that prints per-module and total test counts from the Surefire and Failsafe XML reports.

Context to read first (exact paths only): `.github/workflows/ci.yml`; `.github/workflows/wave-integration.yml`; `scripts/ci-fixture-scan.sh` (script style).

Files you own (create/modify — nothing else): `scripts/ci-test-summary.sh` (new), `.github/workflows/ci.yml`, `.github/workflows/wave-integration.yml`, `docs/work-orders/WO-0.3.md` (this file, verbatim).

Files you may read but not modify: everything else; `.github/workflows/eval-benchmark.yml` belongs to WO-E.11 in this wave.

Interfaces consumed: Surefire/Failsafe `TEST-*.xml`   Interfaces produced: a log section starting with the line `== test summary ==`.

Implementation notes:
- `scripts/ci-test-summary.sh [--class PATTERN]`: bash + `python3 -B` (standard library only). Find `**/target/surefire-reports/TEST-*.xml` and `**/target/failsafe-reports/TEST-*.xml`; print `== test summary ==`, one line per module (`<module> tests=<n> failures=<n> errors=<n> skipped=<n>`), then one line per test class (`  <class> tests=<n> skipped=<n>`), then `TOTAL tests=<n> failures=<n> errors=<n> skipped=<n>`. Sorted, so output is stable. Exit 0 always when reports exist; exit 0 with `no test reports found` when none (it reports, it does not gate). Parse the XML with `xml.etree.ElementTree`; never `eval` anything.
- Workflows: add the step after the Maven verify step in both jobs of `ci.yml` (and in `wave-integration.yml` if it runs tests) with `if: always()`. Keep `-q` on Maven. Keep `actionlint` green. Change nothing else in the workflows.
- If the push is rejected because the token cannot change workflow files, stop and report (§11).

Tests to write: none in Java. In the PR, paste the summary section from the `ci` job log of your own PR (`gh api repos/BrantDimitrievski/secpipe_v1/actions/jobs/<id>/logs | sed -n '/== test summary ==/,/TOTAL/p' | tail -30`).

Acceptance criteria (all must pass):
- [ ] `bash scripts/ci-test-summary.sh` in a tree without reports → prints `no test reports found`, exit 0
- [ ] The `ci` and `sandbox` job logs of the PR contain `== test summary ==` and a `TOTAL tests=` line with tests > 0
- [ ] `python3 -B scripts/check-plan.py` → `errors=0`
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` and `sandbox` green)

Out of scope: removing `-q`; coverage reports; test gating.

Handoff note for next agent: reviewers can now grep the job log for a test class name; update `docs/prompts/pr-review.md` in a later docs order if wanted.
