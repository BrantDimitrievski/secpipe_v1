### WO-K.16: Record real LLM usage in run metadata
Wave: 14   Depends on: WO-L.1, WO-K.9, WO-R.5   Blocks: none
Milestone: Oral Jan 25
Traces: FR-15, NFR-6 · L, P
Suggested human owner: Elodie Abdo (reviewer: Brant Dimitrievski)
Size: M

Goal (2–3 sentences): `run-metadata.json` has an `ai` block (models, usage per role, fallbacks, effective mode), but the engine always writes it empty and sets `effective_mode` to the requested mode. `UsageLedger` already counts calls, retries, discards and tokens per role. Wire the real numbers into the frozen run metadata, so the first real runs (E.6, Benchmark) report calls and tokens.

Context to read first (exact paths only): `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/run/RunMetadata.java` (find the real path with `find secpipe-contracts -name RunMetadata.java`); the run-metadata JSON schema next to it under `secpipe-contracts/src/main/resources`; `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/{UsageLedger,SecPipeLlmClient,RoutingLlmClient,LlmClients,NoneLlmClient,FakeLlmClient}.java`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/finalize/RunMetadataBuilder.java` and its callers; `secpipe-report/src/main/java/ca/carleton/secpipe/report/RunMetadataAssembler.java`; `secpipe-cli/src/main/java/ca/carleton/secpipe/cli/PipelineAssembly.java`; `docs/work-orders/WO-R.5.md`.

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/finalize/**`, `secpipe-core/src/test/java/ca/carleton/secpipe/core/finalize/**`, `secpipe-report/src/main/java/ca/carleton/secpipe/report/RunMetadataAssembler.java`, `secpipe-report/src/test/java/ca/carleton/secpipe/report/RunMetadataAssembler*Test.java`, `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/{UsageLedger,SecPipeLlmClient,RoutingLlmClient,LlmClients,NoneLlmClient,FakeLlmClient}.java`, `secpipe-llm/src/test/**`, `secpipe-cli/src/main/java/ca/carleton/secpipe/cli/PipelineAssembly.java`, `secpipe-cli/src/test/java/ca/carleton/secpipe/cli/PipelineAssembly*Test.java`, `secpipe-cli/src/test/java/ca/carleton/secpipe/cli/e2e/**`, `docs/work-orders/WO-K.16.md` (this file, verbatim).

Files you may read but not modify: `secpipe-contracts/**` (frozen: use the existing `RunMetadata.Ai`, `Usage`, `ModelAssignment`, `Fallback` records and whatever interface already exposes usage; add nothing), `secpipe-cli/src/main/java/ca/carleton/secpipe/cli/ScanCommand.java` and `secpipe-llm-anthropic/**` (WO-L.9), `secpipe-agents/**` (WO-M.5), golden report files under `secpipe-report/src/test/resources/**`.

Interfaces consumed: `UsageLedger`, `RunMetadata.Ai`, `LlmClient` (contracts)   Interfaces produced: a populated `ai` block in `run-metadata.json`.

Implementation notes:
- First find the seam: how can the finalize step (in `secpipe-core`, which may not depend on `secpipe-llm`: check `secpipe-core/pom.xml`) obtain usage? Use an existing contracts interface or `RunContext` accessor if one exposes usage or the LLM client. If none does and the only way is a new contracts method or field, **stop and report under "Contract change needed" (§11)**; do not add side channels (no static holders, no reflection, no extra JSON).
- Fill: `usage` (one entry per role that made calls, sorted by role), `models` (role → model id actually used), `fallbacks` (if the router recorded any), and `effective_mode`: the requested mode when at least one provider call succeeded or no call was needed, `none` when the provider was unavailable from the start (`LlmClients.unavailable`). With `--no-ai` or `none`: empty lists, as today.
- Determinism (§8): the same fake-LLM run must give byte-identical metadata; no wall-clock values, no model-reported ids that vary. Never write a key, a header or a prompt into metadata (`SecretLeakAssertions` in a test).
- R.5 rule stays: `ReportNode` keeps finalize's frozen file; `RunMetadataAssembler` only serves the path where no frozen file exists and must produce the same `ai` block from the same inputs.
- Existing e2e expectations and goldens that compare run metadata will change for fake-LLM runs: update the ones you own; if a golden you do not own must change, stop and report it.

Tests to write: fake-LLM run with two roles → `usage` has both roles with the right call counts and token sums; `--no-ai` → empty block, `effective_mode=none`; unavailable provider (fake without the allow variable) → `effective_mode=none` and no usage; byte-stable across two runs; no secret in the metadata; schema validation of the produced file.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core,secpipe-llm,secpipe-report,secpipe-cli -am test` → 0 failures, ≥ 6 new tests
- [ ] `./mvnw -q -B -Pe2e verify` → exit 0, and the e2e run's `run-metadata.json` has a non-empty `ai.usage`
- [ ] `git diff --name-only origin/main...HEAD | grep '^secpipe-contracts/'` → no output
- [ ] `python3 -B scripts/check-plan.py` → `errors=0`
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` and `sandbox` green)

Out of scope: cost in dollars; `prompts_hash`; showing usage in `report.md` (a later report order); `guardrail_downgrades`.

Handoff note for next agent: `scripts/eval/smoke.sh` (WO-L.9) can later switch from `budget.used` to `ai.usage`; the E.6 scorecard can add calls and tokens per fix.
