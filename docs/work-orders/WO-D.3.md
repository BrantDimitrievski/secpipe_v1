### WO-D.3: Build the demo kit (scripted live demo + recorded fallback + figure scripts)
Wave: 11   Depends on: WO-R.2, WO-H.6, WO-E.6   Blocks: oral presentation, poster fair
Milestone: Oral Jan 25
Traces: — · (demo of A–S)
Suggested human owner: Abdul Babatunde (reviewer: Elodie Abdo)
Size: S

Goal (2–3 sentences): Provide `demo/` with a deterministic demo repository (a fork-able copy of the sqli
fixture plus one CMDi and one dependency CVE), `demo/run-local.sh` (CLI path), `demo/pr-demo.md` (the
Action path on a prepared PR), and figure scripts that turn `docs/eval/*.csv` into report and poster
charts. Also record a fallback video and screenshots.

Context to read first (exact paths only): `docs/PLAN.md` Part 10; `docs/eval/*.md`; `fixtures/e2e/sqli-min/**`.

Files you own (create/modify — nothing else): `demo/**`, `scripts/figures/**`, `docs/demo/**`.

Files you may read but not modify: everything else.

Interfaces consumed: the CLI, the Action   Interfaces produced: the demo script plus figures (`docs/demo/figures/*.png|svg`).

Implementation notes:
- `run-local.sh` runs with `--llm-provider fake` by default (works offline, deterministic) and with
  `--llm-provider anthropic` if a key is set.
- The video is ≤ 3 min, covering: the PR opens → the Action runs → the comment → the SARIF alert →
  suggestion accepted → the re-run is clean.

Tests to write: `demo/run-local.sh --check` exits 0 on CI (fake mode).

Acceptance criteria (all must pass):
- [ ] `bash demo/run-local.sh --check` → `DEMO OK` (fake mode; no network)
- [ ] `ls docs/demo/figures | wc -l` → ≥ 5 (ablation, tool comparison, providers, remediation, perf)
- [ ] `docs/demo/storyboard.md` lists timed steps (≤ 8 min) and a fallback for each live step

Out of scope: poster layout (team).

Handoff note for next agent: Rehearse during the Jan 21–24 buffer.
