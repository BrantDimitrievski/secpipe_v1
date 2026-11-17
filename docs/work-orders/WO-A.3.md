### WO-A.3: Add the CodeQL adapter (opt-in, licence-gated)
Wave: 12   Depends on: WO-K.7   Blocks: WO-E.3
Milestone: Final Feb 20
Traces: FR-3, NFR-3 · G, (ADR-09)
Suggested human owner: Abdul Babatunde (reviewer: Elaine Huang)
Size: S

Goal (2–3 sentences): `CodeQlAdapter` runs `codeql database create --language=java --build-mode=none`, then
`codeql database analyze … java-security-extended.qls --format=sarif-latest`, and ingests the result. It
runs **only** with `--tools codeql --accept-codeql-terms`, and the doctor prints the licence note.

Context to read first (exact paths only): `docs/PLAN.md` ADR-09; `secpipe-sarif/src/main/java/ca/carleton/secpipe/sarif/SarifIngest.java`.

Files you own (create/modify — nothing else): `secpipe-adapter-codeql/src/**`.

Files you may read but not modify: sarif.

Interfaces consumed: `AnalyzerAdapter`   Interfaces produced: adapter id `codeql`.

Implementation notes: `defaultEnabled` is always false. Without acceptance → availability `no("CodeQL terms not
accepted", "pass --accept-codeql-terms after reading https://github.com/github/codeql-cli-binaries/blob/main/LICENSE.md")`.

Tests to write: SARIF fixture ingest; the gate.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-adapter-codeql -am verify` → exit 0
- [ ] A test asserts availability is false without `acceptCodeqlTerms`

Out of scope: custom queries.

Handoff note for next agent: E.3 may use CodeQL as an extra comparison point under its terms (academic,
open-source benchmark).
