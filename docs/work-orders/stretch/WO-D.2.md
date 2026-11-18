### WO-D.2: Write the architecture docs, ADRs, developer guide, and credits
Wave: 6   Depends on: WO-K.1, WO-G.4, WO-X.1   Blocks: WO-D.4
Milestone: Oral Jan 25
Traces: NFR-3 · A–S (documentation of every feature)
Suggested human owner: Brant Dimitrievski (reviewer: Clinton Odinamadu)
Size: S

Goal (2–3 sentences): Copy the ADRs from `docs/PLAN.md` into `docs/adr/ADR-NN-*.md` (updated with spike
outcomes). Write `docs/architecture.md` (pipeline diagram in Mermaid, module map, data flow, verdict
lifecycle), `docs/dev/adding-an-analyzer.md` (NFR-3 walkthrough using the ServiceLoader pattern), and a
"Cloudflare-derived vs original" table. Supports the Dec 11 progress report.

Context to read first (exact paths only): `docs/PLAN.md` Parts 1–5; `docs/spikes/*.md`; `THIRD_PARTY_NOTICES.md`.

Files you own (create/modify — nothing else): `docs/adr/**`, `docs/architecture.md`, `docs/dev/**`, `THIRD_PARTY_NOTICES.md`.

Files you may read but not modify: everything else.

Interfaces consumed: —   Interfaces produced: docs.

Implementation notes: The "Cloudflare-derived vs original" table lists features A–S with columns: Cloudflare source file, what SecPipe
adopted, what changed, and what is original (tools as candidate generators, sandbox fixes, test evidence as
observed result, provider guardrail, Java implementation).

Tests to write: none; the Mermaid renders (check with `npx -y @mermaid-js/mermaid-cli` locally, or note it).

Acceptance criteria (all must pass):
- [ ] `ls docs/adr/ADR-*.md | wc -l` → ≥ 10
- [ ] `grep -c "^| " docs/architecture.md` → ≥ 19 (the A–S table rows)

Out of scope: user docs.

Handoff note for next agent: The final report's design chapter reuses these files.
