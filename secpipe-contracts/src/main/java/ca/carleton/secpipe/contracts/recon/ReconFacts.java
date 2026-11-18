package ca.carleton.secpipe.contracts.recon;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What recon found (WO-CC.2), persisted as {@code <work>/recon-facts.json} by {@code ReconFactsCodec}.
 *
 * <p>{@code entrySurfaces}: where untrusted input enters (servlet/Spring handlers). {@code sinksByFamily}: sink call
 * sites keyed by CWE family root id ({@code CWE-89}). The constructor makes immutable copies: lists sorted by
 * {@link Location#ORDER} without duplicates, map keys sorted, so equal facts give byte-identical JSON.
 */
public record ReconFacts(List<Location> entrySurfaces, Map<String, List<Location>> sinksByFamily) {

    /** A CWE family root id: {@code CWE-} followed by a number without leading zero. */
    public static final Pattern FAMILY = Pattern.compile("CWE-[1-9][0-9]*");

    public ReconFacts {
        entrySurfaces = sorted(Objects.requireNonNull(entrySurfaces, "entrySurfaces"), "entrySurfaces");
        Objects.requireNonNull(sinksByFamily, "sinksByFamily");
        TreeMap<String, List<Location>> sinks = new TreeMap<>();
        sinksByFamily.forEach((family, locations) -> {
            if (family == null || !FAMILY.matcher(family).matches()) {
                throw new IllegalArgumentException("sinksByFamily key must look like CWE-89 but was " + family);
            }
            if (locations == null) {
                throw new NullPointerException("sinksByFamily[" + family + "]");
            }
            sinks.put(family, sorted(locations, "sinksByFamily[" + family + "]"));
        });
        sinksByFamily = Collections.unmodifiableSortedMap(sinks);
    }

    /** Facts with no entry surfaces and no sinks. */
    public static ReconFacts empty() {
        return new ReconFacts(List.of(), Map.of());
    }

    /** Sinks for {@code family} (e.g. {@code CWE-89}); empty if none were found. */
    public List<Location> sinks(String family) {
        return sinksByFamily.getOrDefault(family, List.of());
    }

    private static List<Location> sorted(List<Location> locations, String what) {
        TreeSet<Location> set = new TreeSet<>(Location.ORDER);
        for (Location location : locations) {
            set.add(Objects.requireNonNull(location, what + " contains null"));
        }
        return List.copyOf(set);
    }
}
