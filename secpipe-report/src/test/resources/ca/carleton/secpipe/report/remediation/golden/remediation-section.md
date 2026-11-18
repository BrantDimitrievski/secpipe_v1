## Remediation

1 of 4 confirmed findings fixed. Patches are suggestions: SecPipe never writes to your working tree. Only verified fixes are in `secpipe-fixes.patch`.

#### 1. SQL injection in LoginServlet.doPost

- Fingerprint: `code:CWE-89:3f9a1c0d5e7b2a4c6d8e0f12`
- Outcome: fixed
- Attempts: 1
  1. fixed: strategy: PreparedStatement; regression test passed; suite passed; rescan clean; adequacy 3/3 changed lines covered (adequate)
- Patch (verified): [patches/code-CWE-89-3f9a1c0d.patch](patches/code-CWE-89-3f9a1c0d.patch)

#### 2. Still vulnerable admin export

- Fingerprint: `code:CWE-89:b2b2b2b2b2b2b2b2b2b2b2b2`
- Outcome: still_present
- Stopped: agent-call budget exhausted
- Attempts: 2
  1. tests_broken: strategy: strategy 1; regression test passed; suite failed
  2. still_present: strategy: strategy 2; regression test failed
- Patch (candidate, not verified): [patches/code-CWE-89-b2b2b2b2-attempt2.patch](patches/code-CWE-89-b2b2b2b2-attempt2.patch)

#### 3. Export servlet injection

- Fingerprint: `code:CWE-89:c3c3c3c3c3c3c3c3c3c3c3c3`
- Outcome: still_present (fix unverified: the regression test was skipped after the patch)
- Attempts: 1
  1. still_present: strategy: strategy 1; adequacy 1/4 changed lines covered (not adequate): only one changed line is exercised
- Patch (candidate, not verified): [patches/code-CWE-89-c3c3c3c3-attempt1.patch](patches/code-CWE-89-c3c3c3c3-attempt1.patch)

#### 4. Report servlet injection

- Fingerprint: `code:CWE-89:d4d4d4d4d4d4d4d4d4d4d4d4`
- Outcome: not_attempted
- Reason: no regression test available
- Attempts: 0

