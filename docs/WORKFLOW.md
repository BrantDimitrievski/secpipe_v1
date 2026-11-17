# SecPipe team workflow (one page)

**Roles.**
- Fable/Opus *architect*: writes and re-plans `docs/PLAN.md` and the work orders.
- *Opus sessions*: one work order each.
- *Reviewer session*: an independent PR check.
- *CI*: the always-on integration tester.
- *Each team member*: owns their work orders, supervises their sessions, reviews, and merges.

## Pick up a work order
1. Open the board (Issues filtered by `wave:<current>`). Take an issue labelled with your `owner:` (or agree to swap
   in the team chat).
2. Check that every "Depends on" work order is merged. If not, it isn't startable; the wave isn't finished.
3. Assign yourself and move it to *In progress*.

## Run a session
1. Fresh checkout of `main` (or a new git worktree: `git worktree add ../wo-k.8 -b wo-k.8-normalize-dedup`).
2. Start a **new** Opus 5.5 session in that directory. Paste `docs/prompts/kickoff.md` with `<WO-ID>` replaced. Give
   it nothing else: no plan, no other work orders.
3. Watch the session. If it stops with `BLOCKED —`, don't push it to improvise. Label the issue `blocked` and post
   the reason in the team chat. The architect issues a fix-up or contract-change work order.
4. The session opens a PR with the template filled in. Up to ~5 sessions run in parallel, one per member.

## Review and merge
1. CI must be green: `ci` is required, and `sandbox` is informational unless the work order touches the sandbox
   (then it must be green too).
2. Start a **fresh** reviewer session (Opus or Fable). Paste `docs/prompts/pr-review.md`, then the work-order file,
   `CLAUDE.md`, and the diff (`gh pr diff <n>`). Paste its JSON verdict into the PR.
3. `changes_requested` → send the findings back to the original session (or a new session on the same branch with
   the kickoff prompt plus the findings).
4. The human reviewer named in the work order checks the acceptance commands against the PR output, then
   approves. The owner merges with **squash** (linear history). Never merge your own PR without that approval.

## End of a wave
1. When every work order in the wave is merged, a member runs **Actions → wave-integration → Run workflow** with the
   wave number.
2. Green → the next wave starts. Red (validator failure, TPR/FPR regression > 2 pp, e2e failure) → open a fix-up
   work order in the *same* wave label, and fix it before anything in the next wave starts.
3. Post the run summary (TPR/FPR table) in the team chat. Keep it for the progress report.

## When to call for a re-plan
- At the fixed points: after Dec 5, after the oral exam, after Feb 20.
- Any time: two or more `blocked` work orders in one wave; a spike returns NO-GO; a wave overruns by more than 2
  days; the Prototype-2 ablation shows FPR > 30 % after the disprover.
- Use `docs/prompts/replan.md` with the attachments it lists. Apply the result with
  `python3 scripts/split-plan.py <new-plan>.md --force`, run `python3 scripts/check-plan.py`, then
  `python3 scripts/create-issues.py --repo <owner>/secpipe` to sync the issues.

## Never
- Push to `main` directly.
- Give a session the whole plan.
- Let a session edit contracts outside `WO-C.*`/`WO-CC.*`.
- Put API keys in CI.
- Run target code outside the sandbox.
