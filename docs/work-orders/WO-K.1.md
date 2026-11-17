### WO-K.1: Build the plain-Java graph engine and the sole-writer state store
Wave: 2   Depends on: WO-C.1, WO-C.2   Blocks: WO-K.3, WO-K.5, WO-K.8, WO-K.13, WO-K.2, WO-D.2, WO-Y.2
Milestone: Prototype 2 Dec 5
Traces: FR-1, NFR-2 · A, I, P
Suggested human owner: Brant Dimitrievski (reviewer: Clinton Odinamadu)
Size: L

Goal (2–3 sentences): `PlainGraphRunner` executes `PipelineGraph`. It loads nodes by FQCN (a missing class
→ SKIPPED edge plus a note), skips LLM nodes in `none` mode, and times every stage (NFR-2). It applies
`StateDelta`s as the **only writer**, running the injected ledger and findings validators after each write (wired to `LedgerValidator` and
`FindingsValidator` by the CLI assembly, WO-K.13). Node exceptions become an `incomplete_reason`, never a
crash.

Context to read first (exact paths only): contracts `pipeline/*.java`, `spi/Budget.java`, `spi/LlmClient.java`;
`docs/PLAN.md` §4.2; `secpipe-validators/src/main/java/ca/carleton/secpipe/validators/ValidationResult.java`.

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/engine/**`,
`secpipe-core/src/main/java/ca/carleton/secpipe/core/ledger/LedgerStore.java` (minimal version; WO-K.5
takes over this package in wave 3), `secpipe-core/src/test/java/ca/carleton/secpipe/core/engine/**`.

Files you may read but not modify: contracts, validators.

Interfaces consumed: `GraphRunner`, `PipelineNode`, `RunContext`, `StateDelta`, `ValidationResult`   Interfaces produced:
- `PlainGraphRunner implements GraphRunner`;
- `EngineValidators` (the record `(Function<List<Finding>, ValidationResult> findings,
  BiFunction<List<LedgerUnit>, List<LedgerUnit>, ValidationResult> ledgerTransition)`), injected into the
  runner; tests use stubs;
- `DeltaApplier.apply(PipelineState, StateDelta) → PipelineState` (throws `DeltaRejectedException`);
- `DefaultRunContext.builder()`;
- `NodeLoader.load(id)`;
- `EngineCheckpoint.write(state, dir)` (`state.json` after every node, for debugging and resume);
- `DefaultRunLog` (plain; WO-Y.2 adds redaction).

Implementation notes:
- **Loop guard:** a maximum of 200 node executions per run → `incomplete`.
- `disabledNodes` → treated as missing (the SKIPPED edge plus the note "disabled by --disable-node").
- **State application order:**
  1. scalar fields;
  2. `newLedgerUnits`;
  3. `ledgerUpdates`, through `LedgerStore.apply`, which in this WO just maps the update fields onto the
     unit, appends an `Attempt` when `agentId` is present, re-sorts, and runs the validator;
  4. candidates;
  5. records;
  6. tool runs and timings.
- The `REQUIRED_FOR_COMPLETE` nodes being skipped or missing → incomplete.
- Thread model: nodes run sequentially; a node may parallelize internally.
- All maps in state are `LinkedHashMap`, sorted on write for determinism.

Tests to write: use stub nodes registered by FQCN in test sources, covering:
- linear run; `loop` transition with a counter; a missing node takes SKIPPED;
- an LLM node is skipped when `mode()==NONE`;
- a node exception → incomplete and the run continues to finalize/report stubs;
- a delta failing the validator → incomplete with the validator message;
- the 200-step guard;
- timings recorded for every executed node.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='PlainGraphRunner*Test,DeltaApplier*Test'` → ≥ 12 tests, 0 failures
- [ ] A test proves a node cannot mutate state except through its returned delta (state passed in is deeply immutable: mutation attempt → `UnsupportedOperationException`)

Session split (L-size; same branch, one PR): **Session A** — `DeltaApplier`, minimal `LedgerStore`, `EngineValidators`, and their tests (incl. the immutability test). Commit and push; stop. **Session B** (fresh session, same kickoff + "continue <WO-ID> part B") — `PlainGraphRunner`, `NodeLoader`, `DefaultRunContext`, `EngineCheckpoint`, `DefaultRunLog`, and the runner tests. Then run all acceptance commands and open the PR.

Out of scope: node implementations; the LangGraph4j runner.

Handoff note for next agent: The node WOs only create their class at the FQCN in `PipelineGraph.NODES`. The
engine picks it up with no registration edits.
