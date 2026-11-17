### WO-R.1: Implement JSON and SARIF writers and the report node
Wave: 3   Depends on: WO-V.1, WO-C.1, WO-C.2   Blocks: WO-0.2, WO-R.2, WO-H.1, WO-R.3, WO-R.4
Milestone: Prototype 2 Dec 5
Traces: FR-4, FR-9, FR-10 · F, G, S
Suggested human owner: Abdul Babatunde (reviewer: Elaine Huang)
Size: M

Goal (2–3 sentences): `ReportNode` writes `findings.json`, `coverage-ledger.json`, `run-metadata.json` (all
byte-stable), and `secpipe.sarif` (SARIF 2.1.0, mapped per ADR-05). Rejected records go to `suppressions`,
verdict and evidence to `properties.secpipe`, fingerprints to `partialFingerprints`. Other renderers plug in
through `ReportRenderer` ServiceLoader without touching the node.

Context to read first (exact paths only): `docs/PLAN.md` ADR-05, §2.2-S; contracts `json/ContractJson.java`,
`json/LedgerCodec.java`, `json/RunMetadataCodec.java`; MVP `src/main/java/ca/carleton/secpipe/report/SarifReporter.java`.

Files you own (create/modify — nothing else): `secpipe-report/src/main/java/ca/carleton/secpipe/report/ReportNode.java`,
`secpipe-report/src/main/java/ca/carleton/secpipe/report/ReportRenderer.java`, `secpipe-report/src/main/java/ca/carleton/secpipe/report/JsonOutputs.java`, `secpipe-report/src/main/java/ca/carleton/secpipe/report/SarifWriter.java`,
`secpipe-report/src/main/resources/META-INF/services/ca.carleton.secpipe.report.ReportRenderer`,
`secpipe-report/src/test/java/ca/carleton/secpipe/report/{ReportNode,JsonOutputs,SarifWriter}*Test.java`,
`secpipe-report/src/test/resources/ca/carleton/secpipe/report/sarif/**`.

Files you may read but not modify: contracts, validators.

Interfaces consumed: `PipelineState`, `RunMetadata`   Interfaces produced: `ReportNode`, the
`ReportRenderer { String format(); void render(ReportInputs in, Path outDir) }` SPI, `ReportInputs` (records,
ledger, metadata, coverage summary).

Implementation notes:
- **SARIF:**
  - `tool.driver.name=secpipe`, with rules keyed by CWE family plus title;
  - `results[].ruleId=CWE-xx`, `message.text`=title + claimed/root cause;
  - locations from the sink trace step; `codeFlows` from the trace;
  - `partialFingerprints["secpipeFingerprint/v1"]`;
  - level: confirmed severity critical/high → `error`, medium → `warning`, low/info → `note`;
    needs_validation → `warning` + `properties.tags:["needs-validation"]`; rejected → `note` +
    `suppressions:[{kind:"external",status:"accepted",justification:reason}]`;
  - `properties.secpipe` = `{verdict, confidence, test_evidence: [ids/outcomes], remediation_status}`.
- **Header in `runs[0].properties`:** the AI mode, effective provider, terminal state.
- The SARIF must validate against the official 2.1.0 schema. Vendor the schema JSON under test resources
  (OASIS licence permits it; note it in `THIRD_PARTY_NOTICES` via PR comment for D.2).
- Sort results by fingerprint.
- `ReportRenderer`s run after the JSON/SARIF; a renderer failure → a note, not a crash.

Tests to write: SARIF schema validation; each verdict mapping; rejected → suppressed; byte-identical
output on two runs; the JSON outputs validate.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-report -am test` → ≥ 10 tests, 0 failures
- [ ] A test validates the written `secpipe.sarif` against the vendored `sarif-schema-2.1.0.json`
- [ ] A test asserts a `rejected` record appears with exactly one `suppressions` entry and is never level `error`

Out of scope: HTML (R.2), PR comment (R.3), patches (R.4).

Handoff note for next agent: Renderers receive only `ReportInputs` (verified records + ledger + metadata).
No raw candidates, so prose can never change a verdict (feature S).
