### WO-D.1: Write user documentation (install, CLI, providers, Action, privacy)
Wave: 10   Depends on: WO-H.1, WO-H.3, WO-K.13   Blocks: WO-D.4
Milestone: Oral Jan 25
Traces: NFR-1, NFR-5, NFR-6, FR-2 · (§2.7 docs)
Suggested human owner: Elaine Huang (reviewer: Abdul Babatunde)
Size: M

Goal (2–3 sentences): Write the README quick start (one-command no-AI scan) and pages under `docs/user/`:
install per OS, CLI reference (generated from picocli `--help`), provider setup per mode (with the
free-tier data-use warning and the honest Ollama-on-hosted-runner note), the Action guide (permissions,
inputs/outputs, fork safety link), reading reports (verdicts, needs-validation, coverage), and privacy
(what leaves the machine, `--print-prompts`).

Context to read first (exact paths only): `action.yml`; `docs/PLAN.md` §4.3, ADR-10; `docs/action/fork-safety.md`; `examples/workflows/*.yml`.

Files you own (create/modify — nothing else): `README.md`, `docs/user/**`, `scripts/docs/gen-cli-reference.sh`.

Files you may read but not modify: everything else.

Interfaces consumed: the CLI `--help`   Interfaces produced: docs.

Implementation notes: Every command in the docs is copy-pasteable and checked by `scripts/docs/check-snippets.sh` (part of this WO,
listed in owned files under `scripts/docs/`), which runs the no-AI commands on the fixture.

Tests to write: the snippet checker.

Acceptance criteria (all must pass):
- [ ] `bash scripts/docs/check-snippets.sh` → `ALL PASS`
- [ ] `grep -ci "unpaid\|free tier" docs/user/providers.md` → ≥ 1 (data-use warning present)
- [ ] `markdown-link-check README.md docs/user/*.md` (or the equivalent offline link check in the script) → no broken internal links

Out of scope: architecture docs (D.2).

Handoff note for next agent: D.4 refreshes the version numbers only.
