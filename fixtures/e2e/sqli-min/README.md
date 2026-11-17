# sqli-min

Canonical minimal e2e fixture: a Maven project with no parent, `javax.servlet-api` (provided) and
`junit-jupiter` (test). Do not run its build on the host; scans reach it only through the sandbox.

| File | Pattern | Expected finding (no AI) |
|---|---|---|
| `LoginServlet.java` | `getParameter("username")` concatenated into `executeQuery` (CWE-89) | `needs_validation` (no AI means no test evidence, so it cannot be confirmed) |
| `TagFilter.java` | The parameter is overwritten with a constant before the query (CWE-89) | `rejected` (the deterministic refuter sees the constant overwrite) |
| `Db.java` | Helper that reads the connection settings from env vars | none |

`expected.json` holds the counts for the `no_ai` run: 1 `needs_validation`, 1 `rejected`, 0 `confirmed`.
WO-0.2 later adds `llm/` with the fake LLM fixtures and extends `expected.json`.

`TagFilter.java` and `Db.java` are copied unchanged from `capstone-mvp-1@2905635:eval/samples/src/main/java/com/shop/app/`.
