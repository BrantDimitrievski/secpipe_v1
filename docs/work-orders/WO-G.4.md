### WO-G.4: Implement the adversarial disprover agent and node
Wave: 5   Depends on: WO-G.1, WO-K.8, WO-K.4, WO-L.1   Blocks: WO-G.7, WO-K.13
Milestone: Prototype 2 Dec 5
Traces: FR-1, FR-3, FR-8 · A, D, E, F, I
Suggested human owner: Clinton Odinamadu (reviewer: Brant Dimitrievski)
Size: M

Goal (2–3 sentences): A fresh `DisproverAgent` per candidate tries to refute it from source context, returning `refuted` with a grounded control location or `survives` with root cause and proposed severity. Survivors go to `StateDelta.putSurvivorDrafts` for test generation.

Context to read first (exact paths only): `docs/PLAN.md` Part 5 §A7; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/runner/AgentRunner.java`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/recon/ReconFacts.java`; `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/ModelRouting.java`; contracts `agents/DisproverResult.java`; Cloudflare `references/adversarial-review.md`

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/disprover/**`, `secpipe-agents/src/main/resources/prompts/disprover.v1.txt`, `secpipe-agents/src/main/resources/disprover/**`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/disprover/**`, `secpipe-agents/src/test/resources/llm-fixtures/disprover/**`.

Files you may read but not modify: runner, refuter, llm, recon.

Interfaces consumed: `AgentRunner`, `ModelRouting`, `ReconFacts`, `SourceIndex`   Interfaces produced: `DisproverNode`, `DisproverAgent`, `DisproverContextBuilder`; survivor drafts in `PipelineState.survivorDrafts`.

Implementation notes:
- Context = sink method + caller chain from the trace, via `PromptRenderer.untrusted(...)`, capped in size.
- Guardrail (replaces L.6): if the routed tier is `small`, a `refuted` verdict on a tool-flagged candidate becomes `needs_validation`, never `rejected`.
- `Dropped`/`Unavailable` outcomes leave the candidate `needs_validation` with the reason; AI mode `none` skips the node.
- One fresh agent per candidate; no shared conversation state.

Tests to write: fake-LLM fixtures: refuted (grounded) → rejected; refuted with ungrounded control → re-run then dropped; survives → draft; small-tier refutation → `needs_validation`.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='Disprover*Test'` → ≥ 8 tests, 0 failures
- [ ] A test asserts a `small`-tier model alone never yields `rejected` for a tool-flagged candidate
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: record verifier (G.8, stretch).

Handoff note for next agent: G.7 reads survivor drafts; K.13 wires the node after the refuter.
