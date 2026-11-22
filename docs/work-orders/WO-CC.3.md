### WO-CC.3: Contract change — expose LLM usage on `LlmClient` and record it in run metadata
Wave: 14   Depends on: WO-L.1, WO-K.9, WO-R.5   Blocks: none
Milestone: Oral Jan 25
Traces: FR-15, NFR-6 · L, P
Suggested human owner: Elodie Abdo (reviewer: Brant Dimitrievski)
Size: M

Goal (2–3 sentences): `run-metadata.json` has an `ai` block (models, usage per role, fallbacks, effective mode), but the engine always writes it empty and sets `effective_mode` to the requested mode. `UsageLedger` already counts calls, retries, discards and tokens per role. Core and report cannot see `secpipe-llm`, so there is no seam: this order adds one small accessor to the contracts `LlmClient` (a contract change proposed by the coordinator in the 2026-11-22 re-plan and flagged to the owner) and wires the real numbers into the frozen run metadata, so the first real runs (E.6, Benchmark) report calls and tokens.

Context to read first (exact paths only): `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/run/RunMetadata.java` (find the real path with `find secpipe-contracts -name RunMetadata.java`); the run-metadata JSON schema next to it under `secpipe-contracts/src/main/resources`; `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/{UsageLedger,SecPipeLlmClient,RoutingLlmClient,LlmClients,NoneLlmClient,FakeLlmClient}.java`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/finalize/RunMetadataBuilder.java` and its callers; `secpipe-report/src/main/java/ca/carleton/secpipe/report/RunMetadataAssembler.java`; `secpipe-cli/src/main/java/ca/carleton/secpipe/cli/PipelineAssembly.java`; `docs/work-orders/WO-R.5.md`.

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/finalize/**`, `secpipe-core/src/test/java/ca/carleton/secpipe/core/finalize/**`, `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/spi/LlmClient.java` (one default method, see notes), `secpipe-contracts/src/test/**` (a test for the default), `secpipe-report/src/main/java/ca/carleton/secpipe/report/{RunMetadataAssembler,ReportNode}.java`, `secpipe-report/src/test/java/ca/carleton/secpipe/report/{RunMetadataAssembler*Test,ReportNode*Test,ReportTest,Fixtures}.java`, `secpipe-report/src/test/resources/ca/carleton/secpipe/report/golden/run-metadata.json` (only if the fake-LLM golden must change), `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/{UsageLedger,SecPipeLlmClient,RoutingLlmClient,LlmClients,NoneLlmClient,FakeLlmClient}.java`, `secpipe-llm/src/test/**`, `secpipe-cli/src/main/java/ca/carleton/secpipe/cli/PipelineAssembly.java`, `secpipe-cli/src/test/java/ca/carleton/secpipe/cli/PipelineAssembly*Test.java`, `secpipe-cli/src/test/java/ca/carleton/secpipe/cli/e2e/**`, `docs/work-orders/WO-CC.3.md` (this file, verbatim).

Files you may read but not modify: the rest of `secpipe-contracts/**` (records and JSON schemas stay as they are: `RunMetadata.Ai`, `Usage`, `ModelAssignment`, `Fallback` already exist), `secpipe-cli/src/main/java/ca/carleton/secpipe/cli/ScanCommand.java` and `secpipe-llm-anthropic/**` (WO-L.9), `secpipe-agents/**` (WO-M.5), the other golden files under `secpipe-report/src/test/resources/**`, `SarifSchemaTest` and `GoldenVerifiedFixTest` (WO-M.5).

Interfaces consumed: `UsageLedger`, `RunMetadata.Ai`, `LlmClient` (contracts)   Interfaces produced: a populated `ai` block in `run-metadata.json`.

Implementation notes:
- The seam: `RunContext.llm()` returns the contracts `LlmClient` (`mode()`, `modelFor()`, `complete()`), and neither `secpipe-core` nor `secpipe-report` depends on `secpipe-llm`. Add exactly one default method to the contracts `LlmClient`: `default RunMetadata.Ai usageSnapshot() { ... }` returning an `Ai` with `requestedMode = effectiveMode = mode()` and empty lists (so every existing implementation and test double keeps compiling). `SecPipeLlmClient`/`RoutingLlmClient` override it from `UsageLedger`; `NoneLlmClient` keeps the default; `FakeLlmClient` reports its scripted calls. `RunMetadataBuilder` and `RunMetadataAssembler` (via `ReportNode`, which has `ctx`) read `ctx.llm().usageSnapshot()`. That is the whole contract change: no schema edit, no new record, no other interface. If you find you need more than this one method, stop and report (§11). No side channels (no static holders, no reflection, no extra JSON).
- Branch name must start with `wo-cc.3-` (CI's contract guard allows contract edits only there).
- Fill: `usage` (one entry per role that made calls, sorted by role), `models` (role → model id actually used), `fallbacks` (if the router recorded any), and `effective_mode`: the requested mode when at least one provider call succeeded or no call was needed, `none` when the provider was unavailable from the start (`LlmClients.unavailable`). With `--no-ai` or `none`: empty lists, as today.
- Determinism (§8): the same fake-LLM run must give byte-identical metadata; no wall-clock values, no model-reported ids that vary. Never write a key, a header or a prompt into metadata (`SecretLeakAssertions` in a test).
- R.5 rule stays: `ReportNode` keeps finalize's frozen file; `RunMetadataAssembler` only serves the path where no frozen file exists and must produce the same `ai` block from the same inputs.
- Existing e2e expectations and goldens that compare run metadata may change for fake-LLM runs: update the ones you own; if a file you do not own must change, stop and report it.

Tests to write: fake-LLM run with two roles → `usage` has both roles with the right call counts and token sums; `--no-ai` → empty block, `effective_mode=none`; unavailable provider (fake without the allow variable) → `effective_mode=none` and no usage; byte-stable across two runs; no secret in the metadata; schema validation of the produced file.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core,secpipe-llm,secpipe-report,secpipe-cli -am test` → 0 failures, ≥ 6 new tests
- [ ] `./mvnw -q -B -Pe2e verify` → exit 0, and the e2e run's `run-metadata.json` has a non-empty `ai.usage`
- [ ] `git diff --name-only origin/main...HEAD | grep '^secpipe-contracts/'` → only `LlmClient.java` and files under `secpipe-contracts/src/test/`
- [ ] `python3 -B scripts/check-plan.py` → `errors=0`
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` and `sandbox` green)

Out of scope: cost in dollars; `prompts_hash`; showing usage in `report.md` (a later report order); `guardrail_downgrades`.

Handoff note for next agent: `scripts/eval/smoke.sh` (WO-L.9) can later switch from `budget.used` to `ai.usage`; the E.6 scorecard can add calls and tokens per fix.
