# SecPipe coordination log

## 2026-11-17 / 18 — Setup, Wave 1, lean re-plan, completion

### Decisions (Brant)
- Repo `BrantDimitrievski/secpipe_v1`, public (a free personal account can't protect private repos), MIT, no collaborators.
- Build check = GitHub CI (Maven Central unreachable from the cloud coordinator session).
- Merge policy: coordinator squash-merges when CI is green, the independent reviewer approves, and no human checks are open. Otherwise Brant merges.
- S.2 Copilot spike NO-GO → WO-L.5 closed; WO-CC.1 removed the `copilot` provider.
- Approved deviation: `secpipe-sandbox` dependency for agents, adapter-spotbugs, adapter-osv, eval.
- **Lean re-plan (WO-RP.1, #108):** CLI-only core (detect → normalize/score → refuter + disprover → test generation → sandbox evidence → report → Benchmark scorecard). About 60 orders moved to `docs/work-orders/stretch/`.
- Contract change WO-CC.2 (SourceIndex, Location, ReconFacts). Fix-ups: WO-R.5 (report keeps finalize's metadata), G.4 (survivors recorded as needs_validation), G.7 option A (unrefuted candidates recorded when AI is off), K.5 (L2 fingerprint check at finalize), K.8 (fingerprints on tool-settled ledger units).
- WO-DEMO.1: a time-boxed demo slice for the Nov 17 presentation, replaced by K.13.

### Merged (37 PRs)
0.1 · P.0 (#93) · C.5 (#94) · C.4 (#95) · C.1 (#96) · S.2 (#101) · C.2 (#102) · C.3 (#103) · DEMO.1 (#105) · CC.1 (#106) ·
V.1 (#107) · RP.1 (#108) · K.1 (#109) · P.2 (#110) · E.1 (#111) · R.1 (#114) · L.1 (#115) · K.8 (#116) · X.1 (#117) ·
K.6 (#118) · CC.2 (#119) · K.5 (#120) · P.6 (#121) · L.2 (#122) · G.1 (#123) · K.9 (#124) · K.4 (#125) · R.5 (#126) ·
X.2 (#127) · G.6 (#128) · G.4 (#129) · G.5 (#130) · G.4 fix-up (#132) · G.7 (#133) · K.13 (#134) · D.1 (#135) · K.8 fix-up (#136)

### Blocked → resolved
- K.4: missing recon contracts → WO-CC.2.
- K.5: re-plan used a nonexistent status → corrected to PLAN §4.1 L3.
- K.13: unrefuted candidates never became findings → G.4 fix-up + G.7 option A; ledger L2 failure → K.8 fix-up.
- X.2: build-extension preflight missed `<build><extensions>` → fixed in round 2.

### Open human checks (Brant)
- S.3 laptop probe runs (macOS / Windows / Linux) and merging PR #98.
- X.1: independently confirm the JUnit console jar sha256 pin.
- X.2 / E.1: OWASP Benchmark run (needs a local checkout); confirm the E.1 baseline score (23.8 vs TPR−FPR 36.4) and the pinned BenchmarkJava commit.
- D.1: run `scripts/docs/check-snippets.sh` and `scripts/demo/demo.sh` on a clean machine.
- Confirm the L.2 default model ids.
- Turn on branch protection (PR required, `ci` required, linear history, no force-push).

### Tracked follow-ups (not blocking)
- Refuter: verify that cross-class UPPER_CASE statics are `static final` constants; tar-style argument injection is an accepted false-negative risk.
- G.7: confirm only when the failure matches testgen's `expected_failure`; add a testgen hint for env-configured helpers.
- Prompts: `.txt` prompt files bypass `DefaultPromptRenderer.sha256` (disprover, testgen).
- SARIF: swap in the official OASIS schema.
- Records-vs-schema optionals (guardrail_applied, reachable, ledger optional ints) → a later WO-CC.
- Remove the empty `secpipe-llm-copilot` module; README Copilot mention; PLAN §4.4 contract copies.
- `.gitignore` `__pycache__/`; Maven wrapper `distributionSha256Sum`; CI actions v4 → v5 (Node 20 deprecation).
- CliExtension SPI cycle (github ↔ cli), only relevant if the GitHub Action is revived.

## 2026-11-18 / 19 — Remediation re-plan, waves 8–12, follow-up re-plan

### Decisions (Brant)
- Remediation back in scope (re-plan 2026-11-18): K.15, G.11, G.10 (W8), M.1 (W9), M.3 + R.4 (W10), M.2 (W11), E.6 (W12). `--enable-fixes` keeps its frozen default `suggest`.
- 2026-11-19: approved the coordinator's repo-review plan; coordinator merges the held PRs; delete the empty `secpipe-llm-copilot` module; follow-up re-plan (wave 13: R.6, G.12, M.4, D.5).

### Merged
K.15 · G.11 · G.10 · M.1 (#147) · M.3 (#149) · R.4 (#150) · coordination log (#137) · M.2 (#151) · E.6 (#152) · S.3 (#98)

### Human checks resolved
- M.2: JaCoCo 0.8.13 `jacocoagent.jar` / `jacococli.jar` sha256 pins match `lib/` in the official GitHub release zip `jacoco-0.8.13.zip` (checked by the coordinator 2026-11-19; the CI image build had already matched sha1 against Central).

### Still open (Brant)
- S.3 laptop probes (macOS / Windows / Linux) — PR #98 merged on Brant's instruction; the probe runs remain a check.
- E.6 real run (Docker + LLM key, ≥ 10 fixes with patches); `docs/eval/remediation.md` is a placeholder until then.
- X.1 JUnit jar sha256; OWASP Benchmark run + E.1 baseline + pinned BenchmarkJava commit; D.1 clean-machine scripts; L.2 model ids; branch protection; Maven wrapper `distributionSha256Sum`.

### Environment
- Opus subagents hit the monthly spend limit on 2026-11-19; builders and reviewers run on Sonnet.
