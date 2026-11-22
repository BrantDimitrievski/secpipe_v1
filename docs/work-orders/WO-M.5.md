### WO-M.5: Multi-module JaCoCo class files and wave-13 review minors
Wave: 14   Depends on: WO-M.4, WO-R.6, WO-G.12   Blocks: none
Milestone: Oral Jan 25
Traces: FR-14, FR-15 · Q
Suggested human owner: Abdul Babatunde (reviewer: Elaine Huang)
Size: S

Goal (2–3 sentences): WO-M.4 taught the adequacy check multi-module source paths, but the JaCoCo report is still built from the root `target/classes` only, so a real multi-module project is measured wrongly. Fix that, and close the small test gaps that the wave-13 reviews listed.

Context to read first (exact paths only): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/AdequacyCheck.java` (the command near line 290); `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/remediation/AdequacyCheckTest.java`; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/RemediationLoopNode.java` (`promoteFollowUpTest`, `shortId`); `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/runner/AgentRunnerTest.java`; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/runner/AgentRunner.java`; `secpipe-report/src/test/java/ca/carleton/secpipe/report/SarifSchemaTest.java`; `docs/coordination/log.md` (section "Wave 13 (follow-ups) complete", the tracked minors).

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/{AdequacyCheck,RemediationLoopNode}.java`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/remediation/{AdequacyCheck,RemediationLoopNode}*Test.java`, `secpipe-agents/src/test/resources/ca/carleton/secpipe/agents/remediation/jacoco/**`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/runner/AgentRunnerTest.java`, `secpipe-report/src/test/java/ca/carleton/secpipe/report/SarifSchemaTest.java`, `secpipe-report/src/test/java/ca/carleton/secpipe/report/GoldenVerifiedFixTest.java` (new), `secpipe-report/src/test/resources/ca/carleton/secpipe/report/verified-fix/**` (new, only if needed), `docs/work-orders/WO-M.5.md` (this file, verbatim).

Files you may read but not modify: everything else; in particular `secpipe-report/src/main/**`, the other report tests (WO-K.16 owns the run-metadata ones in this wave), `scripts/eval/**`, `secpipe-sandbox/**`, `secpipe-contracts/**`.

Interfaces consumed: `SandboxRunner`, `AgentRunner.run(agent, input, grounding, maxCalls)`, `ReportBundle`, `PatchSource`   Interfaces produced: unchanged signatures.

Implementation notes:
- Class files: for the changed production files, derive each module directory (the path before `src/main/java/`; empty for the root). The in-container command must pass `--classfiles <module>/target/classes` once per touched module (root: `target/classes`), skip a directory that does not exist inside the container (shell test) instead of failing, and keep the current behaviour byte-for-byte for a single-module project (the existing `command(classes, includes)` may gain an overload or a module-list parameter). Module paths come from the patch, which is model output: accept only path segments matching `[A-Za-z0-9._-]+`, reject anything else (no adequacy result, a note), and quote every argument. Never build the command from unvalidated text.
- If the test run itself (`-javaagent`, class path) also needs per-module handling to produce coverage for a multi-module project, say so in the PR and stop there: that is a design question for a re-plan, not this order.
- Minors: (1) move the misplaced Javadoc so it documents `shortId` again; (2) the rejected-promotion test asserts the reason text, not only the prefix; (3) moved to WO-L.9; (4) `AgentRunnerTest`: the call-budget overload (budget 1 → exactly one call; budget 0 and budget > `MAX_ATTEMPTS` rejected); (5) `SarifSchemaTest` discovers every `*.sarif` under the report test resources instead of a hand-written list; (6) add a report test that renders the shared golden bundle **with** a `PatchSource` holding its patch and shows the verified-fix wording, so both paths are covered (do not change `golden/report.md`).

Tests to write: two-module command has both `--classfiles` entries; single-module command unchanged (string equality with the current one); a hostile module path (`a;rm -rf x/src/main/java/A.java`, `../x/src/main/java/A.java`) yields no command and a note; plus the six minors above.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='AdequacyCheck*Test,RemediationLoopNode*Test,AgentRunner*Test'` → 0 failures, ≥ 5 new tests
- [ ] `./mvnw -q -B -pl secpipe-report -am test` → 0 failures, ≥ 2 new tests
- [ ] `python3 -B scripts/check-plan.py` → `errors=0`
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` and `sandbox` green; the sqli-fixable IT still reaches `fixed`)

Out of scope: `ScannedTypes` simple-name matching (errs toward tainted; accepted); demo step 3's warning (WO-D.6); report main code.

Handoff note for next agent: multi-module adequacy is unit-tested only; no multi-module fixture runs in the sandbox yet.
