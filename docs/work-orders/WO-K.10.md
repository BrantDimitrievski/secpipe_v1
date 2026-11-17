### WO-K.10: Implement the agent-call budget with reservations
Wave: 3   Depends on: WO-C.4   Blocks: WO-G.4, WO-K.11, WO-L.7, WO-G.2
Milestone: Prototype 2 Dec 5
Traces: NFR-2 · O, P
Suggested human owner: Elaine Huang (reviewer: Elodie Abdo)
Size: S

Goal (2–3 sentences): `CallBudget implements Budget`: thread-safe counters with per-role reservations,
reservation-first acquisition, a separate sandbox-run budget, and an exhaustion reason string. The engine
puts it in `RunContext`, and finalize reads it into `run-metadata.json`.

Context to read first (exact paths only): contracts `spi/Budget.java`, `agent/AgentRole.java`,
`run/RunMetadata.java` (`BudgetSnapshot`).

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/budget/**`,
`secpipe-core/src/test/java/ca/carleton/secpipe/core/budget/**`.

Files you may read but not modify: contracts.

Interfaces consumed: `Budget`   Interfaces produced: `CallBudget(int max, int maxSandboxRuns)`,
`snapshot() → RunMetadata.BudgetSnapshot`, `exhaustionReason()`.

Implementation notes:
- `tryAcquire(role)` consumes the role's reservation first, then the unreserved pool. Hunters can never
  consume critic or verifier reservations.
- Use `AtomicInteger`s or a lock; it must be correct under 16 concurrent threads.

Tests to write: the reservation protects verifiers from hunters; concurrency stress (16 threads × 1,000
acquires never exceed max); the snapshot; sandbox runs are independent.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='CallBudget*Test'` → ≥ 6 tests, 0 failures, including the concurrency test

Out of scope: retry policy (WO-L.7 consumes the budget per attempt).

Handoff note for next agent: Every provider attempt calls `tryAcquire`. A false return →
`BudgetExhaustedException`; the node then marks the remaining work and the run incomplete.
