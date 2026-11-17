### WO-E.6: Verify remediation on deliberately vulnerable apps (≥ 10 fixes)
Wave: 10   Depends on: WO-M.1, WO-M.2, WO-G.7   Blocks: WO-D.3, WO-E.10, WO-D.4
Milestone: Oral Jan 25
Traces: FR-13, FR-14, FR-15 · Q, (evaluation §2.8 item 6)
Suggested human owner: Elodie Abdo (reviewer: Abdul Babatunde)
Size: M

Goal (2–3 sentences): Run the full pipeline (frontier model, sandbox on) on JavaVulnerableLab and WebGoat
(pinned commits, downloaded) and the MVP sample. For up to 10 confirmed findings across the supported
CWEs, record: test fails before fix, passes after; rescan clean; no new findings; existing suite green;
adequacy. Report every attempt honestly, including failures.

Context to read first (exact paths only): `docs/PLAN.md` §2.8 item 6; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/RemediationLoopNode.java`.

Files you own (create/modify — nothing else): `scripts/eval/remediation.sh`, `secpipe-eval/src/main/java/ca/carleton/secpipe/eval/remediation/**`,
`secpipe-eval/src/test/java/ca/carleton/secpipe/eval/remediation/**`, `docs/eval/remediation.md`.

Files you may read but not modify: everything else.

Interfaces consumed: the CLI, `findings.json` `fix_attempts`   Interfaces produced: `remediation.md` table.

Implementation notes: The table columns are app, fingerprint, CWE, attempts, pre-fix test, post-fix test,
suite, rescan, new findings, adequacy, final status. If fewer than 10 suitable cases exist, report the
number (as the proposal says).

Tests to write: table extraction from findings.json fixtures.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-eval -am test -Dtest='Remediation*Test'` → ≥ 3 tests, 0 failures
- [ ] `docs/eval/remediation.md` has ≥ 10 rows (or states why fewer), each with a link to the patch artifact

Out of scope: tuning prompts (open a follow-up WO if the fix rate is < 50 %).

Handoff note for next agent: The oral demo uses one row from this table as the scripted live case (D.3).
