# SecPipe demo slice (WO-DEMO.1)

Run from the repository root (JDK 21; the first build needs Maven Central):

```bash
./mvnw -q -B -DskipTests package
java -jar secpipe-cli/target/secpipe.jar scan fixtures/e2e/sqli-min --out /tmp/secpipe-demo
cat /tmp/secpipe-demo/report.md
```

Optional: `cat /tmp/secpipe-demo/findings.json`, or scan `fixtures/mvp-samples/shop-samples` the same way.
The scan exits 0 even with findings (frozen exit codes, PLAN §4.3: demo findings are `needs_validation`);
add `--fail-on-needs-validation` to get exit 1 when anything is found.

What this shows and what it cuts:
1. `secpipe scan` parses Java sources with JavaParser (target code is never compiled or run) and flags CWE-89 when a JDBC sink gets concatenated SQL.
2. Each hit becomes a contract `Finding` with a frozen ADR-06 `code:` fingerprint, written to `findings.json` and validated against `findings.schema.json`.
3. `report.md` is rendered from those findings: counts by (tool-hint) severity plus one section per finding.
4. All findings are `needs_validation`: nothing is confirmed or rejected without sandbox evidence or a disprover.
5. Cut from this slice: LLM agents, the Docker sandbox and generated tests, remediation, other analyzers, SARIF/HTML, the ledger and run metadata.
