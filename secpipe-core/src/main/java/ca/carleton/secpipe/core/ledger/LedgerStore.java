package ca.carleton.secpipe.core.ledger;

import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.validators.ValidationError;
import ca.carleton.secpipe.validators.ValidationResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * The sole writer of the coverage ledger (feature A). Minimal version from WO-K.1; WO-K.5 takes this package over.
 *
 * <p>Every write returns a new, immutable ledger sorted by {@code coverage_id}, then runs the injected transition
 * validator with (before, after). A failing validator or a structural problem raises {@link LedgerRejectedException}
 * and the caller keeps the old ledger.
 *
 * <p>Update semantics ({@link #apply}), applied in list order:
 * <ul>
 *   <li>{@code status} becomes {@code newStatus};</li>
 *   <li>{@code agentId} becomes the update's agent id when present; a move to {@code PLANNED} clears it
 *       (reassignment);</li>
 *   <li>each list field ({@code reviewedPaths}, {@code localChecks}, {@code resultFingerprints}, {@code unresolved},
 *       {@code settledBy}) is replaced when the update's list is non-empty and kept when it is empty;</li>
 *   <li>when the update carries an agent id, an {@link LedgerUnit.Attempt} is appended (attempts are append-only)
 *       with the unit's wave, the merged list fields, the update's {@code archiveReason}, and status
 *       {@code newStatus} if that is COVERED/CANDIDATE/BLOCKED, otherwise the unit's previous status if that is one of
 *       those (the archived result of a reassignment); if neither is, no attempt is appended (e.g. an assignment
 *       {@code PLANNED -> IN_PROGRESS}).</li>
 * </ul>
 * The validator runs once per call, on the ledger before the first and after the last update.
 */
public final class LedgerStore {

    private static final Comparator<LedgerUnit> BY_COVERAGE_ID = Comparator.comparing(LedgerUnit::coverageId);

    private final BiFunction<List<LedgerUnit>, List<LedgerUnit>, ValidationResult> transitionValidator;

    public LedgerStore(BiFunction<List<LedgerUnit>, List<LedgerUnit>, ValidationResult> transitionValidator) {
        this.transitionValidator = Objects.requireNonNull(transitionValidator, "transitionValidator");
    }

    /** Adds new units (coverage ids must be new and distinct), re-sorts, and validates. */
    public List<LedgerUnit> add(List<LedgerUnit> current, List<LedgerUnit> newUnits) throws LedgerRejectedException {
        Set<String> ids = new HashSet<>();
        for (LedgerUnit u : current) {
            ids.add(u.coverageId());
        }
        List<LedgerUnit> out = new ArrayList<>(current);
        for (LedgerUnit u : newUnits) {
            if (u == null || u.coverageId() == null || u.coverageId().isBlank()) {
                throw new LedgerRejectedException("new ledger unit without a coverage_id");
            }
            if (!ids.add(u.coverageId())) {
                throw new LedgerRejectedException("duplicate coverage_id " + u.coverageId());
            }
            out.add(immutableCopy(u));
        }
        return validated(current, sorted(out));
    }

    /** Applies updates in order (see the class comment), re-sorts, and validates. */
    public List<LedgerUnit> apply(List<LedgerUnit> current, List<LedgerUnit.Update> updates)
            throws LedgerRejectedException {
        Map<String, LedgerUnit> byId = new LinkedHashMap<>();
        for (LedgerUnit u : current) {
            byId.put(u.coverageId(), u);
        }
        for (LedgerUnit.Update upd : updates) {
            if (upd == null || upd.coverageId() == null || upd.newStatus() == null) {
                throw new LedgerRejectedException("ledger update without a coverage_id or new status");
            }
            LedgerUnit unit = byId.get(upd.coverageId());
            if (unit == null) {
                throw new LedgerRejectedException("ledger update for unknown coverage_id " + upd.coverageId());
            }
            byId.put(unit.coverageId(), applyOne(unit, upd));
        }
        return validated(current, sorted(new ArrayList<>(byId.values())));
    }

    private static LedgerUnit applyOne(LedgerUnit u, LedgerUnit.Update upd) {
        List<String> reviewed = pick(upd.reviewedPaths(), u.reviewedPaths());
        List<String> checks = pick(upd.localChecks(), u.localChecks());
        List<String> fingerprints = pick(upd.resultFingerprints(), u.resultFingerprints());
        List<String> unresolved = pick(upd.unresolved(), u.unresolved());
        List<String> settledBy = pick(upd.settledBy(), u.settledBy());

        List<LedgerUnit.Attempt> attempts = new ArrayList<>(u.attempts());
        if (upd.agentId().isPresent()) {
            Optional<LedgerUnit.Attempt.AttemptStatus> status =
                    attemptStatus(upd.newStatus()).or(() -> attemptStatus(u.status()));
            status.ifPresent(s -> attempts.add(new LedgerUnit.Attempt(upd.agentId().get(), u.wave(), s, reviewed,
                    checks, fingerprints, unresolved, upd.archiveReason())));
        }
        Optional<String> agentId = upd.newStatus() == LedgerUnit.Status.PLANNED
                ? Optional.empty()
                : upd.agentId().or(u::agentId);

        return immutableCopy(new LedgerUnit(u.coverageId(), u.canonicalRefs(), u.surface(), u.boundary(),
                u.subsystem(), u.attackClass(), u.cwe(), u.startingPaths(), u.ordinaryAttackClassBlock(),
                u.selectedCompanionBlocks(), u.excludedBlocks(), u.priorStatus(), attempts, u.wave(),
                upd.newStatus(), agentId, reviewed, checks, fingerprints, unresolved, settledBy));
    }

    private static List<String> pick(List<String> update, List<String> existing) {
        return update == null || update.isEmpty() ? existing : update;
    }

    private static Optional<LedgerUnit.Attempt.AttemptStatus> attemptStatus(LedgerUnit.Status s) {
        return switch (s) {
            case COVERED -> Optional.of(LedgerUnit.Attempt.AttemptStatus.COVERED);
            case CANDIDATE -> Optional.of(LedgerUnit.Attempt.AttemptStatus.CANDIDATE);
            case BLOCKED -> Optional.of(LedgerUnit.Attempt.AttemptStatus.BLOCKED);
            default -> Optional.empty();
        };
    }

    private List<LedgerUnit> validated(List<LedgerUnit> before, List<LedgerUnit> after)
            throws LedgerRejectedException {
        ValidationResult result = transitionValidator.apply(before, after);
        if (result == null) {
            throw new LedgerRejectedException("ledger validator returned no result");
        }
        if (!result.ok()) {
            throw new LedgerRejectedException("ledger validator: " + summary(result), result);
        }
        return after;
    }

    /** At most five errors, joined with "; ", plus a count of the rest. */
    public static String summary(ValidationResult result) {
        List<ValidationError> errors = result.errors();
        String shown = errors.stream().limit(5).map(ValidationError::toString).collect(Collectors.joining("; "));
        return errors.size() > 5 ? shown + "; (+" + (errors.size() - 5) + " more)" : shown;
    }

    /** An immutable list of the units sorted by coverage id. */
    public static List<LedgerUnit> sorted(List<LedgerUnit> units) {
        return units.stream().sorted(BY_COVERAGE_ID).toList();
    }

    /** A copy of the unit whose lists (including each attempt's lists) are unmodifiable. */
    public static LedgerUnit immutableCopy(LedgerUnit u) {
        List<LedgerUnit.Attempt> attempts = u.attempts().stream()
                .map(a -> new LedgerUnit.Attempt(a.agentId(), a.wave(), a.status(), List.copyOf(a.reviewedPaths()),
                        List.copyOf(a.localChecks()), List.copyOf(a.resultFingerprints()), List.copyOf(a.unresolved()),
                        a.archivedReason()))
                .toList();
        return new LedgerUnit(u.coverageId(), u.canonicalRefs(), u.surface(), u.boundary(), u.subsystem(),
                u.attackClass(), u.cwe(), List.copyOf(u.startingPaths()), u.ordinaryAttackClassBlock(),
                List.copyOf(u.selectedCompanionBlocks()), List.copyOf(u.excludedBlocks()), u.priorStatus(),
                attempts, u.wave(), u.status(), u.agentId(), List.copyOf(u.reviewedPaths()),
                List.copyOf(u.localChecks()), List.copyOf(u.resultFingerprints()), List.copyOf(u.unresolved()),
                List.copyOf(u.settledBy()));
    }

    /** A ledger write was refused; the ledger is unchanged. */
    public static final class LedgerRejectedException extends Exception {

        private static final long serialVersionUID = 1L;

        private final transient ValidationResult validation;

        public LedgerRejectedException(String message) {
            super(message);
            this.validation = null;
        }

        public LedgerRejectedException(String message, ValidationResult validation) {
            super(message);
            this.validation = Objects.requireNonNull(validation, "validation");
        }

        /** The failing validator result, when the validator (not a structural check) refused the write. */
        public Optional<ValidationResult> validation() {
            return Optional.ofNullable(validation);
        }
    }
}
