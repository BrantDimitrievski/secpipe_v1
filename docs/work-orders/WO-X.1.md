### WO-X.1: Implement `DockerSandboxRunner` (enforce-or-refuse)
Wave: 3   Depends on: WO-S.3, WO-X.4   Blocks: WO-X.2, WO-X.3, WO-G.7, WO-D.2, WO-A.1, WO-Y.2
Milestone: Prototype 2 Dec 5
Traces: FR-12, NFR-5, NFR-6 · J
Suggested human owner: Elodie Abdo (reviewer: Elaine Huang)
Size: L

Goal (2–3 sentences): Implement the `SandboxRunner` contract via the `docker` CLI with every control from
ADR-03. `probe()` checks the controls against the S.3 matrix and a live self-test. `run()` refuses when any
required control is unenforceable, enforces the wall clock with kill and cleanup, and returns bounded output
tails. It is registered via ServiceLoader.

Context to read first (exact paths only): `docs/spikes/S3-sandbox.md`; contracts `spi/SandboxRunner.java`;
`docs/PLAN.md` ADR-03; `sandbox-images/runner/Dockerfile`.

Files you own (create/modify — nothing else): `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/DockerSandboxRunner.java`,
`secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/DockerCli.java`, `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/ControlProbe.java`, `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/EnvAllowlist.java`,
`secpipe-sandbox/src/main/resources/META-INF/services/ca.carleton.secpipe.contracts.spi.SandboxRunner`,
`secpipe-sandbox/src/test/java/ca/carleton/secpipe/sandbox/{DockerSandboxRunner,ControlProbe,EnvAllowlist}*.java`.

Files you may read but not modify: contracts.

Interfaces consumed: `SandboxRunner`   Interfaces produced: `DockerSandboxRunner`, `EnvAllowlist.DEFAULT` (`PATH`,
`LANG`, `LC_ALL`, `TZ=UTC`, `HOME=/tmp/home`, `MAVEN_OPTS`, `JAVA_TOOL_OPTIONS` cleared).

Implementation notes:
- **Command:** `docker run --rm --name secpipe-<runId>-<n> --network none --read-only --tmpfs
  /tmp:rw,nosuid,size=512m --user 10001:10001 --cap-drop ALL --security-opt no-new-privileges --pids-limit
  N --memory Mm --cpus C --ulimit fsize=…`, plus `-v scratch:/scratch:rw` and each mount with `:ro`.
  `--env-file` is a temp file built from the allowlist only (deleted after).
- **The host env is never inherited.** No `-e` with host values. API keys can't reach the sandbox.
- **Disk limit:** enforce with a size-capped scratch (pre-check free space + a watcher that kills the
  container on overflow), or report it unenforceable per the S.3 result.
- **Wall clock:** `Process.waitFor(timeout)`, then `docker kill` and `docker rm -f`.
- **Output:** stdout/stderr tails capped at 64 KiB each.
- **Windows paths:** convert via `DockerCli.hostPath(Path)` per the S.3 notes.

Tests to write: (unit) command-line construction for each control; refusal when probe lacks a required
control; env file contents exclude a planted `SECPIPE_API_KEY`. (`@Tag("sandbox")`) network blocked; ro
mount write fails; fork bomb contained; timeout kill; a non-root uid.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-sandbox -am test` → ≥ 10 tests, 0 failures (no Docker)
- [ ] [DOCKER] `./mvnw -q -B -Psandbox -pl secpipe-sandbox -am verify` → all `@Tag("sandbox")` tests pass on ubuntu-latest (CI job `sandbox` in `ci.yml` runs this profile) 
- [ ] A test asserts `SECPIPE_API_KEY=planted-secret` in the host env never appears in the generated command line or env file

Session split (L-size; same branch, one PR): **Session A** — `DockerCli`, `EnvAllowlist`, command-line construction, and the unit tests (no Docker). Commit and push; stop. **Session B** (fresh session, same kickoff + "continue <WO-ID> part B") — `ControlProbe`, `DockerSandboxRunner.run` (refusal, wall clock, tails), services file, and the `@Tag("sandbox")` tests. Then run all acceptance commands and open the PR.

Out of scope: Maven specifics (X.2), promotion (X.3).

Handoff note for next agent: Use `Spec.required` to demand controls. Default required set = all 13 except
`DISK_LIMIT` when S.3 says it is unenforceable on the host (then the blocker text must say so).
