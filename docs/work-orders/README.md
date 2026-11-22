## Part 7 — Work orders

### Index (lean re-plan 2026-11-17 + remediation re-plan 2026-11-18 + follow-up re-plans 2026-11-19 and 2026-11-22; every order through wave 13 is merged; wave in brackets; see `docs/PLAN.md` "Re-plan 2026-11-17 (lean)", "Re-plan 2026-11-18 (remediation)" "Re-plan 2026-11-19 (follow-ups)" and "Re-plan 2026-11-22 (first real runs)")

| Epic | Work orders |
|---|---|
| **0** Bootstrap | 0.1 [W0]; 0.3 [W14] |
| **S** Spikes | S.2 [W1] (merged); S.3 [W1] (merged 2026-11-19; laptop probes still a human check) |
| **C** Contracts | C.1–C.5 [W1] (merged); CC.1 [W2] (merged) |
| **P** MVP ports | P.0 [W1] (merged); P.2 (+P.8) [W2]; P.6 [W4] |
| **V** Validators | V.1 [W2] |
| **K** Core | K.1 [W2]; K.4, K.5 (+V.2 invariants), K.6, K.8 [W3]; K.9 (+K.11) [W4]; K.13 (+K.3, +0.2) [W6]; K.15 [W8]; K.16 [W14] |
| **L** LLM | L.1 (+L.7, +K.10, redaction) [W3]; L.2 (+L.3) [W4]; L.5 closed; L.9 [W14] |
| **G** Agents | G.1 [W4]; G.5 [W4]; G.4, G.6 [W5]; G.7 [W6]; G.10, G.11 [W8]; G.12 [W13] |
| **X** Sandbox | X.1 (+X.3, +X.4) [W2]; X.2 [W4] |
| **M** Remediation | M.1 [W9]; M.3 [W10]; M.2 [W11]; M.4 [W13]; M.5 [W14] |
| **R** Reports | R.1 (+R.2 as Markdown) [W3]; R.5 [W5]; R.4 [W10]; R.6 [W13] |
| **E** Evaluation | E.1 [W2]; E.6 [W12]; E.11 [W14] |
| **D** Docs | D.1 (+D.3) [W7]; D.5 [W13] |

Dropped and folded orders are kept unchanged (13 of the 55 were folded into built orders: K.3, 0.2, K.10, L.7, K.11, V.2, V.3, P.8, L.3, X.3, X.4, R.2, D.3) in `docs/work-orders/stretch/` (not scheduled; not checked by
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
