### WO-K.2: Implement the LangGraph4j runner (only if spike S.1 = GO)
Wave: 5   Depends on: WO-S.1, WO-K.1   Blocks: none
Milestone: Prototype 2 Dec 5
Traces: FR-1 · A
Suggested human owner: Clinton Odinamadu (reviewer: Brant Dimitrievski)
Size: M

Goal (2–3 sentences): If `docs/spikes/S1-langgraph4j.md` says GO, implement `LangGraph4jGraphRunner`, which
compiles `PipelineGraph` to a LangGraph4j `StateGraph` and delegates state application to `DeltaApplier`.
`--engine langgraph4j` must produce byte-identical outputs to `plain` on the e2e fixture. If NO-GO, close
the WO with a one-line PR that adds the decision link to `secpipe-graph-langgraph4j/README.md`.

Context to read first (exact paths only): `docs/spikes/S1-langgraph4j.md`, `secpipe-core/src/main/java/ca/carleton/secpipe/core/engine/PlainGraphRunner.java`,
`DeltaApplier.java`, contracts `pipeline/*`.

Files you own (create/modify — nothing else): `secpipe-graph-langgraph4j/src/**`, `secpipe-graph-langgraph4j/README.md`,
`secpipe-graph-langgraph4j/src/main/resources/META-INF/services/ca.carleton.secpipe.contracts.pipeline.GraphRunner`.

Files you may read but not modify: core engine.

Interfaces consumed: `GraphRunner`, `DeltaApplier`, `NodeLoader`   Interfaces produced: runner selectable
via ServiceLoader with `name()="langgraph4j"`.

Implementation notes: The state channel is a single opaque `PipelineState` value (the reducer = DeltaApplier),
to avoid duplicating semantics. Conditional edges map labels from `PipelineNode.Result`.

Tests to write: equivalence test on stub graphs (same node order and final state as plain); the e2e
fixture equivalence under `-Pe2e`.

Acceptance criteria (all must pass):
- [ ] GO: `./mvnw -q -B -pl secpipe-graph-langgraph4j -am verify` → exit 0, and `diff <(jq -S . out-plain/findings.json) <(jq -S . out-lg4j/findings.json)` → empty in the e2e test
- [ ] NO-GO: `grep -q 'NO-GO' secpipe-graph-langgraph4j/README.md` and the module still builds

Out of scope: changing node semantics.

Handoff note for next agent: The default engine remains `plain` unless the team decides otherwise at
re-planning.
