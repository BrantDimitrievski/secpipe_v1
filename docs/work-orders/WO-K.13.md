### WO-K.13: Build the CLI (picocli) and assemble the pipeline
Wave: 3   Depends on: WO-K.1, WO-C.4, WO-P.1, WO-V.1, WO-V.2, WO-L.1   Blocks: WO-0.2, WO-H.1, WO-D.1, WO-E.8, WO-E.9
Milestone: Prototype 2 Dec 5
Traces: FR-4, NFR-1, NFR-6 · A, P, S
Suggested human owner: Abdul Babatunde (reviewer: Clinton Odinamadu)
Size: M

Goal (2–3 sentences): Implement every command and flag in §4.3 with the MVP's error and hint style. Wire
`ConfigResolver` → `PlainGraphRunner` → exit codes. The validators are injected, the sandbox and graph
runners come through ServiceLoader, and the LLM client comes through `LlmClients.assemble`. Write `$GITHUB_OUTPUT` keys in Actions. Port the launchers and `install.sh`.

Context to read first (exact paths only): `docs/PLAN.md` §4.3; MVP `src/main/java/ca/carleton/secpipe/cli/*.java`,
`bin/secpipe`, `bin/secpipe.cmd`, `install.sh`; `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/config/ConfigResolver.java`;
`secpipe-core/src/main/java/ca/carleton/secpipe/core/engine/PlainGraphRunner.java`, `DefaultRunContext.java`.

Files you own (create/modify — nothing else): `secpipe-cli/src/main/java/ca/carleton/secpipe/cli/**` (except
the `e2e` test package), `secpipe-cli/src/test/java/ca/carleton/secpipe/cli/*.java`, `bin/secpipe`,
`bin/secpipe.cmd`, `install.sh`.

Files you may read but not modify: all modules.

Interfaces consumed: everything public   Interfaces produced: the `secpipe` CLI, `PipelineAssembly.build(config)
→ (GraphRunner, RunContext, initial state)` (used by eval harnesses).

Implementation notes:
- **Subcommands:** `scan`, `validate`, `doctor`, `diff`, `report`, `github`.
  - `github` delegates to `secpipe-github` when present (reflective ServiceLoader `CliExtension`), else
    prints "not built" with exit 2.
  - `report --from` and `diff` call report/incremental classes reflectively in the same way, until those
    WOs exist (prints "available after WO-R.2/K.12").
- **Fake provider gate:** `SECPIPE_ALLOW_FAKE_LLM=1`, plus `SECPIPE_FAKE_LLM_DIR`.
- **`$GITHUB_OUTPUT` keys:** `sarif_path`, `findings_path`, `report_path`, `run_status`, `confirmed`,
  `needs_validation`, `rejected`, `ai_mode`, `out_dir`, `state_dir`, `exit_code`, `can_upload_sarif`.
- **Paths with spaces** must work (a portability check).
- The terminal summary is ported from MVP `TerminalSummary`, adapted to verdicts.

Tests to write: every flag parses to the expected config key (table-driven); unknown flag → exit 2 with a
hint; `validate` exit codes; `doctor` output contains each check; `$GITHUB_OUTPUT` written when
`GITHUB_ACTIONS=true` (temp file); a path with spaces.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-cli -am verify` → exit 0
- [ ] `java -jar secpipe-cli/target/secpipe.jar scan --help | grep -c -- '--'` → ≥ 30
- [ ] `java -jar secpipe-cli/target/secpipe.jar validate findings secpipe-contracts/src/test/resources/examples/findings-invalid-nv-with-severity.json; echo $?` → `1`
- [ ] `java -jar secpipe-cli/target/secpipe.jar scan fixtures/e2e/sqli-min --no-ai --no-sandbox -o /tmp/sp; echo $?` → `0` or `1` (not `2`), and the engine checkpoint `/tmp/sp/.work/state.json` exists (nodes may be skipped as "not installed" at this wave; `report` writes the JSON outputs once WO-R.1 is merged)

Out of scope: GitHub REST (WO-H.2), report rendering (R.*).

Handoff note for next agent: `PipelineAssembly` is the one place that wires concrete classes. Eval WOs reuse
it with `disabledNodes` for ablations.
