### WO-P.0: Confirm the MVP inventory and port the test corpora
Wave: 1   Depends on: WO-0.1   Blocks: WO-E.1, WO-P.1, WO-P.2, WO-P.3, WO-E.5
Milestone: Prototype 2 Dec 5
Traces: FR-3, NFR-4 · (evaluation basis)
Suggested human owner: Abdul Babatunde (reviewer: Elaine Huang)
Size: S

Goal (2–3 sentences): Re-check the Part 3 port map against `capstone-mvp-1` HEAD and record any drift.
Copy the MVP's sample apps and eval corpus into `fixtures/`. Create the canonical e2e fixture
`fixtures/e2e/sqli-min`. Every port WO then works from in-repo fixtures.

Context to read first (exact paths only): `docs/PLAN.md` Part 3 port map; MVP paths `samples/vulnerable-java-app/**`,
`eval/samples/**`, `eval/answers.csv`, `eval/fix-commits.csv`, `eval/results/2026-11-03-noai.md`.

Files you own (create/modify — nothing else): `docs/mvp-inventory.md`, `fixtures/mvp-samples/**`,
`fixtures/e2e/sqli-min/**` (except `llm/` and `expected.json`, which belong to WO-0.2 later; create the
initial `expected.json` here), `secpipe-eval/src/main/resources/mvp/answers.csv`,
`secpipe-eval/src/main/resources/mvp/fix-commits.csv`.

Files you may read but not modify: the MVP repo (read-only reference).

Interfaces consumed: none   Interfaces produced: fixtures.

Implementation notes:
- `docs/mvp-inventory.md`: MVP commit hash; a table of each MVP class with its port-map decision, marked
  "confirmed" or "changed: …"; the five Benchmark CWEs supported (22, 78, 89, 327, 328) plus 502 and 798
  (not scored by the Benchmark).
- `fixtures/e2e/sqli-min` is a minimal Maven project (no parent, junit-jupiter test dep) containing
  `LoginServlet` (SQLi via `getParameter` → `executeQuery`) and `TagFilter` (the value is overwritten with
  a constant before the query, copied from MVP `eval/samples/.../TagFilter.java`). Add a servlet-api
  `provided` dependency.
- The first `expected.json` is `{ "no_ai": { "needs_validation": 1, "rejected": 1, "confirmed": 0 } }`:
  the deterministic refuter rejects TagFilter even without AI.
- Each copied file keeps its relative path; add a `README.md` per fixture describing the expected
  findings.
- Commit message body: `Ported-from: capstone-mvp-1@<hash>:<path>`.

Tests to write: none (data). The fixture must build: `cd fixtures/e2e/sqli-min && ../../../mvnw -q -B -o
compile` is **not** run on the host (sandbox rule); instead, check it is well-formed with `xmllint --noout
pom.xml`.

Acceptance criteria (all must pass):
- [ ] `test -f docs/mvp-inventory.md && grep -c 'confirmed\|changed:' docs/mvp-inventory.md` → ≥ 25
- [ ] `ls fixtures/mvp-samples/shop-samples/src/main/java/com/shop/app/*.java | wc -l` → 21
- [ ] `xmllint --noout fixtures/e2e/sqli-min/pom.xml fixtures/mvp-samples/vulnerable-java-app/pom.xml` → exit 0
- [ ] `wc -l < secpipe-eval/src/main/resources/mvp/answers.csv` → 21

Out of scope: any Java code.

Handoff note for next agent: The fixture paths are stable. WO-P.2 tests run the builtin adapter on
`fixtures/mvp-samples/shop-samples` and must reproduce the MVP table in `eval/results/2026-11-03-noai.md`.
