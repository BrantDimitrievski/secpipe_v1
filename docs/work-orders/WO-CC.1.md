### WO-CC.1: Remove the `copilot` provider mode after the S.2 NO-GO
Wave: 2   Depends on: WO-C.2, WO-C.4, WO-S.2   Blocks: WO-L.1
Milestone: Prototype 2 Dec 5
Traces: NFR-5, ADR-10 · N, O
Suggested human owner: Clinton Odinamadu (reviewer: Brant Dimitrievski)
Size: S

Goal (2–3 sentences): WO-S.2 measured NO-GO for the Copilot CLI as a provider: its no-tools guarantee does
not hold (it read a file outside the working directory and ran shell commands with no tools allowed). Remove
the `copilot` provider mode from every contract and from the documentation, leaving the AI modes `none` plus
bring-your-own-key (`anthropic`, `openai`, `openai-compatible`, `ollama`) and the CI-only `fake`. Record a
migration note so later work orders implement the reduced set, not the old one.

Context to read first (exact paths only): `docs/spikes/S2-copilot.md`; `action.yml`;
`secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/config/{ProviderMode,SecPipeConfig,ConfigResolver}.java`;
`secpipe-contracts/src/main/resources/schemas/{config,run-metadata}.schema.json`; `docs/PLAN.md` ADR-10 and §4.3
(provider flags and environment variables).

Files you own (create/modify — nothing else):
- `action.yml` (provider-related input descriptions only: `llm-provider`, `api-key`, `github-token`);
- `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/config/ProviderMode.java`,
  `SecPipeConfig.java` (javadoc only), `ConfigResolver.java` (provider list, error hints, key-env hints only);
- `secpipe-contracts/src/main/resources/schemas/config.schema.json` and `run-metadata.schema.json`
  (the provider enums only);
- the contracts tests and fixtures that mention `copilot` (update or delete only those cases);
- `docs/PLAN.md` (ADR-10 retirement note, §4.3 provider rows, and a new "Migration notes" entry in the appendix);
- `docs/work-orders/WO-CC.1.md` (this file, committed verbatim), and the `copilot` mentions in
  `docs/work-orders/WO-H.1.md`, `WO-H.3.md`, `WO-H.6.md`, `WO-L.7.md`, `WO-E.10.md` (edit only those
  sentences; `WO-L.5.md` gets a one-line "Closed: not needed after S.2 NO-GO (WO-CC.1)" note at the top).

Files you may read but not modify: everything else, in particular `docs/spikes/S2-copilot.md`.

Interfaces consumed: S.2 decision   Interfaces produced: reduced `ProviderMode` set and provider enums.

Implementation notes:
- `ProviderMode`: delete `COPILOT`. Keep the other constants, their wire names and order.
- Both schema enums: delete `"copilot"`. Do not touch any other enum value or property.
- `ConfigResolver`: `llm-provider: copilot` must now fail with the usual one-line problem plus
  `hint: copilot was removed after spike S.2 (no-tools guarantee does not hold); use anthropic, openai,
  openai-compatible, ollama or none`. Remove `COPILOT_GITHUB_TOKEN` from the key-env hint, and add the
  `SECPIPE_LLM_KEY` alias from §4.3 (review finding on PR #95).
- `action.yml`: `llm-provider` description lists `none | anthropic | openai | openai-compatible | ollama`;
  `api-key` and `github-token` descriptions drop the Copilot wording. Input names, defaults and outputs do not
  change.
- Work-order docs: replace Copilot provisions with the reduced set. H.3 keeps fork PRs at AI mode `none`;
  H.6 drops its `copilot` mode (two modes remain: `none`, `fake`); E.10 drops the `copilot` arm; H.1 drops the
  commented copilot variant; L.7 drops copilot auto-detection.
- Migration note (PLAN appendix): date, reason (S.2 NO-GO, link to the spike doc), what was removed, and that
  `copilot` in an old `secpipe.yml` now fails with the hint above.

Tests to write: update `ConfigResolver*Test` so `llm-provider: copilot` is rejected with a `hint:` that names
spike S.2; a schema test that `"copilot"` is not a valid `llm-provider` or `providerMode`.

Acceptance criteria (all must pass):
- [ ] `git grep -n -i copilot -- secpipe-contracts action.yml` → only the new rejection hint and its test
- [ ] `git grep -n -i 'copilot' -- docs/work-orders | grep -v -e 'WO-S.2' -e 'WO-L.5' -e 'WO-CC.1'` → no
      line that offers copilot as a provider mode
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)
- [ ] `grep -n 'Migration notes' -A6 docs/PLAN.md` shows the WO-CC.1 entry
- [ ] `python3 -B scripts/check-plan.py` → `errors=0`

Out of scope: provider implementations (L.*), Action scripts (H.*), any other contract change.

Handoff note for next agent: The provider set is `none`, `anthropic`, `openai`, `openai-compatible`,
`ollama` (+ CI-only `fake`). There is no zero-setup GitHub-native provider; the Action defaults to `none`
unless the user supplies a key.
