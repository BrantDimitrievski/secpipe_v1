### WO-P.6: Port the PMD adapter and ruleset
Wave: 4   Depends on: WO-K.6   Blocks: none
Milestone: Prototype 2 Dec 5
Traces: FR-3, NFR-3 · G
Suggested human owner: Abdul Babatunde (reviewer: Brant Dimitrievski)
Size: S

Goal (2–3 sentences): Port the embedded PMD 7 adapter and `secpipe-security.xml` into `secpipe-adapter-pmd`, mapping rule violations to CWE-tagged candidates with a sink-only trace and coverage claims.

Context to read first (exact paths only): MVP `src/main/java/ca/carleton/secpipe/tools/PmdAdapter.java`; MVP `src/main/resources/pmd/secpipe-security.xml`; contracts `spi/AnalyzerAdapter.java`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/detect/DetectNode.java`

Files you own (create/modify — nothing else): `secpipe-adapter-pmd/src/main/**`, `secpipe-adapter-pmd/src/test/**`.

Files you may read but not modify: contracts.

Interfaces consumed: `AnalyzerAdapter`   Interfaces produced: adapter id `pmd`.

Implementation notes:
- Map rule → CWE with a table; unmapped rules are dropped, not emitted.
- `sinkStatement` = normalized source line at the violation; `sinkScope` = enclosing method or `Class#<unknown>`.
- PMD runs in-process (no target code); results are read from PMD's report API, not SARIF.

Tests to write: on `fixtures/mvp-samples/vulnerable-java-app` → ≥ 1 candidate; mapping completeness.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-adapter-pmd -am verify` → exit 0
- [ ] A test asserts every rule in `secpipe-security.xml` has a CWE mapping or is in an explicit `IGNORED` set
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: rule tuning.

Handoff note for next agent: Registered by ServiceLoader; detect picks it up with no other change.
