### WO-S.3: Spike — Docker sandbox controls and two-phase Maven on hosted runners and laptops
Wave: 1   Depends on: WO-0.1   Blocks: WO-X.4, WO-X.1
Milestone: Prototype 2 Dec 5
Traces: FR-12, NFR-5, NFR-6 · J, K
Suggested human owner: Elodie Abdo (reviewer: Elaine Huang)
Size: M (time-box: 2 days)

Goal (2–3 sentences): Prove (or disprove) every control in the sandbox contract on GitHub-hosted
`ubuntu-latest`, Docker Desktop on macOS and Windows 11, and Linux. Show that prefetch → offline execution
works with `maven.repo.local.tail` on the MVP's `vulnerable-java-app` and on OWASP Benchmark's pom. Produce
a draft runner Dockerfile and a control matrix.

Context to read first (exact paths only):
`secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/spi/SandboxRunner.java`, `docs/PLAN.md`
ADR-03.

Files you own (create/modify — nothing else): `docs/spikes/S3-sandbox.md`, `scripts/spike/sandbox-probe.sh`,
`scripts/spike/sandbox-probe.ps1`, `spikes/sandbox/Dockerfile`, `.github/workflows/spike-sandbox.yml`.

Files you may read but not modify: `fixtures/**` (after P.0) or a temporary copy of the MVP sample.

Interfaces consumed: `SandboxRunner` contract   Interfaces produced: control matrix and draft image.

Implementation notes:
- **Check each `SandboxRunner.Control`:**
  - `--network none` → `curl` fails;
  - `--read-only` plus `--tmpfs /tmp:rw,noexec,size=…` and HOME;
  - the ro mount for toolchain/cache;
  - `--cpus`, `--memory`, `--pids-limit` (fork bomb is contained), `--ulimit fsize` (large write fails);
  - the disk limit (on overlay2 without quota, emulate it with a size-capped tmpfs scratch, or document
    it as unenforceable);
  - a wall clock (`timeout` + `docker kill`);
  - `--user 10001:10001`, `--cap-drop ALL`, `--security-opt no-new-privileges`;
  - the env allowlist (`--env-file` with only listed keys; verify the host env does not leak).
- **Maven.** Phase 1 runs `mvn -B -q dependency:go-offline dependency:resolve-plugins
  -Dmaven.repo.local=/cache/m2` with network. Phase 2 runs with `--network none`: `mvn -o -B
  -Dmaven.repo.local=/scratch/m2 -Dmaven.repo.local.tail=/cache/m2:ro compile
  dependency:build-classpath`. Then compile and run one JUnit 5 test via
  `junit-platform-console-standalone`.
- Record the Windows/macOS path-mapping quirks (paths with spaces; `C:\` → `/c/`).

Tests to write: none (spike); `shellcheck` on the `.sh`.

Acceptance criteria (all must pass):
- [ ] `grep -E '^Decision: (GO|GO-WITH-LIMITS|NO-GO)$' docs/spikes/S3-sandbox.md` → one line
- [ ] The doc has a control × platform matrix (13 controls × ubuntu-latest, macOS, Windows, Linux laptop) with enforced / unenforceable / untested
- [ ] The workflow run link for `ubuntu-latest` is in the doc, and its log shows the network-none `curl` failing and the offline Maven test passing
- [ ] `docker build -t secpipe-runner:spike spikes/sandbox` → exit 0 locally (screenshot or log excerpt in the doc)

Out of scope: Java implementation (WO-X.1).

Handoff note for next agent: WO-X.1 must treat every "unenforceable" cell as a `probe()` result that
triggers refusal when that control is required. WO-X.4 turns the draft Dockerfile into the pinned image.
