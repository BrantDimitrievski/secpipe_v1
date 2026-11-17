### WO-K.9: Implement deterministic scoring — severity anchors, caps, confidence, summary
Wave: 4   Depends on: WO-P.8, WO-V.1   Blocks: WO-0.2, WO-R.2, WO-R.3
Milestone: Prototype 2 Dec 5
Traces: FR-8, FR-10 · H
Suggested human owner: Elodie Abdo (reviewer: Elaine Huang)
Size: M

Goal (2–3 sentences): `ScoringNode` sets `severity` on `confirmed` records only, using deterministic
anchors (impact class × authentication condition). It caps overall severity at demonstrated impact (test
evidence) and applies the defense-in-depth rule. Confidence comes from the evidence. `ScoringPolicy`
implements `CapPolicy` so the validator enforces F4 with the same logic.

Context to read first (exact paths only): `docs/PLAN.md` §2.2-H (anchors, copied below), §4.1 F4;
`secpipe-core/src/main/java/ca/carleton/secpipe/core/knowledge/KnowledgeBase.java`;
`secpipe-validators/src/main/java/ca/carleton/secpipe/validators/CapPolicy.java`; contracts `finding/Finding.java`.

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/scoring/**`,
`secpipe-core/src/test/java/ca/carleton/secpipe/core/scoring/**`.

Files you may read but not modify: knowledge, validators.

Interfaces consumed: `KnowledgeBase.impactClass`, disprover `proposed_severity` (carried in the record
draft)   Interfaces produced: `ScoringNode`; `ScoringPolicy implements CapPolicy`;
`SeveritySummary.of(records)` (FR-10).

Implementation notes:
- **Anchor table:**

  | Impact class | Unauthenticated | Authenticated |
  |---|---|---|
  | RCE | critical | high |
  | DATASTORE | critical | high |
  | FILE_READ_WRITE | high | medium |
  | CREDENTIAL (hard-coded secret) | high if the secret grants access, else medium | — |
  | CRYPTO_WEAKNESS | medium; low if not protecting secrets, per the disprover's impact reason | — |
  | INFO_DISCLOSURE | low | low |
  | dependency | advisory severity | — |

  Dependency severity is capped at medium when `reachable≠yes`, and likelihood is at most medium.
- **Demonstrated-impact cap:**
  - test evidence `failed_as_expected` that shows only that a payload reached the sink → cap = anchor;
  - no test evidence → cap `medium`. This can't happen for `confirmed`, but guard it anyway.
- **Final value:** `overall = min(anchor, cap, max(likelihood, impact))`.
- **Defense-in-depth:** if `conditions` contains a `system_configuration` control that blocks the attack
  (flagged by the disprover as an effective control) → overall ≤ low and the record is noted as hardening.
- **Confidence:**
  - high = test failed as expected AND disprover survived AND ≥ 1 deterministic tool source;
  - medium = two of the three;
  - low otherwise.
  - Also applied to `needs_validation` as `confidence_hint`.

Tests to write: each table row; the cap; defense-in-depth; confidence combos; `ScoringPolicy` used by
`FindingsValidator` rejects an over-severity record.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='ScoringNode*Test,ScoringPolicy*Test'` → ≥ 14 tests, 0 failures
- [ ] A test asserts no `needs_validation` or `rejected` record ever gains `severity`

Out of scope: LLM rubric text (it comes from the disprover's reasons).

Handoff note for next agent: The report header uses `SeveritySummary`; there is no other severity
arithmetic anywhere.
