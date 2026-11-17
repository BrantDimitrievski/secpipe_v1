### WO-G.5: Implement the deterministic refuter (Benchmark false-positive patterns)
Wave: 4   Depends on: WO-K.8, WO-P.2, WO-K.4   Blocks: WO-0.2, WO-E.2
Milestone: Prototype 2 Dec 5
Traces: FR-3, NFR-4 · E, F, (rule 6: deterministic first)
Suggested human owner: Brant Dimitrievski (reviewer: Elodie Abdo)
Size: L

Goal (2–3 sentences): `DeterministicRefuterNode` rejects candidates whose tainted value provably cannot
reach the sink. It uses intra-procedural constant folding and def-use over JavaParser: overwritten values,
constant arguments, dead branches, collection index/key tricks, parameterized APIs. It runs with AI off,
**never confirms**, and is conservative: unsure means pass through.

Context to read first (exact paths only): `docs/PLAN.md` Part 5 §A6; `secpipe-adapter-builtin/src/main/java/ca/carleton/secpipe/adapter/builtin/TaintTracker.java`;
`fixtures/mvp-samples/shop-samples/src/main/java/com/shop/app/{TagFilter,ArchiveJob,InvoiceLookup,ThemeLoader}.java`;
contracts `finding/*`, `pipeline/*`.

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/refuter/**`,
`secpipe-agents/src/test/java/ca/carleton/secpipe/agents/refuter/**`, `secpipe-agents/src/test/resources/ca/carleton/secpipe/agents/refuter/**`.

Files you may read but not modify: builtin adapter, recon.

Interfaces consumed: `Candidate`, `SourceIndex`   Interfaces produced: `DeterministicRefuterNode` (`requiresLlm()==false`),
`Refuter.check(Candidate, CompilationUnit) → Optional<Refutation(pattern, controlLocation, reason)>`.

Implementation notes:
- **Implement checks as independent rules** (each unit-tested):
  1. `ValueOverwrittenRule` — on every path from the source to the sink, the variable is reassigned from
     a taint-free expression.
  2. `ConstantArgumentRule` — the sink argument folds to a constant (strings, `+`, `String.format` with
     constant args, `StringBuilder` with constant appends).
  3. `DeadBranchRule` — the taint assignment sits in a branch whose condition folds to false (integer
     arithmetic, `switch` on a constant char/int/string, ternaries).
  4. `CollectionTrickRule` — `List` add/remove/get index arithmetic; `Map.put(k1, taint)` then
     `get(k2)` with constant keys ≠.
  5. `ParameterizedApiRule` — the JDBC `PreparedStatement` with `?` and `setX(taint)`; the
     `ProcessBuilder(List)` / `exec(String[])` with a constant program and no `sh -c`/`cmd /c`.
- Each rejection records `controlLocation` (the overwriting assignment, the folded condition, etc.), so
  grounding and the report can show it.
- **Never touch dependency candidates** or CWE families outside {22, 78, 89}.
- **Soundness guard:** if the method contains loops with taint-dependent indices, reflection, lambdas
  capturing the variable, or try/catch that could skip the reassignment → don't refute.

Tests to write:
- one positive and two negative cases per rule;
- the shop samples: `TagFilter` → rejected; `ArchiveJob` → **not** rejected by this WO (it is a regex
  allowlist, i.e. semantic validation that's left to the disprover); `OrderSearch`/`ReportSort`/`HostCheck`
  → not rejected (true positives preserved);
- 30 hand-copied Benchmark-shaped snippets written by the team (not copied GPL code): 15 must be
  refuted, 15 not.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='Refuter*Test,*Rule*Test'` → ≥ 25 tests, 0 failures
- [ ] `RefuterShopSamplesTest` reports `refuted=[TagFilter.java] preserved_tp=10`
- [ ] A test asserts the refuter never produces a `CONFIRMED` or `NEEDS_VALIDATION` record

Session split (L-size; same branch, one PR): **Session A** — Rules 1–3 (`ValueOverwrittenRule`, `ConstantArgumentRule`, `DeadBranchRule`) with tests. Commit and push; stop. **Session B** (fresh session, same kickoff + "continue <WO-ID> part B") — Rules 4–5, `Refuter`, `DeterministicRefuterNode`, the shop-samples and 30-snippet tests. Then run all acceptance commands and open the PR.

Out of scope: inter-procedural analysis.

Handoff note for next agent: The E.2 ablation arm "tools + refuter" measures this WO alone. Post the shop
numbers in the PR.
