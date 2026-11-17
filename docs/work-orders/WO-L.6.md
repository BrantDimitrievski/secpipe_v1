### WO-L.6: Implement capability tiers and the weak-model guardrail
Wave: 3   Depends on: WO-L.1   Blocks: WO-G.4, WO-G.8
Milestone: Prototype 2 Dec 5
Traces: FR-2 · E, F, (§2.7 item 2)
Suggested human owner: Clinton Odinamadu (reviewer: Elaine Huang)
Size: S

Goal (2–3 sentences): Add `CapabilityTiers` (a built-in model→tier table plus `--model-tier` overrides,
unknown → `standard`, Ollama → `small`) and `VerdictGuardrail`. The guardrail rewrites a small-tier
`rejected` on a tool-flagged candidate to `needs_validation`, unless a deterministic refuter or a
strong/standard second verifier agrees. It is enforced in one place and counted in the metadata.

Context to read first (exact paths only): `docs/PLAN.md` ADR-10; contracts `finding/Finding.java`
(`VerifierRef`), `agent/AgentOutputs.java` (`DisproverResult`); `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/ModelRouting.java`.

Files you own (create/modify — nothing else): `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/CapabilityTiers.java`,
`secpipe-llm/src/main/java/ca/carleton/secpipe/llm/VerdictGuardrail.java`,
`secpipe-llm/src/main/resources/llm/capability-tiers.json`,
`secpipe-llm/src/test/java/ca/carleton/secpipe/llm/{CapabilityTiersTest,VerdictGuardrailTest}.java`.

Files you may read but not modify: other `secpipe-llm` files.

Interfaces consumed: `ModelInfo`   Interfaces produced: `CapabilityTiers.tierOf(provider, model, overrides)`;
`VerdictGuardrail.apply(DisproverResult, VerifierRef, boolean toolFlagged, Optional<VerifierRef> corroboration)
→ GuardrailDecision(verdictToRecord, blockerOrEmpty, applied)`.

Implementation notes:
- **The table** (JSON): provider → model glob → tier. Seed it with the current strong/standard/small models
  per provider, verified at implementation time and dated.
- `toolFlagged` = the candidate has ≥ 1 tool source (origin `tool`). Hunter-only candidates are not
  protected by the guardrail but still need grounding.

Tests to write: the full truth table (tier × verdict × toolFlagged × corroboration); overrides; unknown
model → standard; Ollama → small.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-llm test -Dtest='CapabilityTiersTest,VerdictGuardrailTest'` → ≥ 16 tests, 0 failures
- [ ] A test asserts `(small, rejected, toolFlagged=true, no corroboration)` → `NEEDS_VALIDATION` with blocker text containing "small-tier"

Out of scope: second-verifier scheduling (G.4 decides when to ask for one).

Handoff note for next agent: Every node that records an LLM `rejected` must call `VerdictGuardrail.apply`.
`FindingsValidator` F6 double-checks this.
