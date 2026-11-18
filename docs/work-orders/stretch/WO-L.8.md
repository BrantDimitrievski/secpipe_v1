### WO-L.8: Implement privacy controls — snippet caps, denylist, redaction, `--print-prompts`
Wave: 6   Depends on: WO-L.1, WO-G.1   Blocks: WO-Y.2, WO-E.10, WO-Y.3
Milestone: Oral Jan 25
Traces: NFR-5 · (§2.7 item 6)
Suggested human owner: Elaine Huang (reviewer: Clinton Odinamadu)
Size: M

Goal (2–3 sentences): Before any prompt leaves the process, `PrivacyFilter` applies the denylist (built-ins
plus `--deny-path`), hard caps (lines per snippet, files per call, total characters per call), and secret
redaction (key patterns, high-entropy strings, `.env` values). `--print-prompts` writes every would-be
prompt to `<out>/prompts/` and sends nothing.

Context to read first (exact paths only): `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/RoutingLlmClient.java`;
`secpipe-agents/src/main/java/ca/carleton/secpipe/agents/prompt/DefaultPromptRenderer.java`; `docs/PLAN.md`
§4.3 (privacy flags).

Files you own (create/modify — nothing else): `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/privacy/**`,
`secpipe-llm/src/test/java/ca/carleton/secpipe/llm/privacy/**`, `secpipe-llm/src/main/java/ca/carleton/secpipe/llm/LlmClients.java`
(wrap the client as `Privacy(PrintPrompts?(Routing))`),
`secpipe-agents/src/main/java/ca/carleton/secpipe/agents/prompt/UntrustedBlockPolicy.java`.

Files you may read but not modify: `RoutingLlmClient.java` (wrap it with the decorator `PrivacyLlmClient
implements LlmClient`; don't edit it), `DefaultPromptRenderer.java`.

Interfaces consumed: `LlmClient`, `PromptRenderer`   Interfaces produced: `PrivacyLlmClient` (decorator),
`PrintPromptsLlmClient` (writes, returns `NO_SEND` → callers treat it as unavailable),
`Redactor.redact(String)`, `UntrustedBlockPolicy.allow(path)` (consulted by the renderer).

Implementation notes:
- **Redaction patterns:** AWS (`AKIA…`), GitHub (`ghp_`, `github_pat_`), OpenAI-style (`sk-`), Anthropic
  (`sk-ant-`), Slack, JWT, PEM blocks, plus generic `password|secret|token\s*[:=]\s*"…"`. Replacement:
  `«redacted:<kind>»`.
- **Caps:** content beyond them is truncated with a marker. The truncation is recorded in usage notes.
- A denied path → the block is replaced with `«denied by policy: <glob>»`.

Tests to write: each pattern redacted; caps enforced; denylist; print-prompts writes N files and makes 0
provider calls (fake provider counts calls); a planted key in a fixture source never appears in prompts
(assert directly; WO-Y.2 later adds a shared `SecretLeakAssertions` helper).

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-llm -am test -Dtest='*Privacy*Test,*Redactor*Test,*PrintPrompts*Test'` → ≥ 14 tests, 0 failures
- [ ] `SECPIPE_ALLOW_FAKE_LLM=1 SECPIPE_FAKE_LLM_DIR=fixtures/e2e/sqli-min/llm java -jar secpipe-cli/target/secpipe.jar scan fixtures/e2e/sqli-min --llm-provider fake --print-prompts -o /tmp/pp && ls /tmp/pp/prompts | wc -l` → ≥ 1, and the fake call counter in `/tmp/pp/run-metadata.json` shows 0 calls

Out of scope: log redaction (WO-Y.2 reuses `Redactor`).

Handoff note for next agent: All LLM traffic now passes through `PrivacyLlmClient`. Any new client
decorator must be added inside `LlmClients.assemble`, never in the CLI.
