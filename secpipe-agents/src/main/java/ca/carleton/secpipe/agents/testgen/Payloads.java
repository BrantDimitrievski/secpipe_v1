package ca.carleton.secpipe.agents.testgen;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Payload hints per weakness family, from the bundled {@code testgen/payloads.json} (ported from MVP
 * {@code test-templates.json}, payload lists only).
 */
public final class Payloads {

    static final String RESOURCE = "testgen/payloads.json";

    private final Map<String, List<String>> byFamily;

    private Payloads(Map<String, List<String>> byFamily) {
        this.byFamily = Map.copyOf(byFamily);
    }

    /** The bundled catalogue. */
    public static Payloads bundled() {
        try (InputStream in = Payloads.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing bundled resource " + RESOURCE);
            }
            JsonNode families = JsonMapper.builder().build().readTree(in).path("families");
            Map<String, List<String>> out = new TreeMap<>();
            for (Map.Entry<String, JsonNode> e : families.properties()) {
                List<String> list = new ArrayList<>();
                e.getValue().path("payloads").forEach(p -> list.add(p.asString()));
                out.put(e.getKey(), List.copyOf(list));
            }
            return new Payloads(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Payloads for {@code family} ({@code CWE-<n>}); empty if the catalogue has none. */
    public List<String> forFamily(String family) {
        return byFamily.getOrDefault(family, List.of());
    }
}
