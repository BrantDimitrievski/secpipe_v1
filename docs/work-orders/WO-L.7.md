### WO-L.7: Implement the fallback chain, retries with backoff, and budget accounting per attempt
Wave: 5   Depends on: WO-L.1, WO-L.2, WO-K.10   Blocks: WO-H.3, WO-E.10
Milestone: Prototype 2 Dec 5
Traces: FR-2, NFR-5 · O, (§2.7 item 3)
Suggested human owner: Elodie Abdo (reviewer: Clinton Odinamadu)
Size: M

Goal (2–3 sentences): Extend `RoutingLlmClient` with the provider fallback chain (configured provider,
if its probe is ok → none). There is no auto-detected GitHub-native provider (WO-CC.1). It adds
exponential backoff with jitter on rate limits (honouring `Retry-After`) and counts **every** attempt against
the budget. Each fallback is recorded
(`from`, `to`, `reason`), so a scan never fails because a provider did.

Context to read first (exact paths only): `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/RoutingLlmClient.java`,
`UsageLedger.java`; contracts `spi/LlmProviderFactory.java`, `spi/Budget.java`, `run/RunMetadata.java` (`Fallback`).

Files you own (create/modify — nothing else): `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/RoutingLlmClient.java`,
`secpipe-llm/src/main/java/ca/carleton/secpipe/llm/LlmClients.java`,
`secpipe-llm/src/main/java/ca/carleton/secpipe/llm/FallbackChain.java`, `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/Backoff.java`,
`secpipe-llm/src/test/java/ca/carleton/secpipe/llm/{FallbackChainTest,BackoffTest,RoutingRetryTest}.java`.

Files you may read but not modify: other `secpipe-llm` files.

Interfaces consumed: `LlmProviderFactory.probe`, `Budget.tryAcquire`   Interfaces produced:
`RoutingLlmClient.fallbacks() → List<Fallback>`, `effectiveMode()`.

Implementation notes:
- **Probe at startup** (in `create`); on failure, fall back immediately.
- **At runtime:** 3 consecutive auth failures, or rate limits exceeding a total backoff of 60 s → fall
  back for the rest of the run.
- **Backoff:** base 1 s, factor 2, jitter ±20 %, max 20 s per wait, max 4 attempts per request. Each
  attempt calls `tryAcquire`; failure → `BudgetExhaustedException`.
- Falling to `none` mid-run: subsequent `complete` throws `LlmUnavailableException`, and nodes mark their
  work `needs_validation` / `blocked` with "AI provider unavailable: <reason>".
- Use an injectable `Sleeper` for tests (no real sleeping).

Tests to write: the chain order; a probe failure; mid-run rate-limit fallback; the attempts counted (budget
stub); `Retry-After` honoured; no sleeping in tests.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-llm -am test -Dtest='FallbackChainTest,BackoffTest,RoutingRetryTest'` → ≥ 12 tests, 0 failures
- [ ] A test asserts 4 rate-limited attempts consume exactly 4 budget calls

Out of scope: Action-level fork detection (H.3).

Handoff note for next agent: `metadata.ai.fallbacks` and `effective_mode` come from here. The report header
prints them.
