## Remediation

0 of 1 confirmed findings fixed. Patches are suggestions: SecPipe never writes to your working tree. Only verified fixes are in `secpipe-fixes.patch`.

#### 1. Sandboxed export injection

- Fingerprint: `code:CWE-89:e5e5e5e5e5e5e5e5e5e5e5e5`
- Outcome: not_attempted
- Reason: sandbox unavailable
- Stopped: at attempt 1: sandbox unavailable during the regression test: docker not found
- Attempts: 1
  1. sandbox_unavailable: strategy: strategy 1
- Patch (candidate, not verified): [patches/code-CWE-89-e5e5e5e5-attempt1.patch](patches/code-CWE-89-e5e5e5e5-attempt1.patch)

