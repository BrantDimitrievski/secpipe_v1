### WO-X.4: Build and pin the sandbox runner image
Wave: 2   Depends on: WO-S.3   Blocks: WO-X.1, WO-M.2
Milestone: Prototype 2 Dec 5
Traces: FR-12, FR-15, NFR-6 · J
Suggested human owner: Clinton Odinamadu (reviewer: Elodie Abdo)
Size: S

Goal (2–3 sentences): Turn the spike Dockerfile into `sandbox-images/runner/Dockerfile`: Temurin JDK 21 +
Maven 3.9.x + `junit-platform-console-standalone` + JaCoCo agent/CLI at `/opt/…`, non-root uid 10001,
checksum-verified downloads. Add a CI job that builds it, plus a pinned digest file the runner reads.

Context to read first (exact paths only): `spikes/sandbox/Dockerfile`, `docs/spikes/S3-sandbox.md`.

Files you own (create/modify — nothing else): `sandbox-images/runner/Dockerfile`, `sandbox-images/runner/versions.env`,
`sandbox-images/runner/README.md`, `.github/workflows/sandbox-image.yml`, `secpipe-sandbox/src/main/resources/sandbox/runner-image.txt`.

Files you may read but not modify: spike files.

Interfaces consumed: —   Interfaces produced: image reference `ghcr.io/<owner>/secpipe-runner@sha256:…` (or a local
tag `secpipe-runner:<version>` built by `secpipe doctor --build-image`), written to `runner-image.txt`.

Implementation notes:
- Base `eclipse-temurin:21-jdk` pinned by digest.
- Verify downloads with SHA-256 from `versions.env`.
- No package installs at runtime.
- `ENTRYPOINT []`; the workdir is `/scratch`.
- The workflow builds on PRs touching `sandbox-images/**` and pushes to GHCR on `main` only (`packages:
  write` scoped to that job).

Tests to write: workflow-level smoke (`docker run --rm image java -version && mvn -v && test -f
/opt/junit/junit-platform-console-standalone.jar`).

Acceptance criteria (all must pass):
- [ ] `docker build -t secpipe-runner:test sandbox-images/runner` → exit 0
- [ ] `docker run --rm secpipe-runner:test sh -c 'id -u && java -version 2>&1 | head -1 && mvn -v | head -1 && ls /opt/junit /opt/jacoco'` → uid `10001`, Java 21, Maven 3.9.x, both dirs listed
- [ ] `actionlint .github/workflows/sandbox-image.yml` → exit 0

Out of scope: analyzer tools image (not planned; ADR-01).

Handoff note for next agent: X.1 reads `runner-image.txt`. Updating the image means updating both
`versions.env` and the digest.
