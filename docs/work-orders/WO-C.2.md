### WO-C.2: Freeze ledger and run-metadata contracts and implement `CoverageIds`
Wave: 1   Depends on: WO-0.1   Blocks: WO-K.1, WO-V.2, WO-K.4, WO-K.6, WO-R.1
Milestone: Prototype 2 Dec 5
Traces: FR-15, NFR-2 · B, M, P
Suggested human owner: Elodie Abdo (reviewer: Brant Dimitrievski)
Size: S

Goal (2–3 sentences): Add codec support for `LedgerUnit` and `RunMetadata` to `ContractJson`'s module.
Implement `CoverageIds.of` and `newAgentId` exactly as specified. Add run-metadata examples. Ledger ids
become stable and testable.

Context to read first (exact paths only): `secpipe-contracts/src/main/resources/schemas/ledger.schema.json`,
`secpipe-contracts/src/main/resources/schemas/run-metadata.schema.json`, `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/ledger/LedgerUnit.java`, `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/run/RunMetadata.java`,
`secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/ids/CoverageIds.java`, `secpipe-contracts/src/test/resources/examples/ledger-valid.json`.

Files you own (create/modify — nothing else):
- `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/ids/CoverageIds.java` (method bodies only);
- `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/json/LedgerCodec.java`;
- `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/json/RunMetadataCodec.java`;
- `secpipe-contracts/src/test/java/ca/carleton/secpipe/contracts/ids/CoverageIdsTest.java`;
- `secpipe-contracts/src/test/java/ca/carleton/secpipe/contracts/json/LedgerCodecTest.java`;
- `secpipe-contracts/src/test/resources/examples/run-metadata-*.json`, `ledger-*.json` (new files only).

Files you may read but not modify: `ContractJson.java` (WO-C.1). Register your codecs through a Jackson
`Module` class you own (`LedgerCodec` exposes `module()`). C.1 must expose
`ContractJson.mapper(Module... extra)`; coordinate in the PR if needed.

Interfaces consumed: `LedgerUnit`, `RunMetadata`   Interfaces produced: `CoverageIds.of`,
`LedgerCodec.read/write`, `RunMetadataCodec.read/write`.

Implementation notes:
- Canonical JSON = sorted keys, no spaces, UTF-8, Java `Normalizer.Form.NFC`. Reject control or format
  characters (`Character.getType` in CONTROL, FORMAT, LINE_SEPARATOR, PARAGRAPH_SEPARATOR) and
  leading/trailing whitespace.
- Pin a golden vector: refs `{surface:"a.java#GET /x", boundary:"a.java#check", subsystem:"src/a",
  attack_class:"ATTACK-CLASSES.md#Injection/CWE-89"}` → write the resulting id into the test as the
  golden value (computed once and frozen).

Tests to write: golden vector; order independence (same refs → same id); NFC (é composed vs decomposed);
rejection cases; round-trip of `ledger-valid.json`; complete and incomplete metadata examples
schema-valid; complete with `incomplete_reason` schema-invalid.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-contracts verify` → exit 0
- [ ] `./mvnw -q -B -pl secpipe-contracts test -Dtest=CoverageIdsTest` → ≥ 6 tests, 0 failures, and the test source contains a literal `cov:` golden id
- [ ] `ls secpipe-contracts/src/test/resources/examples/run-metadata-*.json | wc -l` → ≥ 2

Out of scope: state invariants (WO-V.2).

Handoff note for next agent: `CoverageIds.of` output is now contract; changing it breaks incremental runs.
