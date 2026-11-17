package ca.carleton.secpipe.validators;

import ca.carleton.secpipe.contracts.json.ContractJson;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.NodePath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Every JSON Schema under {@code schemas/} on the classpath (shipped by {@code secpipe-contracts}), loaded by
 * {@code $id} for networknt json-schema-validator (draft 2020-12). Relative {@code $ref}s such as
 * {@code _common.schema.json#/$defs/text} resolve against the referring schema's {@code $id}, entirely from memory
 * (no network).
 *
 * <p>Schemas are addressed by full {@code $id} ({@code https://secpipe.dev/schemas/findings.schema.json}) or by the
 * path relative to {@code schemas/} ({@code findings.schema.json}, {@code agent/hunter-result.schema.json}).
 * Instances are immutable and thread-safe; use {@link #shared()}.
 */
public final class SchemaRegistry {

    /** The {@code $id} prefix of every SecPipe schema; the rest of the id is the path under {@code schemas/}. */
    public static final String BASE_ID = "https://secpipe.dev/schemas/";

    private static final String ROOT = "schemas";
    private static final String ANCHOR = ROOT + "/findings.schema.json";
    private static final String SUFFIX = ".schema.json";

    private static volatile SchemaRegistry shared;

    private final Map<String, String> sources;
    private final com.networknt.schema.SchemaRegistry registry;
    private final Map<String, Schema> compiled = new ConcurrentHashMap<>();

    private SchemaRegistry(Map<String, String> sources) {
        this.sources = Collections.unmodifiableMap(new TreeMap<>(sources));
        this.registry = com.networknt.schema.SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder
                        .schemaRegistryConfig(SchemaRegistryConfig.builder().locale(Locale.ENGLISH).build())
                        .schemas(this.sources));
    }

    /** The registry for the contract schemas on this class's classpath, loaded once. */
    public static SchemaRegistry shared() {
        SchemaRegistry r = shared;
        if (r == null) {
            synchronized (SchemaRegistry.class) {
                r = shared;
                if (r == null) {
                    r = load();
                    shared = r;
                }
            }
        }
        return r;
    }

    /** Loads every {@code *.schema.json} under {@code schemas/} from the classpath and compiles each one. */
    public static SchemaRegistry load() {
        URL anchor = SchemaRegistry.class.getClassLoader().getResource(ANCHOR);
        if (anchor == null) {
            throw new IllegalStateException("classpath has no " + ANCHOR + " (is secpipe-contracts missing?)");
        }
        SchemaRegistry registry = new SchemaRegistry(readAll(anchor));
        registry.ids().forEach(registry::schema);
        return registry;
    }

    /** All schema {@code $id}s, sorted. */
    public Set<String> ids() {
        return sources.keySet();
    }

    /** The compiled schema for a full {@code $id} or a path relative to {@code schemas/}. */
    public Schema schema(String idOrName) {
        String id = resolve(idOrName);
        return compiled.computeIfAbsent(id, key -> registry.getSchema(SchemaLocation.of(key)));
    }

    /** Validates JSON text. Malformed JSON is reported as one {@code json.malformed} error at the root. */
    public ValidationResult validate(String idOrName, String json) {
        Schema schema = schema(idOrName);
        JsonNode instance;
        try {
            instance = ContractJson.mapper().readTree(json);
        } catch (JacksonException e) {
            return ValidationResult.of(List.of(ValidationError.of("json.malformed", "",
                    "malformed JSON: " + e.getOriginalMessage())));
        }
        return validate(schema, instance);
    }

    /** Validates a parsed document. */
    public ValidationResult validate(String idOrName, JsonNode instance) {
        return validate(schema(idOrName), instance);
    }

    private static ValidationResult validate(Schema schema, JsonNode instance) {
        List<Error> raw = schema.validate(instance);
        List<ValidationError> errors = new ArrayList<>(raw.size());
        for (Error e : raw) {
            String keyword = e.getKeyword() == null ? "unknown" : e.getKeyword();
            errors.add(new ValidationError("schema." + keyword, e.getInstanceLocation().toString(), e.getMessage(),
                    fingerprintOf(instance, e.getInstanceLocation())));
        }
        // Deterministic order (CLAUDE.md §8); oneOf branches often repeat an error, so drop exact duplicates.
        return ValidationResult.of(errors.stream()
                .sorted(Comparator.comparing(ValidationError::path).thenComparing(ValidationError::code)
                        .thenComparing(ValidationError::message))
                .distinct()
                .toList());
    }

    /** For array documents (findings.json), the fingerprint of the top-level element the error points into. */
    private static Optional<String> fingerprintOf(JsonNode root, NodePath location) {
        if (!root.isArray() || location.getNameCount() == 0 || !(location.getElement(0) instanceof Integer index)) {
            return Optional.empty();
        }
        JsonNode fingerprint = root.path(index).path("fingerprint");
        return fingerprint.isString() ? Optional.of(fingerprint.stringValue()) : Optional.empty();
    }

    private String resolve(String idOrName) {
        String id = idOrName.startsWith(BASE_ID) ? idOrName : BASE_ID + idOrName;
        if (!sources.containsKey(id)) {
            throw new IllegalArgumentException("unknown schema '" + idOrName + "'; known: " + sources.keySet());
        }
        return id;
    }

    private static Map<String, String> readAll(URL anchor) {
        URI uri;
        try {
            uri = anchor.toURI();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("bad schema location " + anchor, e);
        }
        if (!"jar".equals(uri.getScheme())) {
            return readTree(Path.of(uri).getParent());
        }
        FileSystem created = null;
        try {
            try {
                created = FileSystems.newFileSystem(uri, Map.of());
            } catch (FileSystemAlreadyExistsException alreadyOpen) {
                // Someone else opened this jar; reuse their file system and leave it open.
            }
            return readTree(Path.of(uri).getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open " + uri, e);
        } finally {
            if (created != null) {
                try {
                    created.close();
                } catch (IOException ignored) {
                    // Read-only zip file system; nothing to flush.
                }
            }
        }
    }

    private static Map<String, String> readTree(Path root) {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(SUFFIX)).sorted().toList()) {
                String relative = root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                String text = Files.readString(file, StandardCharsets.UTF_8);
                JsonNode id = ContractJson.mapper().readTree(text).path("$id");
                String expected = BASE_ID + relative;
                if (!id.isString() || !id.stringValue().equals(expected)) {
                    throw new IllegalStateException(ROOT + "/" + relative + " must declare \"$id\": \"" + expected
                            + "\"");
                }
                out.put(expected, text);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read schemas under " + root, e);
        }
        return out;
    }
}
