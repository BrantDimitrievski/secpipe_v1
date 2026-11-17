### WO-C.4: Freeze the config contract — `secpipe.yml` schema and resolver
Wave: 1   Depends on: WO-0.1   Blocks: WO-L.1, WO-P.1, WO-K.3, WO-K.10, WO-K.13
Milestone: Prototype 2 Dec 5
Traces: FR-2, NFR-1, NFR-5 · N, O
Suggested human owner: Abdul Babatunde (reviewer: Clinton Odinamadu)
Size: M

Goal (2–3 sentences): Implement `ConfigResolver`, which builds a `SecPipeConfig` from defaults <
`secpipe.yml` < environment < explicit overrides (the map the CLI passes). It validates `secpipe.yml`
against a new `config.schema.json`. Profile-dependent defaults (max agent calls) and `--offline`/`--no-ai`
implications are applied here, in one place.

Context to read first (exact paths only): `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/config/SecPipeConfig.java`, `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/config/RunProfile.java`,
`secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/config/ProviderMode.java`, `docs/PLAN.md` §4.3.

Files you own (create/modify — nothing else):
`secpipe-contracts/src/main/resources/schemas/config.schema.json`,
`secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/config/ConfigResolver.java`,
`secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/config/ConfigException.java`,
`secpipe-contracts/src/test/java/ca/carleton/secpipe/contracts/config/**`,
`secpipe-contracts/src/test/resources/config/**`.

Files you may read but not modify: other contracts.

Interfaces consumed: `SecPipeConfig`   Interfaces produced: `ConfigResolver.resolve(Map<String,String>
overrides, Map<String,String> env, Optional<Path> yaml)`.

Implementation notes:
- Keys are the kebab-case CLI long names. Env vars are `SECPIPE_<UPPER_SNAKE>`.
- Implications:
  - `offline` ⇒ provider `none` unless `ollama`;
  - `no-ai` ⇒ `none`;
  - `profile=quick` and `scope` unset in GitHub Actions on `pull_request` ⇒ `diff`;
  - `enable-fixes=true` ⇒ `SUGGEST`.
- **Reject API keys in YAML** (any key matching `(?i)(api[-_]?key|token|secret)`), with a hint to use env.
- `FAKE` provider only when `SECPIPE_ALLOW_FAKE_LLM=1`.
- Validation errors read "problem + hint".

Tests to write: precedence matrix; profile defaults (40/200/800); implications; YAML with an api key
rejected; unknown key rejected; fake gate.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-contracts test -Dtest='ConfigResolver*Test'` → ≥ 12 tests, 0 failures
- [ ] A test proves `SecPipeConfig` built from a YAML containing `api-key: x` throws `ConfigException` whose message contains `hint:`

Out of scope: picocli parsing (WO-K.13).

Handoff note for next agent: The CLI passes raw `--flag value` pairs as the overrides map. Nothing else
computes defaults.
