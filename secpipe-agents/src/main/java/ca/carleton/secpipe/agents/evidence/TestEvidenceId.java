package ca.carleton.secpipe.agents.evidence;

import ca.carleton.secpipe.contracts.finding.Finding.TestEvidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic ids for {@code secpipe.test_evidence[].id} ({@code te-<12 hex>}, schema pattern
 * {@code ^te-[0-9a-f]{12}$}): the first 12 hex digits of SHA-256 over fingerprint, phase and attempt.
 */
public final class TestEvidenceId {

    private TestEvidenceId() {
    }

    /** The id of attempt {@code attempt} (1-based) of {@code phase} for {@code fingerprint}. */
    public static String of(String fingerprint, TestEvidence.Phase phase, int attempt) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(phase, "phase");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1, got " + attempt);
        }
        String input = fingerprint + "\n" + phase.name().toLowerCase(Locale.ROOT) + "\n" + attempt;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return "te-" + HexFormat.of().formatHex(d).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
