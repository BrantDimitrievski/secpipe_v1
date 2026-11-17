### WO-A.2: Add the Joern adapter (CPG taint queries)
Wave: 6   Depends on: WO-S.4, WO-K.7   Blocks: WO-E.3
Milestone: Oral Jan 25
Traces: FR-3, NFR-2, NFR-3 · C, D, G
Suggested human owner: Brant Dimitrievski (reviewer: Clinton Odinamadu)
Size: M

Goal (2–3 sentences): `JoernAdapter` builds a source-only CPG (`javasrc2cpg`) and runs the ported S.4 taint
queries for CWE-22/78/89/327/328. Each flow becomes a candidate with a multi-step trace, including
inter-procedural paths the builtin tracker misses (e.g. MVP `ScriptRunner`). `defaultEnabled` follows the
S.4 decision.

Context to read first (exact paths only): `docs/spikes/S4-joern.md`, `spikes/joern/queries/*.sc`;
`secpipe-sarif/src/main/java/ca/carleton/secpipe/sarif/SarifIngest.java` (or the JSON flow format from Joern); contracts `spi/AnalyzerAdapter.java`.

Files you own (create/modify — nothing else): `secpipe-adapter-joern/src/**`.

Files you may read but not modify: spikes.

Interfaces consumed: `AnalyzerAdapter`   Interfaces produced: adapter id `joern`; optional `SliceProvider` SPI
in this module that hunters may use later (not required).

Implementation notes:
- Run `joern --script <bundled.sc> --params cpgFile=…,out=…` with JSON output.
- Cache the CPG by source hash in `~/.secpipe/cache/joern`.
- The timeout comes from the S.4 numbers.
- Paths are repo-relative.

Tests to write: parse a recorded JSON output fixture; availability; `defaultEnabled` per profile; an
inter-procedural fixture → a multi-step trace.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-adapter-joern -am verify` → exit 0
- [ ] A recorded-output test yields a candidate for `ScriptRunner.java` (CWE-78) with ≥ 3 trace steps

Out of scope: custom CPG passes.

Handoff note for next agent: E.2 adds a "+joern" arm if the adapter is default-enabled.
