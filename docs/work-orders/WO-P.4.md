### WO-P.4: Port the Grype adapter
Wave: 5   Depends on: WO-K.7, WO-P.3   Blocks: WO-E.3
Milestone: Prototype 2 Dec 5
Traces: FR-5, FR-7, NFR-3 · G
Suggested human owner: Abdul Babatunde (reviewer: Elaine Huang)
Size: S

Goal (2–3 sentences): Port `GrypeAdapter` into `secpipe-adapter-grype`. It runs `grype dir:<root> -o json`
(or SARIF via `secpipe-sarif`), maps matches to dependency candidates with purls, and records the DB
age/offline state.

Context to read first (exact paths only): MVP `src/main/java/ca/carleton/secpipe/tools/GrypeAdapter.java`;
contracts `spi/AnalyzerAdapter.java`; `secpipe-sarif/src/main/java/ca/carleton/secpipe/sarif/SarifIngest.java`.

Files you own (create/modify — nothing else): `secpipe-adapter-grype/src/main/**`, `secpipe-adapter-grype/src/test/**`.

Files you may read but not modify: `secpipe-sarif/**`, `secpipe-adapter-osv/**` (reuse purl helpers by
copying the small function, not by depending on it).

Interfaces consumed: `AnalyzerAdapter`   Interfaces produced: adapter id `grype`.

Implementation notes:
- Use `--add-cpes-if-none` off and `--only-fixed` off.
- `offline` → set `GRYPE_DB_AUTO_UPDATE=false`; if there is no DB → skipped with a hint
  (`grype db update`).
- Parse the JSON (preferred: richer purl data) with Jackson tree.
- Ship a recorded JSON output fixture.

Tests to write: parse the fixture (no binary needed); availability when the binary is absent; the offline
flag sets the env.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-adapter-grype -am verify` → exit 0
- [ ] The fixture test yields candidates whose fingerprints equal `Fingerprints.dependency(purl, ids)` for the same CVE that WO-P.3's fixture yields (cross-tool dedup proof)

Out of scope: installing grype (WO-H.1 script).

Handoff note for next agent: Same-CVE, same-purl candidates from osv, grype, and trivy merge in normalize.
