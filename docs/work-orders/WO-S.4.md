### WO-S.4: Spike — Joern CPG cost and Java taint queries for the five CWEs
Wave: 1   Depends on: WO-0.1   Blocks: WO-A.2
Milestone: Prototype 2 Dec 5
Traces: FR-3, NFR-2 · C, D, E
Suggested human owner: Brant Dimitrievski (reviewer: Abdul Babatunde)
Size: S (time-box: 1.5 days)

Goal (2–3 sentences): Measure Joern install size, CPG build time, and memory on the MVP sample (< 10k LOC)
and on the OWASP Benchmark (~284k LOC). Prototype taint queries for CWE-22/78/89/327/328. Decide whether
Joern is default-on, profile-limited, or opt-in under NFR-2.

Context to read first (exact paths only): `docs/PLAN.md` ADR-04 and ADR-09.

Files you own (create/modify — nothing else): `docs/spikes/S4-joern.md`, `spikes/joern/**` (queries + run
script).

Files you may read but not modify: everything else.

Interfaces consumed: none   Interfaces produced: query prototypes + decision.

Implementation notes:
- Use `javasrc2cpg` (source only; never builds the target).
- Measure cold CPG build, query time, and peak RSS on a laptop-class machine (record CPU/RAM).
- Prototype `reachableByFlows` from Servlet `getParameter` and Spring `@RequestParam` sources to the sinks
  for each CWE. Count TP/FP on 100 Benchmark files (20 per CWE) against `expectedresults-1.2.csv`.
- **Decision rule:** if the Benchmark CPG takes > 45 s on a laptop → opt-in for `quick`, default for
  `standard`/`deep`; if > 5 min → opt-in everywhere.

Tests to write: none (spike).

Acceptance criteria (all must pass):
- [ ] `grep -E '^Decision: (DEFAULT|PROFILE-LIMITED|OPT-IN)$' docs/spikes/S4-joern.md` → one line
- [ ] A table with build time, query time, and RSS for both targets, plus a TP/FP table for the 100-file sample
- [ ] `ls spikes/joern/queries/*.sc | wc -l` → ≥ 5 (one per CWE)

Out of scope: adapter code.

Handoff note for next agent: WO-A.2 ports the `.sc` queries and the timing numbers into `defaultEnabled()`.
