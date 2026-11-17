### WO-C.1: Freeze the findings contract — JSON codec and conformance tests
Wave: 1   Depends on: WO-0.1   Blocks: WO-E.1, WO-K.1, WO-K.7, WO-P.2, WO-P.3, WO-V.1, WO-V.3, WO-R.1
Milestone: Prototype 2 Dec 5
Traces: FR-4, FR-8, FR-9 · F, G, I, S
Suggested human owner: Brant Dimitrievski (reviewer: Elodie Abdo)
Size: M

Goal (2–3 sentences): Provide `ContractJson`, the single Jackson-based codec that maps `Finding` (sealed,
by `verdict`), `Candidate`, and all nested records to and from exactly the JSON shape of
`findings.schema.json`: snake_case, lower-case enums, omitted empty optionals. Round-trip tests prove
codec ⇔ schema agreement.

Context to read first (exact paths only):
- `secpipe-contracts/src/main/resources/schemas/findings.schema.json`;
- `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/finding/Finding.java`;
- `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/finding/Candidate.java`;
- `secpipe-contracts/src/test/resources/examples/findings-valid.json`;
- `secpipe-contracts/src/test/resources/examples/findings-invalid-nv-with-severity.json`.

Files you own (create/modify — nothing else):
- `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/json/ContractJson.java`;
- `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/json/FindingCodec.java`;
- `secpipe-contracts/src/test/java/ca/carleton/secpipe/contracts/json/FindingCodecTest.java`;
- `secpipe-contracts/src/test/java/ca/carleton/secpipe/contracts/json/SchemaConformanceTest.java`;
- `secpipe-contracts/src/test/resources/examples/findings-*.json` (new files only);
- `secpipe-validators/src/main/java/ca/carleton/secpipe/validators/SchemaRegistry.java`,
  `ValidationResult.java`, and `ValidationError.java` (the shared validator types, created here so the
  three wave-2 validator WOs and WO-K.1 can build in parallel).

Files you may read but not modify: all other contract files.

Interfaces consumed: `Finding`, `Candidate`   Interfaces produced: `ContractJson.mapper()`,
`ContractJson.readFindings(Path|String)`, `ContractJson.writeFindings(List<Finding>)` (pretty, sorted by
fingerprint, UTF-8, LF), `ContractJson.readCandidates/writeCandidates`.

Implementation notes:
- Use Jackson `PropertyNamingStrategies.SNAKE_CASE` and `Jdk8Module` for `Optional`.
- Enums serialize as lower-case names (`NEEDS_VALIDATION` → `needs_validation`).
- Polymorphism comes from a custom deserializer that dispatches on `verdict` (no `@JsonTypeInfo` on
  contracts: the records stay annotation-free).
- Empty `Optional` and empty `List` are omitted **except** for fields the schema requires: `conditions`
  (confirmed) and `tool_sources`/`cwe` (secpipe) are always written.
- The conformance test validates codec output against the schema using `com.networknt:json-schema-validator`
  (test scope).
- **Shared validator types.**
  - `SchemaRegistry` loads every schema under `schemas/` from the classpath by `$id` and relative ref
    (networknt, draft 2020-12).
  - `ValidationError` is the record `(String code, String path, String message, Optional<String> fingerprint)`.
  - `ValidationResult` is the record `(List<ValidationError> errors)` with `ok()`, `merge()`, and a cap at
    100 errors.

Tests to write: round-trip of `findings-valid.json` (read → write → deep-equal JSON); every verdict
builder; unknown property → error; schema validation of the written output; the invalid example fails the
schema.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-contracts verify` → exit 0
- [ ] `./mvnw -q -B -pl secpipe-contracts test -Dtest=SchemaConformanceTest` → ≥ 4 tests, 0 failures
- [ ] `git diff --name-only origin/main...HEAD -- secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/finding` → empty (records unchanged)

Out of scope: semantic rules F1–F9 (WO-V.1).

Handoff note for next agent: Everyone reads and writes findings only through `ContractJson`. Output is
byte-stable (sorted by fingerprint), so golden-file tests are allowed.
