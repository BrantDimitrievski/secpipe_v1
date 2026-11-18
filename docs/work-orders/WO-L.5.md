### WO-L.5: Implement the Copilot provider (Copilot CLI subprocess, no tools)
Closed: not needed after S.2 NO-GO (WO-CC.1)
Wave: 6   Depends on: WO-L.1, WO-S.2, WO-L.2   Blocks: WO-H.3, WO-E.10
Milestone: Oral Jan 25
Traces: FR-2, NFR-5 · O, (§2.7)
Suggested human owner: Clinton Odinamadu (reviewer: Abdul Babatunde)
Size: M

Goal (2–3 sentences): `CopilotProviderFactory` implements `copilot` mode, which replaces the retired GitHub
Models. It runs the pinned Copilot CLI as a subprocess, exactly as recorded in `docs/spikes/S2-copilot.md`:
non-interactive, no `--allow-tool`, an empty temp working directory, the prompt via stdin or a temp file,
and only the final message captured. Its probe distinguishes "CLI not installed", "no Copilot auth", "auth
lacks Copilot Requests", and "ok". If S.2 says NO-GO, close this WO as "not built" and keep the module a
skeleton.

Context to read first (exact paths only): `docs/spikes/S2-copilot.md`; contracts `spi/LlmProviderFactory.java`,
`spi/LlmClient.java`; `secpipe-llm-openai/src/main/java/**` (pattern reference).

Files you own (create/modify — nothing else): `secpipe-llm-copilot/src/**`, `.github/workflows/llm-smoke-copilot.yml`.

Files you may read but not modify: `secpipe-llm/**`, `secpipe-llm-openai/**`.

Interfaces consumed: `LlmProviderFactory`   Interfaces produced: factory for `COPILOT`.

Implementation notes:
- **The process:**
  - `ProcessBuilder(copilotPath, <non-interactive flags from S.2>, "--model", id)`;
  - `directory(Files.createTempDirectory("secpipe-copilot-"))` (empty; deleted after);
  - env = PATH, HOME (temp), and **only** `COPILOT_GITHUB_TOKEN` or `GITHUB_TOKEN` (whichever applies);
  - a timeout of 120 s; stdout capped at 1 MiB.
- **Never pass `--allow-tool`.** Add a startup self-check: if the CLI version is not the pinned one, or its
  help shows that tools run without approval, refuse (probe reason).
- **Output:** pass the raw final message to `RoutingLlmClient` unchanged. The strict single-object parse
  happens in AgentRunner; this provider never strips fences or prose.
- **Tokens:** if the CLI reports none, estimate `chars/4` and set the usage flag `estimated` (metadata
  note).
- **Quota or auth failure** → `RateLimitedException` or a probe reason; the L.7 fallback goes to `none`.
- **Smoke workflow:** `workflow_dispatch` only, `permissions: {contents: read, copilot-requests: write}`,
  scanning `fixtures/e2e/sqli-min` with `--max-agent-calls 5`.

Tests to write: use a **fake `copilot` executable** (a test script on a temp PATH that echoes recorded
outputs, exits non-zero for auth failures, and sleeps for the timeout case). Cover: the argv never contains
`--allow-tool`; the working dir is empty and outside the repo; the env contains no other variables (assert
via the fake's `env` dump); a prompt > argv limit goes through stdin/file; the timeout; the probe reasons;
the estimated-token flag.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-llm-copilot -am verify` → exit 0 (no network, no real CLI)
- [ ] A test asserts the spawned argv contains no `--allow-tool` and the child env has only `PATH`, `HOME`, and one token variable
- [ ] `grep -c pull_request .github/workflows/llm-smoke-copilot.yml` → 0

Out of scope: Action fork logic (WO-H.3); Copilot agentic features (SecPipe uses Copilot as a text model
only).

Handoff note for next agent: The Action's `copilot` mode depends on this. H.3 decides when the probe result
forces `none`.
