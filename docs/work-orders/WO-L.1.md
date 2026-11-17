### WO-L.1: Build the LLM core — routing client, fake client, role→model mapping, usage accounting
Wave: 2   Depends on: WO-C.3, WO-C.4   Blocks: WO-G.1, WO-K.13, WO-L.6, WO-L.2, WO-0.2, WO-L.3, WO-L.7, WO-L.4, WO-L.5, WO-L.8
Milestone: Prototype 2 Dec 5
Traces: FR-1, FR-2 · A, O
Suggested human owner: Clinton Odinamadu (reviewer: Brant Dimitrievski)
Size: M

Goal (2–3 sentences): `RoutingLlmClient implements LlmClient` selects the provider via `LlmProviderFactory`
ServiceLoader, maps role → model (config > provider defaults), and calls the provider. It records usage per
role (calls, retries, discards, tokens) and exposes `ModelInfo`. `FakeLlmClient` replays recorded fixtures
deterministically for CI.

Context to read first (exact paths only): contracts `spi/LlmClient.java`, `spi/LlmProviderFactory.java`,
`agent/AgentRole.java`, `config/SecPipeConfig.java`, `run/RunMetadata.java` (`Usage`, `ModelAssignment`);
`docs/PLAN.md` ADR-10.

Files you own (create/modify — nothing else): `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/RoutingLlmClient.java`,
`secpipe-llm/src/main/java/ca/carleton/secpipe/llm/FakeLlmClient.java`, `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/FakeFixture.java`, `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/UsageLedger.java`, `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/ModelRouting.java`,
`secpipe-llm/src/main/java/ca/carleton/secpipe/llm/NoneLlmClient.java`, `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/LlmClients.java`, `secpipe-llm/src/test/**`.

Files you may read but not modify: contracts.

Interfaces consumed: `LlmProviderFactory`, `Budget`   Interfaces produced: `RoutingLlmClient.create(config, env,
budget)`, `FakeLlmClient.fromDir(Path)`, `UsageLedger.snapshot() → List<Usage>`, and
**`LlmClients.assemble(config, env, budget, outDir) → LlmClient`**. This is the single assembly point the
CLI calls; L.7 and L.8 extend it in later waves, so the CLI never changes for LLM wiring.

Implementation notes:
- **Fake fixture format:** `{"role":"disprover","match":["LoginServlet.java","CWE-89"],"response":"<raw text>",
  "input_tokens":100,"output_tokens":50}`. The first fixture whose `match` strings all occur in the user
  prompt wins. With no match → the raw text `NO_FIXTURE`, which callers will discard (that exercises the
  discard path).
- `FakeLlmClient.modelFor` returns `fake-strong` / `fake-small` by role, using an optional
  `tiers.json` in the fixture dir.
- `NoneLlmClient.mode()==NONE`; `complete` throws `LlmUnavailableException`.
- Fallback and retries are **not** here (WO-L.7): a single attempt, one budget acquire.

Tests to write: routing precedence; fake matching and order; usage accounting; the `NONE` behaviour; a
budget acquire per call (stub budget).

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-llm -am test` → ≥ 10 tests, 0 failures
- [ ] `grep -rn "System.getenv" secpipe-llm/src/main` → only inside a single `EnvSource` default (the env is injected for tests)

Out of scope: real providers (L.2–L.5), tiers (L.6), fallback (L.7), privacy (L.8).

Handoff note for next agent: Agents never see provider names. `ModelInfo.tier` comes from L.6 once it's
merged (default `standard` until then).
