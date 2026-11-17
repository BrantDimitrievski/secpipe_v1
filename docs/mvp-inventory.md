# MVP inventory (WO-P.0)

Re-check of the `docs/PLAN.md` Part 3 port map against `capstone-mvp-1`.

- MVP commit: `2905635` (`git log -1` on `capstone-mvp-1` HEAD at the time of the check, the same as the pin). No drift in the commit itself.
- Main sources: 75 Java files under `src/main/java/ca/carleton/secpipe/`, 5 resource files, `bin/secpipe(.cmd)`.
- Tests: 8 test classes, 43 `@Test` methods, 2 OSV JSON resources. This agrees with the port map ("43 tests").
- Result of the check: every class named in the port map exists. The classes marked `changed:` below are
  **not covered by the port map**. The decision given for each is a proposal, and the plan owner should confirm it.
  The check changes no contract.

## Class-by-class decisions

Paths are relative to `src/main/java/ca/carleton/secpipe/`. "confirmed" means the port map decision stands.

| MVP class | Port-map decision | Status | WO |
|---|---|---|---|
| `tools/builtin/BuiltinRulesAdapter` | Port, emit `Candidate` with trace | confirmed | WO-P.2 |
| `tools/builtin/JavaRule` | Port | confirmed | WO-P.2 |
| `tools/builtin/RuleContext` | Port | confirmed | WO-P.2 |
| `tools/builtin/TaintTracker` | Port | confirmed | WO-P.2 |
| `tools/builtin/TaintLevel` | Port | confirmed | WO-P.2 |
| `tools/builtin/SqlInjectionRule` | Port (CWE-89) | confirmed | WO-P.2 |
| `tools/builtin/CommandInjectionRule` | Port (CWE-78) | confirmed | WO-P.2 |
| `tools/builtin/PathTraversalRule` | Port (CWE-22) | confirmed | WO-P.2 |
| `tools/builtin/WeakCryptoRule` | Port (CWE-327, 328) | confirmed | WO-P.2 |
| `tools/builtin/HardcodedSecretRule` | Port (CWE-798) | confirmed | WO-P.2 |
| `tools/builtin/DeserializationRule` | Port (CWE-502) | confirmed | WO-P.2 |
| `tools/PmdAdapter` + `resources/pmd/secpipe-security.xml` | Port | confirmed | WO-P.6 |
| `tools/SemgrepAdapter` | Port, bundled ruleset | confirmed | WO-P.7 |
| `tools/OsvAdapter`, `tools/OsvClient` | Port | confirmed | WO-P.3 |
| `tools/deps/DependencyResolver`, `VersionComparator`, `Cvss3`, `core/DependencyRef` | Port, resolution in sandbox prefetch | confirmed | WO-P.3 |
| `tools/GrypeAdapter` | Port | confirmed | WO-P.4 |
| `tools/TrivyAdapter` | Port | confirmed | WO-P.5 |
| `agents/ProjectDetector`, `core/BuildTool`, `cli/DoctorCommand` | Port + JDK detection | confirmed | WO-P.1 |
| `knowledge/KnowledgeBase` + `resources/knowledge/{cwe-subset,owasp-top10-2021}.json` | Port + CWE family table | confirmed | WO-P.8 |
| `resources/knowledge/test-templates.json` | Port payload lists only | confirmed | WO-G.6 |
| `agents/CodeMapBuilder`, `core/CodeMap` | Redesign into recon inventory | confirmed | WO-K.4 |
| `agents/TriageAgent` | Redesign (normalize + scoring) | confirmed | WO-K.8, WO-K.9 |
| `agents/ExploitabilityAgent` | Redesign (heuristics feed the deterministic refuter) | confirmed | WO-G.5, WO-G.4 |
| `agents/OrchestratorAgent`, `agents/PipelineState`, `agents/Agent` | Redesign (graph engine) | confirmed | WO-K.1 |
| `core/Finding`, `Severity`, `Confidence`, `Exploitability`, `ScanResult` | Redesign into contract records | confirmed | WO-C.1 |
| `core/BaselineDiff`, `cli/VerifyCommand`, `report/VerifyReport` | Redesign into remediation status + `secpipe diff` | confirmed | WO-K.12 |
| `report/HtmlReporter` + `resources/report/report-template.html` | Port the template | confirmed | WO-R.2 |
| `report/SarifReporter` | Redesign (verdict mapping) | confirmed | WO-R.1 |
| `report/CsvReporter` | Dropped (stretch) | confirmed | none |
| `report/MarkdownReporter` | Becomes the PR comment | confirmed | WO-R.3, WO-K.13 |
| `report/JsonReporter` | Redesign (schema-validated JSON) | confirmed | WO-R.3 |
| `report/TerminalSummary` | Port | confirmed | WO-R.3 |
| `llm/OpenAiCompatibleClient`, `LlmSettings`, `NoopLlmClient`, `LlmClient`, `LlmClients` | Drop; keep `SECPIPE_LLM_*` env var aliases | confirmed | WO-L.1 |
| `cli/SecPipeCli`, `ScanCommand`, `ScanOptions`, `bin/secpipe(.cmd)`, `install.sh` | Port structure, hint style, launchers | confirmed | WO-K.13 |
| `util/Proc` | Port to core util | confirmed | WO-P.1 |
| `util/Console`, `util/Json` | Port | confirmed | WO-P.1 |
| `util/SourceText` | Port to validators | confirmed | WO-V.3 |
| `samples/vulnerable-java-app`, `eval/samples/**`, `eval/answers.csv`, `eval/fix-commits.csv` | Port as fixtures | confirmed | WO-P.0 (this WO) |
| `eval/*.py`, `eval/*.sh` | Redesign in Java; `SAME_AS` moves to CWE families | confirmed | WO-E.1, WO-E.5 |
| `src/test/**` (43 tests, `MockOsvServer`) | Ported alongside each class | confirmed | per module |
| `agents/TestGenAgent` (472 lines) | Not listed. Only `test-templates.json` payloads are in the map | changed: not in the port map. Proposal: drop the regex source-guard tests and the classpath version check (not reproductions); the payload skeleton idea is redone in testgen | WO-G.6 |
| `agents/AdapterRunner` | Not listed | changed: not in the port map. Proposal: redesign as part of the engine's parallel node execution | WO-K.1 |
| `agents/CodePatternAgent`, `agents/DependencyAgent` | Not listed | changed: not in the port map. Proposal: drop; the pipeline graph runs adapters as detect nodes | WO-K.1 |
| `agents/KnowledgeAgent` | Not listed (only `KnowledgeBase` is) | changed: not in the port map. Proposal: follows `KnowledgeBase` | WO-P.8 |
| `agents/ReportAgent` | Not listed | changed: not in the port map. Proposal: redesign as `ReportNode` | WO-R.3 |
| `core/Category`, `core/ToolRun`, `core/ProjectProfile` | Not listed | changed: not in the port map. Proposal: redesign into contract records and recon data | WO-C.1 |
| `core/ScanConfig`, `core/ScanContext` | Not listed | changed: not in the port map. Proposal: redesign as `RunContext` and the CLI flags | WO-K.13 |
| `core/SecPipeException`, `tools/ScanException` | Not listed | changed: not in the port map. Proposal: port the one-line problem + `hint:` style | WO-K.13 |
| `tools/ScannerAdapter`, `tools/ToolRegistry` | Not listed | changed: not in the port map. Proposal: redesign as the adapter contract and the `--tools` selection | WO-K.13 |
| `report/Reports`, `report/ReportWriter` | Not listed | changed: not in the port map. Proposal: redesign inside `secpipe-report` | WO-R.3 |

