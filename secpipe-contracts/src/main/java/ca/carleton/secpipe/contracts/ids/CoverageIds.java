package ca.carleton.secpipe.contracts.ids;

import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** ADR-06 ledger ids. Body implemented by WO-C.2; output stability is contract. */
public final class CoverageIds {
    private CoverageIds() { }

    /**
     * cov:&lt;first 32 hex of SHA-256(canonical JSON {"attack_class","boundary","lifecycle"?,"subsystem","surface"})&gt;.
     * Each ref must be NFC, trimmed, non-empty, no control characters (else IllegalArgumentException).
     */
    public static String of(LedgerUnit.CanonicalRefs refs) {
        if (refs == null) {
            throw new IllegalArgumentException("canonical refs must not be null");
        }
        if (refs.lifecycle() == null) {
            throw new IllegalArgumentException("canonical ref 'lifecycle' must be an Optional, not null");
        }
        // Keys in code-point (sorted) order; "lifecycle" only when present.
        StringBuilder json = new StringBuilder(256).append('{');
        member(json, "attack_class", refs.attackClass()).append(',');
        member(json, "boundary", refs.boundary()).append(',');
        if (refs.lifecycle().isPresent()) {
            member(json, "lifecycle", refs.lifecycle().get()).append(',');
        }
        member(json, "subsystem", refs.subsystem()).append(',');
        member(json, "surface", refs.surface()).append('}');
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
        return "cov:" + HexFormat.of().formatHex(digest, 0, 16);
    }

    /** Appends {@code "key":"value"} with the value NFC-normalized and checked. */
    private static StringBuilder member(StringBuilder json, String key, String rawValue) {
        String value = canonicalRef(key, rawValue);
        json.append('"').append(key).append("\":\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                json.append('\\');
            }
            json.append(c);
        }
        return json.append('"');
    }

    private static String canonicalRef(String key, String rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("canonical ref '" + key + "' must not be null");
        }
        String value = Normalizer.normalize(rawValue, Normalizer.Form.NFC);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("canonical ref '" + key + "' must not be empty");
        }
        if (isSpace(value.codePointAt(0)) || isSpace(value.codePointBefore(value.length()))) {
            throw new IllegalArgumentException("canonical ref '" + key + "' has leading or trailing whitespace");
        }
        value.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || type == Character.SURROGATE) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "canonical ref '%s' contains a control, format or separator character U+%04X", key, cp));
            }
        });
        return value;
    }

    /** Java whitespace or a Unicode space separator (so U+00A0 and U+3000 count as untrimmed too). */
    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    /** The fixed subsystem ref for the quick profile (Cloudflare convention). */
    public static final String QUICK_SUBSYSTEM = "profile/quick/all-in-scope-subsystems";

    /** Agent ids: &lt;role&gt;-&lt;8 hex&gt;, fresh per invocation. */
    public static String newAgentId(String role, java.util.random.RandomGenerator rng) {
        if (role == null || !AGENT_ROLE.matcher(role).matches()) {
            throw new IllegalArgumentException("agent role must match " + AGENT_ROLE.pattern() + ": " + role);
        }
        if (rng == null) {
            throw new IllegalArgumentException("random generator must not be null");
        }
        return role + "-" + HexFormat.of().toHexDigits(rng.nextInt());
    }

    /** The role part of ledger.schema.json's agentId pattern. */
    private static final Pattern AGENT_ROLE = Pattern.compile("^[a-z][a-z0-9_-]*$");
}
