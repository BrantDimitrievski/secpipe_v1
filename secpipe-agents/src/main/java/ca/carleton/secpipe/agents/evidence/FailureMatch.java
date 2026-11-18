package ca.carleton.secpipe.agents.evidence;

import java.util.regex.Pattern;

/**
 * The one rule (WO-G.11) that decides whether a JUnit failure is the generated test's intended failure: the
 * whitespace-normalised failure message contains the whitespace-normalised testgen {@code expected_failure}.
 * Deterministic string containment, no LLM. Used by the evidence node and by remediation (WO-M.1) so that both judge
 * "failed / passes for the right reason" the same way.
 */
public final class FailureMatch {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private FailureMatch() {
    }

    /**
     * True when {@code message} contains {@code expectedFailure} after both are whitespace-normalised. A null or
     * blank {@code expectedFailure} never matches; a null {@code message} is treated as empty.
     */
    public static boolean matches(String message, String expectedFailure) {
        String expected = normalize(expectedFailure);
        return !expected.isEmpty() && normalize(message).contains(expected);
    }

    /** Collapses every whitespace run to one space and strips the ends; null becomes the empty string. */
    public static String normalize(String s) {
        return s == null ? "" : WHITESPACE.matcher(s).replaceAll(" ").strip();
    }
}
