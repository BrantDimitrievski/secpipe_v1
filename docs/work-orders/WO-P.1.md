### WO-P.1: Port environment detection and doctor checks (NFR-1)
Wave: 2   Depends on: WO-C.4, WO-P.0   Blocks: WO-K.3, WO-K.4, WO-K.13
Milestone: Prototype 2 Dec 5
Traces: NFR-1, NFR-6 · (setup for N)
Suggested human owner: Elodie Abdo (reviewer: Abdul Babatunde)
Size: M

Goal (2–3 sentences): Port `ProjectDetector`, `BuildTool` detection, and the `DoctorCommand` probes into
`secpipe-core` as `EnvironmentDetector` and `ProjectScanner`. Add real JDK, Maven-wrapper, and Docker
detection with actionable errors. The setup node and `secpipe doctor` call these.

Context to read first (exact paths only): MVP `src/main/java/ca/carleton/secpipe/agents/ProjectDetector.java`,
`(MVP) src/main/java/ca/carleton/secpipe/core/BuildTool.java`, `(MVP) src/main/java/ca/carleton/secpipe/cli/DoctorCommand.java`, `secpipe-core/src/main/java/ca/carleton/secpipe/core/util/Proc.java`; contracts
`spi/EnvironmentInfo.java`, `pipeline/ProjectProfile.java`.

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/env/**`,
`secpipe-core/src/main/java/ca/carleton/secpipe/core/util/Proc.java`,
`secpipe-core/src/test/java/ca/carleton/secpipe/core/env/**`,
`secpipe-core/src/test/resources/ca/carleton/secpipe/core/env/**`.

Files you may read but not modify: contracts, fixtures.

Interfaces consumed: `EnvironmentInfo`, `ProjectProfile`   Interfaces produced:
`EnvironmentDetector.detect(Map<String,String> env) → EnvironmentInfo`;
`ProjectScanner.scan(Path root, boolean includeTests) → ProjectProfile`;
`DoctorReport EnvironmentDetector.doctor(...)` (list of checks with status, detail, and hint).

Implementation notes:
- **Java version:** read `Runtime.version()` for the host, and `java -version` of `JAVA_HOME` if set.
  Below 21 → error with an install hint.
- **Maven wrapper:** detect `mvnw`/`mvnw.cmd`; read `.mvn/wrapper/maven-wrapper.properties` for the
  version. **Do not execute** the target's wrapper (that would be target code). Report the version text
  only.
- **Docker:** `docker version --format '{{.Server.Version}}'` with a 5 s timeout.
- **Tools on PATH:** `semgrep`, `joern`, `grype`, `trivy`, `spotbugs`, `codeql`. Check `PATHEXT` on
  Windows.
- Keep the MVP's ignored dirs, test-source detection, and LOC counting.
- Paths are repo-relative with `/` in `ProjectProfile`.
- `Proc` gets a timeout, output caps (1 MiB), and no shell (`ProcessBuilder` with an argument list).

Tests to write: build-tool detection (5 cases, using temp dirs); test-source classification; Windows path
normalization; a missing Docker binary reports `dockerAvailable=false` with a hint (inject a fake PATH); the
LOC count on `fixtures/mvp-samples/vulnerable-java-app`.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='EnvironmentDetector*Test,ProjectScanner*Test'` → ≥ 10 tests, 0 failures
- [ ] A test asserts `ProjectScanner.scan(fixtures/mvp-samples/vulnerable-java-app)` returns `MAVEN` and ≥ 8 source files
- [ ] `grep -rn "ProcessBuilder" secpipe-core/src/main/java/ca/carleton/secpipe/core/env | grep -v Proc.java` → empty (all through `Proc`)

Out of scope: diff scope (WO-K.3), CLI printing (WO-K.13).

Handoff note for next agent: The doctor checks return structured data; the CLI only formats them.
