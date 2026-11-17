### WO-K.8: Implement normalize — fingerprinting, cross-tool dedup, root-cause merge
Wave: 3   Depends on: WO-C.5, WO-P.8, WO-K.1   Blocks: WO-G.4, WO-G.5, WO-0.2, WO-E.2, WO-K.12
Milestone: Prototype 2 Dec 5
Traces: FR-9, FR-3, FR-5 · G
Suggested human owner: Elodie Abdo (reviewer: Brant Dimitrievski)
Size: M

Goal (2–3 sentences): `NormalizeNode` (registered as both `normalize` and `normalize_llm`) assigns every
candidate its ADR-06 fingerprint and merges same-fingerprint candidates. Tool sources, CWEs, CVEs, ledger
units, and evidence are unioned, and the richest trace is kept. Ledger units are updated with
`result_fingerprints`. Semgrep, PMD, builtin, and Joern hitting one sink become **one** candidate.

Context to read first (exact paths only): contracts `ids/Fingerprints.java`, `finding/Candidate.java`;
MVP `src/main/java/ca/carleton/secpipe/agents/TriageAgent.java` (merge semantics);
`secpipe-core/src/main/java/ca/carleton/secpipe/core/knowledge/KnowledgeBase.java`.

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/normalize/**`,
`secpipe-core/src/test/java/ca/carleton/secpipe/core/normalize/**`.

Files you may read but not modify: contracts, knowledge.

Interfaces consumed: `Fingerprints`, `KnowledgeBase`   Interfaces produced: `NormalizeNode`;
`CandidateMerger.merge(List<Candidate>) → List<Candidate>` (pure, sorted by fingerprint).

Implementation notes:
- **Code candidate without `sinkScope`/`sinkStatement`** (e.g. a tool gave only a line): resolve them by
  parsing the file at that line (enclosing method + statement at the line) via `SourceIndex`. If that
  still fails, use scope `Class#<file-level>` and the line's text, normalized.
- **Occurrence index:** count identical normalized sinks within the scope, ordered by line.
- **Trace choice:** prefer the trace with an `entrypoint` step, then the longer one.
- **Severity hint:** the max. **Confidence hint:** the max (no automatic raise — the MVP behaviour is
  dropped).
- Fill `owasp` from the knowledge base.
- Idempotent: running it twice gives the same output.

Tests to write: three tools on the same sink (different lines ±1, different CWE variants 22/23) → 1
candidate with 3 tool sources; two sinks in one method → 2; dependency dedup across osv/grype/trivy;
idempotence; deterministic order; the ledger updates validate.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='NormalizeNode*Test,CandidateMerger*Test'` → ≥ 10 tests, 0 failures
- [ ] A test asserts `merge(merge(x)) == merge(x)` for a 50-candidate fixture

Out of scope: verdicts.

Handoff note for next agent: After normalize, `Candidate.fingerprint()` is always present, and it is the
record key everywhere downstream.
