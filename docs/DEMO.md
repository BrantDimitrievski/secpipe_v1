# SecPipe live demo

The demo slice from WO-DEMO.1 has been replaced by the real `secpipe scan` pipeline (WO-K.13).
The full demo script and explanations are in [`docs/user/guide.md`](user/guide.md) §6 and
`scripts/demo/demo.sh`.

Quick version, from the repository root (JDK 21; the first build needs Maven Central):

```bash
./mvnw -q -B -DskipTests package
bash scripts/demo/demo.sh              # no-AI scan, fake-LLM scan, sandbox scan and verified fix if Docker is available
bash scripts/demo/demo.sh --no-docker  # skip the two sandbox steps (3 and 4)
```

On `fixtures/e2e/sqli-min` you should see two candidates:
- `LoginServlet` SQL injection: `needs_validation` without Docker, `confirmed` by a generated regression test with Docker.
- `TagFilter`: `rejected` by the deterministic refuter (the value is overwritten with a constant).

Step 4 (Docker) scans `fixtures/remediation/sqli-fixable` with the fake LLM (`fixtures/remediation/sqli-fixable/llm/`):
the disprover lets the `LoginServlet` candidate survive, the generated regression test fails on the vulnerable code
(`confirmed`), the remediation agent answers `fix/prepared-statement.patch`, and the loop verifies it (regression test
passes, the four baseline tests still pass, the rescan is clean). The script asserts that the run's `report.md` has a
Remediation entry with outcome `fixed` and that `secpipe-fixes.patch` is not empty; it exits non-zero otherwise. Without
Docker it prints `skipped:` and the demo still ends with `DEMO OK`. CI's `sandbox` job runs the full script.
