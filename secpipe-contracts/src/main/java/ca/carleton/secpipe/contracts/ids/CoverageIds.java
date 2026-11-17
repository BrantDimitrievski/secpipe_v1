package ca.carleton.secpipe.contracts.ids;

import ca.carleton.secpipe.contracts.ledger.LedgerUnit;

/** ADR-06 ledger ids. Body implemented by WO-C.2; output stability is contract. */
public final class CoverageIds {
    private CoverageIds() { }

    /**
     * cov:&lt;first 32 hex of SHA-256(canonical JSON {"attack_class","boundary","lifecycle"?,"subsystem","surface"})&gt;.
     * Each ref must be NFC, trimmed, non-empty, no control characters (else IllegalArgumentException).
     */
    public static String of(LedgerUnit.CanonicalRefs refs) {
        throw new UnsupportedOperationException("implemented by WO-C.2");
    }

    /** The fixed subsystem ref for the quick profile (Cloudflare convention). */
    public static final String QUICK_SUBSYSTEM = "profile/quick/all-in-scope-subsystems";

    /** Agent ids: &lt;role&gt;-&lt;8 hex&gt;, fresh per invocation. */
    public static String newAgentId(String role, java.util.random.RandomGenerator rng) {
        throw new UnsupportedOperationException("implemented by WO-C.2");
    }
}
