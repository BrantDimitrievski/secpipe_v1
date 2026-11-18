### WO-G.1: Build the prompt framework and AgentRunner
Wave: 4   Depends on: WO-L.1, WO-C.3   Blocks: WO-G.4, WO-G.6
Milestone: Prototype 2 Dec 5
Traces: FR-1, NFR-5 · A, C, I, R
Suggested human owner: Clinton Odinamadu (reviewer: Elodie Abdo)
Size: M

Goal (2–3 sentences): `AgentRunner` renders a versioned prompt, calls the `LlmClient`, decodes exactly one JSON object with the frozen C.3 codecs, and re-runs with a fresh agent on malformed or ungrounded output (never repairs). `DefaultPromptRenderer` puts repo content in prompts only via `PromptRenderer.untrusted(...)`.

Context to read first (exact paths only): contracts `spi/Agent.java`, `agents/*.java` (C.3 codecs); `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/FakeLlmClient.java`; `docs/PLAN.md` Part 5 (shared rules); Cloudflare `SKILL.md`

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/prompt/**`, `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/runner/**`, `secpipe-agents/src/main/resources/prompts/common/**`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/prompt/**`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/runner/**`, `secpipe-agents/src/test/resources/llm-fixtures/runner/**`.

Files you may read but not modify: contracts, `secpipe-llm`.

Interfaces consumed: `Agent<I,O>`, `LlmClient`, `Budget`, C.3 codecs   Interfaces produced: `AgentRunner.run(Agent<I,O>, I, GroundingContext) → AgentOutcome<O>` (`Ok`, `Dropped(reason, attempts)`, `Unavailable`); `DefaultPromptRenderer`; `GroundingCheck` (minimal).

Implementation notes:
- Strict decode only (no lenient parsing). Max 2 fresh re-runs, then `Dropped`.
- Minimal grounding (folded from V.3): every cited file/line must exist in `SourceIndex` and every cited fingerprint in the input.
- Untrusted blocks get random delimiters and an instruction that their content is data.
- Only shared prompt fragments live here; agent prompts belong to G.4/G.6. Cloudflare attribution in `THIRD_PARTY_NOTICES.md` already exists.

Tests to write: `AgentRunnerTest` (ok, malformed → re-run → ok, malformed ×3 → dropped, ungrounded → re-run, budget exhausted → unavailable), `DefaultPromptRendererTest` (untrusted delimiting).

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='AgentRunner*Test,DefaultPromptRenderer*Test'` → ≥ 10 tests, 0 failures
- [ ] A test asserts malformed output is never passed to the codec a second time (fresh call each attempt)
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: hunter/critic/verifier/remediation prompts (stretch).

Handoff note for next agent: G.4 and G.6 implement `Agent<I,O>` and call only `AgentRunner`.
