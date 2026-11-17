## Work order
WO-ID: `WO-<epic>.<n>` · Issue: #<n> · Branch: `wo-<epic>.<n>-<slug>`

## What changed (2–5 lines)

## Acceptance criteria (paste each command and the relevant output)
- [ ] `<command 1>` → <result>
- [ ] `<command 2>` → <result>

## Files changed vs files owned
Output of `git diff --name-only origin/main...HEAD`:
```
<paste>
```
- [ ] Every changed path is in the work order's "Files you own" list
- [ ] No changes under `secpipe-contracts/**` or `action.yml` (unless this is a WO-C.* / WO-CC.* order)
- [ ] No new dependencies or modules

## Test evidence
- [ ] `./mvnw -q -B verify` green (paste the last lines)
- [ ] `bash scripts/ci-fixture-scan.sh` green or "skipped: not enabled"
- New tests and what each would catch if the code were wrong:

## Safety checklist
- [ ] No target code runs outside `SandboxRunner`; no network in tests; no API keys anywhere
- [ ] LLM-touching code tested only with `FakeLlmClient`; malformed agent output is discarded, never repaired

## Contract change needed / blockers / surprises for the next agent
<none, or describe — see CLAUDE.md §11>

## Independent review
Reviewer session verdict (JSON from `docs/prompts/pr-review.md`): <paste or link>
