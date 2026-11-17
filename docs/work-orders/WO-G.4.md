### WO-G.4: Implement the adversarial disprover agent and node
Wave: 4   Depends on: WO-G.1, WO-L.6, WO-K.8, WO-K.10, WO-K.4   Blocks: WO-0.2, WO-E.2, WO-G.7, WO-D.2, WO-K.12, WO-Y.1
Milestone: Prototype 2 Dec 5
Traces: FR-1, FR-3, FR-8 · A, D, E, F, I
Suggested human owner: Clinton Odinamadu (reviewer: Brant Dimitrievski)
Size: L

Goal (2–3 sentences): `DisproverAgent` gets one candidate's claim with fresh context, opens with "You did
not write this candidate. Try to refute it.", and returns a schema-valid verdict. `DisproverNode` processes
every candidate without a record in fingerprint order, within the verifier reservation. It applies the
weak-model guardrail and writes `rejected` or `needs_validation` records. Survivors become provisional
records (`needs_validation`, blocker "awaiting regression test evidence") carrying the draft confirmed
fields for WO-G.7.

Context to read first (exact paths only): `docs/PLAN.md` Part 5 §A7, §2.2-E/F, ADR-05; contracts
`schemas/agent/disprover-result.schema.json`, `agent/AgentOutputs.java`, `finding/Finding.java`;
`secpipe-agents/src/main/java/ca/carleton/secpipe/agents/runner/AgentRunner.java`; `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/VerdictGuardrail.java`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/recon/ReconFacts.java`.

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/disprover/**`,
`secpipe-agents/src/main/resources/prompts/disprover.v1.txt`, `secpipe-agents/src/main/resources/disprover/control-catalogue.json`,
`secpipe-agents/src/test/java/ca/carleton/secpipe/agents/disprover/**`, `secpipe-agents/src/test/resources/llm-fixtures/disprover/**`.

Files you may read but not modify: runner, refuter, llm, recon.

Interfaces consumed: `AgentRunner`, `VerdictGuardrail`, `CapabilityTiers`, `ReconFacts`, `SourceIndex`   Interfaces produced:
- `DisproverNode`, `DisproverAgent`, `DisproverContextBuilder`;
- the survivors' `DisproverResult`s, placed in `StateDelta.putSurvivorDrafts` (contract field
  `PipelineState.survivorDrafts`). That is how WO-G.7 receives the confirmed draft (root cause, intended
  behaviour, conditions, proposed severity) without side-channel files.

Implementation notes:
- **Context:** the claim **without** tool names, tool severity, confidence, or the other verifiers'
  outputs; slices ±15 lines around each cited line; enclosing methods; the control catalogue for the CWE
  family (PreparedStatement binding, `ESAPI.encoder`, allowlist regex patterns, `Path.normalize` +
  `startsWith`, `ProcessBuilder` list form, `MessageDigest` SHA-256+, AES/GCM, `ObjectInputFilter`).
- **Benchmark checklist in the prompt:** overwritten value; constant; dead branch; parameterized;
  collection tricks (`list.remove(0)`, map key mismatch); `switch` on a constant.
- **`deep` profile:** a second fresh disprover when the first returns `rejected` from a non-strong tier on
  a tool-flagged candidate (corroboration for the guardrail).
- **Dropped** → `needs_validation`, blocker "verifier output invalid twice". **Unavailable** → leave no
  record; finalize turns it into needs_validation with the reason.
- **Rejected records** carry `verification.disprover` = llm + model + tier + agent id.
- **Dependency candidates** skip the LLM disprover. They get a provisional `confirmed` from resolution
  evidence (ADR-05), with `observed_result` `resolved: <purl> in dependency tree via <path>`. Records become
  `confirmed` directly here, with severity pending scoring.

Tests to write:
- fake fixtures for: the TagFilter-style overwritten case → rejected; the LoginServlet case → provisional;
  needs_validation with blockers;
- the small-tier rejected on a tool candidate → needs_validation (guardrail);
- grounding failure ×2 → needs_validation;
- the prompt contains no tool names (assert on the rendered prompt);
- the budget reservation honoured (stops at the reservation, rest left without records);
- a dependency candidate → confirmed with the `resolved:` observed result.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='Disprover*Test'` → ≥ 12 tests, 0 failures
- [ ] A test asserts the rendered system prompt starts with `You did not write this candidate. Try to refute it.`
- [ ] A test asserts the rendered prompt contains none of `builtin`, `semgrep`, `pmd`, `joern`, `spotbugs` for a candidate whose tool sources include them

Session split (L-size; same branch, one PR): **Session A** — `DisproverContextBuilder` (no tool names), `DisproverAgent`, control catalogue, prompt, and their tests. Commit and push; stop. **Session B** (fresh session, same kickoff + "continue <WO-ID> part B") — `DisproverNode`: ordering, reservation, guardrail, survivor drafts, dependency path, and the node tests. Then run all acceptance commands and open the PR.

Out of scope: test execution (G.7); deterministic refutation (G.5).

Handoff note for next agent: G.7 reads `state.survivorDrafts()` to build the final `confirmed` record. Survivors without test evidence stay `needs_validation` with an exact blocker.
