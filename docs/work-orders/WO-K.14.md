### WO-K.14: Tune performance to meet NFR-2 (≤ 60 s / ≤ 10 s, AI off)
Wave: 13   Depends on: WO-E.8   Blocks: WO-D.4
Milestone: Final Feb 20
Traces: NFR-2 · (stage timings)
Suggested human owner: Elodie Abdo (reviewer: Brant Dimitrievski)
Size: M

Goal (2–3 sentences): Use `docs/eval/perf-baseline.md` (from WO-E.8) to remove the top hot spots until
the Benchmark (~284k LOC) scan with default analyzers and AI off is ≤ 60 s, and the MVP sample (< 10k LOC)
is ≤ 10 s on the reference laptop. Re-measure with the same harness.

Context to read first (exact paths only): `docs/eval/perf-baseline.md`, `secpipe-eval/src/main/java/ca/carleton/secpipe/eval/perf/**`,
the hot-spot files named in the baseline.

Files you own (create/modify — nothing else): at most 8 production files named in the "hot list" section
of `docs/eval/perf-baseline.md` (list them in the PR before editing), plus `docs/eval/perf-after.md`. No file
owned by another open WO in wave 12.

Files you may read but not modify: everything else.

Interfaces consumed: —   Interfaces produced: no API changes (any API change → stop).

Implementation notes: Likely wins:
- parse each file once and share ASTs across recon, builtin, and the refuter (cache keyed by path +
  hash);
- run Semgrep with `--jobs`;
- stream SARIF;
- skip Joern in `quick`;
- parallelize normalize grounding;
- avoid repeated `Files.readAllLines`.

Tests to write: existing tests stay green; a micro-benchmark per change (JMH optional; a timed JUnit
`@Tag("perf")` is acceptable).

Acceptance criteria (all must pass):
- [ ] `bash scripts/eval/perf.sh --target benchmark --no-ai` → `total_ms ≤ 60000` printed on the reference laptop (record the machine spec in `docs/eval/perf-after.md`)
- [ ] `bash scripts/eval/perf.sh --target mvp-sample --no-ai` → `total_ms ≤ 10000`
- [ ] `./mvnw -q -B verify` → exit 0

Out of scope: lowering detection breadth to hit the numbers (forbidden: profiles change breadth, never the
evidence bar; changing default analyzers needs a re-plan).

Handoff note for next agent: If the targets can't be met without dropping an analyzer, report the numbers
and stop. The team decides at the Feb re-plan.
