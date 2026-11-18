### WO-Y.3: Run the SecPipe self-audit (AI-AND-LLM checklist + SecPipe on itself)
Wave: 13   Depends on: WO-Y.1, WO-Y.2, WO-L.8   Blocks: WO-D.4
Milestone: Final Feb 20
Traces: NFR-5 · I, J, (§2.6)
Suggested human owner: Brant Dimitrievski (reviewer: Elaine Huang)
Size: M

Goal (2–3 sentences): Audit SecPipe against Cloudflare's `AI-AND-LLM.md` classes (tool/action abuse, output
handling, context poisoning, sub-agent trust) and the sandbox contract. Run `secpipe scan` (standard,
frontier model) on the SecPipe repo itself. Every item is triaged with evidence; issues found become
fix-up WOs.

Context to read first (exact paths only): Cloudflare `skills/security-audit/AI-AND-LLM.md`; `docs/security/prompt-injection.md`;
`docs/PLAN.md` ADR-03, ADR-08.

Files you own (create/modify — nothing else): `docs/security/self-audit.md`, `docs/security/self-scan/**` (reports).

Files you may read but not modify: everything.

Interfaces consumed: —   Interfaces produced: an audit report plus a list of follow-up issues.

Implementation notes: For each AI-AND-LLM class: applicable? Defence? Evidence (test name / file)? Residual risk?
Include the sandbox-escape surface (Docker socket on the host, prefetch network) honestly.

Tests to write: none (an audit). Every claim links a test or file.

Acceptance criteria (all must pass):
- [ ] `grep -c '^| ' docs/security/self-audit.md` → ≥ 15 table rows (one per class/control)
- [ ] `java -jar secpipe-cli/target/secpipe.jar validate findings docs/security/self-scan/findings.json` → exit 0
- [ ] Every "residual risk: high" row has a linked GitHub issue

Out of scope: fixing the findings (separate WOs).

Handoff note for next agent: The final report's security section cites this document.
