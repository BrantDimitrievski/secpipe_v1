### WO-E.8: Measure performance per stage (NFR-2, AI off)
Wave: 12   Depends on: WO-E.1, WO-K.13   Blocks: WO-K.14
Milestone: Final Feb 20
Traces: NFR-2 · (evaluation §2.8 item 8)
Suggested human owner: Elodie Abdo (reviewer: Elaine Huang)
Size: S

Goal (2–3 sentences): `scripts/eval/perf.sh` runs cold and warm scans (5 repetitions) on the MVP sample (<
10k LOC) and the Benchmark (~284k LOC) with default analyzers and AI off. It reports per-stage timings from
`run-metadata.json`, totals, and machine specs, and writes `docs/eval/perf-baseline.md` with a profiler hot
list for K.14.

Context to read first (exact paths only): contracts `run/RunMetadata.java` (`stage_timings_ms`); `scripts/eval/benchmark.sh`.

Files you own (create/modify — nothing else): `scripts/eval/perf.sh`, `secpipe-eval/src/main/java/ca/carleton/secpipe/eval/perf/**`,
`secpipe-eval/src/test/java/ca/carleton/secpipe/eval/perf/**`, `docs/eval/perf-baseline.md`.

Files you may read but not modify: everything else.

Interfaces consumed: metadata   Interfaces produced: `perf.csv`, `perf-baseline.md` (incl. a "hot list" of ≤ 8 files
from a JFR profile).

Implementation notes: Use JFR (`-XX:StartFlightRecording`) for the hot list. Record the CPU model, cores, RAM,
OS, JDK, and whether Semgrep/Joern ran.

Tests to write: metadata → CSV aggregation.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-eval -am test -Dtest='Perf*Test'` → ≥ 2 tests, 0 failures
- [ ] `docs/eval/perf-baseline.md` has a per-stage table for both targets and a hot list

Out of scope: tuning (K.14).

Handoff note for next agent: K.14 edits only hot-list files.
