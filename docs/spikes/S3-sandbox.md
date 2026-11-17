# S3 — Docker sandbox controls and two-phase Maven (WO-S.3)

Decision: GO-WITH-LIMITS

**Short version.** All 13 `SandboxRunner.Control`s are enforceable with the plain `docker` CLI on GitHub-hosted
`ubuntu-latest`. One of them, `DISK_LIMIT`, works only through emulation: a size-capped tmpfs scratch, because
`--storage-opt size=` is accepted and then silently ignored. The two-phase Maven flow works end to end on the MVP's
`vulnerable-java-app`: prefetch, then an offline compile against a read-only local-repo tail, then an offline JUnit 5
run. It does **not** work on OWASP Benchmark's pom. `dependency:go-offline dependency:resolve-plugins` left a
compile-scope jar out of the cache, so the offline build failed. The "limits" in the decision are those two items and
the X.1 rules listed under "What WO-X.1 / WO-X.2 / WO-X.4 must do".

The macOS, Windows 11 and Linux-laptop columns are **untested** (Brant runs them; see "Laptop runs"). They could
change this decision:
- If Docker Desktop does not enforce a control, WO-X.1's `probe()` must report that control as unenforceable on that
  host, and runs that require it are refused there. The ubuntu-latest decision still stands for CI.
- The decision would drop to NO-GO for laptops only if `NETWORK_NONE`, `READ_ONLY_ROOT` or `NON_ROOT_USER` failed
  there. Those three have no fallback.

## Evidence (ubuntu-latest)

