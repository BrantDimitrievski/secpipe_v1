package ca.carleton.secpipe.core.ledger;

import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit.Status;
import ca.carleton.secpipe.validators.ValidationError;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The ledger state machine, exactly {@code docs/PLAN.md} §4.1 rule L3, plus static builders for the
 * {@link LedgerUnit.Update}s that drive it.
 *
 * <pre>
 * planned     -> in_progress, covered*, candidate*, deferred, out_of_scope, not_applicable
 * in_progress -> covered, candidate, blocked, planned (reassignment; requires an archived attempt)
 * blocked     -> in_progress, deferred
 * covered     -> planned (critic reassignment; requires an archived attempt)
 * candidate   -> candidate (the fingerprint set grows)
 * (* only with settled_by: tool settlement)
 * </pre>
 * Everything else, including any other same-status "transition", is rejected with code {@code L3}. A unit that did
 * not change at all is not a transition.
 */
public final class LedgerTransitions {

    /** Error code of every transition error. */
    public static final String RULE = "L3";

    private static final Map<Status, Set<Status>> ALLOWED = Map.of(
            Status.PLANNED, EnumSet.of(Status.IN_PROGRESS, Status.COVERED, Status.CANDIDATE, Status.DEFERRED,
                    Status.OUT_OF_SCOPE, Status.NOT_APPLICABLE),
            Status.IN_PROGRESS, EnumSet.of(Status.COVERED, Status.CANDIDATE, Status.BLOCKED, Status.PLANNED),
            Status.BLOCKED, EnumSet.of(Status.IN_PROGRESS, Status.DEFERRED),
            Status.COVERED, EnumSet.of(Status.PLANNED),
            Status.CANDIDATE, EnumSet.of(Status.CANDIDATE),
            Status.DEFERRED, EnumSet.noneOf(Status.class),
            Status.OUT_OF_SCOPE, EnumSet.noneOf(Status.class),
            Status.NOT_APPLICABLE, EnumSet.noneOf(Status.class));

    private LedgerTransitions() { }

    /** The statuses {@code from} may move to (ignoring the side conditions marked * above). */
    public static Set<Status> allowedTargets(Status from) {
        return Set.copyOf(ALLOWED.get(from));
    }

    /** Whether the bare status pair is in the L3 table (side conditions not checked). */
    public static boolean isListed(Status from, Status to) {
        return ALLOWED.get(from).contains(to);
    }

    /**
     * Checks one unit's change. Returns the L3 error when {@code prev -> next} is not allowed, including its side
     * conditions: settled_by for planned->covered/candidate, an archived attempt (a newly appended attempt with an
     * archived reason) for a move to planned, a strictly growing fingerprint set for candidate->candidate.
     *
     * @param path the JSON pointer to report (e.g. {@code /3})
     */
    public static Optional<ValidationError> check(LedgerUnit prev, LedgerUnit next, String path) {
        if (prev.equals(next)) {
            return Optional.empty();
        }
        Status from = prev.status();
        Status to = next.status();
        String name = name(from) + " -> " + name(to);
        if (!isListed(from, to)) {
            return error(path, "illegal ledger transition " + name + " for " + prev.coverageId());
        }
        if (from == Status.PLANNED && (to == Status.COVERED || to == Status.CANDIDATE) && next.settledBy().isEmpty()) {
            return error(path, "ledger transition " + name + " requires settled_by (tool settlement) for "
                    + prev.coverageId());
        }
        if (to == Status.PLANNED && !archived(prev, next)) {
            return error(path, "ledger transition " + name + " requires an archived attempt for " + prev.coverageId());
        }
        if (from == Status.CANDIDATE && to == Status.CANDIDATE) {
            Set<String> before = new HashSet<>(prev.resultFingerprints());
            Set<String> after = new HashSet<>(next.resultFingerprints());
            if (!after.containsAll(before) || after.size() <= before.size()) {
                return error(path, "ledger transition " + name + " requires the fingerprint set to grow for "
                        + prev.coverageId());
            }
        }
        return Optional.empty();
    }

