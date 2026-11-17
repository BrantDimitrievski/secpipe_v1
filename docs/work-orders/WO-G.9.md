### WO-G.9: Implement the optional architecture-summary agent
Wave: 8   Depends on: WO-G.1, WO-K.4   Blocks: none
Milestone: Oral Jan 25
Traces: FR-1 · A, (Phase 1 summary)
Suggested human owner: Abdul Babatunde (reviewer: Clinton Odinamadu)
Size: S

Goal (2–3 sentences): `ArchitectureSummaryNode` asks one LLM call for an `architecture.md` (≤ 1,000
words), built only from the recon inventory, the first 200 README lines (untrusted), and the dependency
list. The text is written to `<out>/architecture.md` and never influences verdicts.

Context to read first (exact paths only): `docs/PLAN.md` Part 5 §A2; `secpipe-core/src/main/java/ca/carleton/secpipe/core/recon/ReconFacts.java`;
`secpipe-agents/src/main/java/ca/carleton/secpipe/agents/runner/AgentRunner.java`.

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/recon/**`,
`secpipe-agents/src/main/resources/prompts/recon_summary.v1.txt`, `secpipe-agents/src/main/resources/schemas-local/recon-summary.schema.json`,
`secpipe-agents/src/test/java/ca/carleton/secpipe/agents/recon/**`.

Files you may read but not modify: recon, runner.

Interfaces consumed: `AgentRunner`   Interfaces produced: `ArchitectureSummaryNode`.

Implementation notes: The output schema is `{ "markdown": string ≤ 7000 chars }` (a local schema, not a
contract, because it never affects records). Enforce the word count deterministically: > 1,000 words →
truncate with a note. Skip in `quick` unless `--profile standard|deep`.

Tests to write: fake output written; the word cap; skipped in quick; untrusted README content is wrapped.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='ArchitectureSummary*Test'` → ≥ 4 tests, 0 failures

Out of scope: companion selection (deterministic in recon).

Handoff note for next agent: The report links `architecture.md` if present.
