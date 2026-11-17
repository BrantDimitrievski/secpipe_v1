### WO-P.6: Port the PMD adapter and ruleset
Wave: 4   Depends on: WO-K.7, WO-K.6   Blocks: WO-E.3
Milestone: Prototype 2 Dec 5
Traces: FR-3, NFR-2, NFR-3 · G
Suggested human owner: Abdul Babatunde (reviewer: Brant Dimitrievski)
Size: S

Goal (2–3 sentences): Port the embedded PMD 7 adapter and `secpipe-security.xml` into `secpipe-adapter-pmd`,
mapping rule violations to CWE-tagged candidates with a sink-only trace and coverage claims.

Context to read first (exact paths only): MVP `src/main/java/ca/carleton/secpipe/tools/PmdAdapter.java`,
`src/main/resources/pmd/secpipe-security.xml`; contracts `spi/AnalyzerAdapter.java`, `finding/Candidate.java`.

Files you own (create/modify — nothing else): `secpipe-adapter-pmd/src/main/**`, `secpipe-adapter-pmd/src/test/**`.

Files you may read but not modify: contracts.

Interfaces consumed: `AnalyzerAdapter`   Interfaces produced: adapter id `pmd`.

Implementation notes:
- Map rule → CWE with a table (`HardcodedCryptoKey`→CWE-321→family 798; `InsecureCryptoIv`→CWE-329; etc.).
  Unmapped rules are dropped, not emitted.
- `sinkStatement` = the normalized source line text at the violation (read the file); `sinkScope` = the
  enclosing method via PMD's AST, if available, else `Class#<unknown>`.
- PMD runs in-process (no target code).

Tests to write: on `fixtures/mvp-samples/vulnerable-java-app` → ≥ 1 candidate for CWE-798 family; the
mapping table is complete for every rule in the XML.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-adapter-pmd -am verify` → exit 0
- [ ] A test asserts every rule referenced in `secpipe-security.xml` has a CWE mapping or is explicitly in an `IGNORED` set

Out of scope: rule tuning.

Handoff note for next agent: PMD candidates often duplicate builtin ones; normalize merges them when the
sink statement matches.
