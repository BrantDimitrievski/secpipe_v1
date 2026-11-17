### WO-S.1: Spike — LangGraph4j go/no-go for the pipeline graph
Wave: 1   Depends on: WO-0.1   Blocks: WO-K.2
Milestone: Prototype 2 Dec 5
Traces: FR-1 · A, P
Suggested human owner: Clinton Odinamadu (reviewer: Brant Dimitrievski)
Size: S (time-box: 1 working day)

Goal (2–3 sentences): Decide with evidence whether LangGraph4j can run `PipelineGraph` with conditional
edges, the hunt⇄critic loop, typed state channels, and checkpoint/resume on Java 21. The output is a go/no-go
document plus throwaway code. A no-go means `PlainGraphRunner` stays the only engine.

Context to read first (exact paths only):
`secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/pipeline/PipelineGraph.java`,
`secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/pipeline/PipelineState.java`, `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/pipeline/GraphRunner.java`, `docs/PLAN.md` ADR-02.

Files you own (create/modify — nothing else): `docs/spikes/S1-langgraph4j.md`, `spikes/langgraph4j/**`
(standalone Maven project, **not** in the reactor), and the `langgraph4j.version` property in the root
`pom.xml` (that one line only).

Files you may read but not modify: all contracts.

Interfaces consumed: `PipelineGraph`, `PipelineState`   Interfaces produced: decision record.

Implementation notes:
- Build a 5-node toy graph mirroring `setup → detect → hunt ⇄ critic → finalize` with a conditional `loop`
  edge and a counter in state.
- Measure: overhead per node transition (target < 5 ms); state serialization of a `PipelineState`-sized
  object (1,000 candidates); checkpoint + resume after a simulated crash; behaviour with Java records in
  state; the licence (expected MIT); release cadence (last 6 months); open issues about async/blocking.
- **Go criteria:** all features work without forking the library; overhead < 5 ms per node; compiles on JDK
  21 with no `--add-opens`.
- **No-go fallback (named):** `PlainGraphRunner` (WO-K.1), with checkpoints done by the engine writing
  `state.json` after every node.

Tests to write: the spike's own JUnit tests in `spikes/langgraph4j` (not run by CI).

Acceptance criteria (all must pass):
- [ ] `test -f docs/spikes/S1-langgraph4j.md && grep -E '^Decision: (GO|NO-GO)$' docs/spikes/S1-langgraph4j.md` → one line
- [ ] The doc contains a measurements table (transition overhead, checkpoint/resume result, record support) and the pinned version
- [ ] `cd spikes/langgraph4j && ../../mvnw -q -B test` → exit 0 (if GO), or the doc lists the failing feature with a link to the issue/API (if NO-GO)
- [ ] `./mvnw -q -B verify` at the repo root still passes

Out of scope: production code in any `secpipe-*` module.

Handoff note for next agent: WO-K.2 reads the "Decision" line. On NO-GO, WO-K.2 is closed as "not needed"
and `secpipe-graph-langgraph4j` stays a skeleton.
