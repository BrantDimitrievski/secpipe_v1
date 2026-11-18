# SecPipe

SecPipe is a multi-agent defensive security analysis pipeline for Java/Maven projects (Carleton University
SYSC 4907 capstone). Deterministic analyzers and LLM agents find candidate vulnerabilities. An adversarial
disprover tries to refute each candidate. A generated JUnit 5 regression test, run in a network-less Docker
sandbox, supplies the evidence. A remediation loop proposes fixes and verifies them. Reports are rendered only
from schema-validated JSON (`findings.json`, `coverage-ledger.json`, `run-metadata.json`).

SecPipe ships as a CLI (`secpipe.jar`). The composite GitHub Action (`action.yml`) is planned, not built.

> **Status:** CLI-only prototype. See [docs/user/guide.md](docs/user/guide.md) for usage, providers, outputs,
> the demo script (`bash scripts/demo/demo.sh --no-docker`) and the Limitations (no GitHub Action, no remediation).

## Build

Requirements: JDK 21 (Temurin recommended). Maven 3.9.x is downloaded by the wrapper. Do not use a system `mvn`.

```bash
./mvnw -q -B verify                          # compile, unit tests, validators, integration tests
./mvnw -q -B -Psandbox verify                # also run the Docker-backed tests (needs docker)
java -jar secpipe-cli/target/secpipe.jar --version
```

By default, tests tagged `network` or `sandbox` are excluded. All tests run offline and use a fake LLM, so no
API key is needed.

## Repository map

| Path | What |
|---|---|
| `secpipe-contracts/` | Frozen contracts: JSON schemas, records, interfaces, ids |
| `secpipe-validators/`, `secpipe-sarif/` | Validators, grounding, SARIF ingest |
| `secpipe-core/`, `secpipe-graph-langgraph4j/` | Pipeline engine and deterministic nodes; optional LangGraph4j runner |
| `secpipe-llm*/` | LLM routing and providers (OpenAI, Anthropic, Ollama, Copilot) |
| `secpipe-agents/` | Prompt framework and LLM agents |
| `secpipe-sandbox/` | Docker sandbox runner |
| `secpipe-adapter-*/` | Analyzer adapters (built-in rules, PMD, Semgrep, SpotBugs, Joern, CodeQL, OSV, Grype, Trivy) |
| `secpipe-report/`, `secpipe-github/` | Report writers; GitHub REST helpers |
| `secpipe-cli/` | `secpipe` command line, shaded into `secpipe.jar` |
| `secpipe-eval/` | Evaluation harnesses |
| `docs/PLAN.md` | The development plan (architecture, contracts, schedule) |
| `docs/work-orders/` | One file per work order; mirrored as GitHub issues |
| `CLAUDE.md` | Conventions for coding agents (and humans) working on a work order |

## Contributing

Each change implements exactly one work order on a branch named `wo-<epic>.<n>-<slug>`. Its pull request
follows `.github/pull_request_template.md`. See `docs/WORKFLOW.md` and `CLAUDE.md`. Contract files
(`secpipe-contracts/**`, `action.yml`) change only through `WO-C.*` / `WO-CC.*` work orders.

## Licence

MIT (see `LICENSE`). Parts of the prompts, schemas and validator rules are adapted from Cloudflare's
MIT-licensed `security-audit-skill`. See `THIRD_PARTY_NOTICES.md`.
