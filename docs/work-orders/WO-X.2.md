### WO-X.2: Implement Maven prefetch and offline workspace preparation
Wave: 4   Depends on: WO-X.1, WO-P.3   Blocks: WO-G.7, WO-A.1, WO-M.1
Milestone: Prototype 2 Dec 5
Traces: FR-12, NFR-5 · J
Suggested human owner: Elodie Abdo (reviewer: Abdul Babatunde)
Size: M

Goal (2–3 sentences): `MavenWorkspace` runs the two-phase Maven flow from ADR-03. First a pre-flight
refuses build extensions unless allowed. Then a network-enabled, secret-free prefetch container resolves
dependencies and plugins into a per-project cache. Then an offline scratch copy runs with
`maven.repo.local.tail` (read-only). It also implements the OSV adapter's `TransitiveResolver` (full
dependency tree, computed in the sandbox).

Context to read first (exact paths only): `docs/spikes/S3-sandbox.md`; `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/DockerSandboxRunner.java`;
`secpipe-adapter-osv/src/main/java/ca/carleton/secpipe/adapter/osv/TransitiveResolver.java`; `docs/PLAN.md` ADR-03.

Files you own (create/modify — nothing else): `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/MavenWorkspace.java`,
`secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/BuildExtensionPreflight.java`, `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/SandboxTransitiveResolver.java`,
`secpipe-sandbox/src/main/resources/META-INF/services/ca.carleton.secpipe.adapter.osv.TransitiveResolver`,
`secpipe-sandbox/src/test/java/ca/carleton/secpipe/sandbox/{MavenWorkspace,BuildExtensionPreflight,SandboxTransitiveResolver}*.java`.

Files you may read but not modify: sandbox runner, osv adapter. (The `secpipe-sandbox` pom already depends
on `secpipe-adapter-osv` per WO-0.1; if not, stop and report.)

Interfaces consumed: `SandboxRunner`, `TransitiveResolver`   Interfaces produced: `MavenWorkspace.prepare(Path target) →
Prepared(cacheDir, scratchCopy)`, `MavenWorkspace.offlineCommand(String... goals)`, `SandboxTransitiveResolver`.

Implementation notes:
- **Pre-flight:** parse `pom.xml` and `.mvn/extensions.xml` for `<extensions>true</extensions>` or
  extensions entries → refuse with `--allow-build-extensions` as the hint. Also reject `<repositories>`
  using `http:` (not https).
- **Prefetch command:** `mvn -B -q -Dmaven.repo.local=/cache/m2 dependency:go-offline
  dependency:resolve-plugins -DskipTests -Dmaven.test.skip=true`. Network `PREFETCH_ONLY`; mounts: the
  scratch copy (rw) and the cache (rw); no other mounts; env from the allowlist only.
- **Cache key:** the SHA-256 of all pom files; reused across runs at `~/.secpipe/cache/m2/<key>`.
- **Offline:** copy the target into a fresh scratch for every execution (never reuse a scratch across
  attempts). The cache is mounted ro at `/cache/m2`, with local repo `/scratch/m2` and tail `/cache/m2`.
- **`TransitiveResolver`:** offline `mvn -o dependency:list -DoutputFile=/scratch/deps.txt
  -DincludeScope=runtime` → promote → parse.

Tests to write: pre-flight refusal cases; the command construction; cache key stability.
(`@Tag("sandbox")`) prefetch + offline compile of `fixtures/e2e/sqli-min`; the transitive list includes
the servlet-api provided scope excluded.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-sandbox -am test -Dtest='MavenWorkspace*Test,BuildExtensionPreflight*Test'` → ≥ 8 tests, 0 failures
- [ ] [DOCKER] `./mvnw -q -B -Psandbox -pl secpipe-sandbox -am verify -Dtest='MavenWorkspaceIT'` → the offline compile succeeds with `--network none` (paste the log)

Out of scope: Gradle (stretch).

Handoff note for next agent: Every later sandbox execution uses `offlineCommand`. Never run Maven with
network after prefetch.
