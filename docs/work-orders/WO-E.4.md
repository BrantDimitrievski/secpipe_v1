### WO-E.4: Run the cost-capped agentic baseline (Cloudflare skill on a stratified sample) — optional
Wave: 13   Depends on: WO-E.2   Blocks: WO-D.4
Milestone: Final Feb 20
Traces: NFR-4, NFR-5 · (evaluation §2.8 item 4)
Suggested human owner: Clinton Odinamadu (reviewer: Abdul Babatunde)
Size: S

Goal (2–3 sentences): Run the actual Cloudflare `security-audit-skill` in a coding agent on 100–200
Benchmark cases (subset of the E.2 sample), with a hard dollar cap. Convert its `findings.json` (same
verdict schema family) and score it with E.1. Compare precision, cost, wall time, and data exposure (whole
files read vs SecPipe snippets).

Context to read first (exact paths only): `docs/eval/sample-400.txt`; `secpipe-eval/src/main/java/ca/carleton/secpipe/eval/benchmark/BenchmarkScorecard.java`.

Files you own (create/modify — nothing else): `scripts/eval/agentic-baseline.md` (runbook), `secpipe-eval/src/main/java/ca/carleton/secpipe/eval/agentic/**`,
`secpipe-eval/src/test/java/ca/carleton/secpipe/eval/agentic/**`, `docs/eval/agentic-baseline.md`.

Files you may read but not modify: everything else.

Interfaces consumed: Cloudflare findings format   Interfaces produced: comparison table.

Implementation notes:
- Run per case directory (one Benchmark file plus helpers) to stay inside the budget. Record tokens and
  cost from the agent's usage output.
- **Skip with a documented reason** if the cost cap would be exceeded. This WO is optional: closing it
  "not run: <reason>" is acceptable.

Tests to write: converter from Cloudflare `findings.json` → scorecard input.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-eval -am test -Dtest='Agentic*Test'` → ≥ 2 tests, 0 failures
- [ ] `docs/eval/agentic-baseline.md` has either a results table (cases, TPR, FPR, cost, files read) or a "not run" section with the reason and the estimated cost

Out of scope: tuning the skill.

Handoff note for next agent: none.
