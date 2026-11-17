package ca.carleton.secpipe.contracts.config;

/** Feature N. Profiles change breadth, never the evidence bar. */
public enum RunProfile {
    /** PR diff-scoped: one hunter wave, one critic pass, one verifier per candidate, coarse subsystem identity. */
    QUICK(1, 1, false),
    /** Nightly / full repo: up to 3 hunter waves, critic after each, record verification on confirmed only. */
    STANDARD(3, 3, false),
    /** Release: per-subsystem units, critic waves until clean (cap 6), record verification on every record. */
    DEEP(6, 6, true);

    private final int maxHunterWaves;
    private final int maxCriticPasses;
    private final boolean verifyAllRecords;

    RunProfile(int maxHunterWaves, int maxCriticPasses, boolean verifyAllRecords) {
        this.maxHunterWaves = maxHunterWaves;
        this.maxCriticPasses = maxCriticPasses;
        this.verifyAllRecords = verifyAllRecords;
    }
    public int maxHunterWaves() { return maxHunterWaves; }
    public int maxCriticPasses() { return maxCriticPasses; }
    public boolean verifyAllRecords() { return verifyAllRecords; }
}
