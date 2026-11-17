### WO-P.7: Port the Semgrep adapter with a bundled MIT ruleset
Wave: 4   Depends on: WO-K.7, WO-K.6   Blocks: WO-H.1, WO-E.3
Milestone: Prototype 2 Dec 5
Traces: FR-3, NFR-3 · G, (licensing ADR-09)
Suggested human owner: Elaine Huang (reviewer: Clinton Odinamadu)
Size: M

Goal (2–3 sentences): Port `SemgrepAdapter` into `secpipe-adapter-semgrep`. It runs `semgrep scan --sarif
--metrics=off --config <bundled rules>` and ingests through `secpipe-sarif`. It ships a team-written
ruleset for CWE-22/78/89/327/328 so the default avoids Semgrep Rules License terms. Registry rules (`p/java`)
are opt-in.

Context to read first (exact paths only): MVP `src/main/java/ca/carleton/secpipe/tools/SemgrepAdapter.java`;
`secpipe-sarif/src/main/java/ca/carleton/secpipe/sarif/SarifIngest.java`; `docs/PLAN.md` ADR-09.

Files you own (create/modify — nothing else): `secpipe-adapter-semgrep/src/main/**` (incl.
`src/main/resources/semgrep/secpipe-java.yml`), `secpipe-adapter-semgrep/src/test/**`.

Files you may read but not modify: `secpipe-sarif/**`.

Interfaces consumed: `AnalyzerAdapter`, `SarifIngest`   Interfaces produced: adapter id `semgrep`.

Implementation notes:
- **Always pass `--metrics=off`** (NFR-5), and never log in.
- Extract the bundled rules to `workDir`. `SECPIPE_SEMGREP_CONFIG` or `--semgrep-config` overrides them
  (doctor shows a licence note when the value is `p/...`).
- Rules use taint mode (`mode: taint`) with Servlet/Spring sources and JDBC/Process/File/MessageDigest/Cipher
  sinks.
- Each rule has `metadata.cwe`.
- Add a `semgrep --test` fixture directory with annotated examples (`// ruleid:` / `// ok:`).

Tests to write: SARIF fixture ingest (no binary); a rule test under `@Tag("network")`-free conditions only
if `semgrep` is present (`Assumptions.assumeTrue`); every rule has a CWE in its metadata.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-adapter-semgrep -am verify` → exit 0
- [ ] `grep -c "id:" secpipe-adapter-semgrep/src/main/resources/semgrep/secpipe-java.yml` → ≥ 8, and each rule has `cwe:` metadata (asserted by a test)
- [ ] If semgrep is installed: `semgrep --test secpipe-adapter-semgrep/src/test/resources/semgrep-tests --config secpipe-adapter-semgrep/src/main/resources/semgrep/secpipe-java.yml` → all pass (the CI job installs semgrep for this module, see WO-H.1's install script)

Out of scope: Semgrep Pro/AppSec platform.

Handoff note for next agent: The rule ids prefix `secpipe.java.` and appear in `local_checks`.
