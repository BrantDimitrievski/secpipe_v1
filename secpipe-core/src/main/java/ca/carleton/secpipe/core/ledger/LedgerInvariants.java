package ca.carleton.secpipe.core.ledger;

import ca.carleton.secpipe.contracts.ids.CoverageIds;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit.Status;
import ca.carleton.secpipe.validators.ValidationError;
import ca.carleton.secpipe.validators.ValidationResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The essential ledger invariants of {@code docs/PLAN.md} §4.1 (folded in from the old WO-V.2). Error codes are the
 * rule names ({@code L1} .. {@code L6}); paths are JSON pointers into the ledger array.
 *
 * <ul>
 *   <li>{@link #check(List, List)}: L1, L2 on {@code after}; L3, L4 per unit between {@code before} and
 *       {@code after}. Same shape as {@code EngineValidators.ledgerTransition}, so WO-K.13 wires
 *       {@code LedgerInvariants::check} directly.</li>
 *   <li>{@link #checkFingerprints(List, Set)}: L5 (needs the known fingerprints, so it is not part of
 *       {@code check}).</li>
 *   <li>{@link #checkTerminal(List)} / {@link #incompleteReason(List)}: L6.</li>
 * </ul>
 * L3 compares the unit before and after one write. A write that moves one unit through two statuses in one call
 * (e.g. assign and settle in the same delta) is judged on the net change, so callers should not do that.
 */
public final class LedgerInvariants {

    private LedgerInvariants() { }

    /** L1–L4 for one ledger write. */
    public static ValidationResult check(List<LedgerUnit> before, List<LedgerUnit> after) {
        List<ValidationError> errors = new ArrayList<>();
        errors.addAll(checkL1(after));
        for (int i = 0; i < after.size(); i++) {
            errors.addAll(checkL2(after.get(i), "/" + i));
        }
        errors.addAll(checkChanges(before, after));
        return ValidationResult.of(errors);
    }

    /** L1: unique ids, each equal to {@code CoverageIds.of(canonical_refs)}, sorted; no semantic tuple twice. */
    public static List<ValidationError> checkL1(List<LedgerUnit> ledger) {
        List<ValidationError> errors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Map<List<Object>, String> tuples = new HashMap<>();
        String previous = null;
        for (int i = 0; i < ledger.size(); i++) {
            LedgerUnit u = ledger.get(i);
            String path = "/" + i;
            if (!ids.add(u.coverageId())) {
                errors.add(ValidationError.of("L1", path, "duplicate coverage_id " + u.coverageId()));
            }
            String expected;
            try {
                expected = CoverageIds.of(u.canonicalRefs());
            } catch (IllegalArgumentException e) {
                expected = "(invalid canonical_refs: " + e.getMessage() + ")";
            }
            if (!expected.equals(u.coverageId())) {
                errors.add(ValidationError.of("L1", path + "/coverage_id",
                        "coverage_id " + u.coverageId() + " does not match canonical_refs (expected " + expected
                                + ")"));
            }
            if (previous != null && previous.compareTo(u.coverageId()) > 0) {
                errors.add(ValidationError.of("L1", path, "ledger is not sorted by coverage_id at " + u.coverageId()));
            }
            previous = u.coverageId();
            List<Object> tuple = List.of(u.surface(), u.boundary(), u.subsystem(), u.attackClass(),
                    u.canonicalRefs().lifecycle());
            String other = tuples.putIfAbsent(tuple, u.coverageId());
            if (other != null && !other.equals(u.coverageId())) {
                errors.add(ValidationError.of("L1", path, "semantic tuple " + tuple + " appears under "
                        + other + " and " + u.coverageId()));
            }
        }
        return List.copyOf(errors);
    }

    /** L2: the status invariant table, for one unit. */
    public static List<ValidationError> checkL2(LedgerUnit u, String path) {
        List<ValidationError> errors = new ArrayList<>();
        String s = LedgerTransitions.name(u.status());
        boolean agent = u.agentId().isPresent();
        switch (u.status()) {
            case PLANNED -> {
                requireAgent(errors, path, s, agent, false);
                requireEmpty(errors, path, s, "reviewed_paths", u.reviewedPaths());
                requireEmpty(errors, path, s, "local_checks", u.localChecks());
                requireEmpty(errors, path, s, "result_fingerprints", u.resultFingerprints());
                requireEmpty(errors, path, s, "unresolved", u.unresolved());
            }
            case IN_PROGRESS -> {
                requireAgent(errors, path, s, agent, true);
                requireEmpty(errors, path, s, "result_fingerprints", u.resultFingerprints());
            }
            case COVERED -> {
                if (!agent && u.settledBy().isEmpty()) {
                    errors.add(ValidationError.of("L2", path + "/agent_id",
                            "covered unit needs an agent_id or settled_by"));
                }
                requireNonEmpty(errors, path, s, "reviewed_paths", u.reviewedPaths());
                requireNonEmpty(errors, path, s, "local_checks", u.localChecks());
                requireEmpty(errors, path, s, "result_fingerprints", u.resultFingerprints());
                requireEmpty(errors, path, s, "unresolved", u.unresolved());
            }
            case CANDIDATE -> {
                requireNonEmpty(errors, path, s, "reviewed_paths", u.reviewedPaths());
                requireNonEmpty(errors, path, s, "local_checks", u.localChecks());
                requireNonEmpty(errors, path, s, "result_fingerprints", u.resultFingerprints());
            }
            case BLOCKED -> {
                requireAgent(errors, path, s, agent, true);
                requireEmpty(errors, path, s, "result_fingerprints", u.resultFingerprints());
                requireNonEmpty(errors, path, s, "unresolved", u.unresolved());
            }
            case DEFERRED, OUT_OF_SCOPE, NOT_APPLICABLE -> {
                requireAgent(errors, path, s, agent, false);
                requireEmpty(errors, path, s, "result_fingerprints", u.resultFingerprints());
                requireNonEmpty(errors, path, s, "unresolved", u.unresolved());
            }
            default -> throw new IllegalStateException("unknown status " + u.status());
        }
        return List.copyOf(errors);
    }

    /** L3 and L4 per unit; a removed unit is an error, a new unit must be planned or tool-settled. */
    private static List<ValidationError> checkChanges(List<LedgerUnit> before, List<LedgerUnit> after) {
        List<ValidationError> errors = new ArrayList<>();
        Map<String, Integer> afterIndex = new HashMap<>();
        for (int i = 0; i < after.size(); i++) {
            afterIndex.putIfAbsent(after.get(i).coverageId(), i);
        }
        Set<String> beforeIds = new HashSet<>();
        for (LedgerUnit prev : before) {
            beforeIds.add(prev.coverageId());
            Integer i = afterIndex.get(prev.coverageId());
            if (i == null) {
                errors.add(ValidationError.of("L3", "", "ledger unit " + prev.coverageId() + " was removed"));
                continue;
            }
            LedgerUnit next = after.get(i);
            String path = "/" + i;
            LedgerTransitions.check(prev, next, path).ifPresent(errors::add);
            if (!isPrefix(prev.attempts(), next.attempts())) {
                errors.add(ValidationError.of("L4", path + "/attempts",
                        "attempts of " + prev.coverageId() + " were rewritten (attempts are append-only)"));
            }
        }
        for (int i = 0; i < after.size(); i++) {
            LedgerUnit u = after.get(i);
            if (beforeIds.contains(u.coverageId())) {
                continue;
            }
            boolean settled = (u.status() == Status.COVERED || u.status() == Status.CANDIDATE)
                    && !u.settledBy().isEmpty();
            if (u.status() != Status.PLANNED && !settled) {
                errors.add(ValidationError.of("L3", "/" + i, "new ledger unit " + u.coverageId() + " must be planned "
                        + "or tool-settled, not " + LedgerTransitions.name(u.status())));
            }
        }
        return List.copyOf(errors);
    }

    private static boolean isPrefix(List<LedgerUnit.Attempt> prefix, List<LedgerUnit.Attempt> list) {
        return list.size() >= prefix.size() && list.subList(0, prefix.size()).equals(prefix);
    }

    /** L5: every result fingerprint exists in findings.json or the current candidates ({@code known}). */
    public static ValidationResult checkFingerprints(List<LedgerUnit> ledger, Set<String> known) {
        List<ValidationError> errors = new ArrayList<>();
        for (int i = 0; i < ledger.size(); i++) {
            List<String> fps = ledger.get(i).resultFingerprints();
            for (int j = 0; j < fps.size(); j++) {
                if (!known.contains(fps.get(j))) {
                    errors.add(ValidationError.of("L5", "/" + i + "/result_fingerprints/" + j,
                            "unknown result fingerprint " + fps.get(j)));
                }
            }
        }
        return ValidationResult.of(errors);
    }

    /** L6: the incomplete reason when any unit is still planned or in_progress; empty when the run may complete. */
    public static Optional<String> incompleteReason(List<LedgerUnit> ledger) {
        int unassigned = CoverageSummary.of(ledger).unassigned();
        return unassigned == 0 ? Optional.empty() : Optional.of("unassigned ledger units: " + unassigned);
    }

    /** L6 as a validation result: one error per planned or in_progress unit. */
    public static ValidationResult checkTerminal(List<LedgerUnit> ledger) {
        List<ValidationError> errors = new ArrayList<>();
        for (int i = 0; i < ledger.size(); i++) {
            Status s = ledger.get(i).status();
            if (s == Status.PLANNED || s == Status.IN_PROGRESS) {
                errors.add(ValidationError.of("L6", "/" + i + "/status", "unit " + ledger.get(i).coverageId()
                        + " is still " + LedgerTransitions.name(s) + " at the end of the run"));
            }
        }
        return ValidationResult.of(errors);
    }

    private static void requireAgent(List<ValidationError> errors, String path, String status, boolean present,
                                     boolean required) {
        if (present != required) {
            errors.add(ValidationError.of("L2", path + "/agent_id",
                    status + " unit must " + (required ? "have" : "not have") + " an agent_id"));
        }
    }

    private static void requireEmpty(List<ValidationError> errors, String path, String status, String field,
                                     List<String> values) {
        if (!values.isEmpty()) {
            errors.add(ValidationError.of("L2", path + "/" + field, status + " unit must have no " + field));
        }
    }

    private static void requireNonEmpty(List<ValidationError> errors, String path, String status, String field,
                                        List<String> values) {
        if (values.isEmpty()) {
            errors.add(ValidationError.of("L2", path + "/" + field, status + " unit needs at least one " + field));
        }
    }
}
