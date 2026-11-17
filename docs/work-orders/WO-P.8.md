### WO-P.8: Port the knowledge base and CWE family mapping
Wave: 2   Depends on: WO-C.5   Blocks: WO-K.8, WO-K.9, WO-R.2
Milestone: Prototype 2 Dec 5
Traces: FR-3, FR-11 · H
Suggested human owner: Elaine Huang (reviewer: Abdul Babatunde)
Size: S

Goal (2–3 sentences): Port `KnowledgeBase` with `cwe-subset.json` and `owasp-top10-2021.json` into
`secpipe-core` (package `knowledge`). Add the per-CWE impact anchor class and default remediation text used
by scoring (FR-8) and recommended remediation (FR-11).

Context to read first (exact paths only): MVP `src/main/java/ca/carleton/secpipe/knowledge/KnowledgeBase.java`,
`src/main/resources/knowledge/cwe-subset.json`, `owasp-top10-2021.json`, MVP `KnowledgeBaseTest.java`;
contracts `ids/Fingerprints.java`.

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/knowledge/**`,
`secpipe-core/src/main/resources/knowledge/**`, `secpipe-core/src/test/java/ca/carleton/secpipe/core/knowledge/**`.

Files you may read but not modify: contracts.

Interfaces consumed: `Fingerprints.CWE_FAMILIES`   Interfaces produced: `KnowledgeBase.load()`, `cwe(id)`,
`owaspFor(cwe)`, `impactClass(cwe)` (enum `RCE, DATASTORE, FILE_READ_WRITE, CREDENTIAL, CRYPTO_WEAKNESS,
INFO_DISCLOSURE, OTHER`), `defaultRemediation(cwe)`.

Implementation notes: Add the impact classes for every CWE in the subset. The scoring anchors (WO-K.9) map
impact class + auth condition → severity.

Tests to write: port `KnowledgeBaseTest`; every family root has an impact class and remediation text.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='KnowledgeBase*Test'` → 0 failures
- [ ] A test asserts `impactClass("CWE-89")==DATASTORE`, `impactClass("CWE-78")==RCE`, `owaspFor("CWE-22")` starts with `A01`

Out of scope: severity logic.

Handoff note for next agent: Severity anchors use `impactClass`; never re-derive them from strings.
