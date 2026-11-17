### WO-P.3: Port dependency resolution and the OSV.dev adapter
Wave: 2   Depends on: WO-C.1, WO-C.5, WO-P.0   Blocks: WO-K.6, WO-X.2, WO-P.4, WO-P.5, WO-A.4
Milestone: Prototype 2 Dec 5
Traces: FR-5, FR-7, NFR-5 · G
Suggested human owner: Elodie Abdo (reviewer: Clinton Odinamadu)
Size: M

Goal (2–3 sentences): Port `DependencyResolver` (manifest parsing), `VersionComparator`, `Cvss3`,
`OsvClient`, and `OsvAdapter` into `secpipe-adapter-osv`. It emits dependency `Candidate`s with purl,
advisory ids, the fixed version, and a trace from the pom declaration line. Only package names and versions
leave the machine (NFR-5).

Context to read first (exact paths only): MVP `src/main/java/ca/carleton/secpipe/tools/OsvAdapter.java`,
`OsvClient.java`, `tools/deps/*.java`, `core/DependencyRef.java`; MVP tests `DepsSupportTest.java`,
`testing/MockOsvServer.java`, `src/test/resources/osv/*.json`; contracts `finding/Finding.java`
(DependencyRef), `ids/Fingerprints.java`.

Files you own (create/modify — nothing else): `secpipe-adapter-osv/src/main/java/ca/carleton/secpipe/adapter/osv/**`,
`secpipe-adapter-osv/src/main/resources/META-INF/services/ca.carleton.secpipe.contracts.spi.AnalyzerAdapter`,
`secpipe-adapter-osv/src/test/**`.

Files you may read but not modify: contracts, fixtures.

Interfaces consumed: `AnalyzerAdapter`   Interfaces produced: adapter id `osv`;
`DependencyResolver.fromManifests(...)` (public, reused by A.4 and the sandbox prefetch).

Implementation notes:
- **Host-side resolution is manifest-only.** The MVP's `mvn dependency:list` path ran target plugins on
  the host. It moves to the sandbox prefetch: when `Request.sandbox()` is present, call a provided
  callback `TransitiveResolver` (an interface in this module; WO-X.2 supplies an implementation via
  ServiceLoader). Otherwise parse `pom.xml` (properties, `dependencyManagement`, parent in the same repo).
- **Trace:** entrypoint = the `pom.xml` `<dependency>` line (or the parent declaration). Propagation = each
  transitive hop (if known). Sink = the artifact coordinate.
- **Cache:** `~/.secpipe/cache/osv` (configurable). `offline` → cache only; record a ToolRun reason when
  the cache is missing.
- Request body logging is at debug level, showing purls only.

Tests to write: port `DepsSupportTest`; `MockOsvServer`-based adapter test (the log4j 2.14.1 sample yields
CVE-2021-44228 with a fixed version); offline with an empty cache → status `skipped` and reason "offline:
no cached advisories"; an NFR-5 test asserting the captured HTTP bodies contain only `package` and `version`
fields.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-adapter-osv -am verify` → exit 0
- [ ] A test asserts the `vulnerable-java-app` fixture yields ≥ 1 candidate with `cve` containing `CVE-2021-44228` and `dependency.purl` = `pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1`
- [ ] `grep -rn "new ProcessBuilder\|Runtime.getRuntime" secpipe-adapter-osv/src/main` → empty

Out of scope: reachability (WO-A.4), sandbox resolution implementation (WO-X.2).

Handoff note for next agent: `TransitiveResolver` lives in
`ca.carleton.secpipe.adapter.osv.TransitiveResolver`; WO-X.2 implements it in `secpipe-sandbox`.
