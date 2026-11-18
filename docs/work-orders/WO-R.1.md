### WO-R.1: Implement JSON and SARIF writers, the Markdown report, and the report node
Wave: 3   Depends on: WO-V.1, WO-C.1, WO-C.2   Blocks: WO-K.13
Milestone: Prototype 2 Dec 5
Traces: FR-4, FR-9, FR-10, FR-11 · F, G, S
Suggested human owner: Abdul Babatunde (reviewer: Elaine Huang)
Size: M

Goal (2–3 sentences): `ReportNode` writes schema-validated `findings.json`, `coverage-ledger.json`, `run-metadata.json`, a SARIF 2.1.0 file, and `report.md` (replacing the HTML report of old R.2). Every output renders only from the validated JSON and is byte-stable for the same input.

Context to read first (exact paths only): contracts `pipeline/PipelineState.java`, `pipeline/RunMetadata.java`, `ContractJson.java`; `secpipe-validators/src/main/java/ca/carleton/secpipe/validators/FindingsValidator.java`; `secpipe-cli/src/main/java/ca/carleton/secpipe/cli/demo/DemoReport.java`

Files you own (create/modify — nothing else): `secpipe-report/src/main/java/ca/carleton/secpipe/report/**`, `secpipe-report/src/main/resources/**`, `secpipe-report/src/test/**`.

Files you may read but not modify: contracts, validators, demo slice.

Interfaces consumed: `PipelineState`, `RunMetadata`, `FindingsValidator`   Interfaces produced: `ReportNode`; `ReportRenderer` SPI (`json`, `sarif`, `markdown`); `ReportInputs`.

Implementation notes:
- Validate before writing; a validation failure writes nothing and returns an `incomplete_reason`.
- Markdown: summary counts by severity/status, one section per finding (location, trace, evidence, remediation text), coverage summary.
- SARIF: one rule per CWE family, `partialFingerprints.secpipe/v1` = fingerprint, rejected findings as suppressed results.
- Sorted by fingerprint / `coverage_id`; no timestamps beyond `run-metadata.json`.

Tests to write: golden-file tests for each format on a hand-made `PipelineState`; SARIF validates against the 2.1.0 schema in test resources; determinism.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-report -am test` → ≥ 10 tests, 0 failures
- [ ] A test asserts two renders of the same input are byte-identical for all five files
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: HTML report, PR comment, patches (stretch).

Handoff note for next agent: K.13 replaces `DemoReport` with `ReportNode`.
