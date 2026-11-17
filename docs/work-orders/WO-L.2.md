### WO-L.2: Implement the OpenAI and OpenAI-compatible provider
Wave: 4   Depends on: WO-L.1   Blocks: WO-E.2, WO-L.7, WO-L.5, WO-E.10
Milestone: Prototype 2 Dec 5
Traces: FR-2, NFR-3 · (§2.7)
Suggested human owner: Brant Dimitrievski (reviewer: Clinton Odinamadu)
Size: S

Goal (2–3 sentences): `OpenAiProviderFactory` serves both `openai` and `openai-compatible` (a base URL plus
a key), using LangChain4j's OpenAI chat model with JSON response format. It maps 429 to
`RateLimitedException` with `Retry-After`, and includes a manual smoke workflow.

Context to read first (exact paths only): contracts `spi/LlmProviderFactory.java`, `spi/LlmClient.java`;
`secpipe-llm/src/main/java/ca/carleton/secpipe/llm/RoutingLlmClient.java`; `docs/PLAN.md` ADR-10.

Files you own (create/modify — nothing else): `secpipe-llm-openai/src/**`, `.github/workflows/llm-smoke-openai.yml`.

Files you may read but not modify: `secpipe-llm/**`.

Interfaces consumed: `LlmProviderFactory`   Interfaces produced: factories for `OPENAI` and `OPENAI_COMPATIBLE`
(two classes in the services file).

Implementation notes:
- Keys come from `SECPIPE_API_KEY` or `OPENAI_API_KEY` at `create()` time; they are never stored in a field
  beyond the LangChain4j client instance; `toString()` must not include them.
- **Default models:** strong `gpt-4.1`, small `gpt-4.1-mini` (verify the current names; record them in
  the class Javadoc with the date).
- `probe()`: key present (no network call). `openai-compatible` needs `llmBaseUrl`.
- Tests use WireMock to emulate `/v1/chat/completions` (200, 429 with Retry-After, 500).
- **Smoke workflow:** `workflow_dispatch` only, uses `secrets.OPENAI_API_KEY`, runs `secpipe scan
  fixtures/e2e/sqli-min --llm-provider openai --max-agent-calls 5`, and uploads the outputs.

Tests to write: WireMock happy path → raw text + tokens; 429 → `RateLimitedException` with the duration;
no key → probe reason; the key absent from `toString` and exception messages.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-llm-openai -am verify` → exit 0 (no network)
- [ ] `grep -n "workflow_dispatch" .github/workflows/llm-smoke-openai.yml` → present, and `grep -c "pull_request" …` → 0

Out of scope: retries (L.7).

Handoff note for next agent: `openai-compatible` also covers Azure/Microsoft Foundry OpenAI-v1 endpoints and
self-hosted gateways. Document the base-URL forms in D.1.
