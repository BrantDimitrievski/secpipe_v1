# Remediation scorecard

Status: pending: real run on a Docker machine with an LLM key (human check).

This file is a placeholder. No results have been recorded yet. On a Docker machine with an LLM API key in the
environment (and the S.3 laptop checks passed), run:

```bash
bash scripts/eval/remediation.sh --model <frontier-model-id>
```

The script scans `fixtures/mvp-samples/vulnerable-java-app` and `fixtures/mvp-samples/shop-samples` with
`--enable-fixes suggest` and the sandbox on, then replaces this file with the scorecard: one row per confirmed
finding (app, fingerprint, CWE, attempts, pre-fix test, post-fix test, suite, rescan, new findings, adequacy, final
status, patch link) and summary totals (fix rate, attempts per fix, fix unverified, sandbox unavailable, introduced
findings). To re-render from existing runs: `bash scripts/eval/remediation.sh --from <out>/<app>/findings.json...`.

Target: at least 10 confirmed findings remediated across both apps. Every attempt is reported, including failures.
