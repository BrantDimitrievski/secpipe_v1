package ca.carleton.secpipe.contracts.ids;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ADR-06 fingerprint algorithm (feature G). Signatures are frozen here; bodies are implemented by WO-C.5 and are
 * themselves contract: changing their output for the same input is a contract change.
 */
public final class Fingerprints {
    private Fingerprints() { }

    public static final String PATTERN = "^[A-Za-z0-9][A-Za-z0-9._:/@+-]*$";

    /** CWE variant -> family root (ported from MVP eval/sarif_util.py SAME_AS). */
    public static final Map<String, Set<String>> CWE_FAMILIES = Map.of(
            "CWE-22", Set.of("CWE-22", "CWE-23", "CWE-36", "CWE-73"),
            "CWE-78", Set.of("CWE-78", "CWE-77", "CWE-88"),
            "CWE-89", Set.of("CWE-89", "CWE-564", "CWE-943"),
            "CWE-327", Set.of("CWE-327", "CWE-326", "CWE-310", "CWE-757", "CWE-780"),
            "CWE-328", Set.of("CWE-328", "CWE-916", "CWE-759", "CWE-760"),
            "CWE-798", Set.of("CWE-798", "CWE-321", "CWE-259"),
            "CWE-502", Set.of("CWE-502"));

    /** Accepts {@code CWE-22}, {@code cwe-022}, {@code CWE_22}, {@code CWE 22} or bare {@code 22}. */
    private static final Pattern CWE_ID = Pattern.compile("(?i)(?:cwe[-_ ]?)?0*(\\d+)");

    private static final Pattern NUMERIC_FAMILY = Pattern.compile("CWE-(\\d+)");

    private static final int CODE_HEX_CHARS = 24;

    /** Family root for a CWE id; unknown ids are their own family. */
    public static String cweFamily(String cwe) {
        Objects.requireNonNull(cwe, "cwe");
        String canonical = canonicalCwe(cwe).orElse(cwe);
        for (Map.Entry<String, Set<String>> family : CWE_FAMILIES.entrySet()) {
            if (family.getValue().contains(canonical)) {
                return family.getKey();
            }
        }
        return canonical;
    }

    private static Optional<String> canonicalCwe(String cwe) {
        Matcher m = CWE_ID.matcher(cwe.strip());
        return m.matches() ? Optional.of("CWE-" + m.group(1)) : Optional.empty();
    }

    /**
     * code:CWE-&lt;family&gt;:&lt;24 hex&gt; where hex = SHA-256 over canonical JSON (sorted keys, no whitespace, UTF-8)
     * {"cwe_family":..., "file":..., "occurrence":n, "scope":..., "sink":...}. sink = sink call source text with comments
     * stripped and whitespace runs collapsed to one space; file repo-relative with '/'.
     */
    public static String code(String cwe, String repoRelativeFile, String scope, String sinkStatement, int occurrence) {
        Objects.requireNonNull(cwe, "cwe");
        Objects.requireNonNull(repoRelativeFile, "repoRelativeFile");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(sinkStatement, "sinkStatement");
        if (occurrence < 0) {
            throw new IllegalArgumentException("occurrence must be >= 0, was " + occurrence);
        }
        String family = cweFamily(cwe);
        Matcher numeric = NUMERIC_FAMILY.matcher(family);
        if (!numeric.matches()) {
            throw new IllegalArgumentException("not a CWE id: '" + cwe + "' (expected CWE-<number>)");
        }
        String file = repoRelativeFile.replace('\\', '/');
        while (file.startsWith("./")) {
            file = file.substring(2);
        }
        // Keys in sorted order: cwe_family, file, occurrence, scope, sink.
        StringBuilder json = new StringBuilder(128);
        json.append("{\"cwe_family\":");
        appendJsonString(json, family);
        json.append(",\"file\":");
        appendJsonString(json, file);
        json.append(",\"occurrence\":").append(occurrence);
        json.append(",\"scope\":");
        appendJsonString(json, scope);
        json.append(",\"sink\":");
        appendJsonString(json, normalizeStatement(sinkStatement));
        json.append('}');
        String hex = HexFormat.of().formatHex(sha256(json.toString().getBytes(StandardCharsets.UTF_8)));
        return "code:CWE-" + numeric.group(1) + ":" + hex.substring(0, CODE_HEX_CHARS);
    }

