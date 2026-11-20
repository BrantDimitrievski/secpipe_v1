### WO-D.5: Docs catch up with remediation; demo remediation step; repo hygiene; remove the Copilot module
Wave: 13   Depends on: WO-D.1, WO-M.1, WO-M.2, WO-M.3, WO-R.4, WO-CC.1   Blocks: none
Milestone: Oral Jan 25
Traces: FR-13, FR-14 · Q, S
Suggested human owner: Elodie Abdo (reviewer: Clinton Odinamadu)
Size: M

Goal (2–3 sentences): The README and the user guide still say there is no remediation, and `--enable-fixes` is "accepted but unused". Both are false since WO-M.1/M.3/R.4. Bring the docs and the demo script up to date: a "Fixes" section, honest Limitations, and a demo step that shows a verified fix. Also clear the tracked hygiene items and delete the empty `secpipe-llm-copilot` module (owner decision 2026-11-19).

Context to read first (exact paths only): `README.md`; `docs/user/guide.md`; `docs/DEMO.md`; `scripts/demo/demo.sh`; `scripts/docs/check-snippets.sh`; `fixtures/remediation/sqli-fixable/README.md`; `secpipe-agents/src/test/resources/llm-fixtures/remediation-loop/` (how the fake-LLM remediation cases look); `secpipe-cli/src/main/java/ca/carleton/secpipe/cli/ScanCommand.java` (flag help only); `secpipe-report/src/main/java/ca/carleton/secpipe/report/MarkdownWriter.java` (what the Remediation section prints); `.github/workflows/ci.yml`; `pom.xml`; `secpipe-cli/pom.xml`.

Files you own (create/modify — nothing else): `README.md`, `docs/user/guide.md`, `docs/DEMO.md`, `scripts/demo/**`, `scripts/docs/**`, `fixtures/remediation/sqli-fixable/llm/**` (new: fake-LLM responses for the demo), `fixtures/remediation/sqli-fixable/README.md`, `.gitignore`, `.github/workflows/ci.yml`, `.github/workflows/wave-integration.yml`, `pom.xml` (the `<module>secpipe-llm-copilot</module>` line and any `dependencyManagement` entry for it, nothing else), `secpipe-cli/pom.xml` (the copilot dependency only), `secpipe-llm-copilot/**` (delete), `docs/work-orders/WO-D.5.md` (this file, verbatim).

Files you may read but not modify: everything else; in particular `secpipe-contracts/**`, `action.yml`, `docs/PLAN.md` (the coordinator records the module removal there), the stretch work orders.

Interfaces consumed: the CLI flags of PLAN §4.3 (frozen), the report's Remediation section, the fake-LLM provider (`SECPIPE_ALLOW_FAKE_LLM`, `SECPIPE_FAKE_LLM_DIR`)   Interfaces produced: user-facing docs; `scripts/demo/demo.sh` step 4.

Implementation notes:
- README: replace "(no GitHub Action, no remediation)" with the true state; drop "Copilot" from the module table (the provider was removed by WO-CC.1).
- Guide: add a "Fixes (remediation)" section: what runs (`--enable-fixes suggest` is the default and runs only for `confirmed` findings when the sandbox is usable; fixes go to scratch copies, never to the working tree); the per-attempt check (regression test fails before and passes after, baseline suite unchanged, rescan clean, adequacy measured with JaCoCo); `--max-fix-attempts`; `--enable-fixes false`; `--enable-fixes pr` exits 2 with a hint; outputs (`patches/`, `secpipe-fixes.patch` with verified fixes only, SARIF `fixes[]`, the report's Remediation section, the outcomes `fixed`, `still_present`, "fix unverified", `sandbox_unavailable`, `not_attempted`); how to apply a patch yourself (`git apply secpipe-fixes.patch`). Every command you show must be real; `scripts/docs/check-snippets.sh` must stay green (extend it if it checks flags).
- Guide Limitations: remove the FR-13/FR-14/Q "not delivered" lines; keep FR-7, `secpipe diff`, no GitHub Action / `--enable-fixes pr`, no HTML report, dependency-upgrade fixes, unmeasured perf/portability. Update the "Stretch items" sentence.
- Demo step 4 (`[DOCKER]`): scan `fixtures/remediation/sqli-fixable` with `--llm-provider fake` and the sandbox on, using new fake responses under `fixtures/remediation/sqli-fixable/llm/` (disprover → survives, testgen → the fixture's regression test, remediation → `fix/prepared-statement.patch`). Assert the run's `report.md` has a Remediation section with outcome `fixed` and that `secpipe-fixes.patch` is non-empty. Skip cleanly (exit 0, a "skipped:" line) without Docker or with `--no-docker`. Renumber the step banners (`1/4` … `4/4`). Update `docs/DEMO.md` to match.
- CI: add `bash scripts/demo/demo.sh` to the `sandbox` job after its existing steps, so step 4 is checked on every PR (Docker is there). Bump `actions/checkout`, `actions/setup-java`, `actions/upload-artifact` to `@v5` in both workflows (check the tags exist with `gh api repos/actions/<name>/git/refs/tags/v5`). If the push is rejected because the token cannot change workflow files, revert only the workflow edits, finish the rest, and list them under "Human checks pending".
- `.gitignore`: add `__pycache__/` and `*.pyc`.
- Copilot module: delete `secpipe-llm-copilot/` with `git rm -r`, remove its `<module>` line from `pom.xml` and its dependency from `secpipe-cli/pom.xml`. `grep -rn copilot --include=pom.xml .` must print nothing. Do not touch `docs/PLAN.md` or stretch work orders.
- Maven wrapper `distributionSha256Sum` is **not** in scope (Central is unreachable from the build machine); leave it as a human check.

Tests to write: none in Java (docs and scripts), but `demo.sh` step 4 must fail (non-zero) if the report has no `fixed` outcome. Show it once in the PR by pointing it at a run without remediation, or explain why that cannot be shown without Docker.

Acceptance criteria (all must pass):
- [ ] `grep -n -i 'no remediation\|accepted but unused' README.md docs/user/guide.md` → no output
- [ ] `grep -rn copilot --include=pom.xml .` → no output; `test ! -d secpipe-llm-copilot`
- [ ] `bash scripts/docs/check-snippets.sh` → exit 0
- [ ] `bash scripts/demo/demo.sh --no-docker` → `DEMO OK` (steps 1–3 run, step 4 skipped)
- [ ] [DOCKER] `bash scripts/demo/demo.sh` → step 4 prints a `fixed` outcome; in CI this is the `sandbox` job
- [ ] `python3 -B scripts/check-plan.py` → `errors=0`
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` and `sandbox` green)

Out of scope: the oral storyboard and recorded fallback (WO-D.6); PLAN.md edits; the GitHub Action; Maven wrapper checksum.

Handoff note for next agent: WO-D.6 builds the oral demo kit on top of demo step 4 and one real E.6 row.
