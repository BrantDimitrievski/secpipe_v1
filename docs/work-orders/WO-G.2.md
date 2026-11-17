### WO-G.2: Implement the hunter agent and the hunter-wave node
Wave: 8   Depends on: WO-G.1, WO-K.4, WO-K.5, WO-K.10   Blocks: WO-G.3, WO-Y.1
Milestone: Oral Jan 25
Traces: FR-1, FR-3 · A, B, C, D, N, O, R
Suggested human owner: Clinton Odinamadu (reviewer: Abdul Babatunde)
Size: L

Goal (2–3 sentences): `HunterAgent` applies the invariant method to one ledger unit. `HuntWaveNode` assigns
`planned` units (sorted by `coverage_id`, capped by the profile and remaining unreserved budget) to fresh
hunters in parallel. It moves units `in_progress` → `covered`/`candidate`/`blocked`, adds hunter
candidates, and never repairs outputs.

Context to read first (exact paths only): `docs/PLAN.md` Part 5 §A4; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/runner/AgentRunner.java`;
`secpipe-agents/src/main/resources/prompts/hunter.v1.txt`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/recon/ReconFacts.java`,
`core/ledger/LedgerTransitions.java`; contracts `agent/AgentOutputs.java` (HunterResult),
`schemas/agent/hunter-result.schema.json`.

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/hunter/**`,
`secpipe-agents/src/main/resources/prompts/hunter.v1.txt`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/hunter/**`,
`secpipe-agents/src/test/resources/llm-fixtures/hunter/**`.

Files you may read but not modify: recon, ledger, runner.

Interfaces consumed: `AgentRunner`, `ReconFacts`, `SourceIndex`, `Budget`   Interfaces produced: `HuntWaveNode`
(FQCN in `PipelineGraph`), `HunterAgent`, `HunterContextBuilder` (deterministic slice selection).

Implementation notes:
- **Context builder:** the starting paths' entry methods + callee methods in the same files (≤
  `max-files-per-call`, ≤ `max-snippet-lines` each) + the sink catalogue for the CWE family + the peer
  list ("do not duplicate": other units' ids and labels in this wave) + excluded same-source root causes
  (from carry-forward, if present).
- **Each hunter candidate** → a `Candidate` with origin `hunter`, the trace from the result, and
  `strongestControl`/`smallestFix`/`regressionCase`. Hunter-only candidates are never "tool-flagged"
  (the guardrail doesn't protect them; grounding still applies).
- **Wave size:**
  - `quick`: all planned units in one wave, max 10;
  - `standard`: 25 per wave;
  - `deep`: all.
- **Budget:** only unreserved calls (verifier and critic reservations are untouchable).
- **Result labels:** `next` if a wave ran; `done` otherwise.
- **Ledger:** `in_progress` with the agent id at assignment (one delta), then the completion delta.
  `Dropped` → `blocked` ("hunter output invalid twice"). `Unavailable` → back to `planned` and an
  `incompleteReason` note.

Tests to write: context builder determinism and caps; fake hunter results for covered, candidate, blocked,
and invalid ×2; the budget stops assignment (units stay planned, note added); the ledger stays
validator-clean; the peer list is excluded from the unit's own context.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='Hunt*Test,HunterAgent*Test'` → ≥ 10 tests, 0 failures
- [ ] A test asserts a prompt rendered for unit A contains unit B's id only in the "do not duplicate" section, and contains no source from files outside A's starting paths and callees

Session split (L-size; same branch, one PR): **Session A** — `HunterContextBuilder` and `HunterAgent` with tests. Commit and push; stop. **Session B** (fresh session, same kickoff + "continue <WO-ID> part B") — `HuntWaveNode` (assignment, ledger deltas, budget stop) and the integration tests. Then run all acceptance commands and open the PR.

Out of scope: the critic (G.3); Joern slices (optional later via A.2's `SliceProvider` if present).

Handoff note for next agent: The critic reads `state.hunterWave` and the ledger. The loop is the graph's
`critic → hunt` edge.
