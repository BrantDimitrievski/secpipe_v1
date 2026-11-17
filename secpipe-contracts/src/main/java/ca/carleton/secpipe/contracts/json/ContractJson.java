package ca.carleton.secpipe.contracts.json;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.PrettyPrinter;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.EnumNamingStrategies;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The single JSON codec for SecPipe contracts. Everyone reads and writes {@code findings.json} (and candidate
 * lists) only through this class.
 *
 * <ul>
 *   <li>{@link #mapper()} is a strict, shared Jackson 3 mapper: snake_case properties, snake_case lower-case enums,
 *       absent {@code Optional} and empty collections omitted, unknown properties, duplicate keys and trailing
 *       tokens rejected, LF pretty printing. It has adapters for {@link Finding}, {@link Candidate} and every
 *       nested record, so {@code mapper().readValue(json, Finding.class)} uses {@link FindingCodec}.</li>
 *   <li>{@link #writeFindings(List)} is byte-stable: findings sorted by fingerprint, two-space indent, LF line
 *       ends, final newline. Golden-file tests may compare its output byte for byte.</li>
 *   <li>Every read error is a {@link ContractJsonException} carrying a JSON pointer.</li>
 * </ul>
 */
public final class ContractJson {

    private static final PrettyPrinter PRETTY = prettyPrinter();
    private static final JsonMapper MAPPER = build(List.of());
    private static final ObjectWriter PRETTY_WRITER = MAPPER.writer().with(PRETTY);

    private ContractJson() {
    }

    /** The shared, immutable contract mapper. */
    public static JsonMapper mapper() {
        return MAPPER;
    }

    /**
     * A new mapper with the contract configuration plus {@code extra} modules (e.g. for ledger or run-metadata
     * codecs). With no arguments it returns the shared mapper.
     */
    public static JsonMapper mapper(JacksonModule... extra) {
        if (extra.length == 0) {
            return MAPPER;
        }
        return build(List.of(extra));
    }

    /** The pretty printer used by {@link #writeFindings(List)}: two-space indent, LF, {@code "name": value}. */
    public static PrettyPrinter prettyPrinter() {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        Separators separators = new Separators(Separators.DEFAULT_ROOT_VALUE_SEPARATOR,
                ':', Separators.Spacing.AFTER, ',', Separators.Spacing.NONE, "",
                ',', Separators.Spacing.NONE, "");
        return new DefaultPrettyPrinter(separators).withObjectIndenter(indenter).withArrayIndenter(indenter);
    }

    // ------------------------------------------------------------------------------------------------------------
    // findings.json
    // ------------------------------------------------------------------------------------------------------------

    /** Reads a {@code findings.json} file (UTF-8). */
    public static List<Finding> readFindings(Path file) {
        return readFindings(readFile(file));
    }

    /** Reads {@code findings.json} content (a JSON array of findings). */
    public static List<Finding> readFindings(String json) {
        return readArray(json, FindingCodec::findingFromTree);
    }

    /** Converts findings to the exact JSON array {@link #writeFindings(List)} prints (same order). */
    public static ArrayNode findingsTree(List<Finding> findings) {
        Objects.requireNonNull(findings, "findings");
        List<JsonNode> trees = new ArrayList<>(findings.size());
        for (int i = 0; i < findings.size(); i++) {
            Finding finding = findings.get(i);
            trees.add(rebase(i, () -> FindingCodec.toTree(finding)));
        }
        // Sort by fingerprint; the compact JSON text breaks ties so equal inputs give equal bytes in any order.
        List<JsonNode> sorted = new ArrayList<>(trees);
        sorted.sort(Comparator.comparing((JsonNode n) -> n.get("fingerprint").stringValue())
                .thenComparing(JsonNode::toString));
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        sorted.forEach(array::add);
        return array;
    }

    /** Writes findings: pretty, sorted by fingerprint, LF line ends, final newline. Encode the result as UTF-8. */
    public static String writeFindings(List<Finding> findings) {
        return pretty(findingsTree(findings));
    }

    /** Writes findings to {@code file} as UTF-8 (see {@link #writeFindings(List)}). */
    public static void writeFindings(Path file, List<Finding> findings) {
        writeFile(file, writeFindings(findings));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Candidates
    // ------------------------------------------------------------------------------------------------------------

    /** Reads a JSON array of candidates from a file (UTF-8). */
    public static List<Candidate> readCandidates(Path file) {
        return readCandidates(readFile(file));
    }

    /** Reads a JSON array of candidates. */
    public static List<Candidate> readCandidates(String json) {
        return readArray(json, FindingCodec::candidateFromTree);
    }

    /**
     * Writes candidates in the given order (candidates may lack a fingerprint, so ordering is the caller's job):
     * pretty, LF line ends, final newline.
     */
    public static String writeCandidates(List<Candidate> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            array.add(rebase(i, () -> FindingCodec.toTree(candidate)));
        }
        return pretty(array);
    }

    /** Writes candidates to {@code file} as UTF-8 (see {@link #writeCandidates(List)}). */
    public static void writeCandidates(Path file, List<Candidate> candidates) {
        writeFile(file, writeCandidates(candidates));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------------------------

    private static String pretty(JsonNode tree) {
        return PRETTY_WRITER.writeValueAsString(tree) + "\n";
    }

    private static <T> List<T> readArray(String json, Function<JsonNode, T> reader) {
        Objects.requireNonNull(json, "json");
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new ContractJsonException("", "malformed JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || root.isMissingNode() || !root.isArray()) {
            throw new ContractJsonException("", "expected a JSON array at the top level");
        }
        List<T> out = new ArrayList<>(root.size());
        for (int i = 0; i < root.size(); i++) {
            JsonNode item = root.get(i);
            out.add(rebase(i, () -> reader.apply(item)));
        }
        return List.copyOf(out);
    }

    /** Runs {@code step} for array element {@code index}, prefixing the error pointer with {@code /index}. */
    private static <T> T rebase(int index, Supplier<T> step) {
        try {
            return step.get();
        } catch (ContractJsonException e) {
            throw new ContractJsonException("/" + index + e.path(), e.problem(), e);
        }
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static void writeFile(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    private static JsonMapper build(List<JacksonModule> extra) {
        JsonInclude.Value omitEmpty = JsonInclude.Value.construct(JsonInclude.Include.NON_EMPTY,
                JsonInclude.Include.NON_EMPTY);
        JsonMapper.Builder builder = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enumNamingStrategy(EnumNamingStrategies.SNAKE_CASE)
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_ABSENT))
                .withConfigOverride(List.class, o -> o.setInclude(omitEmpty))
                .withConfigOverride(Set.class, o -> o.setInclude(omitEmpty))
                .withConfigOverride(Collection.class, o -> o.setInclude(omitEmpty))
                .withConfigOverride(Map.class, o -> o.setInclude(omitEmpty))
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .defaultPrettyPrinter(PRETTY)
                .addModule(contractModule());
        extra.forEach(builder::addModule);
        return builder.build();
    }

    private static SimpleModule contractModule() {
        SimpleModule module = new SimpleModule("secpipe-contracts");
        for (Class<?> type : FindingCodec.supportedTypes()) {
            register(module, type);
        }
        return module;
    }

    private static <T> void register(SimpleModule module, Class<T> type) {
        module.addSerializer(type, new ValueSerializer<T>() {
            @Override
            public void serialize(T value, JsonGenerator gen, SerializationContext ctxt) {
                try {
                    gen.writeTree(FindingCodec.write(value));
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
                    return FindingCodec.read(node, type);
                } catch (ContractJsonException e) {
                    throw DatabindException.from(p, e.getMessage(), e);
                }
            }
        });
    }

    /**
     * A contract JSON document does not match the contract shape (or is not JSON). {@link #path()} is a JSON
     * pointer into the document ({@code ""} for the root).
     */
    public static final class ContractJsonException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final String path;
        private final String problem;

        public ContractJsonException(String path, String problem) {
            this(path, problem, null);
        }

        public ContractJsonException(String path, String problem, Throwable cause) {
            super((path.isEmpty() ? "/" : path) + ": " + problem, cause);
            this.path = path;
            this.problem = problem;
        }

        /** JSON pointer of the offending value ({@code ""} for the document root). */
        public String path() {
            return path;
        }

        /** The problem, without the pointer. */
        public String problem() {
            return problem;
        }

        /** The offending property name, if the pointer ends in one. */
        public Optional<String> property() {
            int slash = path.lastIndexOf('/');
            String last = slash < 0 ? path : path.substring(slash + 1);
            return last.isEmpty() || last.chars().allMatch(Character::isDigit) ? Optional.empty() : Optional.of(last);
        }
    }
}
