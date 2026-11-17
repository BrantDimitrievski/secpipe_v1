### WO-H.3: Implement fork-PR safety and Action provider auto-selection
Wave: 9   Depends on: WO-S.2, WO-L.5, WO-L.7, WO-H.1   Blocks: WO-D.1, WO-H.6
Milestone: Oral Jan 25
Traces: FR-2, NFR-5 · O, (§2.5, §2.7 item 4)
Suggested human owner: Clinton Odinamadu (reviewer: Elaine Huang)
Size: S

Goal (2–3 sentences): In `run-scan.sh` and the `none` fallback path, detect fork PRs and missing secrets.
Use the configured key provider only when its key is actually present and the probe succeeds. Fork PRs
always run at AI mode `none` (S.2: no secrets and a read-only token on fork PRs).
Record the reason. Document and ship the safe two-workflow pattern (`pull_request` scan → artifact →
`workflow_run` publisher with write permissions that never checks out or executes PR code).

Context to read first (exact paths only): `docs/spikes/S2-copilot.md`; `scripts/action/run-scan.sh`;
`secpipe-github/src/main/java/ca/carleton/secpipe/github/EventContext.java`.

Files you own (create/modify — nothing else): `scripts/action/select-provider.sh`, `scripts/action/run-scan.sh` (call
`select-provider.sh`), `examples/workflows/secpipe-fork-scan.yml`, `examples/workflows/secpipe-fork-publish.yml`,
`docs/action/fork-safety.md`, `scripts/action/test/select-provider.test.sh`.

Files you may read but not modify: everything else.

Interfaces consumed: `secpipe doctor --probe-provider` (K.13 doctor already probes)   Interfaces produced: provider
decision lines in metadata (`ai.fallbacks` entry `from: <requested> to: <effective> reason: fork PR: no secrets / read-only token`).

Implementation notes:
- **Never `pull_request_target`.**
- The publisher workflow downloads the artifact, uploads SARIF, and posts the comment using `secpipe
  github comment --out` on the downloaded dir. Treat artifact contents as untrusted data: the comment body
  is regenerated from `findings.json` by `secpipe report --from --format markdown`, after
  `secpipe validate findings`. Never post a raw `pr-comment.md` that came from the untrusted run.

Tests to write: the provider selection matrix (fork × key present × probe result);
`actionlint` on the examples.

Acceptance criteria (all must pass):
- [ ] `bash scripts/action/test/select-provider.test.sh` → `ALL PASS` (≥ 8 cases)
- [ ] `actionlint examples/workflows/secpipe-fork-*.yml` → exit 0, and `grep -c pull_request_target examples/workflows/*.yml docs/action/fork-safety.md` → 0 occurrences outside a "never use" warning sentence (the test greps for the warning wording)

Out of scope: secrets management beyond detection.

Handoff note for next agent: D.1 links `docs/action/fork-safety.md` from the README.
