# SecPipe scan report

- Run: `run-20261117T143005Z-a1b2c3`
- Target: vulnlab
- Profile: standard, scope: diff
- Status: complete

## Summary

| Severity | Confirmed | Needs validation | Rejected |
|---|---|---|---|
| critical | 1 | 1 | 0 |
| high | 0 | 0 | 0 |
| medium | 0 | 0 | 0 |
| low | 0 | 0 | 0 |
| informational | 0 | 0 | 0 |
| unrated | 0 | 0 | 1 |

Totals: 1 confirmed, 1 needs validation, 1 rejected.
Severity is the overall severity for confirmed findings and the analyzer's hint otherwise.

## Findings

### 1. Possible command injection in PingServlet

- Fingerprint: `code:CWE-78:aa11bb22cc33dd44ee55ff66`
- Verdict: needs_validation
- Severity: critical (analyzer hint)
- CWE: CWE-78
- Location: `src/main/java/com/example/vulnlab/PingServlet.java:16`
- Origin: tool, category: code

Host parameter reaches Runtime.exec; no verifier configured.

**Claimed root cause.** Request parameter concatenated into an exec string.

**Trace.**

1. entrypoint `src/main/java/com/example/vulnlab/PingServlet.java:12` `PingServlet#doGet(HttpServletRequest,HttpServletResponse)`: getParameter("host")
2. sink `src/main/java/com/example/vulnlab/PingServlet.java:16` `PingServlet#doGet(HttpServletRequest,HttpServletResponse)`: Runtime.getRuntime().exec("ping " + host)

**Code evidence.**

- `src/main/java/com/example/vulnlab/PingServlet.java:16`: exec with concatenated host

**Blockers.**

- No verifier configured (llm-provider=none); unverified tool finding

**Validate locally.** Re-run with an LLM provider configured so the disprover and test generator can validate.

### 2. SQL injection claim in TagFilter.search

- Fingerprint: `code:CWE-89:0f0e0d0c0b0a090807060504`
- Verdict: rejected
- CWE: CWE-89
- Location: `src/main/java/com/shop/app/TagFilter.java:19`
- Origin: tool, category: code

Tool flagged a concatenated query; the tainted value is overwritten with a constant before use.

**Claimed root cause.** Request parameter concatenated into SQL.

**Trace.**

1. entrypoint `src/main/java/com/shop/app/TagFilter.java:14` `TagFilter#search(HttpServletRequest)`: getParameter("tag")
2. sink `src/main/java/com/shop/app/TagFilter.java:19` `TagFilter#search(HttpServletRequest)`: executeQuery

**Code evidence.**

- `src/main/java/com/shop/app/TagFilter.java:16`: tag = "featured"; overwrites the request value

**Why rejected.** Value overwritten with a constant at line 16 before reaching the sink; no tainted data reaches executeQuery.

### 3. SQL injection in LoginServlet.doPost

- Fingerprint: `code:CWE-89:3f9a1c0d5e7b2a4c6d8e0f12`
- Verdict: confirmed
- Severity: critical
- CWE: CWE-89
- Location: `src/main/java/com/example/vulnlab/LoginServlet.java:27`
- Origin: tool, category: code

The username request parameter is concatenated into a SQL query executed with Statement.executeQuery.

**Root cause.** Untrusted request input is concatenated into a SQL string instead of being bound as a parameter.

**Trace.**

1. entrypoint `src/main/java/com/example/vulnlab/LoginServlet.java:21` `LoginServlet#doPost(HttpServletRequest,HttpServletResponse)`: request.getParameter("username")
2. sink `src/main/java/com/example/vulnlab/LoginServlet.java:27` `LoginServlet#doPost(HttpServletRequest,HttpServletResponse)`: stmt.executeQuery(query) with concatenated username

**Code evidence.**

- `src/main/java/com/example/vulnlab/LoginServlet.java:26`: String query = "SELECT \* FROM users WHERE name='" + username + "'"

**Evidence.** te-0a1b2c3d4e5f: test failed as expected; captured SQL contained the injected OR clause

**Confidence.** high: Disprover could not refute; regression test failed as expected.

**Remediation.** Use PreparedStatement with a ? placeholder for username.

## Remediation

1 of 1 confirmed findings fixed. Patches are suggestions: SecPipe never writes to your working tree. Only verified fixes are in `secpipe-fixes.patch`.

#### 1. SQL injection in LoginServlet.doPost

- Fingerprint: `code:CWE-89:3f9a1c0d5e7b2a4c6d8e0f12`
- Outcome: fixed
- Attempts: 1
  1. fixed: strategy: PreparedStatement; regression test passed; suite passed; rescan clean; adequacy 3/3 changed lines covered (adequate)
- Patch (verified): [patches/code-CWE-89-3f9a1c0d.patch](patches/code-CWE-89-3f9a1c0d.patch)

## Coverage

Ledger units: 1

| Status | Units |
|---|---|
| planned | 0 |
| in_progress | 0 |
| covered | 0 |
| candidate | 1 |
| blocked | 0 |
| deferred | 0 |
| out_of_scope | 0 |
| not_applicable | 0 |

## Tools

| Tool | Status | Candidates | Note |
|---|---|---|---|
| builtin | ok | 3 |  |
| semgrep | skipped | 0 | not installed |

## Run notes

- Fixture run; analyzers were not executed.
