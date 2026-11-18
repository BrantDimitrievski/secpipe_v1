package ca.carleton.secpipe.core.recon;

import ca.carleton.secpipe.contracts.recon.ReconFacts;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The recon sink catalogue ({@code /recon/sinks.json}): one entry per (family, class, method, tainted-arg index).
 * Method {@code <init>} names a constructor. A {@code static} entry only matches a call that names the class
 * ({@code Files.write(..)}) or is statically imported.
 */
public final class SinkCatalogue {

    /** The five Benchmark CWE families recon covers, in sorted order. */
    public static final List<String> FAMILIES = List.of("CWE-22", "CWE-327", "CWE-328", "CWE-78", "CWE-89");

    static final String CONSTRUCTOR = "<init>";
    private static final String RESOURCE = "/recon/sinks.json";

    /** One catalogue entry. */
    public record Sink(String family, String className, String method, int arg, boolean staticOnly) {

        /** The simple (unqualified) class name. */
        public String simpleClassName() {
            return className.substring(className.lastIndexOf('.') + 1);
        }

        /** The package of the class. */
        public String packageName() {
            int dot = className.lastIndexOf('.');
            return dot < 0 ? "" : className.substring(0, dot);
        }
    }

    private final List<Sink> sinks;
    private final Map<String, List<Sink>> byMethod;

    private SinkCatalogue(List<Sink> sinks) {
        List<Sink> sorted = new ArrayList<>(sinks);
        sorted.sort(Comparator.comparing(Sink::family).thenComparing(Sink::className).thenComparing(Sink::method)
                .thenComparingInt(Sink::arg));
        this.sinks = List.copyOf(sorted);
        Map<String, List<Sink>> index = new TreeMap<>();
        for (Sink s : this.sinks) {
            index.computeIfAbsent(s.method(), k -> new ArrayList<>()).add(s);
        }
        index.replaceAll((k, v) -> List.copyOf(v));
        this.byMethod = Map.copyOf(index);
    }

    /** Loads the bundled catalogue. */
    public static SinkCatalogue load() {
        try (InputStream in = SinkCatalogue.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing bundled resource " + RESOURCE);
            }
            JsonNode root = JsonMapper.builder().build().readTree(in);
            List<Sink> out = new ArrayList<>();
            for (JsonNode n : root.path("sinks")) {
                Sink s = new Sink(n.path("family").asString(), n.path("class").asString(), n.path("method").asString(),
                        n.path("arg").asInt(-1), n.path("static").asBoolean(false));
                if (!ReconFacts.FAMILY.matcher(s.family()).matches() || s.className().isEmpty()
                        || s.method().isEmpty() || s.arg() < 0) {
                    throw new IllegalStateException("invalid sink catalogue entry: " + n);
                }
                out.add(s);
            }
            return new SinkCatalogue(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** All entries, sorted by (family, class, method, arg). */
    public List<Sink> sinks() {
        return sinks;
    }

    /** Entries for a method name ({@code <init>} for constructors). */
    public List<Sink> forMethod(String method) {
        return byMethod.getOrDefault(method, List.of());
    }

    /** Families present in the catalogue, sorted. */
    public Set<String> families() {
        Set<String> out = new TreeSet<>();
        sinks.forEach(s -> out.add(s.family()));
        return Set.copyOf(out);
    }
}
