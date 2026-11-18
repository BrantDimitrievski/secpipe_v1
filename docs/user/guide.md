# SecPipe user guide

SecPipe scans a Java/Maven project for vulnerabilities. Analyzers (and, optionally, LLM agents) propose
candidates, a disprover tries to refute each one, and a generated JUnit test run in a network-less Docker sandbox
supplies evidence. Every finding ends as `confirmed`, `needs_validation` (with the reason) or `rejected`.

This guide describes the CLI as built in this release. Run `java -jar secpipe.jar scan --help` for the full flag
list; the flags below are the ones you need.

## 1. Install and build

Requirements: JDK 21 (Temurin recommended). Docker is optional (see section 5). The Maven wrapper downloads
Maven 3.9.x; do not use a system `mvn`.

```bash
./mvnw -q -B -DskipTests package
java -jar secpipe-cli/target/secpipe.jar --version
```

The examples below use `secpipe` for `java -jar secpipe-cli/target/secpipe.jar`:

```bash
alias secpipe='java -jar secpipe-cli/target/secpipe.jar'
```

## 2. Scan

```bash
secpipe scan PATH [-o DIR] [options]
```

`PATH` is the root of a Java/Maven project (default `.`). Target code is never compiled or run on your machine;
anything that executes it goes through the Docker sandbox.

| Option | Meaning |
|---|---|
| `-o, --out DIR` | Output directory (default `secpipe-out`) |
| `--no-ai` | No LLM at all (same as `--llm-provider none`) |
| `--no-sandbox` | Do not use Docker; findings that need evidence stay `needs_validation` |
| `--llm-provider P` | `none` (default), `fake`, `openai`, `openai-compatible`, `anthropic` |
| `--model ID`, `--llm-base-url URL` | Model id; base URL for `openai-compatible` |
| `--profile quick\|standard\|deep` | Effort and budget (default `standard`) |
| `--fail-on SEVERITY` | `critical\|high\|medium\|low\|informational\|none` (default `high`) |
| `--fail-on-needs-validation`, `--fail-on-incomplete` | Turn those states into non-zero exits |
| `--offline` | No network (implies `--llm-provider none`) |
| `-q`, `-v` | Quiet; verbose (debug log and stack traces) |

Validate a result file against its schema:

```bash
secpipe validate findings secpipe-out/findings.json --root PATH
```

(`validate` also accepts `ledger`, `run` and `agent:<schema>`.)

## 3. Providers and API keys

| Provider | Use | Key |
|---|---|---|
| `none` | Deterministic analyzers only (`--no-ai`) | none |
| `fake` | CI and demos: canned responses, no network. Needs `SECPIPE_ALLOW_FAKE_LLM=1` and `SECPIPE_FAKE_LLM_DIR=<dir>` | none |
| `openai` | OpenAI API | env |
| `openai-compatible` | Any OpenAI-style endpoint; set `--llm-base-url` | env |
| `anthropic` | Anthropic API | env |

The API key is read **only from the environment**, never from a flag or config file: `SECPIPE_API_KEY`, or one of
`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `SECPIPE_LLM_KEY`.

```bash
export ANTHROPIC_API_KEY=...        # your key
secpipe scan PATH --llm-provider anthropic --model <model-id>
```

With an LLM provider, repository text is sent to that provider. Use `--no-ai` or `--offline` when it must not be.

## 4. Outputs and exit codes

Files written to the output directory:

| File | Content |
|---|---|
| `findings.json` | Every finding with verdict, evidence and fingerprint (schema-validated) |
| `coverage-ledger.json` | What was and was not examined |
| `run-metadata.json` | Settings, budget, sandbox status, terminal state |
| `secpipe.sarif` | SARIF 2.1.0 for code-scanning viewers |
| `report.md` | Human-readable report rendered from the JSON above |

Exit codes:

| Code | Meaning |
|---|---|
| 0 | OK |
| 1 | A `confirmed` finding at or above `--fail-on`, or `--fail-on-needs-validation` and a `needs_validation` finding exists |
| 2 | Usage or environment error (a one-line problem and a one-line `hint:`) |
| 3 | Run incomplete and `--fail-on-incomplete` set |

## 5. Docker and evidence

With Docker available (`docker info` works), `secpipe scan` runs generated regression tests in a sandbox with no
network and turns surviving candidates into `confirmed` findings. Without Docker, or with `--no-sandbox`, those
candidates stay `needs_validation` with the blocker "sandbox unavailable". That is expected, not a failure.

## 6. Demo

`scripts/demo/demo.sh` runs three steps on `fixtures/e2e/sqli-min`. Add `--no-docker` to skip step 3.

```bash
bash scripts/demo/demo.sh --no-docker
```

1. No AI: `scan --no-ai --no-sandbox`. `LoginServlet` (CWE-89) is `needs_validation`; `TagFilter` is `rejected`
   because the deterministic refuter sees the constant overwrite.
2. Fake LLM: `--llm-provider fake` with canned responses (offline, deterministic).
3. Sandbox (needs Docker): the same scan without `--no-sandbox`; `LoginServlet` becomes `confirmed`. Skipped with a
   message when Docker is absent.

Each step writes `report.md` into its own directory (`secpipe-demo/noai`, `.../fake`, `.../sandbox`).

## 7. Limitations

Not delivered in this build (see `docs/PLAN.md`, "Re-plan 2026-11-17 (lean)"):

- **FR-7** dependency CVE scanning.
- **FR-13** verified fixes, and **feature Q** (remediation). There is no remediation in this build: no patches are
  produced; `--enable-fixes`, `--max-fix-attempts` are accepted but unused.
- **FR-14** the remediation loop, and `secpipe diff`.
- **No GitHub Action.** `action.yml` and PR comments/suggestions are not built; SecPipe is a CLI only.
- No HTML report (`report.md` and SARIF only), no `secpipe doctor` or `secpipe report`.
- `--scope diff` falls back to a full scan with a note; `--print-prompts` and `--engine langgraph4j` exit 2 with a
  hint.
- Weakened: privacy extras (path denylist, prompt audit) and prompt-injection test suite; performance and
  portability are unmeasured.

Stretch items (moved to `docs/work-orders/stretch/`): the GitHub Action, remediation and fix verification,
dependency scanning, incremental scans, HTML report, release packaging, and the extra analyzers. Update this
section whenever one lands.