- Workflow run: <https://github.com/BrantDimitrievski/secpipe_v1/actions/runs/44198089290> (commit `dd7ce30`, PR #98).
  Jobs:
  - [isolation](https://github.com/BrantDimitrievski/secpipe_v1/actions/runs/44198089290/job/134444771421)
  - [limits](https://github.com/BrantDimitrievski/secpipe_v1/actions/runs/44198089290/job/134444771082)
  - [maven](https://github.com/BrantDimitrievski/secpipe_v1/actions/runs/44198089290/job/134444771456)
  - [.ps1 parity, isolation](https://github.com/BrantDimitrievski/secpipe_v1/actions/runs/44198089290/job/134444771411)
  - [.ps1 parity, limits](https://github.com/BrantDimitrievski/secpipe_v1/actions/runs/44198089290/job/134444771498)
  Each job writes the control table to its step summary, the raw container output to its log, and one `::notice`
  per control (the source of the excerpts below). The logs are also uploaded as artifacts.
- Engine: Docker 29.4.2 linux/amd64, cgroup v2 (systemd driver), storage `overlayfs` (containerd snapshotter),
  security options apparmor, seccomp (builtin profile), cgroupns.
- Network-none `curl` failing (isolation job log, `NETWORK_NONE`):
  `--network none: curl to Maven Central exit 6 (curl: (6) Could not resolve host: repo.maven.apache.org);
  interfaces: lo. Control run with default network: http_code=200`
- Offline Maven test passing (maven job log, `mvp-vulnerable-java-app-junit5-offline`):
  `--network none: javac + junit-platform-console-standalone against build-classpath, target POM untouched:
  [ 3 tests successful ] [ 0 tests failed ]`. One of the three tests asserts that a socket to Maven Central throws.

### Draft image build (`docker build -t secpipe-runner:spike spikes/sandbox`)

On ubuntu-latest, each job's "Build the draft runner image" step ran this command and printed it to the log and the
step summary:

```
docker-build: exit 0 in 14s; sha256:db1e23e87b6a741d1a68197db4f10eb00a5dc8c1cdb7506e4aa168c9760b3e4f; 212 MiB;
openjdk version "21.0.13" 2026-10-20 LTS; Apache Maven 3.9.11 (3e54c93a704957b63ee3494413a2b544fd3d825b);
junit-platform-console-standalone-1.13.4.jar; uid=10001(sandbox) gid=10001(sandbox) groups=10001(sandbox)
```

The **local** (laptop) build is pending. The agent session that wrote this spike had no Docker daemon (CLAUDE.md
§1b, "pending: needs Docker"). Brant: paste your `docker build` exit status and the last lines here.

## Control × platform matrix

`enforced` means the probe showed the control working, `unenforceable` means it showed the control not working, and
`untested` means the probe has not been run on that platform. Probe = `scripts/spike/sandbox-probe.sh` (or `.ps1`).

| # | Control (`SandboxRunner.Control`) | Docker mechanism | ubuntu-latest | macOS (Docker Desktop) | Windows 11 (Docker Desktop, WSL 2) | Linux laptop |
|---|---|---|---|---|---|---|
| 1 | `NETWORK_NONE` | `--network none` | enforced | untested | untested | untested |
| 2 | `READ_ONLY_ROOT` | `--read-only` | enforced | untested | untested | untested |
| 3 | `READ_ONLY_TOOLCHAIN` | `-v cache:/cache/m2:ro`; image toolchain under `--read-only` | enforced | untested | untested | untested |
| 4 | `SCRATCH_ONLY_WRITES` | rw bind for scratch only; `--tmpfs /tmp` and HOME `rw,noexec,nosuid,nodev,size=…` | enforced | untested | untested | untested |
| 5 | `ENV_ALLOWLIST` | `--env-file` with `KEY=VALUE` lines only | enforced | untested | untested | untested |
| 6 | `CPU_LIMIT` | `--cpus` | enforced | untested | untested | untested |
| 7 | `MEMORY_LIMIT` | `--memory` = `--memory-swap` | enforced | untested | untested | untested |
| 8 | `PID_LIMIT` | `--pids-limit` | enforced | untested | untested | untested |
| 9 | `FILE_SIZE_LIMIT` | `--ulimit fsize=<bytes>:<bytes>` | enforced | untested | untested | untested |
| 10 | `DISK_LIMIT` | size-capped tmpfs scratch (emulation; `--storage-opt` is ignored) | enforced¹ | untested | untested | untested |
| 11 | `WALL_CLOCK_LIMIT` | host timer, then `docker kill <name>` | enforced | untested | untested | untested |
| 12 | `NON_ROOT_USER` | `--user 10001:10001` | enforced | untested | untested | untested |
| 13 | `NO_NEW_PRIVILEGES` | `--security-opt no-new-privileges` + `--cap-drop ALL` | enforced | untested | untested | untested |

¹ Enforced by emulation only. With native `--storage-opt size=64m` the container **started**, and a 100 MiB write to
its rootfs **succeeded**, so the option is silently ignored on overlayfs without project quotas. WO-X.1 must never
treat a successful `--storage-opt` run as enforcement.

### What each probe showed on ubuntu-latest

| Control | Evidence |
|---|---|
| `NETWORK_NONE` | `curl` exit 6 "Could not resolve host"; only `lo`. The same `curl` with the default network got HTTP 200. |
| `READ_ONLY_ROOT` | Even root got EROFS on 7/7 paths (`/`, `/etc`, `/usr`, `/var`, `/opt/java`, `/opt/maven`, `/scratch`). Without `--read-only`, root can write `/etc`. |
| `READ_ONLY_TOOLCHAIN` | Root can read the `:ro` cache. Create and delete both get EROFS, and the host file stays intact. |
| `SCRATCH_ONLY_WRITES` | Writes succeed only in `/scratch`, `/tmp` and `$HOME`. 5/5 other paths are denied. Exec from `/tmp` is blocked (noexec). Exec from the scratch bind mount is **allowed**, because bind mounts cannot be noexec. |
| `ENV_ALLOWLIST` | The container env is the image ENV, the allowlist and `HOSTNAME`. An exported host canary is absent. **Pitfall:** a bare `KEY` line in `--env-file` copies the host value (the canary leaked). |
| `CPU_LIMIT` | `cpu.max` = `50000 100000`. Two busy loops for 4 s used 0.50 CPUs. |
| `MEMORY_LIMIT` | `memory.max` = 134217728. Buffering 1 GiB was OOM-killed (exit 137, `oom_kill=1`). |
| `PID_LIMIT` | A fork bomb was held at 64 PIDs. A second container still started at once, and `docker kill` stopped the bomb. |
| `FILE_SIZE_LIMIT` | `--ulimit fsize=10485760` is in **bytes** (`ulimit -f` shows 10240 KiB). A 32 MiB write stopped at exactly 10 MiB with SIGXFSZ (exit 153). |
| `DISK_LIMIT` | A tmpfs scratch with `size=64m` stopped a 100 MiB write at 67104768 bytes (ENOSPC). tmpfs pages count against `--memory` (`memory.current` ≈ 66 MiB). Files went in and out as a tar stream on stdin/stdout. |
| `WALL_CLOCK_LIMIT` | After a 5 s host timer killed the `docker` CLI, the container was **still running** (sig-proxy forwards TERM to PID 1, which ignores it). `docker kill <name>` stopped it within 9 s. |
| `NON_ROOT_USER` | uid/gid/groups = 10001. Files written to scratch are owned by host uid 10001. |
| `NO_NEW_PRIVILEGES` | `NoNewPrivs: 1`; `CapPrm`, `CapEff` and `CapBnd` are all zero. Even root in the container cannot `chown`. |

The `.ps1` port reached the same 13 verdicts on the same runner. That proves the port works; it is not Windows
evidence. Hosted Windows runners cannot run Linux containers.

## Two-phase Maven

Commands as the probe runs them. Every control is on in both phases except the network in phase 1. The image has no
secrets, and in both phases only scratch and the cache are mounted.

```
# Phase 1 (prefetch, network on): scratch rw, cache rw
mvn -B -q -Dmaven.repo.local=/cache/m2 dependency:go-offline dependency:resolve-plugins
# Phase 2 (--network none): scratch rw, cache mounted :ro
mvn -o -B -q -Dmaven.repo.local=/scratch/m2 -Dmaven.repo.local.tail=/cache/m2 \
    compile dependency:build-classpath -Dmdep.outputFile=/scratch/classpath.txt
# Generated test (--network none): no POM edits
javac -d /scratch/spike-test-classes -cp "target/classes:$(cat classpath.txt):$JUNIT_CONSOLE_JAR" ...
java -jar "$JUNIT_CONSOLE_JAR" execute -cp "...:$(cat classpath.txt)" --select-class ...
```

The work order writes the tail as `-Dmaven.repo.local.tail=/cache/m2:ro`. The `:ro` belongs to the **mount**
(`-v cache:/cache/m2:ro`); the property takes a plain path list. Maven never tried to write into the read-only tail.

| Project | Phase 1 prefetch | Phase 2 offline compile + classpath | Offline JUnit 5 |
|---|---|---|---|
| MVP `vulnerable-java-app` (capstone-mvp-1@2905635) | pass: 16 s, 74 MB cache | pass: 4 s, 12 classpath entries; `/scratch/m2` stays at 4 KiB | pass: 3/3 tests |
| OWASP BenchmarkJava (`a452b3a`) | pass: 37 s, 229 MB cache | **fail**: `Could not resolve dependencies … org.apache.directory.api:api-util:jar:2.1.7 (compile)` | not run |

**Why Benchmark failed.** The prefetch cache held only `api-util-2.1.7.pom` (and its `_remote.repositories`), not the
jar. `dependency:go-offline` resolved a different graph from the one `compile` needs. This is a known gap: go-offline
does not always download every jar. A retry with `-llr` also failed, so tracking files are not the cause.
Benchmark also ships `.mvn/extensions.xml`, so ADR-03's pre-flight would refuse it without `--allow-build-extensions`
anyway. The spike continued past that check only to measure the prefetch.

**This disproves one ADR-03 assumption:** "`dependency:go-offline dependency:resolve-plugins` is a sufficient
prefetch." It holds for the MVP sample and not for Benchmark. Candidate fixes for WO-X.2 (not verified here):
- add `dependency:resolve` (all scopes) next to `dependency:go-offline dependency:resolve-plugins` in the prefetch;
- or prefetch with `dependency:copy-dependencies`;
- either way, treat any offline "Could not resolve dependencies" as a `needs_validation` blocker
  ("prefetch incomplete"), never as a test failure.

## What WO-X.1 / WO-X.2 / WO-X.4 must do (handoff)

- **WO-X.1 (`DockerSandboxRunner`).**
  - Every `unenforceable` cell is a `probe()` result. A run that requires that control is refused
    (`SandboxRefusedException`), and the finding stays `needs_validation` with the control named.
  - `probe()` must test controls on the live engine; it cannot rely on flag acceptance. Docker accepted
    `--storage-opt size=` and ignored it.
  - `DISK_LIMIT`: either emulate it with a tmpfs scratch (`--tmpfs /scratch:rw,nosuid,nodev,size=<diskMb>m,
    uid=10001,gid=10001`), or report it unenforceable. Emulation has three consequences:
    - scratch is not a host bind mount, so files go in and out as a tar stream on stdin/stdout (the spike verified
      this; `docker cp` into a tmpfs mount is not reliable);
    - tmpfs counts against `--memory`, so size `memoryMb` to cover `diskMb` plus the JVM;
    - promotion (WO-X.3) reads from the tar stream, not from a host directory.

    `Spec.scratchDir` stays a host `Path` that the runner tars in and out. No contract change is needed.
  - `WALL_CLOCK_LIMIT`: always name containers (`--name`) and run `docker kill <name>` on timeout. Then
    `docker rm -f`. Killing the CLI process (`Process.destroy`) leaves the container running.
  - `ENV_ALLOWLIST`: write `--env-file` lines as `KEY=VALUE` only, UTF-8 without BOM, LF. Never pass `-e KEY` or a
    bare `KEY`, which import the host value.
  - `MEMORY_LIMIT`: set `--memory-swap` equal to `--memory`. On cgroup v2 an OOM may end the whole container (exit
    137), so map 137 to a resource-limit outcome, not to a test failure.
  - `FILE_SIZE_LIMIT`: `--ulimit fsize` takes bytes. Use `fileSizeMb * 1048576` for both soft and hard.
  - Scratch exec cannot be blocked on a bind mount. That is acceptable, because `--read-only`, non-root, no
    capabilities and no-new-privileges still hold.
  - Clean up scratch from a container (`--user 0:0 … find /w -mindepth 1 -delete`). Files created by uid 10001 may
    not be deletable by the host user.
- **WO-X.2 (`MavenPrefetch`).** Fix the prefetch gap above and add Benchmark as a regression case. Keep the
  extensions pre-flight.
- **WO-X.4 (pinned image).** Start from `spikes/sandbox/Dockerfile`:
  - pin both base images by digest;
  - replace the same-origin `.sha1` check of the JUnit console jar with a sha256 kept in the repo;
  - keep `ENTRYPOINT []` (the official Maven image's entrypoint writes into HOME) and uid/gid 10001.

## Laptop runs (Brant)

Run from the repo root, with the MVP sample checked out as a sibling (`../capstone-mvp-1` at `2905635`). Optionally
point `SECPIPE_SPIKE_BENCHMARK` at a BenchmarkJava checkout.

```bash
docker build -t secpipe-runner:spike spikes/sandbox          # local acceptance item; paste exit + tail above
bash scripts/spike/sandbox-probe.sh --summary                # macOS and Linux laptop (all groups)
```
```powershell
docker build -t secpipe-runner:spike spikes/sandbox
pwsh scripts/spike/sandbox-probe.ps1 -Summary                # Windows 11, PowerShell 7.4+, Docker Desktop (WSL 2)
```

Paste the printed table under a heading per platform, then fill in that platform's matrix column.

## Windows and macOS path quirks (to confirm on the laptop runs)

- **Git Bash/MSYS rewrites paths.** `/scratch` becomes `C:/Program Files/Git/scratch`, and `-v` host paths are
  mangled. `sandbox-probe.sh` refuses to run under MSYS. Use `pwsh` (or set `MSYS_NO_PATHCONV=1` and pass `cygpath -w`
  paths, which the spike does not do). From PowerShell, Docker Desktop accepts `C:\Users\…\x:/scratch` as-is. The
  `/c/Users/…` form is a Git Bash/WSL convention, not a docker one.
- **Spaces in paths.** Windows profile paths often contain a space (`C:\Users\First Last\AppData\Local\Temp`). The
  `.ps1` work dir deliberately contains a space (`secpipe-s3 <run-id>`). It passes each `-v` argument as one array
  element (`$PSNativeCommandArgumentPassing = 'Standard'`) and never builds a command string. On ubuntu the
  space-containing bind mount worked.
- **`${dir}:/scratch` in PowerShell.** `"$dir:/scratch"` parses as a drive-qualified variable. Always write
  `"${dir}:/scratch"`.
- **Line endings.** With `core.autocrlf=true` the `.ps1` here-strings arrive with CRLF, and bash in the container
  then fails on `\r`. The script strips `\r` before handing scripts to the container (`Lf`). Env files and the
  generated test are written LF without BOM.
- **Byte pipes.** The tar round trip needs PowerShell 7.4+ (byte-preserving native pipes) and the built-in
  `tar.exe`.
- **macOS.** There is no GNU `timeout` and the default shell is bash 3.2. The bash probe uses its own timer and no
  bash-4 features. `$TMPDIR` lives under `/var/folders`, which Docker Desktop shares by default. On Docker Desktop
  (macOS and Windows), bind-mount ownership is translated by the file-sharing layer, so the host uid of files written
  by 10001 is expected to differ from Linux. The probe prints it.
- **Docker Desktop VM.** The cgroup and seccomp checks run inside the Desktop Linux VM. Whether the VM uses cgroup v2
  and honours `--pids-limit` and `--storage-opt` is exactly what the laptop columns will show.
