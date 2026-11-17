package ca.carleton.secpipe.contracts.ids;

import java.util.Map;
import java.util.Set;

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

    /** Family root for a CWE id; unknown ids are their own family. */
    public static String cweFamily(String cwe) {
        throw new UnsupportedOperationException("implemented by WO-C.5");
    }

    /**
     * code:CWE-&lt;family&gt;:&lt;24 hex&gt; where hex = SHA-256 over canonical JSON (sorted keys, no whitespace, UTF-8)
     * {"cwe_family":..., "file":..., "occurrence":n, "scope":..., "sink":...}. sink = sink call source text with comments
     * stripped and whitespace runs collapsed to one space; file repo-relative with '/'.
     */
    public static String code(String cwe, String repoRelativeFile, String scope, String sinkStatement, int occurrence) {
        throw new UnsupportedOperationException("implemented by WO-C.5");
    }

    /** dep:&lt;purl&gt;:&lt;vulnId&gt;, vulnId preference CVE alias &gt; GHSA &gt; OSV id; chars outside PATTERN -> %HH then '%'->'+'. */
    public static String dependency(String purl, java.util.List<String> vulnIdsInPreferenceOrder) {
        throw new UnsupportedOperationException("implemented by WO-C.5");
    }

    /** Whitespace/comment normalization used for sink statements (exposed for adapters and tests). */
    public static String normalizeStatement(String javaSource) {
        throw new UnsupportedOperationException("implemented by WO-C.5");
    }
}
