### WO-H.5: Deliver verified fixes as review suggestions and an opt-in draft PR
Wave: 10   Depends on: WO-R.4, WO-H.2, WO-M.1   Blocks: none
Milestone: Oral Jan 25
Traces: FR-13 · Q, (ADR-07)
Suggested human owner: Elodie Abdo (reviewer: Abdul Babatunde)
Size: M

Goal (2–3 sentences): `secpipe github suggest` posts one PR review with suggestions from `suggestions.json`
(idempotent per head SHA). `secpipe github fix-pr` (only with `enable-fixes: pr`, never on forks) pushes
`secpipe/fix-<run>` from the PR head SHA with `secpipe-fixes.patch` applied and opens a **draft PR into the
PR's head branch**. Protected or base branches are never targeted.

Context to read first (exact paths only): `docs/PLAN.md` ADR-07; `secpipe-github/src/main/java/ca/carleton/secpipe/github/GitHubClient.java`;
`secpipe-report/src/main/java/ca/carleton/secpipe/report/patch/PatchRenderer.java`.

Files you own (create/modify — nothing else): `secpipe-github/src/main/java/ca/carleton/secpipe/github/fix/**`,
`secpipe-github/src/test/java/ca/carleton/secpipe/github/fix/**`, `secpipe-github/src/main/resources/META-INF/services/ca.carleton.secpipe.cli.CliExtension`
(append lines).

Files you may read but not modify: other `secpipe-github` files.

Interfaces consumed: `GitHubClient`, `EventContext`   Interfaces produced: `github suggest`, `github fix-pr`.

Implementation notes:
- **fix-pr:** use the Git Data API (blobs/trees/commits/refs) to avoid a local push. The base is the PR
  head SHA. Before creating, check the branch protection of the target (head branch) and refuse if it is
  protected or if it is the repo default branch.
- **Suggestions:** a review with `event: COMMENT`, keyed by a marker in the body plus the head SHA.

Tests to write: WireMock — suggestion review created once per SHA; fork → refuse; protected head branch →
refuse; default branch → refuse; the draft PR body lists the fixed findings.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-github -am test -Dtest='Suggest*Test,FixPr*Test'` → ≥ 8 tests, 0 failures
- [ ] A test asserts `fix-pr` never issues a request that updates `refs/heads/<default branch>` or a protected ref

Out of scope: auto-merge.

Handoff note for next agent: The demo (D.3) shows the suggestion flow on the demo PR.
