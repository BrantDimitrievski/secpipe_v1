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
| `--enable-fixes false\|suggest\|pr` | Remediation mode (default `suggest`; `pr` is not built, see section 6) |
| `--max-fix-attempts N` | Fix attempts per finding (see section 6) |
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
| `report.md` | Human-readable report rendered from the JSON above, with a Remediation section |
| `patches/` | One patch file per fix attempt (section 6) |
| `secpipe-fixes.patch` | The verified fixes only, as one patch you can apply (section 6) |

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
candidates stay `needs_validation` with the blocker "sandbox unavailable". That is expected, not a failure. Fixes
(section 6) need the sandbox too.

## 6. Fixes (remediation)

`--enable-fixes suggest` is the default. It runs only for `confirmed` findings and only when the Docker sandbox is
usable. A remediation agent proposes a patch, and every patch is applied to a scratch copy of your project, never to
your working tree. Each attempt is checked in the sandbox:

- the generated regression test fails before the patch and passes after it;
- the baseline test suite gives the same results as before;
- a rescan no longer reports the finding (and no new ones);
- adequacy is measured with JaCoCo: how many of the changed lines the regression test covers.

A failed attempt is retried with the failure fed back, up to `--max-fix-attempts N` times per finding (default 3).
`--enable-fixes false` turns remediation off, so confirmed findings are reported as `not_attempted`.
`--enable-fixes pr` exits 2 with a hint, because opening pull requests needs the GitHub Action, which is not built.

```bash
secpipe scan PATH --max-fix-attempts 2        # fewer attempts
secpipe scan PATH --enable-fixes false        # findings and evidence only
```

Outputs, all in the output directory:

| Output | Content |
|---|---|
| `patches/` | The patch of each attempt, including candidates that did not verify |
| `secpipe-fixes.patch` | One unified diff with the **verified** fixes only |
| `secpipe.sarif` | The `fixes[]` of verified fixes on the matching results |
| `report.md` | A Remediation section: outcome, attempts, test/suite/rescan results, adequacy, patch link |

Outcomes in the Remediation section:

| Outcome | Meaning |
|---|---|
| `fixed` | The patch passed every check above; it is in `secpipe-fixes.patch` |
| `still_present` | Attempts did not remove the finding (or broke the suite or build); no verified patch |
| `still_present` with "fix unverified" | The regression test was skipped after the patch, so the fix could not be proven |
| `sandbox_unavailable` | Docker refused or died during the check; nothing was verified |
| `not_attempted` | Remediation was off, the finding was not `confirmed`, or no regression test or sandbox was available |

SecPipe only suggests patches. To apply the verified ones yourself, from the project root:

```bash
git apply secpipe-out/secpipe-fixes.patch
```

Review the diff and run your own tests first. The demo (section 7) shows a fix end to end.

## 7. Demo

`scripts/demo/demo.sh` runs four steps. Add `--no-docker` to skip steps 3 and 4.

```bash
bash scripts/demo/demo.sh --no-docker
```

1. No AI: `scan --no-ai --no-sandbox` on `fixtures/e2e/sqli-min`. `LoginServlet` (CWE-89) is `needs_validation`;
   `TagFilter` is `rejected` because the deterministic refuter sees the constant overwrite.
2. Fake LLM: `--llm-provider fake` with canned responses (offline, deterministic).
3. Sandbox (needs Docker): the same scan without `--no-sandbox`; `LoginServlet` becomes `confirmed`. Skipped with a
   message when Docker is absent.
4. Verified fix (needs Docker): a fake-LLM scan of `fixtures/remediation/sqli-fixable`. `LoginServlet` is `confirmed`,
   the fix (a `PreparedStatement`) is verified, and the script checks that `report.md` shows the outcome `fixed` and
   that `secpipe-fixes.patch` is not empty. Skipped with a message when Docker is absent.

Each step writes `report.md` into its own directory (`secpipe-demo/noai`, `.../fake`, `.../sandbox`, `.../fix`).

## 8. Limitations

Not delivered in this build (see `docs/PLAN.md`, "Re-plan 2026-11-17 (lean)"):

- **FR-7** dependency CVE scanning, and therefore no dependency-upgrade fixes (fixes are code patches only).
- `secpipe diff`.
- **No GitHub Action.** `action.yml` and PR comments/suggestions are not built; `--enable-fixes pr` exits 2. SecPipe
  is a CLI only.
- No HTML report (`report.md` and SARIF only), no `secpipe doctor` or `secpipe report`.
- `--scope diff` falls back to a full scan with a note; `--print-prompts` and `--engine langgraph4j` exit 2 with a
  hint.
- Fixes are verified only for `confirmed` findings with a working Docker sandbox; a fix is as strong as its
  regression test and the project's own suite.
- Weakened: privacy extras (path denylist, prompt audit) and prompt-injection test suite; performance and
  portability are unmeasured.

Stretch items (moved to `docs/work-orders/stretch/`): the GitHub Action (and `--enable-fixes pr`), dependency
scanning and dependency-upgrade fixes, incremental scans and `secpipe diff`, HTML report, release packaging, and the
extra analyzers. Update this section whenever one lands.
