### WO-H.6: Add the Action self-test workflow on a fixture repository
Wave: 10   Depends on: WO-H.1, WO-H.2, WO-H.3   Blocks: WO-D.3, WO-E.9
Milestone: Oral Jan 25
Traces: FR-1…FR-15 (system test), NFR-5 · (§2.5)
Suggested human owner: Elaine Huang (reviewer: Clinton Odinamadu)
Size: S

Goal (2–3 sentences): `.github/workflows/action-selftest.yml` runs the local action (`uses: ./`) against
`fixtures/e2e/sqli-min` in two modes: `none` and `fake` (CI-only, via env). It asserts outputs, SARIF
validity, comment idempotency (on a throwaway PR), and the exit-code policy.

Context to read first (exact paths only): `action.yml`; `scripts/action/*.sh`; `fixtures/e2e/sqli-min/expected.json`.

Files you own (create/modify — nothing else): `.github/workflows/action-selftest.yml`, `scripts/action/test/assert-outputs.sh`.

Files you may read but not modify: everything else.

Interfaces consumed: `action.yml`   Interfaces produced: the self-test.

Implementation notes:
- Pass `secpipe-jar` built in an earlier job to skip the rebuild.
- The `fake` mode sets `SECPIPE_ALLOW_FAKE_LLM=1` only in this workflow.
- The second run within the same workflow checks incremental carry-forward (H.4).

Tests to write: the workflow itself plus `assert-outputs.sh`.

Acceptance criteria (all must pass):
- [ ] `actionlint .github/workflows/action-selftest.yml` → exit 0
- [ ] [HUMAN] The latest `action-selftest` run on `main` → success, with job summaries showing `run-status=complete` for the `none` and `fake` modes (link in the PR)

Out of scope: marketplace publishing (D.4).

Handoff note for next agent: The self-test is the Jan 27 "integration testing & deployment" evidence.
