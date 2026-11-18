## Part 7 — Work orders

### Index (lean re-plan 2026-11-17 + remediation re-plan 2026-11-18; wave in brackets; see `docs/PLAN.md` "Re-plan 2026-11-17 (lean)" and "Re-plan 2026-11-18 (remediation)")

| Epic | Work orders |
|---|---|
| **0** Bootstrap | 0.1 [W0] (merged) |
| **S** Spikes | S.2 [W1] (merged); S.3 [W1] (PR #98, laptop checks pending) |
| **C** Contracts | C.1–C.5 [W1] (merged); CC.1 [W2] (merged) |
| **P** MVP ports | P.0 [W1] (merged); P.2 (+P.8) [W2]; P.6 [W4] |
| **V** Validators | V.1 [W2] |
| **K** Core | K.1 [W2]; K.4, K.5 (+V.2 invariants), K.6, K.8 [W3]; K.9 (+K.11) [W4]; K.13 (+K.3, +0.2) [W6]; K.15 [W8] |
| **L** LLM | L.1 (+L.7, +K.10, redaction) [W3]; L.2 (+L.3) [W4]; L.5 closed |
| **G** Agents | G.1 [W4]; G.5 [W4]; G.4, G.6 [W5]; G.7 [W6]; G.10, G.11 [W8] |
| **X** Sandbox | X.1 (+X.3, +X.4) [W2]; X.2 [W4] |
| **M** Remediation | M.1 [W9]; M.2 [W10] |
| **R** Reports | R.1 (+R.2 as Markdown) [W3]; R.4 [W10] |
| **E** Evaluation | E.1 [W2]; E.6 [W11] |
| **D** Docs | D.1 (+D.3) [W7] |

Dropped and folded orders are kept unchanged in `docs/work-orders/stretch/` (not scheduled; not checked by
`scripts/check-plan.py`). Bringing one back requires a re-plan.

Path shorthand used in the ownership lists: none. Every path is written in full from the repo root.

---

### Stretch backlog (not scheduled; requires NFR-4 met and a re-plan)

| Id | Item | Notes |
|---|---|---|
| Z.1 | OWASP ZAP DAST adapter | Needs a running app; sandbox network design change |
| Z.2 | Gradle support (prefetch + offline) | Gradle offline mode + `--dependency-verification` |
| Z.3 | Web dashboard / hosted multi-project view | Conflicts with NFR-5 unless self-hosted |
| Z.4 | Python language support | New recon, adapters, testgen |
| Z.5 | Extra Benchmark CWEs (79, 90, 330, 501, 614, 643) | Changes the NFR-4 average; report separately |
| Z.6 | Deterministic test templates for no-AI confirmation | Must not lower the evidence bar |
| Z.7 | Remaining Cloudflare companion domains (data isolation, resource exhaustion, cloud/IaC) | Hunter blocks only |
| Z.8 | CSV export (MVP parity) | Small renderer |
| Z.9 | gVisor/rootless sandbox backend | Stronger isolation |

---
