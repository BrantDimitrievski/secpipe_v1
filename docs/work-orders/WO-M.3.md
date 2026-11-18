### WO-M.3: Remediation follow-ups (verifiable regression tests, honest outcomes)
Wave: 10   Depends on: WO-M.1, WO-G.10, WO-G.11   Blocks: WO-M.2, WO-E.6
Milestone: Oral Jan 25
Traces: FR-13, FR-14 · Q
Suggested human owner: Brant Dimitrievski (reviewer: Abdul Babatunde)
Size: S

Goal (2–3 sentences): The WO-M.1 review found that real-run regression tests (from G.7/G.11) are often SKIPPED after a correct fix, because their stubs only record the unsafe API, so correct fixes are classified `still_present`. Make generated tests verifiable after a fix, report a post-fix skip honestly, and fix three smaller outcome issues, before M.2 and E.6.

Context to read first (exact paths only): `secpipe-agents/src/main/resources/prompts/testgen.v1.txt`; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/{RemediationLoopNode,RemediationAgent,FixClassifier}.java`; `fixtures/remediation/sqli-fixable/regression/secpipe/generated/LoginServletSqliRegressionTest.java`; `docs/work-orders/WO-M.1.md`.

Files you own (create/modify — nothing else): `secpipe-agents/src/main/resources/prompts/testgen.v1.txt`; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/{RemediationLoopNode,RemediationAgent,FixClassifier}.java`; their tests and fixtures under `secpipe-agents/src/test/**/remediation/**` and `secpipe-agents/src/test/resources/llm-fixtures/remediation-loop/**`; `docs/work-orders/WO-M.3.md` (this file, verbatim); the Wave/Depends lines of `docs/work-orders/WO-M.2.md` and `WO-E.6.md`; `docs/work-orders/README.md`; one changelog line in `docs/PLAN.md` "Re-plan 2026-11-18 (remediation)".

Interfaces consumed: `FixClassifier`, `RemediationAgent`, `SuiteBaseline`   Interfaces produced: updated testgen guidance; honest remediation outcomes.

Implementation notes:
- Testgen prompt: stubs must record both the unsafe and the safe API for the sink family (e.g. `Statement.executeQuery(String)` AND `Connection.prepareStatement` + `setString` + `executeQuery()`), assert on the attacker-visible effect (input reaching the command/query/path text), and use `Assumptions` only for environment setup. The test must pass on a correct parameterized/validated fix.
- Post-fix SKIP: record the attempt as `still_present` (frozen enum) but with `strategy`/run note `regression test skipped after patch: fix unverified`; do not send it to the agent as a failure; stop retrying that record. E.6 counts these separately.
- `NotRun` (sandbox refused/died) during regression or suite → outcome `sandbox_unavailable`, not `still_present`/`tests_broken`.
- Non-confirmed CODE records touched by the node get `remediation_status=not_attempted` (WO-M.1/PLAN principle); R.4 also treats an absent status as not attempted.
- `RemediationAgent`: pass the prior attempt's `failingStep` through `untrusted(...)`, not `oneLine()`.

Tests to write: post-fix skip → `still_present` + "fix unverified" note, no further attempts; NotRun → `sandbox_unavailable`; non-confirmed → `not_attempted`; failingStep rendered inside an untrusted block; a prompt test that the testgen template contains the safe-API guidance.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='RemediationLoopNode*Test,FixClassifier*Test,RemediationAgent*Test'` → 0 failures, ≥ 5 new tests
- [ ] `python3 -B scripts/check-plan.py` → `errors=0`
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` and `sandbox` green; the sqli-fixable IT still reaches `fixed`)

Out of scope: test adequacy (M.2); reporting (R.4); regenerating verification tests after a fix.

Handoff note for next agent: M.2 inserts adequacy after the rescan in `RemediationLoopNode`; E.6 counts `fix unverified` attempts separately from failures.
