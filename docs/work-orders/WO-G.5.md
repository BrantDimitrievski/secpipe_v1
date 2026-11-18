### WO-G.5: Implement the deterministic refuter (Benchmark false-positive patterns)
Wave: 4   Depends on: WO-K.8, WO-P.2, WO-K.4   Blocks: WO-K.13
Milestone: Prototype 2 Dec 5
Traces: FR-3, NFR-4 · E, F
Suggested human owner: Brant Dimitrievski (reviewer: Elodie Abdo)
Size: M

Goal (2–3 sentences): `DeterministicRefuterNode` rejects candidates whose tainted value provably cannot reach the sink, using intra-procedural constant folding and def-use over JavaParser. It runs with AI off, **never confirms**, and is conservative: unsure means pass through.

Context to read first (exact paths only): `docs/PLAN.md` Part 5 §A6; `secpipe-adapter-builtin/src/main/java/ca/carleton/secpipe/adapter/builtin/TaintTracker.java`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/recon/FileSourceIndex.java`; `fixtures/mvp-samples/shop-samples/`

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/refuter/**`, `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/refuter/**`, `secpipe-agents/src/test/resources/ca/carleton/secpipe/agents/refuter/**`.

Files you may read but not modify: builtin adapter, recon.

Interfaces consumed: `Candidate`, `SourceIndex`   Interfaces produced: `DeterministicRefuterNode` (`requiresLlm()==false`); `Refuter.check(Candidate, CompilationUnit) → Optional<Refutation>`.

Implementation notes:
- Four independent rules: `ValueOverwrittenRule`, `ConstantArgumentRule`, `DeadBranchRule`, `ParameterizedApiRule` (CollectionTrickRule moved to stretch).
- Each rejection records `controlLocation` and reason for the report.
- Only families {22, 78, 89}; never touch dependency candidates.
- Soundness guard: loops with taint-dependent indices, reflection, captured lambdas, or try/catch around the reassignment → don't refute.

Tests to write: one positive and two negative cases per rule; shop samples (`TagFilter` refuted; true positives preserved); 16 team-written Benchmark-shaped snippets (8 refuted, 8 not).

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='Refuter*Test,*Rule*Test'` → ≥ 20 tests, 0 failures
- [ ] A test asserts the refuter never produces a `CONFIRMED` or `NEEDS_VALIDATION` record
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: inter-procedural analysis, collection tricks.

Handoff note for next agent: Runs before the disprover; the disprover only sees survivors.
