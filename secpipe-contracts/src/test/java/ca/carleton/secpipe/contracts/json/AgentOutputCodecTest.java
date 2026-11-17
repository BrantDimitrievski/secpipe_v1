package ca.carleton.secpipe.contracts.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.agent.AgentOutputs.CriticResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.DisproverResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.HunterResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.RecordVerifierResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.RefutationPattern;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.RemediationResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.TestGenResult;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.json.ContractJson.ContractJsonException;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * WO-C.3: every {@code AgentOutputs} record round-trips with its agent schema, and the example corpus under
 * {@code examples/agent/<schema>/} is accepted (valid-*) or rejected (invalid-*) by both the schema and the codec.
 */
class AgentOutputCodecTest {

    private static final String CORPUS = "/examples/agent";
    private static final String BASE = "https://secpipe.dev/schemas/agent/";
    private static final String FINDINGS_ID = "https://secpipe.dev/schemas/findings.schema.json";

    /** Corpus directory (= schema file name without {@code .schema.json}) for each agent output type. */
    private static final Map<String, Class<?>> TYPES = new LinkedHashMap<>();

    static {
        TYPES.put("hunter-result", HunterResult.class);
        TYPES.put("critic-result", CriticResult.class);
        TYPES.put("disprover-result", DisproverResult.class);
        TYPES.put("record-verifier-result", RecordVerifierResult.class);
        TYPES.put("testgen-result", TestGenResult.class);
        TYPES.put("remediation-result", RemediationResult.class);
    }

    private static final SchemaRegistry REGISTRY = registry();

    // ---------------------------------------------------------------------------------------------------------
    // Corpus shape
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void codecCoversExactlyTheSixAgentSchemas() {
        assertEquals(List.copyOf(TYPES.values()), AgentOutputCodec.supportedTypes());
        for (Map.Entry<String, Class<?>> e : TYPES.entrySet()) {
            assertEquals("/schemas/agent/" + e.getKey() + ".schema.json",
                    AgentOutputCodec.schemaResource(e.getValue()));
        }
        assertEquals(TYPES.keySet().stream().sorted().toList(),
                list(corpusRoot()).stream().map(p -> p.getFileName().toString()).sorted().toList(),
                "one corpus directory per agent schema");
    }

    @Test
    void corpusHasAtLeastThreeValidAndThreeInvalidExamplesPerSchema() {
        for (String dir : TYPES.keySet()) {
            assertTrue(valid(dir).size() >= 3, dir + ": fewer than 3 valid examples");
            assertTrue(invalid(dir).size() >= 3, dir + ": fewer than 3 invalid examples");
            for (Path p : list(corpusRoot().resolve(dir))) {
                String name = p.getFileName().toString();
                assertTrue(name.startsWith("valid-") && name.endsWith(".json") || name.startsWith("invalid-")
                        && (name.endsWith(".json") || name.endsWith(".txt")), "unexpected corpus file " + p);
            }
        }
    }

