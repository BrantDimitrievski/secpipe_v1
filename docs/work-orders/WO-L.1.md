### WO-L.1: Build the LLM core — routing, fake client, retries/fallback, call budget, redaction
Wave: 3   Depends on: WO-C.3, WO-C.4, WO-CC.1   Blocks: WO-L.2, WO-G.1, WO-G.4, WO-K.13
Milestone: Prototype 2 Dec 5
Traces: FR-1, FR-2, NFR-5 · A, O
Suggested human owner: Clinton Odinamadu (reviewer: Brant Dimitrievski)
Size: M

Goal (2–3 sentences): `LlmClients.assemble(config, env, budget, outDir)` is the single LLM assembly point: it builds a routing client over the configured provider (ServiceLoader `LlmProviderFactory`), the CI `FakeLlmClient`, or `NoneLlmClient`. It folds in the essentials of retry/fallback (old L.7), the agent-call budget (old K.10) and secret redaction, plus per-role model and tier routing and usage accounting.

Context to read first (exact paths only): contracts `spi/LlmClient.java`, `spi/LlmProviderFactory.java`, `spi/Budget.java`, `config/ProviderMode.java`; `docs/work-orders/stretch/WO-L.7.md`; `docs/work-orders/stretch/WO-K.10.md`

Files you own (create/modify — nothing else): `secpipe-llm/src/**`.

Files you may read but not modify: contracts.

Interfaces consumed: `LlmProviderFactory`, `Budget`   Interfaces produced: `LlmClients.assemble(...) → LlmClient`; `FakeLlmClient.fromDir(Path)`; `ModelRouting.forRole(role) → (model, tier)`; `CallBudget implements Budget` (`--max-agent-calls`); `UsageLedger.snapshot()`; `Redactor.redact(String)`; `SecretLeakAssertions` (test fixture).

Implementation notes:
- Retry: up to 2 retries with exponential backoff on 429/5xx/timeouts; then fall back to the next configured model; each attempt counts against the budget.
- `CallBudget` reserves before a call and refunds on transport failure; exhausted budget → `Unavailable`, never an exception to the user.
- API keys are read from env at call time only; `Redactor` scrubs key values from every error message and usage record.
- Providers are `none`, `anthropic`, `openai`, `openai-compatible`, `ollama`, `fake` (post-CC.1); no copilot.
- Dropped: capability-tier guardrail module (L.6), privacy denylist/`--print-prompts` (L.8).

Tests to write: `FakeLlmClientTest`, `RoutingLlmClientTest` (retry, fallback, budget per attempt), `CallBudgetTest`, `RedactorTest` + `SecretLeakAssertions` on errors and usage.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-llm -am test` → ≥ 15 tests, 0 failures
- [ ] A test asserts an injected key never appears in exception messages, usage records or logs
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: concrete providers (L.2), Ollama, privacy controls.

Handoff note for next agent: L.2 registers factories; agents call only `LlmClient` from `RunContext`.
