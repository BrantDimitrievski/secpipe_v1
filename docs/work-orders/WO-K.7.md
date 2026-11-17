### WO-K.7: Build the SARIF 2.1.0 ingest library
Wave: 2   Depends on: WO-C.1   Blocks: WO-K.6, WO-P.6, WO-P.7, WO-P.4, WO-P.5, WO-A.2, WO-A.1, WO-A.3
Milestone: Prototype 2 Dec 5
Traces: FR-9, NFR-3 · G
Suggested human owner: Clinton Odinamadu (reviewer: Elaine Huang)
Size: M

Goal (2–3 sentences): `SarifIngest` converts any tool's SARIF 2.1.0 into `Candidate`s and `CoverageClaim`s.
It handles CWE extraction from `taxa`, `properties.tags`, `cwe` fields, and rule help text, plus code flows
→ trace steps, `partialFingerprints` passthrough, and repo-relative path normalization. Adapters that
emit SARIF need only a few lines.

Context to read first (exact paths only): contracts `finding/Candidate.java`, `spi/AnalyzerAdapter.java`;
MVP `eval/sarif_util.py` (CWE extraction regex).

Files you own (create/modify — nothing else): `secpipe-sarif/src/main/java/ca/carleton/secpipe/sarif/**`,
`secpipe-sarif/src/test/**`.

Files you may read but not modify: contracts.

Interfaces consumed: `Candidate`   Interfaces produced: `SarifIngest.read(Path sarif, Path projectRoot,
String toolId, IngestOptions) → AnalyzerAdapter.Result`; `IngestOptions` (rule→CWE override map, severity
map, `sinkStatementFromSource` flag).

Implementation notes:
- Use Jackson tree (no generated SARIF model needed).
- **Locations:** strip `file://`, `%SRCROOT%`, and the absolute project root; reject paths outside the
  root.
- **`codeFlows[0].threadFlows[0].locations`:** first → entrypoint, middle → propagation, last → sink.
  With no flow → a single sink step at `locations[0]`.
- Read the sink line text from the source to fill `sinkStatement` (when the flag is set).
- **Coverage claims:** for each rule with a CWE, the claim covers every artifact in `run.artifacts`, or
  else every file in the project the tool was told to scan (passed in options).

Tests to write: Semgrep, CodeQL, SpotBugs, and Trivy recorded SARIF fixtures (small, hand-trimmed); CWE
extraction variants; path normalization (Windows `file:///C:/…`); code flow → trace.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-sarif -am verify` → exit 0
- [ ] `./mvnw -q -B -pl secpipe-sarif test -Dtest=SarifIngestTest` → ≥ 10 tests, 0 failures, including a Windows-path case

Out of scope: SARIF writing (WO-R.1).

Handoff note for next agent: When a tool lacks CWE metadata, pass a rule→CWE map in `IngestOptions`. Never
guess from message text, except through the shared regex for `CWE-\d+`.
