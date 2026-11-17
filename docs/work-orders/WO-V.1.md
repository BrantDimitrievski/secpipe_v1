### WO-V.1: Implement the findings validator (schema + rules F1–F9)
Wave: 2   Depends on: WO-C.1, WO-C.5   Blocks: WO-K.13, WO-R.1, WO-K.9, WO-K.11
Milestone: Prototype 2 Dec 5
Traces: FR-4, FR-8, FR-9 · F, I, P
Suggested human owner: Elaine Huang (reviewer: Brant Dimitrievski)
Size: M

Goal (2–3 sentences): `FindingsValidator` checks `findings.json` against the schema and the deterministic
rules F1–F9 (§4.1). It returns structured errors (path + rule + message, capped at 100). The engine runs it
after every record write and CI runs it on fixtures. This answers the "inconsistent output formats" risk.

Context to read first (exact paths only): `docs/PLAN.md` §4.1; `secpipe-contracts/src/main/resources/schemas/findings.schema.json`;
`secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/json/ContractJson.java`; Cloudflare reference rules summarized in §4.1.

Files you own (create/modify — nothing else): `secpipe-validators/src/main/java/ca/carleton/secpipe/validators/FindingsValidator.java`,
`secpipe-validators/src/main/java/ca/carleton/secpipe/validators/CapPolicy.java`, `secpipe-validators/src/test/java/ca/carleton/secpipe/validators/FindingsValidatorTest.java`,
`secpipe-validators/src/test/resources/ca/carleton/secpipe/validators/findings/**`.

Files you may read but not modify: contracts, `SchemaRegistry.java`, `ValidationResult.java`,
`ValidationError.java` (WO-C.1).

Interfaces consumed: `ContractJson`, `Fingerprints`   Interfaces produced:
`FindingsValidator.validate(List<Finding>, Optional<Path> root, CapPolicy) → ValidationResult`,
`validateJson(String)`, and the interface `CapPolicy { Optional<Score5> cap(Finding.Confirmed) }` (WO-K.9
provides the real one; the default is `CapPolicy.NONE`).

Implementation notes:
- Use networknt `JsonSchemaFactory` for draft 2020-12, with schemas loaded from the classpath via
  `SchemaRegistry` (shared with V.2/V.3).
- F7 file existence only with `root`.
- Error messages include the fingerprint.
- Cap the input at 20 MiB and 10,000 records (the Cloudflare-style limits).

Tests to write: one passing and ≥ 1 failing fixture per rule F1–F9; schema failures surface with a JSON
pointer; the 100-error cap; the size limit.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-validators -am test -Dtest=FindingsValidatorTest` → ≥ 20 tests, 0 failures
- [ ] A test validates `secpipe-contracts/src/test/resources/examples/findings-valid.json` as valid, apart from F7 (no root) and F3 (`te-0a1b2c3d4e5f` evidence present → passes)

Out of scope: CLI wiring (WO-K.13 calls it).

Handoff note for next agent: `ValidationResult.ok()` plus `errors()` are the only API. The engine treats
any error as a rejected delta.
