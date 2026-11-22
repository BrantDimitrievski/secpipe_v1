### WO-L.9: Current default Claude models, honest `ollama` flag, real-provider smoke script
Wave: 14   Depends on: WO-L.2, WO-CC.2, WO-D.5   Blocks: none
Milestone: Oral Jan 25
Traces: FR-11 · L, Q
Suggested human owner: Brant Dimitrievski (reviewer: Clinton Odinamadu)
Size: S

Goal (2–3 sentences): Get the LLM layer ready for the first real run. The `anthropic` provider defaults to model ids that are a generation old, `--llm-provider ollama` is accepted although no provider exists, and there is no cheap way to check a real key and endpoint before the long E.6 run. Fix all three (owner decision 2026-11-22).

Context to read first (exact paths only): `secpipe-llm-anthropic/src/main/java/ca/carleton/secpipe/llm/anthropic/AnthropicProviderFactory.java`; `secpipe-llm-anthropic/src/test/java/ca/carleton/secpipe/llm/anthropic/AnthropicProviderTest.java`; `secpipe-cli/src/main/java/ca/carleton/secpipe/cli/ScanCommand.java` (how `--print-prompts` and `--engine langgraph4j` exit 2); `secpipe-cli/src/test/java/ca/carleton/secpipe/cli/ScanCommandTest.java`; `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/LlmClients.java`; `scripts/eval/remediation.sh` (the `--check-env` pattern); `docs/user/guide.md` (sections on providers and Limitations); `fixtures/remediation/sqli-fixable/README.md`.

Files you own (create/modify — nothing else): `secpipe-llm-anthropic/src/main/java/ca/carleton/secpipe/llm/anthropic/AnthropicProviderFactory.java`, `secpipe-llm-anthropic/src/test/**`, `secpipe-cli/src/main/java/ca/carleton/secpipe/cli/ScanCommand.java`, `secpipe-cli/src/test/java/ca/carleton/secpipe/cli/ScanCommandTest.java`, `scripts/eval/smoke.sh` (new), `scripts/eval/remediation.sh` (the `ollama` handling only), `secpipe-eval/src/test/java/ca/carleton/secpipe/eval/remediation/RemediationScriptTest.java`, `scripts/docs/check-snippets.sh`, `docs/user/guide.md`, `README.md`, `docs/work-orders/WO-L.9.md` (this file, verbatim).

Files you may read but not modify: everything else; in particular `secpipe-contracts/**` (`ProviderMode` keeps its `OLLAMA` value), `secpipe-llm-openai/**`, `secpipe-llm-ollama/**`, `action.yml` and `docs/uml/**` (they still mention ollama; leave them).

Interfaces consumed: `LlmProviderFactory`, `ProviderMode`, the CLI flags of PLAN §4.3 (names frozen)   Interfaces produced: `scripts/eval/smoke.sh`.

Implementation notes:
- Defaults: large tier → `claude-opus-5-5`, small tier → `claude-sonnet-5-5` (owner's choice; `--model` still overrides). Leave the OpenAI defaults alone. Update any test or doc that names the old ids.
- `ollama`: the flag name stays (frozen), the provider is not built. Handle it exactly like `--print-prompts`: `secpipe scan --llm-provider ollama` exits 2 with a one-line problem and a `hint:` that names the working route (`--llm-provider openai-compatible --llm-base-url http://localhost:11434/v1 --model <name>`). Do the check on the **resolved** configuration, where `--print-prompts` is checked, so `--no-ai --llm-provider ollama` still resolves to `none` and runs (`ConfigResolver` and its tests are unchanged). `--offline --llm-provider ollama` therefore exits 2. Help text: `--llm-provider` lists `none|anthropic|openai|openai-compatible` and says `ollama` is not built; `--offline` reads "No network (implies --llm-provider none)". `scripts/eval/remediation.sh`: `--provider ollama` fails the pre-check with the same hint (update `RemediationScriptTest`, and add its missing case: `--check-env` together with `--from` skips the key check). README line "providers (OpenAI, Anthropic, Ollama)" drops Ollama. No contract change.
- `scripts/eval/smoke.sh [--provider P] [--model ID] [--llm-base-url URL] [--check-env]`: (1) the same provider-aware key pre-check as `remediation.sh` (copy the function; do not source or edit that script), never echoing a key; (2) build the jar if missing; (3) scan `fixtures/remediation/sqli-fixable` with the real provider, `--profile quick`, `--no-sandbox`, `--enable-fixes false`, into a temp dir; (4) run the scan with `-v` and keep its stdout/stderr in the temp dir; pass only if `run-metadata.json` has `budget.used` ≥ 1 **and** the text `LLM unavailable` appears nowhere in the captured log, `findings.json`, `run-metadata.json` or `report.md`; print the provider, the model id passed (or "provider default"), `budget.used` and each finding's verdict; exit 1 with a `hint:` otherwise. (`ai.usage` in run metadata is empty today; WO-K.16 fills it, and this script must not depend on it.) `--check-env` runs step 1 only (exit 0/2). Never print the environment or a key.
- Guide: provider table (drop any Ollama promise, add the openai-compatible local route), a short "Check your key first" paragraph that shows `bash scripts/eval/smoke.sh`, Limitations line for Ollama. Keep `scripts/docs/check-snippets.sh` green; add the `ollama` exit-2 case to it.

Tests to write: defaults are the two new ids; `--llm-provider ollama` → exit 2, stderr has the problem line and the hint, nothing is scanned; `--help` text no longer lists ollama as a working provider; `smoke.sh --check-env` with a controlled environment (JUnit invoking bash, as `RemediationScriptTest` does, placed in `secpipe-cli/src/test/java/ca/carleton/secpipe/cli/ScanCommandTest.java` or a new test under `secpipe-llm-anthropic/src/test/**`): anthropic without a key → 2; anthropic with `ANTHROPIC_API_KEY` → 0.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-llm-anthropic,secpipe-cli -am test` → 0 failures, ≥ 4 new tests
- [ ] `grep -rn 'claude-opus-4-5\|claude-sonnet-4-5' --include=*.java --include=*.md --include=*.sh . | grep -v docs/work-orders | grep -v docs/PLAN.md | grep -v docs/coordination` → no output
- [ ] `env -i PATH="$PATH" bash scripts/eval/smoke.sh --provider anthropic --check-env; echo $?` → `2` with a `hint:` line
- [ ] [HUMAN] `ANTHROPIC_API_KEY=… bash scripts/eval/smoke.sh --provider anthropic` → exit 0 on Brant's machine
- [ ] `python3 -B scripts/check-plan.py` → `errors=0`
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` and `sandbox` green)

Out of scope: building the Ollama provider (stretch WO-L.4); OpenAI default ids; prompt changes.

Handoff note for next agent: Brant runs `smoke.sh` before the E.6 run and before any AI-on Benchmark run.
