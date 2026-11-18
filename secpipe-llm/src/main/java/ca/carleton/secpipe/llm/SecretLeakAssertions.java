package ca.carleton.secpipe.llm;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Test helper (in main sources so every module's tests can use it without a test-jar): asserts that a secret value
 * appears nowhere in an exception chain, a set of records or log lines. Failures throw {@link AssertionError} whose
 * message names where the leak was, never the secret itself.
 */
public final class SecretLeakAssertions {

    private SecretLeakAssertions() { }

    /** Fails if {@code secret} occurs in {@code text}. */
    public static void assertNoSecret(String secret, String text, String where) {
        requireSecret(secret);
        if (text != null && text.contains(secret)) {
            throw new AssertionError("secret leaked in " + where);
        }
    }

    /** Checks message, toString and localized message of the throwable, its causes and suppressed exceptions. */
    public static void assertNoSecret(String secret, Throwable t) {
        walk(secret, t, new IdentityHashMap<>());
    }

    /** Checks {@code toString()} of every element (records print all their components). */
    public static void assertNoSecret(String secret, Collection<?> items, String where) {
        int i = 0;
        for (Object o : items) {
            assertNoSecret(secret, String.valueOf(o), where + "[" + i++ + "]");
        }
    }

    private static void walk(String secret, Throwable t, Map<Throwable, Boolean> seen) {
        if (t == null || seen.put(t, Boolean.TRUE) != null) {
            return;
        }
        String type = t.getClass().getSimpleName();
        assertNoSecret(secret, t.getMessage(), type + " message");
        assertNoSecret(secret, t.getLocalizedMessage(), type + " localized message");
        assertNoSecret(secret, t.toString(), type + " toString");
        walk(secret, t.getCause(), seen);
        for (Throwable s : t.getSuppressed()) {
            walk(secret, s, seen);
        }
    }

    private static void requireSecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("secret must be non-empty");
        }
    }
}
