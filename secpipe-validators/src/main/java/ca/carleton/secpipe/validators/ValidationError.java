package ca.carleton.secpipe.validators;

import java.util.Objects;
import java.util.Optional;

/**
 * One validation problem, shared by every SecPipe validator (schema, findings rules, ledger, agent output).
 *
 * @param code        stable machine-readable code, e.g. {@code schema.required} or {@code F3}
 * @param path        JSON pointer into the validated document ({@code ""} for the root)
 * @param message     one-line human-readable description
 * @param fingerprint the fingerprint of the finding the problem belongs to, when known
 */
public record ValidationError(String code, String path, String message, Optional<String> fingerprint) {

    public ValidationError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(fingerprint, "fingerprint");
    }

    /** An error not tied to a finding. */
    public static ValidationError of(String code, String path, String message) {
        return new ValidationError(code, path, message, Optional.empty());
    }

    /** {@code path: message [code]}, plus the fingerprint when present. */
    @Override
    public String toString() {
        String where = path.isEmpty() ? "/" : path;
        return where + ": " + message + " [" + code + "]" + fingerprint.map(f -> " (" + f + ")").orElse("");
    }
}
