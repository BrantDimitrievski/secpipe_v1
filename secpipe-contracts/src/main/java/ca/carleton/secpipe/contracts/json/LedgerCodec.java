package ca.carleton.secpipe.contracts.json;

import ca.carleton.secpipe.contracts.json.ContractJson.ContractJsonException;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit.Attempt;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit.CanonicalRefs;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit.ExcludedBlock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Codec for {@code coverage-ledger.json} (schemas/ledger.schema.json): a JSON array of {@link LedgerUnit}.
 *
 * <ul>
 *   <li>Reading is strict: unknown, missing or mistyped properties, duplicate keys and trailing tokens are a
 *       {@link ContractJsonException} with a JSON pointer. {@code cwe} and {@code agent_id} must be present and
 *       may be {@code null}; {@code lifecycle}, {@code archived_reason} and {@code settled_by} are optional.</li>
 *   <li>{@link #write(List)} is byte-stable: units sorted by {@code coverage_id}, properties in schema order,
 *       two-space indent, LF line ends, final newline. Required arrays are written even when empty.</li>
 *   <li>{@link #module()} registers the codec with {@link ContractJson#mapper(JacksonModule...)}, so
 *       {@code ContractJson.mapper(LedgerCodec.module()).readValue(json, LedgerUnit.class)} uses this codec.</li>
 * </ul>
 * State invariants (status vs. attempts, agent ids, ...) are {@code LedgerValidator}'s job (WO-V.2), not this
 * codec's.
 */
public final class LedgerCodec {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private LedgerCodec() {
    }

    /** A Jackson module with a serializer and deserializer for {@link LedgerUnit}. */
    public static JacksonModule module() {
        SimpleModule module = new SimpleModule("secpipe-ledger");
        adapt(module, LedgerUnit.class, LedgerCodec::toTree, LedgerCodec::fromTree);
        return module;
    }

    /** Reads a {@code coverage-ledger.json} file (UTF-8). */
    public static List<LedgerUnit> read(Path file) {
        return read(readFile(file));
    }

    /** Reads {@code coverage-ledger.json} content (a JSON array of units). */
    public static List<LedgerUnit> read(String json) {
        JsonNode root = parse(json);
        if (!root.isArray()) {
            throw new ContractJsonException("", "expected a JSON array at the top level but found " + describe(root));
        }
        List<LedgerUnit> units = new ArrayList<>(root.size());
        for (int i = 0; i < root.size(); i++) {
            JsonNode item = root.get(i);
            units.add(readUnit(item, "/" + i));
        }
        return List.copyOf(units);
    }

    /** Writes the ledger: pretty, sorted by {@code coverage_id}, LF line ends, final newline. Encode as UTF-8. */
    public static String write(List<LedgerUnit> units) {
        Objects.requireNonNull(units, "units");
        List<ObjectNode> trees = new ArrayList<>(units.size());
        for (int i = 0; i < units.size(); i++) {
            LedgerUnit unit = units.get(i);
            if (unit == null) {
                throw new ContractJsonException("/" + i, "list element is null");
            }
            trees.add(writeUnit(unit, "/" + i));
        }
        // The compact text breaks coverage_id ties, so equal inputs give equal bytes in any order.
        trees.sort(Comparator.comparing((ObjectNode n) -> n.get("coverage_id").stringValue())
                .thenComparing(JsonNode::toString));
        ArrayNode array = NODES.arrayNode();
        trees.forEach(array::add);
        return pretty(array);
    }

    /** Writes the ledger to {@code file} as UTF-8 (see {@link #write(List)}). */
    public static void write(Path file, List<LedgerUnit> units) {
        writeFile(file, write(units));
    }

    /** The JSON object for one unit, exactly as {@link #write(List)} prints it. */
    public static ObjectNode toTree(LedgerUnit unit) {
        return writeUnit(Objects.requireNonNull(unit, "unit"), "");
    }

    /** Reads one unit from a JSON object. */
    public static LedgerUnit fromTree(JsonNode node) {
        return readUnit(node, "");
    }

    // ------------------------------------------------------------------------------------------------------------
    // LedgerUnit
    // ------------------------------------------------------------------------------------------------------------

    private static ObjectNode writeUnit(LedgerUnit u, String path) {
        Out out = new Out(path);
        out.str("coverage_id", u.coverageId());
        out.obj("canonical_refs", u.canonicalRefs(), LedgerCodec::writeRefs);
        out.str("surface", u.surface());
        out.str("boundary", u.boundary());
        out.str("subsystem", u.subsystem());
        out.str("attack_class", u.attackClass());
        out.nullableStr("cwe", u.cwe());
        out.strList("starting_paths", u.startingPaths(), true);
        out.str("ordinary_attack_class_block", u.ordinaryAttackClassBlock());
        out.strList("selected_companion_blocks", u.selectedCompanionBlocks(), true);
        out.list("excluded_blocks", u.excludedBlocks(), LedgerCodec::writeExcluded, true);
        out.enumValue("prior_status", u.priorStatus());
        out.list("attempts", u.attempts(), LedgerCodec::writeAttempt, true);
        out.integer("wave", u.wave());
        out.enumValue("status", u.status());
        out.nullableStr("agent_id", u.agentId());
        out.strList("reviewed_paths", u.reviewedPaths(), true);
        out.strList("local_checks", u.localChecks(), true);
        out.strList("result_fingerprints", u.resultFingerprints(), true);
        out.strList("unresolved", u.unresolved(), true);
        out.strList("settled_by", u.settledBy(), false);
        return out.node();
    }

    private static LedgerUnit readUnit(JsonNode node, String path) {
        In in = new In(node, path);
        LedgerUnit unit = new LedgerUnit(
                in.str("coverage_id"),
                in.obj("canonical_refs", LedgerCodec::readRefs),
                in.str("surface"),
                in.str("boundary"),
                in.str("subsystem"),
                in.str("attack_class"),
                in.nullableStr("cwe"),
                in.strList("starting_paths", true),
                in.str("ordinary_attack_class_block"),
                in.strList("selected_companion_blocks", true),
                in.list("excluded_blocks", LedgerCodec::readExcluded, true),
                in.enumValue("prior_status", LedgerUnit.PriorStatus.class),
                in.list("attempts", LedgerCodec::readAttempt, true),
                in.integer("wave"),
                in.enumValue("status", LedgerUnit.Status.class),
                in.nullableStr("agent_id"),
                in.strList("reviewed_paths", true),
                in.strList("local_checks", true),
                in.strList("result_fingerprints", true),
                in.strList("unresolved", true),
                in.strList("settled_by", false));
        in.done();
        return unit;
    }

    private static ObjectNode writeRefs(CanonicalRefs r, String path) {
        Out out = new Out(path);
        out.str("surface", r.surface());
        out.str("boundary", r.boundary());
        out.str("subsystem", r.subsystem());
        out.str("attack_class", r.attackClass());
        out.optStr("lifecycle", r.lifecycle());
        return out.node();
    }

    private static CanonicalRefs readRefs(JsonNode node, String path) {
        In in = new In(node, path);
        CanonicalRefs refs = new CanonicalRefs(in.str("surface"), in.str("boundary"), in.str("subsystem"),
                in.str("attack_class"), in.optStr("lifecycle"));
        in.done();
        return refs;
    }

    private static ObjectNode writeExcluded(ExcludedBlock b, String path) {
        Out out = new Out(path);
        out.str("block", b.block());
        out.str("reason", b.reason());
        return out.node();
    }

    private static ExcludedBlock readExcluded(JsonNode node, String path) {
        In in = new In(node, path);
        ExcludedBlock block = new ExcludedBlock(in.str("block"), in.str("reason"));
        in.done();
        return block;
    }

    private static ObjectNode writeAttempt(Attempt a, String path) {
        Out out = new Out(path);
        out.str("agent_id", a.agentId());
        out.integer("wave", a.wave());
        out.enumValue("status", a.status());
        out.strList("reviewed_paths", a.reviewedPaths(), true);
        out.strList("local_checks", a.localChecks(), true);
        out.strList("result_fingerprints", a.resultFingerprints(), true);
        out.strList("unresolved", a.unresolved(), true);
        out.optStr("archived_reason", a.archivedReason());
        return out.node();
    }

    private static Attempt readAttempt(JsonNode node, String path) {
        In in = new In(node, path);
        Attempt attempt = new Attempt(in.str("agent_id"), in.integer("wave"),
                in.enumValue("status", Attempt.AttemptStatus.class), in.strList("reviewed_paths", true),
                in.strList("local_checks", true), in.strList("result_fingerprints", true),
                in.strList("unresolved", true), in.optStr("archived_reason"));
        in.done();
        return attempt;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Shared with RunMetadataCodec
    // ------------------------------------------------------------------------------------------------------------

    /** Registers a tree-based serializer and deserializer for {@code type}, keeping JSON pointers in errors. */
    static <T> void adapt(SimpleModule module, Class<T> type, Function<T, ObjectNode> writer,
                          Function<JsonNode, T> reader) {
        module.addSerializer(type, new ValueSerializer<T>() {
            @Override
            public void serialize(T value, JsonGenerator gen, SerializationContext ctxt) {
                try {
                    gen.writeTree(writer.apply(value));
                } catch (ContractJsonException e) {
                    throw DatabindException.from(gen, e.getMessage(), e);
                }
            }
        });
        module.addDeserializer(type, new ValueDeserializer<T>() {
            @Override
            public T deserialize(JsonParser p, DeserializationContext ctxt) {
                JsonNode node = ctxt.readTree(p);
                try {
                    return reader.apply(node);
                } catch (ContractJsonException e) {
                    throw DatabindException.from(p, e.getMessage(), e);
                }
            }
        });
    }

    /** Parses with the strict contract mapper (duplicate keys and trailing tokens rejected). */
    static JsonNode parse(String json) {
        Objects.requireNonNull(json, "json");
        JsonNode root;
        try {
            root = ContractJson.mapper().readTree(json);
        } catch (JacksonException e) {
            throw new ContractJsonException("", "malformed JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || root.isMissingNode()) {
            throw new ContractJsonException("", "empty document");
        }
        return root;
    }

    /** Two-space indent, LF line ends, final newline. */
    static String pretty(JsonNode tree) {
        ObjectWriter writer = ContractJson.mapper().writer().with(ContractJson.prettyPrinter());
        return writer.writeValueAsString(tree) + "\n";
    }

    static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    static void writeFile(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    static String enumName(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    static String describe(JsonNode node) {
        if (node.isObject()) {
            return "an object";
        }
        if (node.isArray()) {
            return "an array";
        }
        if (node.isString()) {
            return "a string";
        }
        if (node.isNumber()) {
            return "a number";
        }
        if (node.isBoolean()) {
            return "a boolean";
        }
        return node.isNull() ? "null" : node.getNodeType().toString().toLowerCase(Locale.ROOT);
    }

    /** Builds one JSON object in insertion order; rejects {@code null} components with their JSON pointer. */
    static final class Out {
        private final ObjectNode node = NODES.objectNode();
        private final String path;

        Out(String path) {
            this.path = path;
        }

        ObjectNode node() {
            return node;
        }

        private String at(String key) {
            return path + "/" + key;
        }

        private <T> T required(String key, T value) {
            if (value == null) {
                throw new ContractJsonException(at(key), "required property '" + key + "' is null");
            }
            return value;
        }

        void str(String key, String value) {
            node.put(key, required(key, value));
        }

        void optStr(String key, Optional<String> value) {
            required(key, value).ifPresent(v -> node.put(key, v));
        }

        /** Writes the value, or JSON {@code null} when empty (the property itself is always present). */
        void nullableStr(String key, Optional<String> value) {
            required(key, value).ifPresentOrElse(v -> node.put(key, v), () -> node.putNull(key));
        }

        void integer(String key, int value) {
            node.put(key, value);
        }

        void longValue(String key, long value) {
            node.put(key, value);
        }

        void optDouble(String key, Optional<Double> value) {
            required(key, value).ifPresent(v -> {
                if (!Double.isFinite(v)) {
                    throw new ContractJsonException(at(key), "number must be finite but was " + v);
                }
                node.put(key, v.doubleValue());
            });
        }

        void bool(String key, boolean value) {
            node.put(key, value);
        }

        void instant(String key, Instant value) {
            node.put(key, required(key, value).toString());
        }

        void enumValue(String key, Enum<?> value) {
            node.put(key, enumName(required(key, value)));
        }

        /** Writes an enum through a custom name function (e.g. {@code ProviderMode::cliName}). */
        <E extends Enum<E>> void enumValue(String key, E value, Function<E, String> name) {
            node.put(key, name.apply(required(key, value)));
        }

        <T> void obj(String key, T value, BiFunction<T, String, ObjectNode> writer) {
            node.set(key, writer.apply(required(key, value), at(key)));
        }

        <T> void optObj(String key, Optional<T> value, BiFunction<T, String, ObjectNode> writer) {
            required(key, value).ifPresent(v -> node.set(key, writer.apply(v, at(key))));
        }

        <T> void list(String key, List<T> values, BiFunction<T, String, ObjectNode> writer, boolean always) {
            required(key, values);
            if (values.isEmpty() && !always) {
                return;
            }
            ArrayNode array = node.putArray(key);
            for (int i = 0; i < values.size(); i++) {
                String itemPath = at(key) + "/" + i;
                T item = values.get(i);
                if (item == null) {
                    throw new ContractJsonException(itemPath, "list element is null");
                }
                array.add(writer.apply(item, itemPath));
            }
        }

        void strList(String key, List<String> values, boolean always) {
            required(key, values);
            if (values.isEmpty() && !always) {
                return;
            }
            ArrayNode array = node.putArray(key);
            for (int i = 0; i < values.size(); i++) {
                String item = values.get(i);
                if (item == null) {
                    throw new ContractJsonException(at(key) + "/" + i, "list element is null");
                }
                array.add(item);
            }
        }

        /**
         * Writes a map of integers as an object whose keys are sorted (the contract mapper does not order map
         * entries, so canonical output depends on this).
         */
        void numberMap(String key, Map<String, ? extends Number> values, boolean always) {
            required(key, values);
            if (values.isEmpty() && !always) {
                return;
            }
            ObjectNode object = node.putObject(key);
            TreeMap<String, Number> sorted = new TreeMap<>();
            values.forEach((k, v) -> {
                if (k == null) {
                    throw new ContractJsonException(at(key), "map key is null");
                }
                if (v == null) {
                    throw new ContractJsonException(at(key) + "/" + k, "map value is null");
                }
                sorted.put(k, v);
            });
            sorted.forEach((k, v) -> object.put(k, v.longValue()));
        }
    }

    /** Reads one JSON object strictly; {@link #done()} rejects properties no accessor asked for. */
    static final class In {
        private final JsonNode node;
        private final String path;
        private final Set<String> seen = new TreeSet<>();

        In(JsonNode node, String path) {
            if (node == null || !node.isObject()) {
                throw new ContractJsonException(path,
                        "expected an object but found " + (node == null ? "nothing" : describe(node)));
            }
            this.node = node;
            this.path = path;
        }

        private String at(String key) {
            return path + "/" + key;
        }

        /** The value of {@code key}, or empty if absent; JSON {@code null} is an error. */
        private Optional<JsonNode> value(String key) {
            seen.add(key);
            JsonNode v = node.get(key);
            if (v == null) {
                return Optional.empty();
            }
            if (v.isNull()) {
                throw new ContractJsonException(at(key), "property '" + key + "' must not be null");
            }
            return Optional.of(v);
        }

        private JsonNode requiredValue(String key) {
            return value(key).orElseThrow(
                    () -> new ContractJsonException(at(key), "missing required property '" + key + "'"));
        }

        private static String asText(JsonNode v, String at) {
            if (!v.isString()) {
                throw new ContractJsonException(at, "expected a string but found " + describe(v));
            }
            return v.stringValue();
        }

        private static int asInt(JsonNode v, String at) {
            if (!v.isIntegralNumber() || !v.canConvertToInt()) {
                throw new ContractJsonException(at, "expected a 32-bit integer but found " + describe(v));
            }
            return v.intValue();
        }

        private static long asLong(JsonNode v, String at) {
            if (!v.isIntegralNumber() || !v.canConvertToLong()) {
                throw new ContractJsonException(at, "expected a 64-bit integer but found " + describe(v));
            }
            return v.longValue();
        }

        String str(String key) {
            return asText(requiredValue(key), at(key));
        }

        Optional<String> optStr(String key) {
            return value(key).map(v -> asText(v, at(key)));
        }

        /** A property that must be present but may be JSON {@code null} (read as empty). */
        Optional<String> nullableStr(String key) {
            seen.add(key);
            JsonNode v = node.get(key);
            if (v == null) {
                throw new ContractJsonException(at(key), "missing required property '" + key + "'");
            }
            return v.isNull() ? Optional.empty() : Optional.of(asText(v, at(key)));
        }

        int integer(String key) {
            return asInt(requiredValue(key), at(key));
        }

        /** An optional integer property; absent reads as {@code 0}. */
        int intOrZero(String key) {
            return value(key).map(v -> asInt(v, at(key))).orElse(0);
        }

        long longValue(String key) {
            return asLong(requiredValue(key), at(key));
        }

        Optional<Double> optDouble(String key) {
            return value(key).map(v -> {
                if (!v.isNumber()) {
                    throw new ContractJsonException(at(key), "expected a number but found " + describe(v));
                }
                return v.doubleValue();
            });
        }

        boolean bool(String key) {
            JsonNode v = requiredValue(key);
            if (!v.isBoolean()) {
                throw new ContractJsonException(at(key), "expected a boolean but found " + describe(v));
            }
            return v.booleanValue();
        }

        Instant instant(String key) {
            String text = str(key);
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException e) {
                throw new ContractJsonException(at(key), "expected an RFC 3339 date-time but found '" + text + "'",
                        e);
            }
        }

        <E extends Enum<E>> E enumValue(String key, Class<E> type) {
            return enumValue(key, type, LedgerCodec::enumName);
        }

        /** Reads an enum whose JSON names come from {@code name} (e.g. {@code ProviderMode::cliName}). */
        <E extends Enum<E>> E enumValue(String key, Class<E> type, Function<E, String> name) {
            String text = str(key);
            List<String> allowed = new ArrayList<>();
            for (E constant : type.getEnumConstants()) {
                if (name.apply(constant).equals(text)) {
                    return constant;
                }
                allowed.add(name.apply(constant));
            }
            throw new ContractJsonException(at(key), "unknown value '" + text + "'; expected one of " + allowed);
        }

        <T> T obj(String key, BiFunction<JsonNode, String, T> reader) {
            return reader.apply(requiredValue(key), at(key));
        }

        <T> Optional<T> optObj(String key, BiFunction<JsonNode, String, T> reader) {
            return value(key).map(v -> reader.apply(v, at(key)));
        }

        private <T> List<T> elements(String key, boolean required, BiFunction<JsonNode, String, T> reader) {
            Optional<JsonNode> v = required ? Optional.of(requiredValue(key)) : value(key);
            if (v.isEmpty()) {
                return List.of();
            }
            JsonNode array = v.get();
            if (!array.isArray()) {
                throw new ContractJsonException(at(key), "expected an array but found " + describe(array));
            }
            List<T> out = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                String itemPath = at(key) + "/" + i;
                JsonNode item = array.get(i);
                if (item.isNull()) {
                    throw new ContractJsonException(itemPath, "list element must not be null");
                }
                out.add(reader.apply(item, itemPath));
            }
            return List.copyOf(out);
        }

        <T> List<T> list(String key, BiFunction<JsonNode, String, T> reader, boolean required) {
            return elements(key, required, reader);
        }

        List<String> strList(String key, boolean required) {
            return elements(key, required, In::asText);
        }

        private <V> Map<String, V> map(String key, boolean required, BiFunction<JsonNode, String, V> reader) {
            Optional<JsonNode> v = required ? Optional.of(requiredValue(key)) : value(key);
            if (v.isEmpty()) {
                return Map.of();
            }
            JsonNode object = v.get();
            if (!object.isObject()) {
                throw new ContractJsonException(at(key), "expected an object but found " + describe(object));
            }
            TreeMap<String, V> out = new TreeMap<>();
            for (String name : object.propertyNames()) {
                JsonNode item = object.get(name);
                String itemPath = at(key) + "/" + name;
                if (item.isNull()) {
                    throw new ContractJsonException(itemPath, "map value must not be null");
                }
                out.put(name, reader.apply(item, itemPath));
            }
            return Collections.unmodifiableMap(out);
        }

        /** An object of 32-bit integers; the result iterates in key order. */
        Map<String, Integer> intMap(String key, boolean required) {
            return map(key, required, In::asInt);
        }

        /** An object of 64-bit integers; the result iterates in key order. */
        Map<String, Long> longMap(String key, boolean required) {
            return map(key, required, In::asLong);
        }

        void done() {
            Set<String> unknown = new TreeSet<>(node.propertyNames());
            unknown.removeAll(seen);
            if (!unknown.isEmpty()) {
                String first = unknown.iterator().next();
                throw new ContractJsonException(at(first), "unknown property '" + first + "'");
            }
        }
    }
}
