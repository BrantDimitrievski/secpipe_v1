package ca.carleton.secpipe.agents.runner;

import ca.carleton.secpipe.contracts.spi.SourceIndex;

import java.util.Objects;
import java.util.Set;

/**
 * What an agent's output may cite: files and lines in {@code sources}, and the fingerprints that were in the agent's
 * input ({@code knownFingerprints}).
 */
public record GroundingContext(SourceIndex sources, Set<String> knownFingerprints) {

    public GroundingContext {
        Objects.requireNonNull(sources, "sources");
        knownFingerprints = Set.copyOf(knownFingerprints);
    }

    /** A context for agents whose input carries no fingerprints (hunter, critic). */
    public static GroundingContext of(SourceIndex sources) {
        return new GroundingContext(sources, Set.of());
    }

    public static GroundingContext of(SourceIndex sources, Set<String> knownFingerprints) {
        return new GroundingContext(sources, knownFingerprints);
    }
}
