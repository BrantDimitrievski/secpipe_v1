### WO-P.5: Port the Trivy adapter
Wave: 5   Depends on: WO-K.7, WO-P.3   Blocks: WO-E.3
Milestone: Prototype 2 Dec 5
Traces: FR-5, FR-7, NFR-3 · G
Suggested human owner: Elaine Huang (reviewer: Abdul Babatunde)
Size: S

Goal (2–3 sentences): Port `TrivyAdapter` into `secpipe-adapter-trivy` using `trivy fs --scanners vuln
--format sarif` through `secpipe-sarif`. Map results to dependency candidates.

Context to read first (exact paths only): MVP `src/main/java/ca/carleton/secpipe/tools/TrivyAdapter.java`;
contracts `spi/AnalyzerAdapter.java`; `secpipe-sarif/src/main/java/ca/carleton/secpipe/sarif/SarifIngest.java`.

Files you own (create/modify — nothing else): `secpipe-adapter-trivy/src/main/**`, `secpipe-adapter-trivy/src/test/**`.

Files you may read but not modify: `secpipe-sarif/**`.

Interfaces consumed: `AnalyzerAdapter`, `SarifIngest`   Interfaces produced: adapter id `trivy`.

Implementation notes:
- `offline` → `--skip-db-update --offline-scan`; no DB → skipped with a hint.
- Trivy SARIF puts the purl in `properties`. Fall back to parsing `PkgName@InstalledVersion` from the
  message.
- Use a recorded SARIF fixture.

Tests to write: fixture parse; offline args; binary absent.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-adapter-trivy -am verify` → exit 0
- [ ] The fixture test produces the same `dep:` fingerprint for log4j CVE-2021-44228 as WO-P.3

Out of scope: secret/misconfig scanners.

Handoff note for next agent: Trivy's Java DB is separate. Note this in the doctor hint.
