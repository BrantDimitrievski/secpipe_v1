### WO-R.3: Render the PR comment markdown and metrics.json
Wave: 8   Depends on: WO-R.1, WO-K.9   Blocks: WO-H.2
Milestone: Oral Jan 25
Traces: FR-4, FR-10, NFR-2 · S
Suggested human owner: Abdul Babatunde (reviewer: Elaine Huang)
Size: S

Goal (2–3 sentences): `MarkdownCommentRenderer` writes `pr-comment.md`: a hidden marker
`<!-- secpipe:pr-summary -->`, the AI-mode line, a confirmed table, a needs-validation table, a coverage
summary, an incomplete banner, and links to artifacts. It stays under GitHub's 65,536-character limit with
deterministic truncation. `MetricsRenderer` writes `metrics.json` (counts, timings, usage, budget) for the
evaluation harnesses.

Context to read first (exact paths only): `secpipe-report/src/main/java/ca/carleton/secpipe/report/ReportRenderer.java`, `ReportInputs.java`;
`docs/PLAN.md` §2.5.

Files you own (create/modify — nothing else): `secpipe-report/src/main/java/ca/carleton/secpipe/report/markdown/**`,
`secpipe-report/src/main/java/ca/carleton/secpipe/report/metrics/**`, `secpipe-report/src/main/resources/META-INF/services/ca.carleton.secpipe.report.ReportRenderer`
(append two lines), `secpipe-report/src/test/java/ca/carleton/secpipe/report/{markdown,metrics}/**`.

Files you may read but not modify: R.1/R.2 classes.

Interfaces consumed: `ReportRenderer`   Interfaces produced: `pr-comment.md`, a `metrics.json` with a JSON shape
documented in the class Javadoc (stable keys: `counts`, `stage_timings_ms`, `ai_usage`, `budget`,
`tools`).

Implementation notes: Escape repo-derived text for Markdown (backticks, `|`, `<`). Show at most 50 rows
per table, then "and N more — see report artifact".

Tests to write: marker present; truncation under the limit with 5,000 records; escaping; metrics keys.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-report -am test -Dtest='Markdown*Test,Metrics*Test'` → ≥ 6 tests, 0 failures

Out of scope: posting (H.2).

Handoff note for next agent: H.2 posts `pr-comment.md` verbatim and finds the old comment by the marker.
