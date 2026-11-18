### WO-X.2: Implement Maven prefetch and offline workspace preparation
Wave: 4   Depends on: WO-X.1   Blocks: WO-G.7
Milestone: Prototype 2 Dec 5
Traces: FR-12, NFR-5 · J
Suggested human owner: Elodie Abdo (reviewer: Abdul Babatunde)
Size: M

Goal (2–3 sentences): `MavenWorkspace` copies the target into scratch, runs the network-enabled, secret-free `MavenPrefetch` phase, and then provides offline commands against a read-only local-repo tail. An incomplete prefetch is a named `prefetch-incomplete` blocker, never a test failure.

Context to read first (exact paths only): `docs/PLAN.md` ADR-03; `docs/spikes/S3-sandbox.md` (branch `origin/wo-s.3-sandbox-spike`, section "Two-phase Maven"); `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/DockerSandboxRunner.java`; contracts `spi/SandboxRunner.java`

Files you own (create/modify — nothing else): `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/MavenWorkspace.java`, `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/BuildExtensionPreflight.java`, `secpipe-sandbox/src/test/java/ca/carleton/secpipe/sandbox/{MavenWorkspace,BuildExtensionPreflight}*.java`, `secpipe-sandbox/src/test/resources/ca/carleton/secpipe/sandbox/prefetch/**`.

Files you may read but not modify: sandbox runner, contracts.

Interfaces consumed: `SandboxRunner`   Interfaces produced: `MavenWorkspace.prepare(Path target) → Prepared(cacheDir, scratchCopy)` or `PrefetchIncomplete(missing)`; `MavenWorkspace.offlineCommand(String... goals)`.

Implementation notes:
- Prefetch goals: `dependency:go-offline dependency:resolve-plugins dependency:resolve` (all scopes), per the corrected ADR-03.
- After prefetch, an offline `dependency:build-classpath` dry check; "Could not resolve dependencies" → `prefetch-incomplete` blocker naming the missing artifact.
- Keep the extensions pre-flight: `<extensions>true</extensions>` or `.mvn/extensions.xml` → refuse unless `--allow-build-extensions`.
- Dropped: the OSV transitive resolver (P.3 is stretch).

Tests to write: `BuildExtensionPreflightTest`; `MavenWorkspaceTest` with a fake `SandboxRunner` (complete, incomplete → blocker); `[DOCKER]` MVP `vulnerable-java-app` prefetch → offline compile.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-sandbox -am test -Dtest='MavenWorkspace*Test,BuildExtensionPreflight*Test'` → ≥ 8 tests, 0 failures
- [ ] [DOCKER] MVP `vulnerable-java-app`: prefetch then offline compile passes
- [ ] [DOCKER] OWASP Benchmark (pinned, `SECPIPE_SPIKE_BENCHMARK`, with `--allow-build-extensions`): prefetch → offline compile passes, or emits a named `prefetch-incomplete` blocker
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: Gradle (stretch Z.2).

Handoff note for next agent: G.7 calls `prepare` once per run and reuses the cache for every test.
