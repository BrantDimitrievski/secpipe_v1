### WO-L.4: Implement the Ollama provider
Wave: 6   Depends on: WO-L.1   Blocks: WO-E.10
Milestone: Oral Jan 25
Traces: FR-2, NFR-5 · (§2.7)
Suggested human owner: Abdul Babatunde (reviewer: Clinton Odinamadu)
Size: S

Goal (2–3 sentences): `OllamaProviderFactory` uses LangChain4j Ollama with `format=json`. The base URL
defaults to `http://localhost:11434` and no key is needed. It is the fully local privacy mode (NFR-5),
with honest small-tier defaults.

Context to read first (exact paths only): contracts `spi/LlmProviderFactory.java`; `secpipe-llm-openai/src/main/java/**`.

Files you own (create/modify — nothing else): `secpipe-llm-ollama/src/**`.

Files you may read but not modify: `secpipe-llm/**`.

Interfaces consumed: `LlmProviderFactory`   Interfaces produced: factory for `OLLAMA`.

Implementation notes:
- `probe()` does `GET /api/tags` with a 2 s timeout; the model must be pulled, else the reason is "model
  <id> not pulled; run `ollama pull <id>`".
- Default models are `small` tier unless the user overrides the tier.
- `offline` mode still allows Ollama on localhost/loopback only. Reject a non-loopback base URL when
  `offline` (NFR-5).

Tests to write: WireMock for `/api/chat` and `/api/tags`; the offline + remote URL rejection.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-llm-ollama -am verify` → exit 0
- [ ] A test asserts `offline=true` with base URL `http://10.0.0.5:11434` → `probe()` returns a reason containing "offline"

Out of scope: GPU runner docs (WO-D.1).

Handoff note for next agent: The tier defaults matter for the guardrail (L.6). Keep them `small`.
