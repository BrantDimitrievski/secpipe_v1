### WO-G.8: Implement the independent record verifier (Phase 5) with material-replacement rule
Wave: 6   Depends on: WO-G.7, WO-L.6   Blocks: none
Milestone: Oral Jan 25
Traces: FR-1, FR-8 · A, F, I
Suggested human owner: Brant Dimitrievski (reviewer: Clinton Odinamadu)
Size: M

Goal (2–3 sentences): `RecordVerifierNode` gives each final record (`confirmed` only in `quick`/`standard`;
all in `deep`) to a fresh verifier that checks every citation against the source. Non-material replacements
are applied. **Material** replacements (stronger verdict, changed root cause, trace, execution, or severity)
are applied only after a second fresh verifier verifies the replacement. Small-tier models can't strengthen
verdicts.

Context to read first (exact paths only): `docs/PLAN.md` Part 5 §A9, §2.2 (Phase 5); contracts
`schemas/agent/record-verifier-result.schema.json`; `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/runner/AgentRunner.java`;
`secpipe-llm/src/main/java/ca/carleton/secpipe/llm/VerdictGuardrail.java`.

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/verifier/**`,
`secpipe-agents/src/main/resources/prompts/record_verifier.v1.txt`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/verifier/**`,
`secpipe-agents/src/test/resources/llm-fixtures/verifier/**`.

Files you may read but not modify: runner, llm.

Interfaces consumed: `AgentRunner`, `FindingsValidator`   Interfaces produced: `RecordVerifierNode`, `RecordVerifierAgent`.

Implementation notes:
- The replacement must itself pass `FindingsValidator` (with root) **and** keep the same fingerprint.
  Otherwise it's discarded.
- A material change to `confirmed` from `needs_validation` is **never** allowed here, because confirmed
  requires test evidence, which only G.7 produces.
- Record `verification.record_verifier` (and `second_verifier`).

Tests to write: verified; non-material (title wording) applied; material + second verifies → applied;
material + second rejects → original kept + note; small-tier strengthening attempt refused; a fingerprint
change refused.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='RecordVerifier*Test'` → ≥ 8 tests, 0 failures

Out of scope: re-running tests.

Handoff note for next agent: The E.2 "+record verifier" arm enables this node.
