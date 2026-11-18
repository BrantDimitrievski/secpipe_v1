package ca.carleton.secpipe.core.ledger;

import ca.carleton.secpipe.contracts.ids.CoverageIds;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit.Status;

import java.util.List;
import java.util.Optional;

/** Schema- and L1/L2-valid ledger units for the ledger tests. */
final class LedgerFixtures {

    static final String AGENT = "hunter-0a1b2c3d";
    static final String AGENT_2 = "hunter-11112222";

    private LedgerFixtures() { }

    /** A planned unit whose coverage id is derived from its canonical refs (subsystem varies per unit). */
    static LedgerUnit planned(String subsystem) {
        LedgerUnit.CanonicalRefs refs = new LedgerUnit.CanonicalRefs("http-endpoint", "user-input", subsystem,
                "sql-injection", Optional.empty());
        return new LedgerUnit(CoverageIds.of(refs), refs, "http-endpoint", "user-input", subsystem, "sql-injection",
                Optional.of("CWE-89"), List.of("src/main/java/A.java"), "INJECTION.md#sql", List.of(), List.of(),
                LedgerUnit.PriorStatus.NEW, List.of(), 1, Status.PLANNED, Optional.empty(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    /** A copy of {@code u} in an L2-valid shape for {@code status}. */
    static LedgerUnit in(LedgerUnit u, Status status) {
        return switch (status) {
            case PLANNED -> with(u, status, Optional.empty(), List.of(), List.of(), List.of(), List.of(), List.of());
            case IN_PROGRESS -> with(u, status, Optional.of(AGENT), List.of(), List.of(), List.of(), List.of(),
                    List.of());
            case COVERED -> with(u, status, Optional.of(AGENT), List.of("src/main/java/A.java"), List.of("sanitized"),
                    List.of(), List.of(), List.of());
            case CANDIDATE -> with(u, status, Optional.of(AGENT), List.of("src/main/java/A.java"),
                    List.of("no sanitizer"), List.of("code:CWE-89:fp1"), List.of(), List.of());
            case BLOCKED -> with(u, status, Optional.of(AGENT), List.of(), List.of(), List.of(),
                    List.of("sandbox unavailable"), List.of());
            case DEFERRED, OUT_OF_SCOPE, NOT_APPLICABLE -> with(u, status, Optional.empty(), List.of(), List.of(),
                    List.of(), List.of("reason"), List.of());
        };
    }

    static LedgerUnit with(LedgerUnit u, Status status, Optional<String> agentId, List<String> reviewed,
                           List<String> checks, List<String> fingerprints, List<String> unresolved,
                           List<String> settledBy) {
        return new LedgerUnit(u.coverageId(), u.canonicalRefs(), u.surface(), u.boundary(), u.subsystem(),
                u.attackClass(), u.cwe(), u.startingPaths(), u.ordinaryAttackClassBlock(), u.selectedCompanionBlocks(),
                u.excludedBlocks(), u.priorStatus(), u.attempts(), u.wave(), status, agentId, reviewed, checks,
                fingerprints, unresolved, settledBy);
    }

    static LedgerUnit withAttempts(LedgerUnit u, List<LedgerUnit.Attempt> attempts) {
        return new LedgerUnit(u.coverageId(), u.canonicalRefs(), u.surface(), u.boundary(), u.subsystem(),
                u.attackClass(), u.cwe(), u.startingPaths(), u.ordinaryAttackClassBlock(), u.selectedCompanionBlocks(),
                u.excludedBlocks(), u.priorStatus(), attempts, u.wave(), u.status(), u.agentId(), u.reviewedPaths(),
                u.localChecks(), u.resultFingerprints(), u.unresolved(), u.settledBy());
    }

    static LedgerUnit.Attempt attempt(String agentId, LedgerUnit.Attempt.AttemptStatus status) {
        return new LedgerUnit.Attempt(agentId, 1, status, List.of("src/main/java/A.java"), List.of("c"), List.of(),
                List.of(), Optional.empty());
    }
}
