# CLAUDE.md — SecPipe conventions for coding agents

You are implementing **one work order** (`docs/work-orders/WO-<id>.md`) in the SecPipe repository. Read this
file, then your work order, then only the files your work order lists under "Context to read first". Do not
read `docs/PLAN.md` unless your work order tells you to.

## 1. What SecPipe is (one paragraph)
A Java 21 / Maven multi-module engine that scans Java/Maven projects. Deterministic analyzers and LLM agents
find candidate vulnerabilities. A fresh adversarial disprover tries to refute each one. A generated JUnit 5
regression test in a network-less Docker sandbox supplies the evidence. A remediation loop proposes and
verifies fixes. Reports render only from schema-validated JSON (`findings.json`, `coverage-ledger.json`,
`run-metadata.json`). It ships as a CLI (`secpipe-cli` → `secpipe.jar`). The composite GitHub Action (`action.yml`)
is a frozen contract file only; the Action itself is not built (stretch). Several design ideas are adapted from Cloudflare's MIT-licensed `security-audit-skill` (see
`THIRD_PARTY_NOTICES.md`).

## 1a. Reference repositories (read-only, outside this repo)
Paths in work orders prefixed `MVP` refer to `../capstone-mvp-1` (pinned `2905635`). Paths prefixed `Cloudflare` refer to
`../security-audit-skill` (pinned `c1c8a8c`). If missing:
```bash
git clone https://github.com/BrantDimitrievski/capstone-mvp-1 ../capstone-mvp-1 && git -C ../capstone-mvp-1 checkout 2905635
git clone https://github.com/cloudflare/security-audit-skill ../security-audit-skill && git -C ../security-audit-skill checkout c1c8a8c
```
Never modify them, never copy their git history.

## 1b. Acceptance markers
- `[DOCKER]`: run it if `docker info` succeeds; otherwise write "pending: needs Docker" in the PR (not a blocker).
- `[HUMAN]`: do not run; list it in the PR under "Human checks pending".
- "Session split": L-size work orders run as two sessions (A then B) on one branch; do only your part.

## 2. Build and test
```bash
./mvnw -q -B verify                         # whole build: compile, unit tests, validators, integration tests
./mvnw -q -B -pl <module> -am verify        # one module plus what it depends on
./mvnw -q -B -pl <module> test -Dtest=ClassNameTest
./mvnw -q -B -Pe2e verify                   # adds the e2e fixture scan (fake LLM, no Docker needed)
./mvnw -q -B -Psandbox verify               # adds Docker-backed tests (needs a working `docker`)
bash scripts/ci-fixture-scan.sh             # what CI runs after verify
java -jar secpipe-cli/target/secpipe.jar --help
```
- JDK 21 (Temurin), Maven 3.9.x through the wrapper only. Never call a system `mvn` in scripts or tests.
- Tests must pass offline. Network-touching tests are tagged `@Tag("network")` and excluded by default.
- Docker-backed tests are tagged `@Tag("sandbox")` and run only with `-Psandbox`.

## 3. Code style
- Java 21. Use records for data, sealed interfaces for closed hierarchies, `Optional` for absent values
  (never return `null` from public methods), and immutable collections (`List.copyOf`).
- Package root `ca.carleton.secpipe`. One top-level public type per file. No wildcard imports.
- Formatting follows `.editorconfig`: 4 spaces, 120 columns, LF, UTF-8, final newline.
- Errors shown to users follow the MVP style: a one-line problem plus a one-line `hint:`. Never show a
  stack trace unless `-v` is set.
- Logging goes only through `RunContext.log()` (it redacts secrets). No `System.out` in library code.
- Dependencies: use only what your module's `pom.xml` already declares. **Do not add dependencies or
  modules.** If you need one, stop and report it (see §11).

## 4. Contracts are frozen
- `secpipe-contracts/**` (schemas, records, interfaces, `PipelineGraph`, `Fingerprints`, `CoverageIds`) and
  `action.yml` are **contracts**. Only work orders whose id starts with `WO-C.` or `WO-CC.` may modify them.
- The same holds for the CLI flags and exit codes in `docs/PLAN.md` §4.3. They are implemented in
  `secpipe-cli`, and their names and semantics are frozen.
- If your work order seems to need a contract change, **stop** and explain it in the PR description under
  "Contract change needed". Do not work around a contract with side channels: no extra JSON fields, no
  stringly-typed maps, no reflection hacks.

## 5. File ownership
- Your work order lists "Files you own". Create or modify **only** those paths (globs allowed as written).
- You may read anything. You may add test resources only under your own module's
  `src/test/resources/<your-package-path>/`.
- Shared registries use plug-ins: `ServiceLoader` files inside *your own* module, or the FQCN table in
  `PipelineGraph`. Never edit another module's registry.
- If a needed change falls outside your ownership list, stop and report it.

## 6. LLM rules
- **CI and all tests use `FakeLlmClient`** (`secpipe-llm`, test fixtures under
  `src/test/resources/llm-fixtures/<agent>/<case>.json`). No test may need an API key or network.
