### WO-H.2: Implement `secpipe-github` and the idempotent PR comment
Wave: 9   Depends on: WO-H.1, WO-R.3   Blocks: WO-H.5, WO-H.6
Milestone: Oral Jan 25
Traces: FR-4 · S, (§2.5)
Suggested human owner: Abdul Babatunde (reviewer: Clinton Odinamadu)
Size: M

Goal (2–3 sentences): Implement the `secpipe-github` module: a small `java.net.http` REST client (token from
env), fork/permission detection from `GITHUB_EVENT_PATH`, and the `github comment` CLI extension. That
command finds the existing comment by the marker and updates it in place, or else creates one.

Context to read first (exact paths only): `secpipe-cli/src/main/java/ca/carleton/secpipe/cli/SecPipeCli.java` (the
`CliExtension` SPI); `secpipe-report/src/main/java/ca/carleton/secpipe/report/markdown/MarkdownCommentRenderer.java`; `docs/PLAN.md` §2.5.

Files you own (create/modify — nothing else): `secpipe-github/src/**`.

Files you may read but not modify: cli, report.

Interfaces consumed: `CliExtension` (K.13)   Interfaces produced: `GitHubClient`, `EventContext` (repo, PR number,
head/base repo, isFork, sha), `secpipe github comment --out DIR`.

Implementation notes:
- **Pagination:** list issue comments (`per_page=100`, follow `Link`), and match a body containing
  `<!-- secpipe:pr-summary -->` authored by the token's actor (`github-actions[bot]`).
- **403 on a fork or with a read-only token** → log "cannot comment (read-only token); results are in
  artifacts/SARIF" and exit 0.
- **Retry** 5xx twice. Never log the token (use `Redactor`).

Tests to write: WireMock — create path; update path (marker found on page 2); 403 graceful; EventContext
parsing for push, pull_request, and fork pull_request samples.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-github -am verify` → ≥ 8 tests, 0 failures
- [ ] A test asserts two consecutive `comment` invocations produce one POST and then one PATCH (no duplicate comments)

Out of scope: suggestions/bot PR (H.5).

Handoff note for next agent: H.3 uses `EventContext.isFork()`; H.5 reuses `GitHubClient`.
