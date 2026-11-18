### WO-L.2: Implement the OpenAI-compatible and Anthropic providers
Wave: 4   Depends on: WO-L.1   Blocks: WO-D.1
Milestone: Prototype 2 Dec 5
Traces: FR-2, NFR-3 · O
Suggested human owner: Brant Dimitrievski (reviewer: Clinton Odinamadu)
Size: M

Goal (2–3 sentences): Add `LlmProviderFactory` implementations for `openai` / `openai-compatible` (Chat Completions, configurable base URL) and `anthropic` (Messages API). Both are fixture-tested against fake HTTP with the pinned WireMock; no test touches the network.

Context to read first (exact paths only): contracts `spi/LlmProviderFactory.java`, `spi/LlmClient.java`; `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/LlmClients.java`; `docs/work-orders/stretch/WO-L.3.md`; `docs/PLAN.md` §4.3 (provider env vars)

Files you own (create/modify — nothing else): `secpipe-llm-openai/src/**`, `secpipe-llm-anthropic/src/**`.

Files you may read but not modify: `secpipe-llm/**`.

Interfaces consumed: `LlmProviderFactory`   Interfaces produced: factories `OPENAI`, `OPENAI_COMPATIBLE`, `ANTHROPIC` registered in each module's services file.

Implementation notes:
- Request JSON-only output where the API supports it; return the raw text, never repair it.
- Map 429/5xx/timeouts to the retryable error type L.1 expects; 4xx auth errors are not retried and carry a `hint:` naming the env var.
- Keys come from env at call time; assert with `SecretLeakAssertions` that they never reach logs or exceptions.
- Dropped: live smoke workflows (stretch).

Tests to write: per provider: happy path, 429 → retryable, 401 → hint, malformed body → error; WireMock stubs under `src/test/resources/`.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-llm-openai,secpipe-llm-anthropic -am test` → ≥ 10 tests, 0 failures
- [ ] A test asserts the `Authorization`/`x-api-key` header is set from env and never appears in a thrown message
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: Ollama (stretch), streaming.

Handoff note for next agent: Users pick a provider with `--llm-provider`; D.1 documents the env vars.
