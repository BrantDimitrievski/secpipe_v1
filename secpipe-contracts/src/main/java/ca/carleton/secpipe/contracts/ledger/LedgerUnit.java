package ca.carleton.secpipe.contracts.ledger;

import java.util.List;
import java.util.Optional;

/** One unit of coverage-ledger.json (schemas/ledger.schema.json). Invariants: LedgerValidator (WO-V.2). */
public record LedgerUnit(
        String coverageId,
        CanonicalRefs canonicalRefs,
        String surface,
        String boundary,
        String subsystem,
        String attackClass,
        Optional<String> cwe,
        List<String> startingPaths,
        String ordinaryAttackClassBlock,
        List<String> selectedCompanionBlocks,
        List<ExcludedBlock> excludedBlocks,
        PriorStatus priorStatus,
        List<Attempt> attempts,                 // append-only
        int wave,
        Status status,
        Optional<String> agentId,
        List<String> reviewedPaths,
        List<String> localChecks,
        List<String> resultFingerprints,
        List<String> unresolved,
        List<String> settledBy) {

    public enum Status { PLANNED, IN_PROGRESS, COVERED, CANDIDATE, BLOCKED, DEFERRED, OUT_OF_SCOPE, NOT_APPLICABLE }

    public enum PriorStatus { NEW, PRIOR_CONFIRMED_SAME_SOURCE, PRIOR_CONFIRMED_CHANGED_SOURCE, PRIOR_NEEDS_VALIDATION,
        PRIOR_DEFERRED, PRIOR_BLOCKED, PRIOR_OUT_OF_SCOPE, PRIOR_COVERED_SAME_SOURCE, PRIOR_COVERED_CHANGED_SOURCE,
        PRIOR_REJECTED_CLAIM_CHANGED, NONE }

    public record CanonicalRefs(String surface, String boundary, String subsystem, String attackClass, Optional<String> lifecycle) { }

    public record ExcludedBlock(String block, String reason) { }

    public record Attempt(String agentId, int wave, AttemptStatus status, List<String> reviewedPaths, List<String> localChecks,
                          List<String> resultFingerprints, List<String> unresolved, Optional<String> archivedReason) {
        public enum AttemptStatus { COVERED, CANDIDATE, BLOCKED }
    }

    /** A requested change; only the engine's LedgerStore applies it (feature A). */
    public record Update(String coverageId, Status newStatus, Optional<String> agentId, List<String> reviewedPaths,
                         List<String> localChecks, List<String> resultFingerprints, List<String> unresolved,
                         List<String> settledBy, Optional<String> archiveReason) { }
}
