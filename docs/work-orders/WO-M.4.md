### WO-M.4: Adequacy and scorecard follow-ups (promote follow-up test, multi-module roots, last-attempt cross-check, provider-aware key check)
Wave: 13   Depends on: WO-M.2, WO-E.6   Blocks: none
Milestone: Oral Jan 25
Traces: FR-14, FR-15 · Q
Suggested human owner: Abdul Babatunde (reviewer: Elaine Huang)
Size: S

Goal (2–3 sentences): Close the minor findings from the WO-M.2 and WO-E.6 reviews before Brant's real E.6 run. A follow-up adequacy test that makes a fix adequate must be promoted, so the result can be reproduced from the run output. Multi-module patches (`<module>/src/main/java/...`) must be measured. The scorecard must count a fix only when the last attempt really is `fixed`, and `scripts/eval/remediation.sh` must check the API key that the chosen `--llm-provider` will actually read.

Context to read first (exact paths only): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/AdequacyCheck.java`; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/RemediationLoopNode.java`; `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/ArtifactPromoter.java`; `secpipe-eval/src/main/java/ca/carleton/secpipe/eval/remediation/` (all files); `scripts/eval/remediation.sh`; `secpipe-llm-anthropic/src/main/java/ca/carleton/secpipe/llm/anthropic/AnthropicMessagesClient.java` and `secpipe-llm-openai/src/main/java/ca/carleton/secpipe/llm/openai/OpenAiCompatibleSupport.java` (which env vars each provider reads); `docs/work-orders/WO-M.2.md`; `docs/work-orders/WO-E.6.md`.

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/{AdequacyCheck,JacocoXml,RemediationLoopNode}.java`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/remediation/{AdequacyCheck,JacocoXml,RemediationLoopNode}*Test.java`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/remediation/RemediationLoopSandboxIT.java`, `secpipe-agents/src/test/resources/ca/carleton/secpipe/agents/remediation/jacoco/**`, `secpipe-agents/src/test/resources/llm-fixtures/remediation-loop/**`, `secpipe-eval/src/main/java/ca/carleton/secpipe/eval/remediation/**`, `secpipe-eval/src/test/java/ca/carleton/secpipe/eval/remediation/**`, `secpipe-eval/src/test/resources/ca/carleton/secpipe/eval/remediation/**`, `scripts/eval/remediation.sh`, `docs/work-orders/WO-M.4.md` (this file, verbatim).

Files you may read but not modify: `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/{RemediationAgent,UnifiedDiff}.java` and `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/testgen/**` (WO-G.12 owns them in this wave), `secpipe-report/**` (WO-R.6), `secpipe-sandbox/**`, `secpipe-contracts/**`.

Interfaces consumed: `ArtifactPromoter`, `SandboxRunner`, `TestGenAgent`, `Finding.FixAttempt`   Interfaces produced: unchanged `AdequacyCheck.measure(...)`; scorecard rows with a cross-checked "fixed".

Implementation notes:
- Promote the follow-up test: when the adequacy follow-up testgen call produces a test that is then used, write it next to the attempt's patch (e.g. `outDir/patches/<fp>-attempt<n>-adequacy-test.java`) via `ArtifactPromoter` (declared promotion, existing size caps), and add a run note `adequacy follow-up test for <fp> attempt <n>: <relative path>`. No contract change: the note and the file are the record. Only when the follow-up test exists; nothing else changes.
- Multi-module: production files are `src/main/java/**.java` at the root **or** `<dir>/.../src/main/java/**.java` in any module directory (a path segment sequence `src/main/java/` anywhere, with no `src/test/` before it). The JaCoCo `includes=` package list and the JaCoCo report lookup must use the package path after `src/main/java/`. `PRODUCTION_ROOT` may stay as a constant for the root case. Add a two-module test fixture (XML + patch).
- Scorecard "fixed": a row counts as fixed only if `remediation_status=fixed` **and** the attempt with the highest `attempt` number has outcome `fixed` **and** that attempt has a patch. A status/attempt mismatch is a row with outcome `inconsistent` (counted in failures, listed in the scorecard notes). Also cover the two cases the review asked for: absent `remediation_status`, and the empty-table branch.
- Key pre-check in `remediation.sh`, by provider: `anthropic` → one of `SECPIPE_API_KEY`, `ANTHROPIC_API_KEY`, `SECPIPE_LLM_KEY`; `openai` and `openai-compatible` → one of `SECPIPE_API_KEY`, `OPENAI_API_KEY`, `SECPIPE_LLM_KEY` (`openai-compatible` also needs `--llm-base-url`); `none` → no key; `ollama` → no key (local endpoint; `--llm-base-url` optional); `fake` → `SECPIPE_ALLOW_FAKE_LLM=1` and `SECPIPE_FAKE_LLM_DIR`. Errors are one line plus `hint:` naming the right variables. Never echo a key value. Add a `--check-env` dry-run flag that runs only the pre-checks and exits 0/2, so it can be tested without Docker or keys. `--check-env` must run the argument and key checks **before** the Docker and jar checks, and exit right after them (no Docker check at all under `--check-env`).

Tests to write: follow-up test promoted + note (FakeSandboxRunner + fake LLM); no promotion when there is no follow-up; multi-module changed lines measured and `includes=` correct; `src/test/java` under a module ignored; scorecard: mismatch → `inconsistent`, absent status, empty table; a shell test (JUnit invoking `bash scripts/eval/remediation.sh --check-env` with a controlled env, no network) for anthropic-with-OPENAI-only → exit 2, openai-with-OPENAI_API_KEY → exit 0, none → exit 0.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='AdequacyCheck*Test,JacocoXml*Test,RemediationLoopNode*Test'` → 0 failures, ≥ 4 new tests
- [ ] `./mvnw -q -B -pl secpipe-eval -am test` → 0 failures, ≥ 4 new tests
- [ ] `env -i PATH="$PATH" OPENAI_API_KEY=x bash scripts/eval/remediation.sh --provider anthropic --check-env; echo $?` → `2` with a `hint:` line
- [ ] `python3 -B scripts/check-plan.py` → `errors=0`
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` and `sandbox` green; the sqli-fixable IT still reaches `fixed`)

Out of scope: the real E.6 run (`[HUMAN]`, Brant); report rendering of the new note (WO-R.6 may pick it up later); prompt changes (WO-G.12/G.13).

Handoff note for next agent: Brant's E.6 run should use `bash scripts/eval/remediation.sh --check-env` first. WO-G.13 (prompt tuning) opens only if the real fix rate is under 50 %.
