### WO-A.1: Add the SpotBugs + FindSecBugs adapter (runs in the sandbox)
Wave: 8   Depends on: WO-K.7, WO-X.1, WO-X.2, WO-X.3   Blocks: WO-E.3
Milestone: Oral Jan 25
Traces: FR-3, NFR-3 · D, G, J
Suggested human owner: Elodie Abdo (reviewer: Abdul Babatunde)
Size: M

Goal (2–3 sentences): `SpotBugsAdapter` (`requiresSandbox=true`) compiles the target offline **inside the
sandbox**, runs SpotBugs with the FindSecBugs plugin (both baked into a derived image layer, or mounted ro
from the cache) with SARIF output, promotes the SARIF, and ingests it.

Context to read first (exact paths only): `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/{MavenWorkspace,ArtifactPromoter}.java`;
`secpipe-sarif/src/main/java/ca/carleton/secpipe/sarif/SarifIngest.java`; contracts `spi/AnalyzerAdapter.java`.

Files you own (create/modify — nothing else): `secpipe-adapter-spotbugs/src/**`, `sandbox-images/spotbugs/Dockerfile`.

Files you may read but not modify: sandbox, sarif.

Interfaces consumed: `SandboxRunner`, `MavenWorkspace`, `SarifIngest`   Interfaces produced: adapter id `spotbugs`.

Implementation notes:
- FindSecBugs bug pattern → CWE map: use the plugin's `messages.xml` CWE references; keep a table for the
  five CWEs plus 502/798.
- `defaultEnabled`: `standard`/`deep` when the sandbox is available.
- Timeout: 10 min.

Tests to write: SARIF fixture ingest (no Docker); `@Tag("sandbox")` run on the MVP sample.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-adapter-spotbugs -am verify` → exit 0
- [ ] [DOCKER] `./mvnw -q -B -Psandbox -pl secpipe-adapter-spotbugs -am verify` → ≥ 1 CWE-89 candidate on `fixtures/mvp-samples/vulnerable-java-app` (paste the log)

Out of scope: non-security bug patterns.

Handoff note for next agent: SpotBugs reports bytecode lines. Normalize resolves the statement from source.
