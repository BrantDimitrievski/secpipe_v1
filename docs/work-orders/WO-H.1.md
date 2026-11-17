### WO-H.1: Implement the composite Action scripts and example workflows
Wave: 8   Depends on: WO-K.13, WO-R.1, WO-P.7   Blocks: WO-H.2, WO-H.3, WO-D.1, WO-H.4, WO-H.6
Milestone: Oral Jan 25
Traces: FR-1…FR-15 (delivery), NFR-1, NFR-5 · N, O, (§2.5)
Suggested human owner: Clinton Odinamadu (reviewer: Abdul Babatunde)
Size: M

Goal (2–3 sentences): Implement the scripts `action.yml` calls: `build-jar.sh`, `install-tools.sh` (pinned
versions + SHA-256), `run-scan.sh` (inputs → CLI flags; outputs; exit code capture), and `post-results.sh`.
Ship `examples/workflows/secpipe-pr.yml` and `secpipe-nightly.yml` with least-privilege permissions.

Context to read first (exact paths only): `action.yml`; `docs/PLAN.md` ADR-01, ADR-10, §2.5, §4.3.

Files you own (create/modify — nothing else): `scripts/action/build-jar.sh`, `scripts/action/install-tools.sh`,
`scripts/action/tools.lock`, `scripts/action/run-scan.sh`, `scripts/action/post-results.sh`,
`examples/workflows/secpipe-pr.yml`, `examples/workflows/secpipe-nightly.yml`, `scripts/action/test/**`.

Files you may read but not modify: `action.yml` (contract).

Interfaces consumed: `action.yml` inputs/outputs, the CLI contract   Interfaces produced: the working composite action.

Implementation notes:
- **Scripts:** `set -euo pipefail`. Inputs come only via env (no `${{ }}` inside scripts; the
  script-injection rule).
- **`run-scan.sh`:**
  - map the inputs (`IN_MODELS` "a=b,c=d" → repeated `--model-for`);
  - the scope `diff` outside `pull_request` → `full`;
  - pass `--prior-run .secpipe-state/last` if present;
  - copy the outputs to `.secpipe-state/last` after the run;
  - write `can_upload_sarif` (false on fork PRs: `github.event.pull_request.head.repo.fork` read from
    `$GITHUB_EVENT_PATH` via `jq`).
- **`install-tools.sh`:** Semgrep (`pipx install semgrep==<pin>`), Trivy/Grype (release tarballs +
  SHA-256 from `tools.lock`), Joern only if the profile ≠ quick (per S.4); cached by `actions/cache` keyed
  on `tools.lock`.
- **`post-results.sh`:** calls `java -jar … github comment --out $OUT_DIR` if `IN_COMMENT=true`, and `github
  suggest` if `IN_FIXES` ∈ {suggest, pr}. It no-ops with a log line before H.2/H.5 exist (detect via
  `secpipe github --help` exit code).
- **Example PR workflow:** `on: pull_request`; `permissions: {contents: read, security-events: write,
  pull-requests: write}`; `uses: <owner>/secpipe@v1`; default `llm-provider: none`.
  - The nightly example is `profile: standard`, `scope: full`, `llm-provider: anthropic`, with the key from
    secrets.
- Use `bats` tests if available; otherwise shell tests run by `scripts/action/test/run.sh` (stub `java`
  via PATH).

Tests to write: input mapping table; fork detection from a sample event JSON; exit-code propagation;
checksum mismatch → failure.

Acceptance criteria (all must pass):
- [ ] `bash scripts/action/test/run.sh` → `ALL PASS`
- [ ] `shellcheck scripts/action/*.sh` → exit 0
- [ ] `actionlint examples/workflows/*.yml action.yml` → exit 0
- [ ] `grep -n '\${{' scripts/action/*.sh` → empty

Out of scope: GitHub REST calls (H.2), self-test (H.6).

Handoff note for next agent: The `.secpipe-state/last` layout is what H.4 caches.
