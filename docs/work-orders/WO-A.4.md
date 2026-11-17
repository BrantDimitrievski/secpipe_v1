### WO-A.4: Add a dependency reachability check
Wave: 12   Depends on: WO-P.3, WO-K.4   Blocks: WO-E.3
Milestone: Final Feb 20
Traces: FR-5, FR-8 · H
Suggested human owner: Elodie Abdo (reviewer: Clinton Odinamadu)
Size: M

Goal (2–3 sentences): `ReachabilityAnalyzer` decides `reachable=yes|no|unknown` for each dependency finding.
It checks whether project sources import or reference classes from the vulnerable artifact (a class list
from the artifact jar in the prefetch cache), and whether those packages appear in recon's call sites.
Scoring then caps severity/likelihood accordingly.

Context to read first (exact paths only): `secpipe-adapter-osv/src/main/java/ca/carleton/secpipe/adapter/osv/**`;
`secpipe-core/src/main/java/ca/carleton/secpipe/core/recon/ReconFacts.java`; `docs/PLAN.md` ADR-05 (dependency severity cap).

Files you own (create/modify — nothing else): `secpipe-adapter-osv/src/main/java/ca/carleton/secpipe/adapter/osv/reach/**`,
`secpipe-adapter-osv/src/test/java/ca/carleton/secpipe/adapter/osv/reach/**`.

Files you may read but not modify: recon, osv adapter.

Interfaces consumed: the prefetch cache layout (`MavenWorkspace`)   Interfaces produced: `ReachabilityAnalyzer.assess(DependencyRef,
ProjectProfile, cacheDir) → Reachability`, called by `OsvAdapter` when available (add the call in the osv
adapter's main class — included in ownership: `secpipe-adapter-osv/src/main/java/ca/carleton/secpipe/adapter/osv/OsvAdapter.java`).

Implementation notes: Read jar entries (zip) → package set. Scan imports and FQNs in sources. `unknown` if the
jar isn't cached. Never execute the jar.

Tests to write: yes/no/unknown cases with tiny test jars built in the test (zip API).

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-adapter-osv -am test -Dtest='Reachability*Test'` → ≥ 5 tests, 0 failures

Out of scope: call-graph reachability through reflection.

Handoff note for next agent: The `reachable` value flows into the record via `DependencyRef.reachable`; the
K.9 caps apply automatically.