## Supported CWEs

Benchmark-scored CWEs (the five categories the eval harness scores): **22, 78, 89, 327, 328**.
Also supported by the MVP but not scored by the Benchmark: **502** (deserialization) and **798** (hard-coded credentials).
The MVP's `SAME_AS` table in `eval/sarif_util.py` also accepts neighbouring CWEs (for example 321, 259 for 798;
326, 310 for 327), and these move to the CWE families.

## Ported data

| Source (MVP) | Destination |
|---|---|
| `samples/vulnerable-java-app/**` | `fixtures/mvp-samples/vulnerable-java-app/` |
| `eval/samples/**` (21 Java files in `com/shop/app`) | `fixtures/mvp-samples/shop-samples/` |
| `eval/answers.csv` (20 rows + header) | `secpipe-eval/src/main/resources/mvp/answers.csv` |
| `eval/fix-commits.csv` (29 rows + header) | `secpipe-eval/src/main/resources/mvp/fix-commits.csv` |
| `eval/samples/.../TagFilter.java`, `Db.java` | `fixtures/e2e/sqli-min/` (plus a new `LoginServlet`) |

`eval/results/2026-11-03-noai.md` was read as the baseline (TPR 100%, FPR 20% on the 20 samples; 27 of 29 fix commits ran)
and is not copied. WO-P.2 must reproduce its table on `fixtures/mvp-samples/shop-samples`.
