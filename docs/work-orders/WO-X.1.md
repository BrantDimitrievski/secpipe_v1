### WO-X.1: Implement `DockerSandboxRunner` (enforce-or-refuse) with pinned image and safe promotion
Wave: 2   Depends on: WO-S.3   Blocks: WO-X.2, WO-G.7
Milestone: Prototype 2 Dec 5
Traces: FR-12, NFR-5, NFR-6 · J, K
Suggested human owner: Elodie Abdo (reviewer: Elaine Huang)
Size: M

Goal (2–3 sentences): `DockerSandboxRunner` drives the `docker` CLI with every `SandboxRunner.Control` enforced or refused after a live `probe()`, following spike S.3. It folds in the pinned runner image (old X.4) and `ArtifactPromoter` (old X.3), which copies only declared, regular, size-capped files out of the scratch tar stream.

Context to read first (exact paths only): contracts `spi/SandboxRunner.java`; `docs/spikes/S3-sandbox.md` (on branch `origin/wo-s.3-sandbox-spike`: `git show origin/wo-s.3-sandbox-spike:docs/spikes/S3-sandbox.md`); `spikes/sandbox/Dockerfile` (same branch); `docs/PLAN.md` ADR-03

Files you own (create/modify — nothing else): `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/DockerSandboxRunner.java`, `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/DockerCli.java`, `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/ControlProbe.java`, `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/EnvAllowlist.java`, `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/ArtifactPromoter.java`, `secpipe-sandbox/src/main/resources/META-INF/services/ca.carleton.secpipe.contracts.spi.SandboxRunner`, `secpipe-sandbox/src/main/resources/sandbox/runner-image.txt`, `sandbox-images/runner/**`, `secpipe-sandbox/src/test/java/ca/carleton/secpipe/sandbox/{DockerSandboxRunner,ControlProbe,EnvAllowlist,ArtifactPromoter}*.java`.

Files you may read but not modify: contracts, the S.3 spike branch.

Interfaces consumed: `SandboxRunner`   Interfaces produced: `DockerSandboxRunner`, `ControlProbe`, `EnvAllowlist.DEFAULT`, `ArtifactPromoter.promote(tarStream, declared, outDir)`.

Implementation notes:
- `DISK_LIMIT` via size-capped tmpfs scratch (`--tmpfs /scratch:rw,nosuid,nodev,size=<diskMb>m,uid=10001,gid=10001`); files go in/out as a tar stream; size `--memory` to cover disk + JVM.
- Always `--name` the container; on timeout `docker kill <name>` then `docker rm -f` (killing the CLI leaves it running).
- `probe()` tests controls on the live engine (flag acceptance is not proof); an unenforceable required control → `SandboxRefusedException`.
- Env via `--env-file` `KEY=VALUE` lines only; `--memory-swap` = `--memory`; exit 137 → resource-limit outcome; `--ulimit fsize` in bytes.
- Image: S.3 Dockerfile with base images pinned by digest, JUnit console jar checked by an in-repo sha256, `ENTRYPOINT []`, uid/gid 10001.

Tests to write: `DockerCli` argument building (every control → flags) without Docker; `EnvAllowlistTest`; `ArtifactPromoterTest` (symlink, hard link, oversize, undeclared rejected); `[DOCKER]` `@Tag("sandbox")` probe + timeout-kill tests.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-sandbox -am test -Dtest='DockerSandboxRunner*Test,ControlProbe*Test,EnvAllowlist*Test,ArtifactPromoter*Test'` → ≥ 15 tests, 0 failures
- [ ] [DOCKER] `./mvnw -q -B -Psandbox -pl secpipe-sandbox -am verify` → probe reports all 13 controls enforced on Linux; a timed-out container is gone (`docker ps -a` by name is empty)
- [ ] [HUMAN] laptop columns of S.3 (macOS/Windows) confirm or mark controls unenforceable
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: gVisor/rootless backends, image publishing workflow (stretch).

Handoff note for next agent: X.2 builds the two-phase Maven flow on top; G.7 is the only node that runs tests.
