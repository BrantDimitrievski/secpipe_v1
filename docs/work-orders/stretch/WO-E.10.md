### WO-E.10: Compare AI provider modes and report cost by profile
Wave: 13   Depends on: WO-E.2, WO-L.2, WO-L.3, WO-L.4, WO-L.5, WO-L.7, WO-L.8, WO-E.6   Blocks: WO-D.4
Milestone: Final Feb 20
Traces: FR-2, NFR-4, NFR-5 · O, (evaluation §2.8 items 10 and 11)
Suggested human owner: Clinton Odinamadu (reviewer: Elaine Huang)
Size: M

Goal (2–3 sentences): On the same 400-case sample and remediation cases, run `none`, `copilot` (Copilot Pro via the
Student Developer Pack; replaces the retired GitHub Models free tier), a frontier model (own key), and `ollama` (local GPU or laptop). Report TPR, FPR, fix success rate,
runtime, tokens, agent calls, estimated cost, fallbacks, and guardrail downgrades. Add a cost-per-run table
by profile (quick/standard/deep) on the MVP sample and a mid-size app.

Context to read first (exact paths only): `scripts/eval/ablation.sh`, `scripts/eval/remediation.sh`; contracts `run/RunMetadata.java` (`ai`).

Files you own (create/modify — nothing else): `scripts/eval/providers.sh`, `secpipe-eval/src/main/java/ca/carleton/secpipe/eval/providers/**`,
`secpipe-eval/src/test/java/ca/carleton/secpipe/eval/providers/**`, `docs/eval/providers.md`, `docs/eval/cost.md`.

Files you may read but not modify: everything else.

Interfaces consumed: `run-metadata.json`   Interfaces produced: the provider table and the cost table.

Implementation notes:
- The price table per model is a dated JSON in the eval module (cost = tokens × price).
- Copilot premium-request quotas may force sampling; record the effective sample size and whether the
  tokens were estimated.

Tests to write: cost computation; aggregation across modes.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-eval -am test -Dtest='Provider*Test,Cost*Test'` → ≥ 4 tests, 0 failures
- [ ] `docs/eval/providers.md` has a 4-mode table with all columns listed above; `docs/eval/cost.md` has a profile × target table

Out of scope: choosing the default provider (team decision at the Feb re-plan).

Handoff note for next agent: This table is the "what each mode buys you" figure.
