### WO-R.2: Port the HTML report and render it from verified records
Wave: 5   Depends on: WO-R.1, WO-K.9, WO-K.11, WO-P.8   Blocks: WO-Y.1, WO-D.3
Milestone: Prototype 2 Dec 5
Traces: FR-4, FR-8, FR-10, FR-11, NFR-2 · S, P, F
Suggested human owner: Abdul Babatunde (reviewer: Elodie Abdo)
Size: M

Goal (2–3 sentences): `HtmlRenderer` ports the MVP's self-contained `report-template.html` (offline,
filters, search, detail view). The header shows the AI mode, terminal state, and incomplete reason. It
renders the severity summary, confirmed findings with test evidence and fix status, a **NEEDS-VALIDATION**
section with blockers and validation plans, the coverage summary, per-stage timings, and tool status.
Rejected claims appear only in a collapsed audit appendix, never as findings.

Context to read first (exact paths only): MVP `src/main/resources/report/report-template.html`,
`src/main/java/ca/carleton/secpipe/report/HtmlReporter.java`; `secpipe-report/src/main/java/ca/carleton/secpipe/report/ReportRenderer.java`,
`ReportInputs.java`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/scoring/SeveritySummary.java`.

Files you own (create/modify — nothing else): `secpipe-report/src/main/java/ca/carleton/secpipe/report/html/**`,
`secpipe-report/src/main/resources/report/report-template.html`, `secpipe-report/src/main/resources/META-INF/services/ca.carleton.secpipe.report.ReportRenderer`
(append one line), `secpipe-report/src/test/java/ca/carleton/secpipe/report/html/**`.

Files you may read but not modify: R.1 classes, scoring.

Interfaces consumed: `ReportRenderer`, `ReportInputs`   Interfaces produced: `HtmlRenderer` (format `html`).

Implementation notes:
- Data goes in as one JSON blob inside a `<script type="application/json">`, with HTML-escaping of
  `</script` and `<!--`. **All repo-derived strings are escaped**: the report must not be an XSS vector
  for the scanned code (WO-Y.1 tests this).
- No external CDN.
- A clean run says "No confirmed vulnerabilities" honestly, and still shows coverage.

Tests to write: renders all sections from a fixture; an XSS fixture (title containing
`<img onerror>`) is escaped; zero-finding run text; incomplete header banner.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-report -am test -Dtest='Html*Test'` → ≥ 6 tests, 0 failures
- [ ] `grep -c "https://" secpipe-report/src/main/resources/report/report-template.html` → 0 external resource references (links in text allowed only in the footer credits)

Out of scope: a web dashboard (stretch).

Handoff note for next agent: The demo kit (D.3) uses this report for screenshots.
