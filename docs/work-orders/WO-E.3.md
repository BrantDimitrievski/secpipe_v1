### WO-E.3: Compare against existing tools (code: Semgrep, Snyk Code; dependencies: Snyk, Grype, Trivy)
Wave: 13   Depends on: WO-E.1, WO-P.4, WO-P.5, WO-P.6, WO-P.7, WO-A.1, WO-A.2, WO-A.3, WO-A.4   Blocks: WO-D.4
Milestone: Final Feb 20
Traces: NFR-4, FR-5 · (evaluation §2.8 item 3)
Suggested human owner: Abdul Babatunde (reviewer: Elaine Huang)
Size: M

Goal (2–3 sentences): Score Semgrep (its registry rules and SecPipe's bundled rules) and Snyk Code (team
account, run manually) on the Benchmark with E.1's rule. Compare dependency findings as (package, CVE) pairs
across SecPipe, Snyk, Grype, and Trivy on the MVP sample apps and WebGoat/JavaVulnerableLab: overlap,
uniques, and SecPipe misses (baseline: the MVP found 47 of 88).

Context to read first (exact paths only): `secpipe-eval/src/main/java/ca/carleton/secpipe/eval/benchmark/BenchmarkScorecard.java`; MVP numbers in `docs/PLAN.md` §1.4.

Files you own (create/modify — nothing else): `secpipe-eval/src/main/java/ca/carleton/secpipe/eval/compare/**`,
`secpipe-eval/src/test/java/ca/carleton/secpipe/eval/compare/**`, `scripts/eval/compare-tools.sh`, `docs/eval/tool-comparison.md`.

Files you may read but not modify: everything else.

Interfaces consumed: SARIF/JSON outputs of each tool   Interfaces produced: `tool-comparison.md` tables + `deps-overlap.csv`.

Implementation notes: Snyk is run by a team member with their account; the script accepts the exported JSON
(no Snyk dependency in SecPipe). Normalize CVE/GHSA aliases via the OSV ids in SecPipe's records.

Tests to write: overlap computation on small hand-made inputs; alias normalization.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-eval -am test -Dtest='Compare*Test'` → ≥ 4 tests, 0 failures
- [ ] `docs/eval/tool-comparison.md` contains a code table (≥ 3 tools × 5 CWEs) and a dependency Venn-count table (4 tools)

Out of scope: DAST.

Handoff note for next agent: The final report figure "SecPipe vs tools" reads this file.
