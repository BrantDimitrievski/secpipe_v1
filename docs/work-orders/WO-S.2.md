### WO-S.2: Spike — Copilot CLI as the GitHub-native provider (auth, limits, fork PRs) after the GitHub Models retirement
Wave: 1   Depends on: WO-0.1   Blocks: WO-L.5, WO-H.3
Milestone: Prototype 2 Dec 5
Traces: FR-2, NFR-5 · O, N (via §2.7)
Suggested human owner: Clinton Odinamadu (reviewer: Abdul Babatunde)
Size: S (time-box: 1.5 days + waiting for workflow runs)

Goal (2–3 sentences): GitHub Models was retired on 2026-07-30, so the brief's zero-setup provider is gone.
Establish the facts for the replacement: how the Copilot CLI authenticates in Actions (`copilot-requests:
write` on org vs personal repos, the `COPILOT_GITHUB_TOKEN` PAT, student licences), whether it can be driven
non-interactively with **no tools** and an empty working directory, its prompt-size limits, its model ids,
its rate/quota behaviour, and **whether any Copilot auth is available on fork `pull_request` runs**. WO-L.5
and WO-H.3 then implement facts, not guesses.

Context to read first (exact paths only): `docs/PLAN.md` ADR-10 (and its retirement note), `action.yml`.

Files you own (create/modify — nothing else): `docs/spikes/S2-copilot.md`, `.github/workflows/spike-copilot.yml`,
`scripts/spike/copilot-probe.sh`.

Files you may read but not modify: everything else.

Interfaces consumed: none   Interfaces produced: the facts table used by L.5/H.3.

Implementation notes:
- **First record the retirement evidence:** link the GitHub changelog posts (2026-06-16 closure,
  2026-07-30 retirement) and show `models.github.ai` no longer serves inference. This justifies the
  contract change from the brief.
- **The probe script:**
  - installs the pinned Copilot CLI (`npm install -g @github/copilot@<pin>`);
  - runs it from an **empty temp dir** with the non-interactive flags (expected `-p`/stdin, `-s`,
    `--no-ask-user`; verify against `copilot --help`), no `--allow-tool`, and a prompt asking for
    `{"ok":true}`;
  - records the exit code, latency, the exact stdout shape (is it clean JSON? does it wrap it?), and any
    usage or token output;
  - repeats with an 8k-, 32k-, and 100k-character prompt, via argv and via stdin/file, to find the size
    limits (Windows argv limits matter for the local CLI).
- **The workflow** runs on `workflow_dispatch`, `pull_request`, and `push`, in two jobs:
  - (a) `permissions: { contents: read, copilot-requests: write }` with `GITHUB_TOKEN`;
  - (b) `contents: read` with `COPILOT_GITHUB_TOKEN` from secrets (skip if absent).

  Run it in the team org repo and in a personal repo. Open one PR from a **fork** and one from a branch.
  No PR code is executed beyond the default checkout.
- **Prove the no-tools guarantee:** ask the CLI (in the empty dir) to list files or read `/etc/hostname`, and
  record that it refuses or can't. If it *can* run tools without `--allow-tool`, the decision is NO-GO for
  `copilot`.
- **Record:** model ids suitable for strong/standard/small roles; the quota behaviour (premium-request
  counting); data-use terms links per plan (student/Pro/Business).

Tests to write: none (spike). `shellcheck scripts/spike/copilot-probe.sh` must pass.

Acceptance criteria (all must pass):
- [ ] `grep -E '^Decision: (GO|GO-ORG-ONLY|NO-GO)$' docs/spikes/S2-copilot.md` → one line
- [ ] `grep -E '^Fork PR copilot auth available: (yes|no)$' docs/spikes/S2-copilot.md` → one line
- [ ] `grep -E '^No-tools guarantee holds: (yes|no)$' docs/spikes/S2-copilot.md` → one line
- [ ] The doc has a table covering: auth path × repo type → works/fails; 3 recommended model ids with tiers;
  the max prompt size via argv and via stdin/file; output shape; 429/quota behaviour; links to ≥ 3 workflow
  runs (org repo, personal repo, fork PR)
- [ ] `shellcheck scripts/spike/copilot-probe.sh` → exit 0

Out of scope: Java code; other providers.

Handoff note for next agent: WO-L.5 uses the invocation, size limit, and model table verbatim. WO-H.3 uses the
fork result. If NO-GO, L.5 is closed as "not built", `copilot` is removed from the docs by a WO-CC contract
change, and the Action modes are `none` plus bring-your-own-key only.