    @Test
    void corpusCoversTheRequiredKindsOfInvalidOutput() {
        String fenced = read(corpusRoot().resolve("hunter-result/invalid-fenced.txt"));
        assertTrue(fenced.startsWith("```json"), "fenced example keeps the raw Markdown fence");
        assertTrue(read(corpusRoot().resolve("disprover-result/invalid-fenced.txt")).startsWith("```json {"));
        assertTrue(Files.exists(corpusRoot().resolve("hunter-result/invalid-prose-plus-json.txt")));
        assertTrue(Files.exists(corpusRoot().resolve("hunter-result/invalid-two-objects.txt")));
        assertTrue(Files.exists(corpusRoot().resolve("hunter-result/invalid-missing-local-checks.json")));
        assertTrue(Files.exists(corpusRoot().resolve("hunter-result/invalid-covered-with-candidates.json")));
        assertTrue(Files.exists(corpusRoot().resolve("disprover-result/invalid-rejected-with-severity.json")));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Valid examples
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void validExamplesPassTheSchemaAndTheCodecAndRoundTripExactly() {
        for (Map.Entry<String, Class<?>> e : TYPES.entrySet()) {
            for (Path file : valid(e.getKey())) {
                String json = read(file);
                String label = e.getKey() + "/" + file.getFileName();
                assertEquals(List.of(), schemaErrors(e.getKey(), json), label + " must satisfy the schema");

                Object record = AgentOutputCodec.read(json, e.getValue());
                assertInstanceOf(e.getValue(), record);

                String written = AgentOutputCodec.write(record);
                assertEquals(List.of(), schemaErrors(e.getKey(), written),
                        label + " as written must satisfy the schema");
                assertEquals(ContractJson.mapper().readTree(json), ContractJson.mapper().readTree(written),
                        label + " must round-trip to the same JSON");
                assertEquals(record, AgentOutputCodec.read(written, e.getValue()), label + " record round trip");
                assertEquals(written, AgentOutputCodec.write(AgentOutputCodec.read(written, e.getValue())),
                        label + " writing is byte-stable");
                assertTrue(written.endsWith("}\n") && !written.contains("\r"), label + " LF and final newline");
            }
        }
    }

    @Test
    void writtenPropertiesFollowSchemaOrder() {
        for (Map.Entry<String, Class<?>> e : TYPES.entrySet()) {
            JsonNode schemaProps = schemaTree(e.getKey()).get("properties");
            List<String> order = List.copyOf(schemaProps.propertyNames());
            for (Path file : valid(e.getKey())) {
                JsonNode written = ContractJson.mapper().readTree(
                        AgentOutputCodec.write(AgentOutputCodec.read(read(file), e.getValue())));
                List<String> names = List.copyOf(written.propertyNames());
                assertEquals(order.stream().filter(names::contains).toList(), names, file.toString());
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Invalid examples
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void invalidExamplesFailTheSchemaAndTheCodec() {
        for (Map.Entry<String, Class<?>> e : TYPES.entrySet()) {
            for (Path file : invalid(e.getKey())) {
                String text = read(file);
                String label = e.getKey() + "/" + file.getFileName();
                assertFalse(schemaErrors(e.getKey(), text).isEmpty(), label + " must fail the schema");
                assertThrows(ContractJsonException.class, () -> AgentOutputCodec.read(text, e.getValue()),
                        label + " must fail the codec");
            }
        }
    }

    @Test
    void nonJsonAndMultiObjectOutputIsRejectedAsMalformedNotRepaired() {
        for (String name : List.of("hunter-result/invalid-fenced.txt", "hunter-result/invalid-prose-plus-json.txt",
                "hunter-result/invalid-two-objects.txt", "disprover-result/invalid-fenced.txt",
                "record-verifier-result/invalid-two-objects.txt")) {
            Class<?> type = TYPES.get(name.substring(0, name.indexOf('/')));
            String text = read(corpusRoot().resolve(name));
            ContractJsonException ex = assertThrows(ContractJsonException.class,
                    () -> AgentOutputCodec.read(text, type));
            assertEquals("", ex.path(), name);
            assertTrue(ex.problem().startsWith("malformed JSON"), name + ": " + ex.problem());
        }
        assertThrows(ContractJsonException.class, () -> AgentOutputCodec.read("[]", HunterResult.class));
        assertThrows(ContractJsonException.class, () -> AgentOutputCodec.read("", HunterResult.class));
        assertThrows(ContractJsonException.class, () -> AgentOutputCodec.read("\"{}\"", HunterResult.class));
    }

    @Test
    void branchViolationsAreRejected() {
        ContractJsonException covered = assertThrows(ContractJsonException.class, () -> AgentOutputCodec.read(
                read(corpusRoot().resolve("hunter-result/invalid-covered-with-candidates.json")), HunterResult.class));
        assertTrue(covered.getMessage().contains("hunter-result.schema.json"), covered.getMessage());
        ContractJsonException rejected = assertThrows(ContractJsonException.class, () -> AgentOutputCodec.read(
                read(corpusRoot().resolve("disprover-result/invalid-rejected-with-severity.json")),
                DisproverResult.class));
        assertTrue(rejected.getMessage().contains("disprover-result.schema.json"), rejected.getMessage());
    }

    @Test
    void unknownFieldsAndDuplicateKeysFail() {
        String valid = read(corpusRoot().resolve("hunter-result/valid-covered.json"));
        ObjectNode extra = (ObjectNode) ContractJson.mapper().readTree(valid);
        extra.put("confidence", "high");
        ContractJsonException ex = assertThrows(ContractJsonException.class,
                () -> AgentOutputCodec.read(extra.toString(), HunterResult.class));
        assertTrue(ex.getMessage().contains("confidence"), ex.getMessage());

        String duplicate = valid.replaceFirst("\\{", "{ \"status\": \"blocked\",");
        assertThrows(ContractJsonException.class, () -> AgentOutputCodec.read(duplicate, HunterResult.class));
    }

    @Test
    void readingIntoTheWrongTypeFails() {
        String hunter = read(corpusRoot().resolve("hunter-result/valid-covered.json"));
        assertThrows(ContractJsonException.class, () -> AgentOutputCodec.read(hunter, CriticResult.class));
        assertThrows(IllegalArgumentException.class, () -> AgentOutputCodec.read(hunter, String.class));
        assertThrows(IllegalArgumentException.class, () -> AgentOutputCodec.write("not a record"));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Specific mappings
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void replacementDeserializesThroughFindingCodec() {
        String json = read(corpusRoot().resolve("record-verifier-result/valid-replace-material.json"));
        RecordVerifierResult result = AgentOutputCodec.read(json, RecordVerifierResult.class);
        assertEquals(RecordVerifierResult.Outcome.REPLACE, result.outcome());
        assertTrue(result.material());
        assertEquals(List.of("verdict", "reason"), result.changedFields());
        Finding replacement = result.replacement().orElseThrow();
        assertInstanceOf(Finding.Rejected.class, replacement);
        assertEquals(FindingCodec.findingFromTree(ContractJson.mapper().readTree(json).get("replacement")),
                replacement);

        RecordVerifierResult verified = AgentOutputCodec.read(
                read(corpusRoot().resolve("record-verifier-result/valid-verified.json")), RecordVerifierResult.class);
        assertEquals(Optional.empty(), verified.replacement());
        assertEquals(List.of(), verified.changedFields());
    }

    @Test
    void replacementErrorsPointIntoTheReplacement() {
        ContractJsonException ex = assertThrows(ContractJsonException.class, () -> AgentOutputCodec.read(
                read(corpusRoot().resolve("record-verifier-result/invalid-replacement-not-a-finding.json")),
                RecordVerifierResult.class));
        assertTrue(ex.path().startsWith("/replacement"), ex.getMessage());
    }

    @Test
    void absentMaterialDefaultsToFalseAndIsAlwaysWritten() {
        ObjectNode node = (ObjectNode) ContractJson.mapper().readTree(
                read(corpusRoot().resolve("record-verifier-result/valid-verified.json")));
        node.remove("material");
        RecordVerifierResult result = AgentOutputCodec.read(node.toString(), RecordVerifierResult.class);
        assertFalse(result.material());
        assertTrue(AgentOutputCodec.toTree(result).get("material").isBoolean());
    }

    @Test
    void nullCweInMissingUnitIsReadAsEmptyAndWrittenAsNull() {
        CriticResult critic = AgentOutputCodec.read(
                read(corpusRoot().resolve("critic-result/valid-missing-units.json")), CriticResult.class);
        assertEquals(Optional.of("CWE-89"), critic.missingUnits().get(0).cwe());
        assertEquals(Optional.empty(), critic.missingUnits().get(1).cwe());
        JsonNode written = AgentOutputCodec.toTree(critic).get("missing_units").get(1).get("cwe");
        assertTrue(written.isNull());
    }

    @Test
    void disproverBranchesMapToOptionals() {
        DisproverResult rejected = AgentOutputCodec.read(
                read(corpusRoot().resolve("disprover-result/valid-rejected.json")), DisproverResult.class);
        assertEquals(Finding.Verdict.REJECTED, rejected.verdict());
        assertEquals(Optional.of(RefutationPattern.VALUE_OVERWRITTEN), rejected.refutationPattern());
        assertEquals(Optional.empty(), rejected.proposedSeverity());
        assertFalse(AgentOutputCodec.toTree(rejected).has("conditions"));

        DisproverResult confirmed = AgentOutputCodec.read(
                read(corpusRoot().resolve("disprover-result/valid-confirmed.json")), DisproverResult.class);
        assertTrue(confirmed.conditions().isEmpty());
        assertTrue(AgentOutputCodec.toTree(confirmed).get("conditions").isArray(),
                "confirmed always writes conditions, even when empty");
        assertEquals(Finding.Score5.HIGH, confirmed.proposedSeverity().orElseThrow().impact());
    }

    @Test
    void writingNullComponentsFailsWithAPointer() {
        HunterResult broken = new HunterResult(null, HunterResult.Status.COVERED, List.of("A.java"), List.of("c"),
                List.of(), List.of(), List.of());
        ContractJsonException ex = assertThrows(ContractJsonException.class, () -> AgentOutputCodec.write(broken));
        assertEquals("/coverage_id", ex.path());
    }

    @Test
    void codecEnumSpellingsAreExactlyTheSchemaEnums() {
        Map<String, Class<? extends Enum<?>>> enums = new LinkedHashMap<>();
        enums.put("hunter-result:/properties/status/enum", HunterResult.Status.class);
        enums.put("disprover-result:/properties/verdict/enum", Finding.Verdict.class);
        enums.put("disprover-result:/properties/refutation_pattern/enum", RefutationPattern.class);
        enums.put("record-verifier-result:/properties/outcome/enum", RecordVerifierResult.Outcome.class);
        enums.put("_common:/$defs/score5/enum", Finding.Score5.class);
        enums.put("_common:/$defs/traceStep/properties/kind/enum", Finding.TraceKind.class);
        enums.put("_common:/$defs/condition/properties/kind/enum", Finding.ConditionKind.class);
        for (Map.Entry<String, Class<? extends Enum<?>>> e : enums.entrySet()) {
            String[] parts = e.getKey().split(":", 2);
            JsonNode tree = parts[0].equals("_common")
                    ? ContractJson.mapper().readTree(classpath("/schemas/agent/_common.schema.json"))
                    : schemaTree(parts[0]);
            List<String> schemaValues = new ArrayList<>();
            tree.at(parts[1]).forEach(v -> schemaValues.add(v.stringValue()));
            List<String> codecValues = new ArrayList<>();
            for (Enum<?> constant : e.getValue().getEnumConstants()) {
                codecValues.add(constant.name().toLowerCase(Locale.ROOT));
            }
            assertEquals(schemaValues, codecValues, e.getKey());
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Schema errors for {@code text} as the output of the agent behind {@code dir}. Text that is not exactly one
     * JSON value counts as an error. For the record verifier, the replacement must also be a findings.json item.
     */
    private static List<String> schemaErrors(String dir, String text) {
        JsonNode root;
        try {
            root = ContractJson.mapper().readTree(text);
        } catch (JacksonException e) {
            return List.of("not exactly one JSON value: " + e.getOriginalMessage());
        }
        if (root == null || root.isMissingNode()) {
            return List.of("empty");
        }
        List<String> errors = new ArrayList<>();
        for (Error error : REGISTRY.getSchema(SchemaLocation.of(BASE + dir + ".schema.json")).validate(root)) {
            errors.add(error.toString());
        }
        if (dir.equals("record-verifier-result") && root.has("replacement")) {
            JsonNode wrapped = JsonNodeFactory.instance.arrayNode().add(root.get("replacement"));
            for (Error error : REGISTRY.getSchema(SchemaLocation.of(FINDINGS_ID)).validate(wrapped)) {
                errors.add("replacement: " + error);
            }
        }
        return List.copyOf(errors);
    }

    private static SchemaRegistry registry() {
        Map<String, String> schemas = new LinkedHashMap<>();
        schemas.put(BASE + "_common.schema.json", classpath("/schemas/agent/_common.schema.json"));
        for (String dir : TYPES.keySet()) {
            schemas.put(BASE + dir + ".schema.json",
                    classpath("/schemas/agent/" + dir + ".schema.json"));
        }
        schemas.put(FINDINGS_ID, classpath("/schemas/findings.schema.json"));
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
                .schemaRegistryConfig(SchemaRegistryConfig.builder().locale(Locale.ENGLISH).build())
                .schemas(Map.copyOf(schemas)));
    }

    private static JsonNode schemaTree(String dir) {
        return ContractJson.mapper().readTree(classpath("/schemas/agent/" + dir + ".schema.json"));
    }

    private static Path corpusRoot() {
        URL url = AgentOutputCodecTest.class.getResource(CORPUS);
        assertTrue(url != null, "missing test resource " + CORPUS);
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Path> valid(String dir) {
        return list(corpusRoot().resolve(dir)).stream()
                .filter(p -> p.getFileName().toString().startsWith("valid-")).toList();
    }

    private static List<Path> invalid(String dir) {
        return list(corpusRoot().resolve(dir)).stream()
                .filter(p -> p.getFileName().toString().startsWith("invalid-")).toList();
    }

    private static List<Path> list(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String classpath(String name) {
        try (InputStream in = AgentOutputCodecTest.class.getResourceAsStream(name)) {
            assertTrue(in != null, "missing test resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
