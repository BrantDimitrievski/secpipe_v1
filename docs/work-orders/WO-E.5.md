### WO-E.5: Build the memorization controls (mutated Benchmark cases, own samples, real fix commits)
Wave: 12   Depends on: WO-E.1, WO-E.2, WO-P.0   Blocks: WO-D.4
Milestone: Final Feb 20
Traces: NFR-4 · (evaluation §2.8 item 5)
Suggested human owner: Elaine Huang (reviewer: Brant Dimitrievski)
Size: M

Goal (2–3 sentences): `BenchmarkMutator` produces semantics-preserving mutants of sample cases (renamed
identifiers/classes/packages, reordered statements, extracted helper methods, a changed taint-source API
style) with labels preserved. Run SecPipe's A2/A3 arms on originals vs mutants, on the team's own shop
samples, and on the 29 MVP fix commits, and report the gaps.

Context to read first (exact paths only): `secpipe-eval/src/main/resources/mvp/{answers.csv,fix-commits.csv}`; MVP `eval/README.md`
(rationale); `scripts/eval/ablation.sh`.

Files you own (create/modify — nothing else): `secpipe-eval/src/main/java/ca/carleton/secpipe/eval/mutate/**`,
`secpipe-eval/src/main/java/ca/carleton/secpipe/eval/fixcommits/**`, `secpipe-eval/src/test/java/ca/carleton/secpipe/eval/{mutate,fixcommits}/**`,
`scripts/eval/memorization.sh`, `docs/eval/memorization.md`.

Files you may read but not modify: everything else.

Interfaces consumed: JavaParser (mutations as AST rewrites), CLI   Interfaces produced: mutant corpus (generated
under `~/.secpipe/eval/mutants`, never committed) and the report.

Implementation notes:
- **Mutations must compile:** compile them in the sandbox via `MavenWorkspace` (the Benchmark's pom).
  Discard mutants that don't compile.
- **Fix commits:** port the MVP's `run_fix_commits.sh` logic (git clone at commit^ and commit, scan
  before/after, "found" and "gone after fix").
- Report old (vul4j, 2013–2019) vs recent (GHSA, 2025–2026) hit rates.

Tests to write: each mutation operator preserves the AST semantics on a small sample (identifier sets);
label propagation; the fix-commit CSV parser.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-eval -am test -Dtest='Mutat*Test,FixCommit*Test'` → ≥ 8 tests, 0 failures
- [ ] `docs/eval/memorization.md` contains: original vs mutant TPR/FPR (per CWE), own-sample table, and fix-commit old/recent rates

Out of scope: new benchmark creation beyond mutants.

Handoff note for next agent: A mutant/original gap > 10 percentage points must be called out in the final
report as a threat to validity.
