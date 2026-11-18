### WO-K.8: Implement normalize — fingerprinting and cross-tool dedup
Wave: 3   Depends on: WO-C.5, WO-P.2, WO-K.1   Blocks: WO-G.4, WO-G.5, WO-K.13
Milestone: Prototype 2 Dec 5
Traces: FR-9, FR-3, FR-5 · G
Suggested human owner: Elodie Abdo (reviewer: Brant Dimitrievski)
Size: S

Goal (2–3 sentences): `NormalizeNode` assigns every candidate its `Fingerprints` value and merges candidates from different tools that share a fingerprint. Output is pure, deterministic and sorted by fingerprint.

Context to read first (exact paths only): contracts `Fingerprints.java`, `findings/Candidate.java`, `pipeline/StateDelta.java`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/knowledge/` (from P.2, same family mapping)

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/normalize/**`, `secpipe-core/src/test/java/ca/carleton/secpipe/core/normalize/**`.

Files you may read but not modify: contracts, knowledge.

Interfaces consumed: `Fingerprints`, `KnowledgeBase.family(cwe)`   Interfaces produced: `NormalizeNode`; `CandidateMerger.merge(List<Candidate>) → List<Candidate>`.

Implementation notes:
- Merge key = fingerprint. Merged candidate keeps the union of tools and rule ids, and the longest trace.
- CWE ids are mapped to their family before fingerprinting (CWE-23 → 22).
- Dropped: root-cause merge across sinks (stretch).

Tests to write: `CandidateMergerTest` (same fingerprint from two tools → one; different sinks → two; order-independence).

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='Normalize*Test,CandidateMerger*Test'` → ≥ 6 tests, 0 failures
- [ ] A test asserts shuffled input produces byte-identical output
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: scoring (K.9).

Handoff note for next agent: Downstream nodes (refuter, disprover) key everything by fingerprint.
