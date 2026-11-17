### WO-P.2: Port the builtin rules adapter and emit traced candidates
Wave: 2   Depends on: WO-C.1, WO-C.5, WO-P.0   Blocks: WO-K.6, WO-G.5, WO-0.2
Milestone: Prototype 2 Dec 5
Traces: FR-3, FR-9, NFR-2, NFR-3 · D, G
Suggested human owner: Brant Dimitrievski (reviewer: Elodie Abdo)
Size: L

Goal (2–3 sentences): Port the MVP's JavaParser rules and intra-procedural taint tracker into
`secpipe-adapter-builtin` as an `AnalyzerAdapter`. Each hit becomes a `Candidate` with a trace (entrypoint
→ propagations → sink), `sinkStatement`, and `sinkScope`, plus coverage claims. The MVP's own-sample results
must be reproduced exactly (baseline parity).

Context to read first (exact paths only): MVP `src/main/java/ca/carleton/secpipe/tools/builtin/*.java`,
MVP `src/test/java/ca/carleton/secpipe/tools/builtin/BuiltinRulesTest.java`; contracts
`spi/AnalyzerAdapter.java`, `finding/Candidate.java`, `ids/Fingerprints.java`;
`fixtures/mvp-samples/shop-samples/**`, `secpipe-eval/src/main/resources/mvp/answers.csv`.

Files you own (create/modify — nothing else):
`secpipe-adapter-builtin/src/main/java/ca/carleton/secpipe/adapter/builtin/**`,
`secpipe-adapter-builtin/src/main/resources/META-INF/services/ca.carleton.secpipe.contracts.spi.AnalyzerAdapter`,
`secpipe-adapter-builtin/src/test/**`.

Files you may read but not modify: contracts, fixtures.

Interfaces consumed: `AnalyzerAdapter`, `Candidate`, `Fingerprints.normalizeStatement`   Interfaces produced:
adapter id `builtin`.

Implementation notes:
- `RuleContext.report(...)` now builds a `Candidate`:
  - the trace's first step is the taint source (the `getParameter` call or the annotated parameter) with
    kind `entrypoint`, or `propagation` if the level is only PARAMETER;
  - intermediate assignments the tracker followed become `propagation` steps (record them in
    `TaintTracker`);
  - the sink call becomes `sink`.
- `sinkScope` = `SimpleClassName#method(ParamSimpleTypes)`. `sinkStatement` = `normalizeStatement(call.toString())`.
- `supportedCwes` = {CWE-22, 78, 89, 327, 328, 502, 798}. Emit one `CoverageClaim` per CWE covering every
  parsed file.
- Keep the MVP's severity mapping as `toolSeverityHint` and `TaintLevel.confidence()` as `confidenceHint`.
- Parse in parallel as the MVP did (thread-local `JavaParser`, language level 21).
- Do not improve detection logic here (that is WO-G.5's refuter). This is a faithful port.

Tests to write:
- port `BuiltinRulesTest` cases;
- trace shape (first entrypoint, last sink) on `LoginServlet`;
- parity: run the adapter on `fixtures/mvp-samples/shop-samples` and score it against `answers.csv` using
  file + CWE family. Expect exactly TP=10, FP=2 (`TagFilter`, `ArchiveJob`), FN=0, TN=8, i.e. the MVP
  2026-11-03 table.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-adapter-builtin -am verify` → exit 0
- [ ] `./mvnw -q -B -pl secpipe-adapter-builtin test -Dtest=MvpParityTest` → reports `tp=10 fp=2 fn=0 tn=8`
- [ ] A test asserts every emitted candidate has `trace.get(last).kind()==SINK`, non-empty `sinkStatement`, and `sinkScope`

Session split (L-size; same branch, one PR): **Session A** — Port `TaintTracker`, `RuleContext`, `JavaRule`, the six rules, and the ported `BuiltinRulesTest` cases (trace building included). Commit and push; stop. **Session B** (fresh session, same kickoff + "continue <WO-ID> part B") — Adapter class, services file, coverage claims, `MvpParityTest`, and the remaining acceptance commands. Then run all acceptance commands and open the PR.

Out of scope: inter-procedural taint, new rules.

Handoff note for next agent: `ArchiveJob` and `TagFilter` are the known FPs that the refuter (WO-G.5) must
remove. `ScriptRunner` is a TP that needs inter-procedural flow (Joern, WO-A.2).
