## Part 7 — Work orders

### Index (epic → work orders; wave in brackets; ★ = critical path)

| Epic | Work orders |
|---|---|
| **0** Bootstrap & CI | ★0.1 [W0], 0.2 [W5] |
| **S** Spikes | S.1 LangGraph4j, S.2 Copilot CLI provider (GitHub Models retired), S.3 Sandbox/Maven, S.4 Joern [W1] |
| **C** Contracts | C.1, C.2, ★C.3, C.4, ★C.5 [W1] |
| **P** MVP ports | P.0 [W1]; P.1, P.2, P.3, P.8 [W2]; P.6, P.7 [W4]; P.4, P.5 [W5] |
| **V** Validators | V.1, V.2, V.3 [W2] |
| **K** Core | K.1, K.7 [W2]; K.3, K.4, K.5, K.6, ★K.8, K.10, K.13 [W3]; K.9, K.11 [W4]; K.2 [W5]; K.12 [W9]; K.14 [W13] |
| **L** LLM | ★L.1 [W2]; L.6 [W3]; L.2 [W4]; L.3, L.7 [W5]; L.4, L.5, L.8 [W6] |
| **G** Agents | ★G.1 [W3]; ★G.4, ★G.5, G.6 [W4]; ★G.7 [W5]; G.8 [W6]; ★G.10 [W7]; G.2, G.9 [W8]; G.3 [W9] |
| **X** Sandbox | X.4 [W2]; X.1 [W3]; X.2, X.3 [W4] (feeds ★G.7) |
| **R** Reports | R.1 [W3]; R.2 [W5]; R.3 [W8]; R.4 [W9] |
| **M** Remediation | ★M.1 [W8]; ★M.2 [W9] |
| **A** New adapters | A.2 [W6]; A.1 [W8]; A.3, A.4 [W12] |
| **H** GitHub Action | H.1 [W8]; H.2, H.3 [W9]; H.4, H.5, H.6 [W10] |
| **Y** Self-security | Y.2 [W8]; Y.1 [W9]; Y.3 [W13] |
| **E** Evaluation | E.1 [W2]; ★E.2 [W5]; ★E.6 [W10]; E.5, E.8, E.9 [W12]; E.3, E.4, E.7, E.10 [W13] |
| **D** Docs | D.2 [W6]; D.1 [W10]; ★D.3 [W11]; D.4 [W14] |

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