- Agents return exactly one JSON object. Malformed output is discarded and re-run with a fresh agent,
  **never repaired**. Do not add "lenient" JSON parsing anywhere.
- Repo content goes into prompts only via `PromptRenderer.untrusted(...)`. Never concatenate source text
  into a prompt string yourself.
- API keys come only from environment variables at call time. Never store them in fields, config records,
  logs, files, prompts, SARIF, reports, or the sandbox env. Tests must assert this where relevant
  (`SecretLeakAssertions`).
- A `small`-tier model must never be the sole reason a tool-flagged candidate is `rejected`.

## 7. Sandbox rules
- **No step may run target-project code** (Maven builds, plugins, tests, generated tests, fixes) except
  through `SandboxRunner.run(...)`. That includes "just compiling" the target.
- Sandbox specs require `NETWORK_NONE`, except the `MavenPrefetch` phase, which runs before any target code
  and has no secrets.
- If `probe()` says a required control is unenforceable, do not run. Record a `needs_validation` blocker
  naming the control.
- Only `ArtifactPromoter` copies files out of scratch.
- Fixes are applied only to scratch copies. Nothing in SecPipe pushes to a protected branch or writes to
  the user's working tree.

## 8. Determinism
Dedup, fingerprints, ledger state, scoring, validation, SARIF, and reports are deterministic code. No LLM
calls there. Sort outputs (by fingerprint or `coverage_id`) so files are byte-stable for the same input.
Use `RunContext.clock()`, not `Instant.now()`.

## 9. Branches, commits, PRs
- Branch: `wo-<epic>.<n>-<short-slug>`, lower case (e.g. `wo-k.8-normalize-dedup`).
- Commits: `WO-<id>: <imperative summary>` (e.g. `WO-K.8: merge cross-tool candidates by fingerprint`).
  When porting MVP code, add `Ported-from: capstone-mvp-1@2905635:<path>` to the commit body.
- One PR per work order, using `.github/pull_request_template.md`. Fill in every section, including the
  acceptance-command output.
- Never force-push to `main`. Never merge your own PR. A human merges after CI and the independent review.

## 10. Definition of done
1. Every acceptance command in the work order passes exactly as written. Paste the outputs into the PR.
2. `./mvnw -q -B verify` is green, and `bash scripts/ci-fixture-scan.sh` is green (or reports "skipped:
   not enabled" before `WO-0.2`).
3. Only owned files changed (`git diff --name-only origin/main...HEAD`, pasted into the PR).
4. New code has tests that would fail without it. No tests that only assert mocks, no `assertTrue(true)`,
   no disabled tests.
5. No contract drift, no new dependencies, no secrets, no network in tests.
6. The handoff note from the work order is reflected in the PR description, plus any surprises for the next
   agent.

## 11. When to stop
Stop and write a PR (or draft PR) titled `WO-<id>: BLOCKED — <reason>` if any of these happen:
- the work order contradicts a contract or this file;
- an acceptance command can't pass for reasons outside your files;
- you need a dependency, a module, or a file you don't own;
- a spike assumption proves false.

Explain what you found and what change you recommend. **Do not improvise around it.**

## 12. Module map (where things go)
| Module | Package | Purpose |
|---|---|---|
| `secpipe-contracts` | `.contracts.*` | schemas, records, interfaces, ids (frozen) |
| `secpipe-validators` | `.validators` | findings/ledger/agent validators, grounding |
| `secpipe-sarif` | `.sarif` | SARIF ingest |
| `secpipe-core` | `.core.{engine,setup,env,recon,ledger,detect,normalize,knowledge,scoring,budget,finalize,incremental,util}` | engine + deterministic nodes |
| `secpipe-graph-langgraph4j` | `.graph.langgraph4j` | optional runner |
| `secpipe-llm`, `secpipe-llm-*` | `.llm`, `.llm.<provider>` | LLM routing + providers |
| `secpipe-agents` | `.agents.{prompt,runner,hunter,critic,refuter,disprover,testgen,evidence,verifier,recon,remediation}` | agents + LLM nodes |
| `secpipe-sandbox` | `.sandbox` | Docker runner, prefetch, promotion |
| `secpipe-adapter-*` | `.adapter.<tool>` | analyzers |
| `secpipe-report` | `.report` | writers + ReportNode |
| `secpipe-github` | `.github` | GitHub REST helpers |
| `secpipe-cli` | `.cli` | picocli, assembly |
| `secpipe-eval` | `.eval` | evaluation harnesses |

Not built (empty placeholder modules, stretch): `secpipe-sarif`, `secpipe-graph-langgraph4j`, `secpipe-llm-ollama`,
`secpipe-github`, and the adapters other than `secpipe-adapter-builtin` and `secpipe-adapter-pmd`. Providers that
exist: `secpipe-llm-anthropic`, `secpipe-llm-openai` (also serves `openai-compatible`). Agents that exist: refuter,
disprover, testgen, evidence, remediation. Do not add code to a placeholder module unless your work order says so.
