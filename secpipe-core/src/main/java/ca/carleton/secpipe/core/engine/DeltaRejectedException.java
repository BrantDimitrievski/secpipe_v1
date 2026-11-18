package ca.carleton.secpipe.core.engine;

import ca.carleton.secpipe.validators.ValidationResult;

import java.util.Objects;
import java.util.Optional;

/**
 * Thrown by {@link DeltaApplier#apply} when a node's {@code StateDelta} cannot be applied. The state is left
 * unchanged; the runner turns the exception into the run's {@code incomplete_reason} via
 * {@link #incompleteReason(String)}.
 */
public final class DeltaRejectedException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient ValidationResult validation;

    /** A rejection for a structural problem (unknown coverage id, null field, ...), not a validator failure. */
    public DeltaRejectedException(String message) {
        super(Objects.requireNonNull(message, "message"));
        this.validation = null;
    }

    /** A rejection because an injected validator returned errors. */
    public DeltaRejectedException(String message, ValidationResult validation) {
        super(Objects.requireNonNull(message, "message"));
        this.validation = Objects.requireNonNull(validation, "validation");
    }

    /** The failing validator result, when a validator (not a structural check) rejected the delta. */
    public Optional<ValidationResult> validation() {
        return Optional.ofNullable(validation);
    }

    /**
     * The text for {@code PipelineState.incompleteReason}: {@code validator rejected delta from node <id>: ...} for
     * validator failures, {@code engine rejected delta from node <id>: ...} for structural problems.
     */
    public String incompleteReason(String nodeId) {
        String who = validation == null ? "engine" : "validator";
        return who + " rejected delta from node " + nodeId + ": " + getMessage();
    }
}
