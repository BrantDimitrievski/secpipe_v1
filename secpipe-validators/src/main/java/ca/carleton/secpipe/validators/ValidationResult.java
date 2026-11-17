package ca.carleton.secpipe.validators;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of a validation: a list of at most {@value #MAX_ERRORS} errors, in the order the validator produced
 * them. Errors past the cap are dropped (the first {@value #MAX_ERRORS} are kept), so a badly broken document
 * cannot flood logs or reports.
 */
public record ValidationResult(List<ValidationError> errors) {

    /** The most errors a result keeps. */
    public static final int MAX_ERRORS = 100;

    private static final ValidationResult OK = new ValidationResult(List.of());

    public ValidationResult {
        Objects.requireNonNull(errors, "errors");
        errors = List.copyOf(errors.size() > MAX_ERRORS ? errors.subList(0, MAX_ERRORS) : errors);
    }

    /** A result without errors. */
    public static ValidationResult valid() {
        return OK;
    }

    /** A result holding {@code errors} (capped). */
    public static ValidationResult of(List<ValidationError> errors) {
        return errors.isEmpty() ? OK : new ValidationResult(errors);
    }

    /** True when there are no errors. */
    public boolean ok() {
        return errors.isEmpty();
    }

    /** This result's errors followed by {@code other}'s, capped at {@value #MAX_ERRORS}. */
    public ValidationResult merge(ValidationResult other) {
        Objects.requireNonNull(other, "other");
        if (other.ok()) {
            return this;
        }
        if (ok()) {
            return other;
        }
        List<ValidationError> all = new ArrayList<>(errors.size() + other.errors.size());
        all.addAll(errors);
        all.addAll(other.errors);
        return new ValidationResult(all);
    }
}
