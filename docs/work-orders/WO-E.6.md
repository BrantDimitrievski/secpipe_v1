### WO-E.6: Evaluate remediation on the MVP sample apps (≥ 10 confirmed findings)
Wave: 12   Depends on: WO-M.1, WO-M.2, WO-M.3, WO-G.7   Blocks: none
Milestone: Oral Jan 25
Traces: FR-13, FR-14, FR-15 · Q, (evaluation §2.8 item 6)
Suggested human owner: Elodie Abdo (reviewer: Abdul Babatunde)
Size: M

Goal (2–3 sentences): Run the full pipeline (frontier model, sandbox on, `--enable-fixes suggest`) on `fixtures/mvp-samples/vulnerable-java-app` and `fixtures/mvp-samples/shop-samples` and remediate at least 10 confirmed findings (PLAN's oral-exam target). For each, record: test fails before, passes after; suite unchanged; rescan clean; attempts; adequacy. Report every attempt honestly, including failures, in a scorecard under `docs/eval/`.

Context to read first (exact paths only): `docs/PLAN.md` §2.8 item 6; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/RemediationLoopNode.java`; `scripts/eval/benchmark.sh`

Files you own (create/modify — nothing else): `scripts/eval/remediation.sh`, `secpipe-eval/src/main/java/ca/carleton/secpipe/eval/remediation/**`, `secpipe-eval/src/test/java/ca/carleton/secpipe/eval/remediation/**`, `secpipe-eval/src/test/resources/ca/carleton/secpipe/eval/remediation/**`, `docs/eval/remediation.md`.

Files you may read but not modify: everything else.

Interfaces consumed: the CLI, `findings.json` `fix_attempts`   Interfaces produced: `docs/eval/remediation.md` scorecard.

Implementation notes:
- Columns: app, fingerprint, CWE, attempts, pre-fix test, post-fix test, suite, rescan, new findings, adequacy, final status, patch.
- The scorecard generator is deterministic code over `findings.json`; tests use committed `findings.json` fixtures (no Docker, no key).
- The real run needs a Docker machine and an LLM key; the S.3 laptop checks gate these results.

Tests to write: table extraction from `findings.json` fixtures (fixed, still_present, not_attempted, inadequate).

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-eval -am test -Dtest='Remediation*Test'` → ≥ 3 tests, 0 failures
- [ ] [HUMAN] on a Docker machine with an LLM key, `bash scripts/eval/remediation.sh` produces `docs/eval/remediation.md` with ≥ 10 confirmed-finding rows, each linking its patch artifact
- [ ] [HUMAN] the S.3 laptop checks have passed on the machine used for the run
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: prompt tuning (open a follow-up order if the fix rate is < 50 %).

Handoff note for next agent: The oral demo uses one row from this scorecard as the scripted live case; update the user guide's Limitations section.
