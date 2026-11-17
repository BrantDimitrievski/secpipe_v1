### WO-D.4: Cut release 1.0 and do the final documentation pass
Wave: 14   Depends on: WO-K.14, WO-Y.3, WO-E.3, WO-E.4, WO-E.5, WO-E.6, WO-E.7, WO-E.9, WO-E.10, WO-D.1, WO-D.2   Blocks: final report
Milestone: Final Feb 20
Traces: all · all
Suggested human owner: Clinton Odinamadu (reviewer: Elaine Huang)
Size: S

Goal (2–3 sentences): Bump the versions to `1.0.0`, tag `v1.0.0` and the moving `v1`, attach `secpipe.jar` +
SHA-256 to the GitHub release, and refresh the README badges and numbers from the final evaluation re-run.
Close out the stretch backlog decisions in `docs/PLAN.md` Appendix "Status at 1.0".

Context to read first (exact paths only): `docs/eval/*.md`; `README.md`; `docs/PLAN.md` Part 8 and the stretch backlog.

Files you own (create/modify — nothing else): every `pom.xml` (the version element only), `README.md` (numbers/badges section),
`docs/PLAN.md` (append the appendix only), `.github/workflows/release.yml`, `CHANGELOG.md`.

Files you may read but not modify: everything else.

Interfaces consumed: —   Interfaces produced: release `v1.0.0`.

Implementation notes: The release workflow builds on a tag push and attaches the jar + checksum; `permissions: contents: write`
only in that job.

Tests to write: none beyond the release workflow dry run (`act` or a workflow_dispatch on a test tag).

Acceptance criteria (all must pass):
- [ ] `gh release view v1.0.0 -R <owner>/secpipe --json assets --jq '.assets[].name'` → includes `secpipe.jar` and `secpipe.jar.sha256`
- [ ] `./mvnw -q -B verify` on the tag → exit 0
- [ ] `grep -E 'TPR|FPR' README.md` → numbers that match `docs/eval/ablation.md`'s final arm

Out of scope: marketplace listing (team decision).

Handoff note for next agent: The final report and video use the `v1.0.0` artifacts.
