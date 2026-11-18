package ca.carleton.secpipe.adapter.builtin;

import ca.carleton.secpipe.contracts.finding.Finding.Level3;

/** How strongly an expression is believed to carry attacker-controlled data. */
public enum TaintLevel {
    /** Constant or derived only from trusted values. */
    NONE,
    /** Comes from a method parameter; callers may pass untrusted data. */
    PARAMETER,
    /** Comes directly from HTTP request input (getParameter, @RequestParam, ...). */
    REQUEST;

    public static TaintLevel max(TaintLevel a, TaintLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    public Level3 confidence() {
        return switch (this) {
            case REQUEST -> Level3.HIGH;
            case PARAMETER -> Level3.MEDIUM;
            case NONE -> Level3.LOW;
        };
    }
}
