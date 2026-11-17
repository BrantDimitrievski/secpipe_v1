### WO-L.3: Implement the Anthropic provider
Wave: 5   Depends on: WO-L.1   Blocks: WO-E.10
Milestone: Prototype 2 Dec 5
Traces: FR-2 · (§2.7)
Suggested human owner: Clinton Odinamadu (reviewer: Elodie Abdo)
Size: S

Goal (2–3 sentences): `AnthropicProviderFactory` uses LangChain4j's Anthropic chat model. It reads the key
from `SECPIPE_API_KEY`/`ANTHROPIC_API_KEY`, maps rate limits to `RateLimitedException`, and ships default
strong/standard model ids plus a manual smoke workflow.

Context to read first (exact paths only): contracts `spi/LlmProviderFactory.java`; `secpipe-llm-openai/src/main/java/**`
(pattern reference).

Files you own (create/modify — nothing else): `secpipe-llm-anthropic/src/**`, `.github/workflows/llm-smoke-anthropic.yml`.

Files you may read but not modify: `secpipe-llm/**`, `secpipe-llm-openai/**`.

Interfaces consumed: `LlmProviderFactory`   Interfaces produced: factory for `ANTHROPIC`.

Implementation notes:
- JSON-only output: system prompt rule plus a prefilled assistant `{` if supported. Otherwise rely on the
  strict parse + discard.
- Default models: verify the current Claude model ids at implementation time and record them with the date.
- Same WireMock test approach as L.2.

Tests to write: as in L.2.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-llm-anthropic -am verify` → exit 0
- [ ] The smoke workflow is `workflow_dispatch` only

Out of scope: tool use, streaming.

Handoff note for next agent: none beyond the defaults table.
