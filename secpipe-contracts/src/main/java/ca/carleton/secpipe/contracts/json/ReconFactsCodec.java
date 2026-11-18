package ca.carleton.secpipe.contracts.json;

import ca.carleton.secpipe.contracts.json.ContractJson.ContractJsonException;
import ca.carleton.secpipe.contracts.recon.Location;
import ca.carleton.secpipe.contracts.recon.ReconFacts;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Strict codec for {@code recon-facts.json} (schemas/recon-facts.schema.json), WO-CC.2.
 *
 * <ul>
 *   <li>Reading parses with {@link ContractJson#mapper()} (duplicate keys and trailing tokens rejected), then
 *       validates against the schema (unknown properties, nulls, wrong types, bad paths, lines {@code < 1} and
 *       non-{@code CWE-n} keys rejected), then builds the records. Errors are {@link ContractJsonException}s with a
 *       JSON pointer. Nothing is repaired.</li>
 *   <li>{@link #write(ReconFacts)} is byte-stable: {@code entry_surfaces} then {@code sinks_by_family} (both always
 *       written), family keys sorted, locations sorted by (file, line, scope), {@code scope} omitted when absent,
 *       two-space indent, LF line ends, final newline.</li>
 * </ul>
 */
public final class ReconFactsCodec {

    /** Conventional file name under the run's work directory. */
    public static final String FILE_NAME = "recon-facts.json";

    /** Classpath resource of the schema. */
    public static final String SCHEMA_RESOURCE = "/schemas/recon-facts.schema.json";

    private static final String SCHEMA_ID = "https://secpipe.dev/schemas/recon-facts.schema.json";
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Schema SCHEMA = loadSchema();

    private ReconFactsCodec() {
    }

    /** Reads a {@code recon-facts.json} file (UTF-8). */
    public static ReconFacts read(Path file) {
        return read(LedgerCodec.readFile(file));
    }

    /** Reads {@code recon-facts.json} content (one JSON object). */
    public static ReconFacts read(String json) {
        return fromTree(LedgerCodec.parse(json));
    }

    /** Validates {@code node} against the schema and converts it. */
    public static ReconFacts fromTree(JsonNode node) {
        Objects.requireNonNull(node, "node");
        failOnSchemaErrors(SCHEMA.validate(node));
        List<Location> entries = readLocations(node.get("entry_surfaces"), "/entry_surfaces");
        Map<String, List<Location>> sinks = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : node.get("sinks_by_family").properties()) {
            sinks.put(e.getKey(), readLocations(e.getValue(), "/sinks_by_family/" + pointerEscape(e.getKey())));
        }
        try {
            return new ReconFacts(entries, sinks);
        } catch (IllegalArgumentException e) {
            throw new ContractJsonException("", e.getMessage(), e);
        }
    }

    /** The JSON object {@link #write(ReconFacts)} prints. */
    public static ObjectNode toTree(ReconFacts facts) {
        Objects.requireNonNull(facts, "facts");
        ObjectNode root = NODES.objectNode();
        root.set("entry_surfaces", writeLocations(facts.entrySurfaces()));
        ObjectNode sinks = NODES.objectNode();
        // ReconFacts already sorts; sort again so a hand-built tree from any Map stays byte-stable.
        facts.sinksByFamily().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sinks.set(e.getKey(), writeLocations(e.getValue())));
        root.set("sinks_by_family", sinks);
        return root;
    }

    /** Writes facts: pretty, sorted, LF, final newline. Encode the result as UTF-8. */
    public static String write(ReconFacts facts) {
        return LedgerCodec.pretty(toTree(facts));
    }

    /** Writes facts to {@code file} as UTF-8 (see {@link #write(ReconFacts)}). */
    public static void write(Path file, ReconFacts facts) {
        LedgerCodec.writeFile(file, write(facts));
    }

    // ------------------------------------------------------------------------------------------------------------

    private static ArrayNode writeLocations(List<Location> locations) {
        ArrayNode array = NODES.arrayNode();
        locations.stream().sorted(Location.ORDER).forEach(l -> {
            ObjectNode o = array.addObject();
            o.put("file", l.file());
            o.put("line", l.line());
            l.scope().ifPresent(s -> o.put("scope", s));
        });
        return array;
    }

    private static List<Location> readLocations(JsonNode array, String path) {
        List<Location> out = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonNode item = array.get(i);
            JsonNode scope = item.get("scope");
            try {
                out.add(new Location(item.get("file").stringValue(), item.get("line").intValue(),
                        scope == null ? Optional.empty() : Optional.of(scope.stringValue())));
            } catch (IllegalArgumentException e) {
                throw new ContractJsonException(path + "/" + i, e.getMessage(), e);
            }
        }
        return out;
    }

    private static String pointerEscape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    /** Throws for the first schema error in a deterministic order. */
    private static void failOnSchemaErrors(List<Error> errors) {
        if (errors.isEmpty()) {
            return;
        }
        Error first = errors.stream()
                .min(Comparator.comparing((Error e) -> e.getInstanceLocation().toString())
                        .thenComparing(Error::getMessage))
                .orElseThrow();
        String pointer = first.getInstanceLocation().toString();
        if (pointer.equals("/")) {
            pointer = "";
        }
        throw new ContractJsonException(pointer, "violates recon-facts.schema.json: " + first.getMessage());
    }

    private static Schema loadSchema() {
        // Preloaded from the classpath, so validation never touches the network.
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder
                        .schemaRegistryConfig(SchemaRegistryConfig.builder().locale(Locale.ENGLISH).build())
                        .schemas(Map.of(SCHEMA_ID, resource(SCHEMA_RESOURCE))));
        Schema schema = registry.getSchema(SchemaLocation.of(SCHEMA_ID));
        schema.initializeValidators();
        return schema;
    }

    private static String resource(String name) {
        try (InputStream in = ReconFactsCodec.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + name, e);
        }
    }
}
