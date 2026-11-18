# sqli-fixable

Remediation-loop fixture (WO-M.1). It is a copy of `fixtures/e2e/sqli-min` (same `src/main`, same dependencies,
`artifactId` changed to `sqli-fixable`) plus a small JUnit 5 suite, so the suite baseline has passing tests to
protect. Do not run its build on the host; SecPipe reaches it only through the sandbox.

| File | Role |
|---|---|
| `src/main/java/com/shop/app/LoginServlet.java` | `getParameter("username")` concatenated into `executeQuery` (CWE-89); the finding to fix |
| `src/main/java/com/shop/app/TagFilter.java` | constant-overwrite false positive (rejected by the refuter); must stay untouched |
| `src/main/java/com/shop/app/Db.java` | reads the connection settings from env vars |
| `src/test/java/com/shop/app/LoginServletTest.java` | 2 tests: `@WebServlet("/login")` mapping; HTTP 500 when no database is configured |
| `src/test/java/com/shop/app/TagFilterTest.java` | 2 tests: `@WebServlet("/products/tag")` mapping; HTTP 500 when no database is configured |
| `src/test/java/com/shop/app/ServletStubs.java` | JDK `Proxy` stand-ins for the servlet request/response (no Mockito) |
| `regression/secpipe/generated/LoginServletSqliRegressionTest.java` | the regression test for the finding (see below); outside `src/`, so it is not part of the baseline suite |
| `fix/prepared-statement.patch` | a correct fix as a unified diff (`a/`/`b/` paths) that `UnifiedDiff` applies cleanly; usable as the fake remediation agent's answer |
| `expected-remediation.json` | expected outcome of `RemediationLoopSandboxIT` and the expected baseline test ids |

The suite passes on the vulnerable code and still passes after the correct fix (a `PreparedStatement` with a `?`
placeholder and `setString(1, username)`): the sandbox env has no `SHOP_DB_URL`, so `Db.get()` throws
`SQLException` before any statement is built and both servlets answer 500 either way. The tests use only
`junit-jupiter` (test) and `javax.servlet-api` (provided), the same artifacts as `sqli-min`, so the X.2 prefetch
fetches everything the offline build needs.

Baseline test ids (`classname#name` from the JUnit XML report) are listed in `expected-remediation.json`.

## Regression test (why not the sqli-min evidence test)

The WO-G.7 evidence test for sqli-min swaps in a recording `Db` whose stub only records
`Statement.executeQuery(String)`. After a correct `PreparedStatement` fix that stub never sees the query, so the test
would hit its "login query did not run" check (as an assumption after WO-G.11: a skip), and a skip is never a pass:
a correct fix would be classified `still_present`.

`regression/secpipe/generated/LoginServletSqliRegressionTest.java` (package `secpipe.generated`, no assumptions, so
it is never skipped) records both paths: `createStatement` + `executeQuery(String)` and `prepareStatement(String)` +
`setString` + `executeQuery()`. It asserts that the query ran exactly once, that the payload `' OR '1'='1` is not in
any SQL text, and that the payload was bound with `setString`.

- vulnerable code: FAILS with `attacker-controlled username reached the SQL text: [...]`
- after `fix/prepared-statement.patch`: PASSES

To use it, copy it into an attempt's scratch copy at `src/test/java/secpipe/generated/` (or serve its source as the
fake test generator's answer); it is a plain JUnit 5 test that compiles against the fixture's own dependencies and
the JDK compiler (`javax.tools`), like the sqli-min evidence test.
