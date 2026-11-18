package ca.carleton.secpipe.validators;

import ca.carleton.secpipe.contracts.finding.Finding;
import java.util.Optional;

/**
 * The severity ceiling for a confirmed finding, used by rule F4 of {@link FindingsValidator}: a record's
 * {@code severity.overall_severity} must not exceed {@link #cap(Finding.Confirmed)} when a cap is present.
 *
 * <p>{@code WO-K.9} provides the real policy ({@code ScoringPolicy}); until then, and wherever no policy applies,
 * use {@link #NONE}. Implementations must be deterministic (CLAUDE.md §8).
 */
@FunctionalInterface
public interface CapPolicy {

    /** No cap: only the {@code max(likelihood, impact)} half of F4 applies. */
    CapPolicy NONE = finding -> Optional.empty();

    /** The highest overall severity {@code finding} may carry, or empty for no cap. */
    Optional<Finding.Score5> cap(Finding.Confirmed finding);
}