    private static boolean archived(LedgerUnit prev, LedgerUnit next) {
        return next.attempts().size() > prev.attempts().size()
                && next.attempts().get(next.attempts().size() - 1).archivedReason().isPresent();
    }

    private static Optional<ValidationError> error(String path, String message) {
        return Optional.of(ValidationError.of(RULE, path, message));
    }

    /** The schema spelling of a status ({@code in_progress}). */
    public static String name(Status s) {
        return s.name().toLowerCase(java.util.Locale.ROOT);
    }

    // ---- builders -------------------------------------------------------------------------------------------------

    /** planned/blocked -> in_progress: assigns an agent (no attempt is archived). */
    public static LedgerUnit.Update assign(String coverageId, String agentId) {
        return update(coverageId, Status.IN_PROGRESS, Optional.of(agentId), List.of(), List.of(), List.of(), List.of(),
                List.of(), Optional.empty());
    }

    /** in_progress -> covered by an agent: appends a COVERED attempt; clears fingerprints and unresolved (L2). */
    public static LedgerUnit.Update covered(String coverageId, String agentId, List<String> reviewedPaths,
                                            List<String> localChecks) {
        return update(coverageId, Status.COVERED, Optional.of(agentId), reviewedPaths, localChecks, List.of(),
                List.of(), List.of(), Optional.empty());
    }

    /** in_progress -> candidate (or candidate -> candidate with more fingerprints) by an agent. */
    public static LedgerUnit.Update candidate(String coverageId, String agentId, List<String> reviewedPaths,
                                              List<String> localChecks, List<String> resultFingerprints,
                                              List<String> unresolved) {
        return update(coverageId, Status.CANDIDATE, Optional.of(agentId), reviewedPaths, localChecks,
                resultFingerprints, unresolved, List.of(), Optional.empty());
    }

    /** in_progress -> blocked by an agent, naming the blocker(s). */
    public static LedgerUnit.Update blocked(String coverageId, String agentId, List<String> blockers) {
        return update(coverageId, Status.BLOCKED, Optional.of(agentId), List.of(), List.of(), List.of(), blockers,
                List.of(), Optional.empty());
    }

    /** planned -> covered/candidate settled by tools, without an agent (no attempt is archived). */
    public static LedgerUnit.Update toolSettled(String coverageId, Status status, List<String> settledBy,
                                                List<String> reviewedPaths, List<String> localChecks,
                                                List<String> resultFingerprints) {
        return update(coverageId, status, Optional.empty(), reviewedPaths, localChecks, resultFingerprints, List.of(),
                settledBy, Optional.empty());
    }

    /** in_progress/covered -> planned: archives the previous result with {@code reason} and clears the unit. */
    public static LedgerUnit.Update reassign(String coverageId, String previousAgentId, String reason) {
        return update(coverageId, Status.PLANNED, Optional.of(previousAgentId), List.of(), List.of(), List.of(),
                List.of(), List.of(), Optional.of(reason));
    }

    /** planned -> deferred/out_of_scope/not_applicable, or blocked -> deferred, with a reason (clears the agent). */
    public static LedgerUnit.Update close(String coverageId, Status status, String reason) {
        if (status != Status.DEFERRED && status != Status.OUT_OF_SCOPE && status != Status.NOT_APPLICABLE) {
            throw new IllegalArgumentException("close() takes deferred, out_of_scope or not_applicable, not " + status);
        }
        return update(coverageId, status, Optional.empty(), List.of(), List.of(), List.of(), List.of(reason),
                List.of(), Optional.empty());
    }

    private static LedgerUnit.Update update(String coverageId, Status status, Optional<String> agentId,
                                            List<String> reviewedPaths, List<String> localChecks,
                                            List<String> resultFingerprints, List<String> unresolved,
                                            List<String> settledBy, Optional<String> archiveReason) {
        return new LedgerUnit.Update(coverageId, status, agentId, List.copyOf(reviewedPaths), List.copyOf(localChecks),
                List.copyOf(resultFingerprints), List.copyOf(unresolved), List.copyOf(settledBy), archiveReason);
    }
}
