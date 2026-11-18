package ca.carleton.secpipe.core.ledger;

import ca.carleton.secpipe.contracts.json.LedgerCodec;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.validators.SchemaRegistry;
import ca.carleton.secpipe.validators.ValidationError;
import ca.carleton.secpipe.validators.ValidationResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The sole writer of the coverage ledger (feature A). WO-K.1 wrote the minimal version; WO-K.5 added the state
 * machine ({@link LedgerTransitions}, {@code docs/PLAN.md} §4.1 L3), L2-driven clearing, and persistence.
 *
 * <p>Every write returns a new, immutable ledger sorted by {@code coverage_id}, then runs the injected validator with
 * (before, after); WO-K.13 injects {@link LedgerInvariants#check}. A failing validator, an illegal transition or a
 * structural problem raises {@link LedgerRejectedException} and the caller keeps the old ledger.
 *
 * <p>Update semantics ({@link #apply}), applied in list order:
 * <ul>
 *   <li>{@code status} becomes {@code newStatus}; each single update must be an allowed L3 transition (checked here,
 *       per update, with code {@code L3});</li>
 *   <li>{@code agentId} becomes the update's agent id when present; a move to {@code PLANNED}, {@code DEFERRED},
 *       {@code OUT_OF_SCOPE} or {@code NOT_APPLICABLE} clears it (L2: agent_id null);</li>
 *   <li>each list field ({@code reviewedPaths}, {@code localChecks}, {@code resultFingerprints}, {@code unresolved},
 *       {@code settledBy}) is replaced when the update's list is non-empty. An empty list keeps the existing list,
 *       <em>except</em> where L2 requires the field to be empty for {@code newStatus}; there it clears it:
 *       {@code PLANNED} clears every list (and {@code settledBy}); every status but {@code CANDIDATE} clears
 *       {@code resultFingerprints}; {@code COVERED} also clears {@code unresolved}. A non-empty list that L2 forbids
 *       is kept, so the validator reports it instead of it being dropped silently;</li>
 *   <li>when the update carries an agent id, an {@link LedgerUnit.Attempt} is appended (attempts are append-only)
 *       with the unit's wave, the merged list fields (before clearing), the update's {@code archiveReason}, and status
 *       {@code newStatus} if that is COVERED/CANDIDATE/BLOCKED, otherwise the unit's previous status if that is one of
 *       those (the archived result of a reassignment). A reassignment of an {@code IN_PROGRESS} unit to
 *       {@code PLANNED} archives a {@code BLOCKED} attempt (the abandoned work); its agent id defaults to the unit's.
 *       Otherwise no attempt is appended (e.g. an assignment {@code PLANNED -> IN_PROGRESS}). L3 requires a move to
 *       {@code PLANNED} to carry an {@code archiveReason}.</li>
 * </ul>
 * The injected validator runs once per call, on the ledger before the first and after the last update.
 *
 * <p>Persistence ({@link #write(Path, List)}, {@link #read(Path)}) goes through {@code LedgerCodec}: sorted by
 * {@code coverage_id}, byte-stable, and validated against {@code ledger.schema.json} on both write and read.
 */
public final class LedgerStore {

    /** The schema {@code coverage-ledger.json} is validated against. */
    public static final String SCHEMA = "ledger.schema.json";

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
            LedgerUnit next = applyOne(unit, upd);
            Optional<ValidationError> illegal = LedgerTransitions.check(unit, next, "");
            if (illegal.isPresent()) {
                ValidationResult r = ValidationResult.of(List.of(illegal.get()));
                throw new LedgerRejectedException("ledger transition: " + summary(r), r);
            }
            byId.put(unit.coverageId(), next);
        }
        return validated(current, sorted(new ArrayList<>(byId.values())));
    }

    private static LedgerUnit applyOne(LedgerUnit u, LedgerUnit.Update upd) {
        LedgerUnit.Status to = upd.newStatus();
        List<String> reviewed = pick(upd.reviewedPaths(), u.reviewedPaths());
        List<String> checks = pick(upd.localChecks(), u.localChecks());
        List<String> fingerprints = pick(upd.resultFingerprints(), u.resultFingerprints());
        List<String> unresolved = pick(upd.unresolved(), u.unresolved());
        List<String> settledBy = pick(upd.settledBy(), u.settledBy());

        List<LedgerUnit.Attempt> attempts = new ArrayList<>(u.attempts());
        Optional<String> attemptAgent = to == LedgerUnit.Status.PLANNED ? upd.agentId().or(u::agentId) : upd.agentId();
        if (attemptAgent.isPresent()) {
            Optional<LedgerUnit.Attempt.AttemptStatus> status = attemptStatus(to).or(() -> attemptStatus(u.status()));
            if (status.isEmpty() && to == LedgerUnit.Status.PLANNED && u.status() == LedgerUnit.Status.IN_PROGRESS) {
                status = Optional.of(LedgerUnit.Attempt.AttemptStatus.BLOCKED);
            }
            if (status.isPresent()) {
                attempts.add(new LedgerUnit.Attempt(attemptAgent.get(), u.wave(), status.get(), reviewed, checks,
                        fingerprints, unresolved, upd.archiveReason()));
            }
        }
        boolean noAgent = to == LedgerUnit.Status.PLANNED || to == LedgerUnit.Status.DEFERRED
                || to == LedgerUnit.Status.OUT_OF_SCOPE || to == LedgerUnit.Status.NOT_APPLICABLE;
        Optional<String> agentId = noAgent ? Optional.empty() : upd.agentId().or(u::agentId);

        boolean planned = to == LedgerUnit.Status.PLANNED;
        reviewed = clearIf(planned, upd.reviewedPaths(), reviewed);
        checks = clearIf(planned, upd.localChecks(), checks);
        fingerprints = clearIf(to != LedgerUnit.Status.CANDIDATE, upd.resultFingerprints(), fingerprints);
        unresolved = clearIf(planned || to == LedgerUnit.Status.COVERED, upd.unresolved(), unresolved);
        settledBy = clearIf(planned, upd.settledBy(), settledBy);

        return immutableCopy(new LedgerUnit(u.coverageId(), u.canonicalRefs(), u.surface(), u.boundary(),
                u.subsystem(), u.attackClass(), u.cwe(), u.startingPaths(), u.ordinaryAttackClassBlock(),
                u.selectedCompanionBlocks(), u.excludedBlocks(), u.priorStatus(), attempts, u.wave(),
                to, agentId, reviewed, checks, fingerprints, unresolved, settledBy));
    }

    private static List<String> pick(List<String> update, List<String> existing) {
        return update == null || update.isEmpty() ? existing : update;
    }

    /** Clears the merged list when L2 requires it empty and the update did not set it explicitly. */
    private static List<String> clearIf(boolean l2RequiresEmpty, List<String> update, List<String> merged) {
        return l2RequiresEmpty && (update == null || update.isEmpty()) ? List.of() : merged;
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

    /** Serializes the ledger with {@code LedgerCodec} (sorted, byte-stable) and validates it against the schema. */
    public static String toJson(List<LedgerUnit> ledger) throws LedgerRejectedException {
        String json = LedgerCodec.write(sorted(ledger));
        ValidationResult r = SchemaRegistry.shared().validate(SCHEMA, json);
        if (!r.ok()) {
            throw new LedgerRejectedException("coverage-ledger.json does not match " + SCHEMA + ": " + summary(r), r);
        }
        return json;
    }

    /** Writes {@code coverage-ledger.json}: sorted by coverage id, schema-validated, byte-stable. */
    public static void write(Path file, List<LedgerUnit> ledger) throws LedgerRejectedException {
        String json = toJson(ledger);
        try {
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    /** Reads {@code coverage-ledger.json}, validating it against the schema first; the result is sorted. */
    public static List<LedgerUnit> read(Path file) throws LedgerRejectedException {
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        ValidationResult r = SchemaRegistry.shared().validate(SCHEMA, json);
        if (!r.ok()) {
            throw new LedgerRejectedException(file + " does not match " + SCHEMA + ": " + summary(r), r);
        }
        return sorted(LedgerCodec.read(json).stream().map(LedgerStore::immutableCopy).toList());
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
