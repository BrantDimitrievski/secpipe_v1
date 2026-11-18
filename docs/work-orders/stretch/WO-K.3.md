### WO-K.3: Implement the setup node (config, environment, diff scope, profile, budget, prior run)
Wave: 3   Depends on: WO-K.1, WO-P.1, WO-C.4   Blocks: WO-0.2, WO-K.12
Milestone: Prototype 2 Dec 5
Traces: NFR-1, NFR-2 · M, N, O
Suggested human owner: Elaine Huang (reviewer: Brant Dimitrievski)
Size: M

Goal (2–3 sentences): `SetupNode` resolves the environment and project profile, computes the diff scope
(changed `.java`/`pom.xml` files between base and head), and loads `--prior-run` (incompatible → recorded,
not fatal). It reserves critic and verifier budget **before** hunters (feature O) and fails fast with
actionable errors on unusable targets.

Context to read first (exact paths only): `secpipe-core/src/main/java/ca/carleton/secpipe/core/env/*.java`,
contracts `pipeline/PipelineState.java`, `config/RunProfile.java`, `spi/Budget.java`, `docs/PLAN.md` §4.2.

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/setup/**`,
`secpipe-core/src/test/java/ca/carleton/secpipe/core/setup/**`.

Files you may read but not modify: `core/env/**`, `core/budget/**`, contracts.

Interfaces consumed: `EnvironmentDetector`, `ProjectScanner`, `Budget`   Interfaces produced: `SetupNode`
(FQCN per PipelineGraph), `DiffScope.compute(root, base, head)`, `PriorRunLoader.load(dir)`.

Implementation notes:
- **Diff:** `git -C <root> diff --name-only --diff-filter=ACMR <merge-base>..<head>` via `Proc`. No git or
  no merge base → fall back to `full`, with a note.
- **Budget reservation:**
  - `quick`: critic 1, verifier = min(10, max/4);
  - `standard`: critic 3, verifier = max/3;
  - `deep`: critic 6, verifier = max/3, record verifier = max/6.
- **Prior run:** read the three JSON files via `ContractJson`. A schema version mismatch → note
  `incompatible_reason`.
- **File hashes:** SHA-256 of every source file in the profile is stored for the incremental comparison
  (WO-K.12).

Tests to write: diff scope in a temp git repo (2 commits); fallback without git; reservation numbers per
profile; prior-run load ok/incompatible; an empty project → `SecPipeException`-style error with a hint.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='SetupNode*Test,DiffScope*Test,PriorRunLoader*Test'` → ≥ 10 tests, 0 failures
- [ ] A test asserts the budget reservation happens in `SetupNode` (reserved map non-empty) before any hunter node could run

Out of scope: ledger seeding (WO-K.4), carry-forward rules (WO-K.12).

Handoff note for next agent: `state.diffScope()` is empty for full scans. Recon and hunters must treat
empty as "everything".