    /** RFC 8259 string: escapes '"', '\\' and U+0000..U+001F (short forms where defined, else lowercase u00xx). */
    private static void appendJsonString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    /** dep:&lt;purl&gt;:&lt;vulnId&gt;, vulnId preference CVE alias &gt; GHSA &gt; OSV id; chars outside PATTERN -> %HH then '%'->'+'. */
    public static String dependency(String purl, java.util.List<String> vulnIdsInPreferenceOrder) {
        Objects.requireNonNull(purl, "purl");
        Objects.requireNonNull(vulnIdsInPreferenceOrder, "vulnIdsInPreferenceOrder");
        if (vulnIdsInPreferenceOrder.isEmpty()) {
            throw new IllegalArgumentException("at least one vulnerability id is required");
        }
        for (String id : vulnIdsInPreferenceOrder) {
            Objects.requireNonNull(id, "vulnerability id");
        }
        String chosen = firstWithPrefix(vulnIdsInPreferenceOrder, "CVE-")
                .or(() -> firstWithPrefix(vulnIdsInPreferenceOrder, "GHSA-"))
                .orElse(vulnIdsInPreferenceOrder.get(0));
        return "dep:" + encodeIdPart(purl) + ":" + encodeIdPart(chosen);
    }

    private static Optional<String> firstWithPrefix(List<String> ids, String prefix) {
        return ids.stream().filter(id -> id.startsWith(prefix)).findFirst();
    }

    /** UTF-8 bytes outside [A-Za-z0-9._:/@+-] become %HH (uppercase hex); then every '%' becomes '+'. */
    private static String encodeIdPart(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (byte b : raw.getBytes(StandardCharsets.UTF_8)) {
            int u = b & 0xFF;
            if (isIdChar(u)) {
                out.append((char) u);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(u >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(u & 0xF, 16)));
            }
        }
        return out.toString().replace('%', '+');
    }

    private static boolean isIdChar(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '.' || c == '_' || c == ':' || c == '/' || c == '@' || c == '+' || c == '-';
    }

    /** Whitespace/comment normalization used for sink statements (exposed for adapters and tests). */
    public static String normalizeStatement(String javaSource) {
        Objects.requireNonNull(javaSource, "javaSource");
        StringBuilder out = new StringBuilder(javaSource.length());
        boolean pendingSpace = false;
        int i = 0;
        int n = javaSource.length();
        while (i < n) {
            char c = javaSource.charAt(i);
            if (isJavaWhitespace(c)) {
                pendingSpace = true;
                i++;
            } else if (c == '/' && i + 1 < n && javaSource.charAt(i + 1) == '/') {
                // Line comment: up to (not including) the line terminator, which then counts as whitespace.
                i += 2;
                while (i < n && javaSource.charAt(i) != '\n' && javaSource.charAt(i) != '\r') {
                    i++;
                }
                pendingSpace = true;
            } else if (c == '/' && i + 1 < n && javaSource.charAt(i + 1) == '*') {
                // Block comment (unterminated runs to end of input); a comment separates tokens like whitespace.
                int end = javaSource.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                pendingSpace = true;
            } else {
                int literalEnd;
                if (c == '"' && javaSource.startsWith("\"\"\"", i)) {
                    literalEnd = endOfTextBlock(javaSource, i + 3);
                } else if (c == '"' || c == '\'') {
                    literalEnd = endOfQuoted(javaSource, i + 1, c);
                } else {
                    literalEnd = i + 1;
                }
                if (pendingSpace && out.length() > 0) {
                    out.append(' ');
                }
                pendingSpace = false;
                out.append(javaSource, i, literalEnd);
                i = literalEnd;
            }
        }
        return out.toString();
    }

    private static boolean isJavaWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\f' || c == '\n' || c == '\r';
    }

    /** End index (exclusive) of a "..." or '...' literal; it also ends at a raw line terminator or end of input. */
    private static int endOfQuoted(String s, int from, char quote) {
        int i = from;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) != '\n' && s.charAt(i + 1) != '\r') {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else if (c == '\n' || c == '\r') {
                return i;
            } else {
                i++;
            }
        }
        return s.length();
    }

    /** End index (exclusive) of a text block whose content starts at {@code from}; unterminated runs to end. */
    private static int endOfTextBlock(String s, int from) {
        int i = from;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                i += 2;
            } else if (s.startsWith("\"\"\"", i)) {
                return i + 3;
            } else {
                i++;
            }
        }
        return s.length();
    }
}
