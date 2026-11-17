package ca.carleton.secpipe.core.engine;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.validators.ValidationResult;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The validators the engine runs after every state write (feature A). The CLI assembly (WO-K.13) wires them to
 * {@code FindingsValidator} and {@code LedgerValidator}; tests pass stubs. The engine never constructs real
 * validators itself, so {@code secpipe-core} does not depend on their implementation.
 *
 * @param findings         validates the full record set (sorted by fingerprint) after any record change
 * @param ledgerTransition validates a ledger change, given the ledger before and after (both sorted by coverage id)
 */
public record EngineValidators(
        Function<List<Finding>, ValidationResult> findings,
        BiFunction<List<LedgerUnit>, List<LedgerUnit>, ValidationResult> ledgerTransition) {

    public EngineValidators {
        Objects.requireNonNull(findings, "findings");
        Objects.requireNonNull(ledgerTransition, "ledgerTransition");
    }

    /** Validators that accept everything. For tests and wiring stubs only; never use in a real run. */
    public static EngineValidators acceptAll() {
        return new EngineValidators(records -> ValidationResult.valid(), (before, after) -> ValidationResult.valid());
    }
}
