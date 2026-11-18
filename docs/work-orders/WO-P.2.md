### WO-P.2: Port the builtin rules adapter and the knowledge base / CWE family mapping
Wave: 2   Depends on: WO-C.1, WO-C.5, WO-P.0   Blocks: WO-K.6, WO-K.8, WO-K.9, WO-G.5
Milestone: Prototype 2 Dec 5
Traces: FR-3, FR-9, FR-11, NFR-2, NFR-3 · D, G, H
Suggested human owner: Brant Dimitrievski (reviewer: Elodie Abdo)
Size: M

Goal (2–3 sentences): Port the MVP builtin taint rules into `secpipe-adapter-builtin` as adapter `builtin`, emitting traced candidates for CWE families 22, 78, 89, 327 and 328. Fold in the knowledge base (old P.8): CWE → family mapping, impact class and remediation text, in `core/knowledge`.

Context to read first (exact paths only): MVP `src/main/java/ca/carleton/secpipe/rules/`; MVP `src/main/java/ca/carleton/secpipe/knowledge/`; contracts `spi/AnalyzerAdapter.java`, `findings/Candidate.java`, `Fingerprints.java`; `fixtures/mvp-samples/shop-samples/`

Files you own (create/modify — nothing else): `secpipe-adapter-builtin/src/main/java/ca/carleton/secpipe/adapter/builtin/**`, `secpipe-adapter-builtin/src/main/resources/META-INF/services/ca.carleton.secpipe.contracts.spi.AnalyzerAdapter`, `secpipe-adapter-builtin/src/test/**`, `secpipe-core/src/main/java/ca/carleton/secpipe/core/knowledge/**`, `secpipe-core/src/main/resources/knowledge/**`, `secpipe-core/src/test/java/ca/carleton/secpipe/core/knowledge/**`.

Files you may read but not modify: contracts, fixtures, MVP.

Interfaces consumed: `AnalyzerAdapter`, `Candidate`, `Fingerprints.normalizeStatement`   Interfaces produced: adapter id `builtin`; `KnowledgeBase.family(cwe)`, `KnowledgeBase.impactClass(family)`, `KnowledgeBase.remediation(family)`.

Implementation notes:
- Candidates carry source → sink trace `Location`s and coverage claims; no fingerprints (normalize assigns them).
- Port only the five families; other MVP rules go to stretch.
- Knowledge data is JSON under `secpipe-core/src/main/resources/knowledge/`, loaded once, immutable.
- Add `Ported-from:` lines in commit bodies.

Tests to write: `BuiltinAdapterTest` on `shop-samples` (expected hits per family), `KnowledgeBaseTest` (family mapping incl. CWE-23 → 22, every family has remediation text).

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-adapter-builtin,secpipe-core -am test -Dtest='Builtin*Test,KnowledgeBase*Test'` → ≥ 10 tests, 0 failures
- [ ] A test asserts the shop-samples true positives are all reported by `builtin`
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: other MVP rules, framework-specific sources.

Handoff note for next agent: G.5 reuses `TaintTracker` read-only; K.8/K.9 use `KnowledgeBase`.
