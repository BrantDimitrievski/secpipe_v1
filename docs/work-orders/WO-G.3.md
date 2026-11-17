### WO-G.3: Implement the coverage critic agent and node
Wave: 9   Depends on: WO-G.2   Blocks: WO-E.7
Milestone: Oral Jan 25
Traces: FR-15 · B, L, N, O
Suggested human owner: Clinton Odinamadu (reviewer: Elaine Huang)
Size: M

Goal (2–3 sentences): `CriticAgent` reads the compact ledger and recon inventory (plus ≤ 4 slices) and
returns missing units, reassignments, and `stop`. `CriticNode` turns missing units into new planned ledger
units (via `CoverageIds`) and reassignments into archived resets. It returns `loop` or `done` per the
profile cap and budget. It proposes coverage, never findings.

Context to read first (exact paths only): `docs/PLAN.md` Part 5 §A5; contracts `schemas/agent/critic-result.schema.json`,
`agent/AgentOutputs.java` (CriticResult), `ids/CoverageIds.java`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/ledger/LedgerTransitions.java`;
`secpipe-agents/src/main/java/ca/carleton/secpipe/agents/hunter/HuntWaveNode.java`.

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/critic/**`,
`secpipe-agents/src/main/resources/prompts/critic.v1.txt`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/critic/**`,
`secpipe-agents/src/test/resources/llm-fixtures/critic/**`.

Files you may read but not modify: hunter, ledger, recon.

Interfaces consumed: `AgentRunner`, `LedgerTransitions`, `CoverageIds`   Interfaces produced: `CriticNode`,
`CriticAgent`.

Implementation notes:
- **Missing units must be grounded:** every starting path must exist; surface refs must match recon
  entries (or be file#method that exists). Ungrounded proposals are discarded individually.
- **Duplicates** of existing ids are ignored.
- Reassigning a `covered` unit requires its `local_checks` count < 2, or a critic reason that names a
  specific unchecked path.
- **Loop condition:** `!stop && criticPasses < profile.maxCriticPasses && budget.remaining ≥ planned
  units (capped by wave size)`.
- `deep` with `Dropped` → incomplete note.

Tests to write: missing → new valid units; reassign → archived attempt; stop → done; cap reached → done;
ungrounded proposal dropped; a critic that tries to emit a "finding" → ignored (schema has no field; test
via extra field → discard).

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='Critic*Test'` → ≥ 8 tests, 0 failures
- [ ] An integration test with fake hunter + critic fixtures runs `hunt → critic → hunt → critic(stop)` and ends with 0 planned units and a validator-clean ledger

Out of scope: the hunter changes.

Handoff note for next agent: E.7 measures coverage gain per critic wave from `attempts` history.
