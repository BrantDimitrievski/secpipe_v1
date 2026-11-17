package ca.carleton.secpipe.contracts.json;

import ca.carleton.secpipe.contracts.agent.AgentOutputs.Citation;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.ControlExamined;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.CriticResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.DependencyChange;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.DisproverResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.HunterCandidate;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.HunterResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.MissingUnit;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.ProposedSeverity;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.Reassignment;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.RecordVerifierResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.RefutationPattern;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.RemediationResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.Step;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Strict codec between the six agent output records in {@code AgentOutputs} and {@code schemas/agent/*.schema.json}.
 *
 * <p>{@link #read(String, Class)} accepts exactly one JSON object and nothing else: no Markdown fence, no prose
 * around it, no second value, no duplicate keys. The object must validate against the type's agent schema
 * (including its {@code if/then} branch rules), and is then mapped field by field: unknown properties, JSON
 * {@code null} where a value is expected, wrong JSON types and unknown enum spellings are errors. Nothing is
 * repaired (CLAUDE.md §6). Every failure is a {@link ContractJsonException} with a JSON pointer.
 *
 * <p>Mapping rules:
 * <ul>
 *   <li>Property names are snake_case and are written in schema property order; enums are lower-case names.</li>
 *   <li>An empty {@code Optional} is omitted, except {@code missing_units[].cwe}, which the schema requires and
 *       which is written as {@code null}.</li>
 *   <li>Lists the schema requires are always written. Optional lists ({@code disprover.conditions},
 *       {@code disprover.blockers}, {@code record_verifier.changed_fields}) are written only when non-empty,
 *       except {@code conditions} on a {@code confirmed} disprover verdict, which is always written.</li>
 *   <li>{@code record_verifier.material} is a primitive: it is always written and defaults to {@code false}
 *       when absent on read.</li>
 *   <li>{@code RecordVerifierResult.replacement} is read and written through {@link FindingCodec}. On read it
 *       must also validate as an item of {@code schemas/findings.schema.json}, as the agent schema demands.</li>
 * </ul>
 */
public final class AgentOutputCodec {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String SCHEMA_BASE = "https://secpipe.dev/schemas/agent/";
    private static final String COMMON = "_common.schema.json";
    private static final String FINDINGS_ID = "https://secpipe.dev/schemas/findings.schema.json";
    private static final ObjectWriter PRETTY_WRITER = ContractJson.mapper().writer()
            .with(ContractJson.prettyPrinter());

    private static final Map<Class<?>, Mapping<?>> MAPPINGS = new LinkedHashMap<>();

    static {
        register(HunterResult.class, "hunter-result.schema.json",
                AgentOutputCodec::writeHunter, AgentOutputCodec::readHunter);
        register(CriticResult.class, "critic-result.schema.json",
                AgentOutputCodec::writeCritic, AgentOutputCodec::readCritic);
        register(DisproverResult.class, "disprover-result.schema.json",
                AgentOutputCodec::writeDisprover, AgentOutputCodec::readDisprover);
        register(RecordVerifierResult.class, "record-verifier-result.schema.json",
                AgentOutputCodec::writeRecordVerifier, AgentOutputCodec::readRecordVerifier);
        register(TestGenResult.class, "testgen-result.schema.json",
                AgentOutputCodec::writeTestGen, AgentOutputCodec::readTestGen);
        register(RemediationResult.class, "remediation-result.schema.json",
                AgentOutputCodec::writeRemediation, AgentOutputCodec::readRemediation);
    }

    private static final Map<String, Schema> SCHEMAS = loadSchemas();
    private static final Schema FINDINGS_SCHEMA = loadFindingsSchema();

    private AgentOutputCodec() {
    }

    /** The agent output record types this codec handles, in a stable order. */
    public static List<Class<?>> supportedTypes() {
        return List.copyOf(MAPPINGS.keySet());
    }

    /** The classpath resource of the agent schema for {@code type}, e.g. {@code /schemas/agent/hunter-result...}. */
    public static String schemaResource(Class<?> type) {
        return "/schemas/agent/" + mapping(type).schemaFile();
    }

    /**
     * Reads one agent output. {@code json} must be exactly one JSON object that validates against the agent schema
     * of {@code type}.
     *
     * @throws ContractJsonException if the text is not exactly one JSON object, violates the schema, or does not
     *                               map onto {@code type}
     * @throws IllegalArgumentException if {@code type} is not an agent output record
     */
    public static <T> T read(String json, Class<T> type) {
        Objects.requireNonNull(json, "json");
        Mapping<?> mapping = mapping(type);
        JsonNode root;
        try {
            root = ContractJson.mapper().readTree(json);
        } catch (JacksonException e) {
            throw new ContractJsonException("", "malformed JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || root.isMissingNode() || !root.isObject()) {
            throw new ContractJsonException("", "expected exactly one JSON object at the top level but found "
                    + (root == null || root.isMissingNode() ? "nothing" : describe(root)));
        }
        failOnSchemaErrors(SCHEMAS.get(mapping.schemaFile()).validate(root), "", mapping.schemaFile());
        if (type == RecordVerifierResult.class && root.has("replacement")) {
            // The agent schema only says "object"; the replacement must itself be a valid findings.json item.
            ArrayNode wrapped = NODES.arrayNode().add(root.get("replacement"));
            List<Error> errors = FINDINGS_SCHEMA.validate(wrapped);
            failOnSchemaErrors(errors, "/0", "findings.schema.json (replacement)");
        }
        return type.cast(mapping.reader().apply(root, ""));
    }

    /** Throws for the first error (deterministic order); {@code strip} is a pointer prefix to drop. */
    private static void failOnSchemaErrors(List<Error> errors, String strip, String schemaName) {
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
        if (!strip.isEmpty()) {
            pointer = "/replacement" + (pointer.startsWith(strip) ? pointer.substring(strip.length()) : "");
        }
        throw new ContractJsonException(pointer, "violates " + schemaName + ": " + first.getMessage());
    }

    /** Converts an agent output record to its JSON object. */
    public static ObjectNode toTree(Object value) {
        if (value == null) {
            throw new ContractJsonException("", "value is null");
        }
        return mapping(value.getClass()).writeUnchecked(value);
    }

    /** Writes an agent output record: two-space indent, LF line ends, final newline. Encode as UTF-8. */
    public static String write(Object value) {
        return PRETTY_WRITER.writeValueAsString(toTree(value)) + "\n";
    }

    // ------------------------------------------------------------------------------------------------------------
    // Registry
    // ------------------------------------------------------------------------------------------------------------

    private static <T> void register(Class<T> type, String schemaFile, BiFunction<T, String, ObjectNode> writer,
                                     BiFunction<JsonNode, String, T> reader) {
        MAPPINGS.put(type, new Mapping<>(type, schemaFile, writer, reader));
    }

    private static Mapping<?> mapping(Class<?> type) {
        Mapping<?> mapping = MAPPINGS.get(Objects.requireNonNull(type, "type"));
        if (mapping == null) {
            throw new IllegalArgumentException("not an agent output type handled by AgentOutputCodec: "
                    + type.getName());
        }
        return mapping;
    }

    private static Map<String, Schema> loadSchemas() {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put(SCHEMA_BASE + COMMON, resource("/schemas/agent/" + COMMON));
        for (Mapping<?> m : MAPPINGS.values()) {
            texts.put(SCHEMA_BASE + m.schemaFile(), resource("/schemas/agent/" + m.schemaFile()));
        }
        // Every schema is preloaded, so $ref resolution never leaves the classpath (no network).
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder
                        .schemaRegistryConfig(SchemaRegistryConfig.builder().locale(Locale.ENGLISH).build())
                        .schemas(Map.copyOf(texts)));
        Map<String, Schema> schemas = new LinkedHashMap<>();
        for (Mapping<?> m : MAPPINGS.values()) {
            Schema schema = registry.getSchema(SchemaLocation.of(SCHEMA_BASE + m.schemaFile()));
            schema.initializeValidators();
            schemas.put(m.schemaFile(), schema);
        }
        return Map.copyOf(schemas);
    }

    private static Schema loadFindingsSchema() {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder
                        .schemaRegistryConfig(SchemaRegistryConfig.builder().locale(Locale.ENGLISH).build())
                        .schemas(Map.of(FINDINGS_ID, resource("/schemas/findings.schema.json"))));
        Schema schema = registry.getSchema(SchemaLocation.of(FINDINGS_ID));
        schema.initializeValidators();
        return schema;
    }

    private static String resource(String name) {
        try (InputStream in = AgentOutputCodec.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + name, e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Hunter
    // ------------------------------------------------------------------------------------------------------------

    private static ObjectNode writeHunter(HunterResult v, String path) {
        Out out = new Out(path);
        out.str("coverage_id", v.coverageId());
        out.enumValue("status", v.status());
        out.strList("reviewed_paths", v.reviewedPaths());
        out.strList("local_checks", v.localChecks());
        out.list("candidates", v.candidates(), AgentOutputCodec::writeHunterCandidate);
        out.strList("unresolved", v.unresolved());
        out.strList("hardening_notes", v.hardeningNotes());
        return out.node;
    }

    private static HunterResult readHunter(JsonNode node, String path) {
        In in = new In(node, path);
        HunterResult v = new HunterResult(in.str("coverage_id"), in.enumValue("status", HunterResult.Status.class),
                in.strList("reviewed_paths", true), in.strList("local_checks", true),
                in.list("candidates", AgentOutputCodec::readHunterCandidate, true),
                in.strList("unresolved", true), in.strList("hardening_notes", true));
        in.done();
        return v;
    }

    private static ObjectNode writeHunterCandidate(HunterCandidate v, String path) {
        Out out = new Out(path);
        out.str("title", v.title());
        out.str("cwe", v.cwe());
        out.str("principal", v.principal());
        out.str("accepted_input", v.acceptedInput());
        out.str("strongest_control", v.strongestControl());
        out.str("claimed_root_cause", v.claimedRootCause());
        out.list("trace", v.trace(), AgentOutputCodec::writeStep);
        out.list("evidence", v.evidence(), AgentOutputCodec::writeCitation);
        out.str("smallest_fix", v.smallestFix());
        out.str("regression_case", v.regressionCase());
        return out.node;
    }

    private static HunterCandidate readHunterCandidate(JsonNode node, String path) {
        In in = new In(node, path);
        HunterCandidate v = new HunterCandidate(in.str("title"), in.str("cwe"), in.str("principal"),
                in.str("accepted_input"), in.str("strongest_control"), in.str("claimed_root_cause"),
                in.list("trace", AgentOutputCodec::readStep, true),
                in.list("evidence", AgentOutputCodec::readCitation, true),
                in.str("smallest_fix"), in.str("regression_case"));
        in.done();
        return v;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Critic
    // ------------------------------------------------------------------------------------------------------------

    private static ObjectNode writeCritic(CriticResult v, String path) {
        Out out = new Out(path);
        out.list("missing_units", v.missingUnits(), AgentOutputCodec::writeMissingUnit);
        out.list("reassign_ids", v.reassignIds(), AgentOutputCodec::writeReassignment);
        out.bool("stop", v.stop());
        out.str("rationale", v.rationale());
        return out.node;
    }

    private static CriticResult readCritic(JsonNode node, String path) {
        In in = new In(node, path);
        CriticResult v = new CriticResult(in.list("missing_units", AgentOutputCodec::readMissingUnit, true),
                in.list("reassign_ids", AgentOutputCodec::readReassignment, true),
                in.bool("stop"), in.str("rationale"));
        in.done();
        return v;
    }

    private static ObjectNode writeMissingUnit(MissingUnit v, String path) {
        Out out = new Out(path);
        out.str("surface_ref", v.surfaceRef());
        out.str("boundary_ref", v.boundaryRef());
        out.str("subsystem_ref", v.subsystemRef());
        out.str("attack_class_block", v.attackClassBlock());
        out.nullableStr("cwe", v.cwe());
        out.strList("starting_paths", v.startingPaths());
        out.str("reason", v.reason());
        return out.node;
    }

    private static MissingUnit readMissingUnit(JsonNode node, String path) {
        In in = new In(node, path);
        MissingUnit v = new MissingUnit(in.str("surface_ref"), in.str("boundary_ref"), in.str("subsystem_ref"),
                in.str("attack_class_block"), in.nullableStr("cwe"), in.strList("starting_paths", true),
                in.str("reason"));
        in.done();
        return v;
    }

    private static ObjectNode writeReassignment(Reassignment v, String path) {
        Out out = new Out(path);
        out.str("coverage_id", v.coverageId());
        out.str("reason", v.reason());
        return out.node;
    }

    private static Reassignment readReassignment(JsonNode node, String path) {
        In in = new In(node, path);
        Reassignment v = new Reassignment(in.str("coverage_id"), in.str("reason"));
        in.done();
        return v;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Disprover
    // ------------------------------------------------------------------------------------------------------------

    private static ObjectNode writeDisprover(DisproverResult v, String path) {
        Out out = new Out(path);
        out.str("fingerprint", v.fingerprint());
        out.enumValue("verdict", v.verdict());
        out.bool("entrypoint_valid", v.entrypointValid());
        out.bool("sink_valid", v.sinkValid());
        out.list("controls_examined", v.controlsExamined(), AgentOutputCodec::writeControl);
        out.optEnum("refutation_pattern", v.refutationPattern());
        out.optStr("root_cause", v.rootCause());
        out.optStr("intended_behavior", v.intendedBehavior());
        out.list("trace", v.trace(), AgentOutputCodec::writeStep);
        out.list("evidence", v.evidence(), AgentOutputCodec::writeCitation);
        boolean alwaysConditions = v.verdict() == Finding.Verdict.CONFIRMED;
        if (alwaysConditions || !out.required("conditions", v.conditions()).isEmpty()) {
            out.list("conditions", v.conditions(), AgentOutputCodec::writeCondition);
        }
        out.optObj("proposed_severity", v.proposedSeverity(), AgentOutputCodec::writeProposedSeverity);
        if (!out.required("blockers", v.blockers()).isEmpty()) {
            out.strList("blockers", v.blockers());
        }
        out.optObj("validation_plan", v.validationPlan(), AgentOutputCodec::writeValidationPlan);
        out.optStr("reason", v.reason());
        return out.node;
    }

    private static DisproverResult readDisprover(JsonNode node, String path) {
        In in = new In(node, path);
        DisproverResult v = new DisproverResult(in.str("fingerprint"),
                in.enumValue("verdict", Finding.Verdict.class),
                in.bool("entrypoint_valid"), in.bool("sink_valid"),
                in.list("controls_examined", AgentOutputCodec::readControl, true),
                in.optEnum("refutation_pattern", RefutationPattern.class),
                in.optStr("root_cause"), in.optStr("intended_behavior"),
                in.list("trace", AgentOutputCodec::readStep, true),
                in.list("evidence", AgentOutputCodec::readCitation, true),
                in.list("conditions", AgentOutputCodec::readCondition, false),
                in.optObj("proposed_severity", AgentOutputCodec::readProposedSeverity),
                in.strList("blockers", false),
                in.optObj("validation_plan", AgentOutputCodec::readValidationPlan),
                in.optStr("reason"));
        in.done();
        return v;
    }

    private static ObjectNode writeControl(ControlExamined v, String path) {
        Out out = new Out(path);
        out.str("file", v.file());
        out.integer("line", v.line());
        out.str("quote", v.quote());
        out.str("description", v.description());
        out.bool("effective", v.effective());
        return out.node;
    }

    private static ControlExamined readControl(JsonNode node, String path) {
        In in = new In(node, path);
        ControlExamined v = new ControlExamined(in.str("file"), in.integer("line"), in.str("quote"),
                in.str("description"), in.bool("effective"));
        in.done();
        return v;
    }

    private static ObjectNode writeProposedSeverity(ProposedSeverity v, String path) {
        Out out = new Out(path);
        out.enumValue("likelihood", v.likelihood());
        out.enumValue("impact", v.impact());
        out.str("likelihood_reason", v.likelihoodReason());
        out.str("impact_reason", v.impactReason());
        return out.node;
    }

    private static ProposedSeverity readProposedSeverity(JsonNode node, String path) {
        In in = new In(node, path);
        ProposedSeverity v = new ProposedSeverity(in.enumValue("likelihood", Finding.Score5.class),
                in.enumValue("impact", Finding.Score5.class), in.str("likelihood_reason"), in.str("impact_reason"));
        in.done();
        return v;
    }

    private static ObjectNode writeCondition(Finding.Condition v, String path) {
        Out out = new Out(path);
        out.enumValue("kind", v.kind());
        out.str("description", v.description());
        return out.node;
    }

    private static Finding.Condition readCondition(JsonNode node, String path) {
        In in = new In(node, path);
        Finding.Condition v = new Finding.Condition(in.enumValue("kind", Finding.ConditionKind.class),
                in.str("description"));
        in.done();
        return v;
    }

    private static ObjectNode writeValidationPlan(Finding.ValidationPlan v, String path) {
        Out out = new Out(path);
        out.optStr("local", v.local());
        out.optStr("deployment", v.deployment());
        return out.node;
    }

    private static Finding.ValidationPlan readValidationPlan(JsonNode node, String path) {
        In in = new In(node, path);
        Finding.ValidationPlan v = new Finding.ValidationPlan(in.optStr("local"), in.optStr("deployment"));
        in.done();
        return v;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Record verifier
    // ------------------------------------------------------------------------------------------------------------

    private static ObjectNode writeRecordVerifier(RecordVerifierResult v, String path) {
        Out out = new Out(path);
        out.str("fingerprint", v.fingerprint());
        out.enumValue("outcome", v.outcome());
        out.integer("checked_citations", v.checkedCitations());
        out.bool("material", v.material());
        if (!out.required("changed_fields", v.changedFields()).isEmpty()) {
            out.strList("changed_fields", v.changedFields());
        }
        out.optObj("replacement", v.replacement(), AgentOutputCodec::writeReplacement);
        out.str("reason", v.reason());
        return out.node;
    }

    private static RecordVerifierResult readRecordVerifier(JsonNode node, String path) {
        In in = new In(node, path);
        RecordVerifierResult v = new RecordVerifierResult(in.str("fingerprint"),
                in.enumValue("outcome", RecordVerifierResult.Outcome.class),
                in.integer("checked_citations"), in.optBool("material").orElse(false),
                in.strList("changed_fields", false),
                in.optObj("replacement", AgentOutputCodec::readReplacement),
                in.str("reason"));
        in.done();
        return v;
    }

    private static ObjectNode writeReplacement(Finding finding, String path) {
        try {
            return FindingCodec.toTree(finding);
        } catch (ContractJsonException e) {
            throw new ContractJsonException(path + e.path(), e.problem(), e);
        }
    }

    private static Finding readReplacement(JsonNode node, String path) {
        try {
            return FindingCodec.findingFromTree(node);
        } catch (ContractJsonException e) {
            throw new ContractJsonException(path + e.path(), e.problem(), e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Test generator and remediation
    // ------------------------------------------------------------------------------------------------------------

    private static ObjectNode writeTestGen(TestGenResult v, String path) {
        Out out = new Out(path);
        out.str("fingerprint", v.fingerprint());
        out.str("package_name", v.packageName());
        out.str("class_name", v.className());
        out.str("source", v.source());
        out.strList("target_symbols", v.targetSymbols());
        out.strList("payloads", v.payloads());
        out.str("expected_failure", v.expectedFailure());
        out.str("rationale", v.rationale());
        return out.node;
    }

    private static TestGenResult readTestGen(JsonNode node, String path) {
        In in = new In(node, path);
        TestGenResult v = new TestGenResult(in.str("fingerprint"), in.str("package_name"), in.str("class_name"),
                in.str("source"), in.strList("target_symbols", true), in.strList("payloads", true),
                in.str("expected_failure"), in.str("rationale"));
        in.done();
        return v;
    }

    private static ObjectNode writeRemediation(RemediationResult v, String path) {
        Out out = new Out(path);
        out.str("fingerprint", v.fingerprint());
        out.str("invariant", v.invariant());
        out.obj("decision_point", v.decisionPoint(), AgentOutputCodec::writeCitation);
        out.str("strategy", v.strategy());
        out.str("patch", v.patch());
        out.strList("files_changed", v.filesChanged());
        out.optObj("dependency_change", v.dependencyChange(), AgentOutputCodec::writeDependencyChange);
        out.strList("risk_notes", v.riskNotes());
        return out.node;
    }

    private static RemediationResult readRemediation(JsonNode node, String path) {
        In in = new In(node, path);
        RemediationResult v = new RemediationResult(in.str("fingerprint"), in.str("invariant"),
                in.obj("decision_point", AgentOutputCodec::readCitation), in.str("strategy"), in.str("patch"),
                in.strList("files_changed", true),
                in.optObj("dependency_change", AgentOutputCodec::readDependencyChange),
                in.strList("risk_notes", true));
        in.done();
        return v;
    }

    private static ObjectNode writeDependencyChange(DependencyChange v, String path) {
        Out out = new Out(path);
        out.str("purl", v.purl());
        out.str("to_version", v.toVersion());
        return out.node;
    }

    private static DependencyChange readDependencyChange(JsonNode node, String path) {
        In in = new In(node, path);
        DependencyChange v = new DependencyChange(in.str("purl"), in.str("to_version"));
        in.done();
        return v;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Shared pieces
    // ------------------------------------------------------------------------------------------------------------

    private static ObjectNode writeStep(Step v, String path) {
        Out out = new Out(path);
        out.enumValue("kind", v.kind());
        out.str("file", v.file());
        out.integer("line", v.line());
        out.str("scope", v.scope());
        out.str("description", v.description());
        out.optStr("quote", v.quote());
        return out.node;
    }

    private static Step readStep(JsonNode node, String path) {
        In in = new In(node, path);
        Step v = new Step(in.enumValue("kind", Finding.TraceKind.class), in.str("file"), in.integer("line"),
                in.str("scope"), in.str("description"), in.optStr("quote"));
        in.done();
        return v;
    }

    private static ObjectNode writeCitation(Citation v, String path) {
        Out out = new Out(path);
        out.str("file", v.file());
        out.integer("line", v.line());
        out.str("description", v.description());
        out.str("quote", v.quote());
        return out.node;
    }

    private static Citation readCitation(JsonNode node, String path) {
        In in = new In(node, path);
        Citation v = new Citation(in.str("file"), in.integer("line"), in.str("description"), in.str("quote"));
        in.done();
        return v;
    }

    private static String enumName(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static String describe(JsonNode node) {
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

    /** A writer, reader and schema for one agent output type. */
    private record Mapping<T>(Class<T> type, String schemaFile, BiFunction<T, String, ObjectNode> writer,
                              BiFunction<JsonNode, String, T> reader) {
        ObjectNode writeUnchecked(Object value) {
            return writer.apply(type.cast(value), "");
        }
    }

    /** Builds one JSON object; rejects {@code null} components with the JSON pointer of the property. */
    private static final class Out {
        private final ObjectNode node = NODES.objectNode();
        private final String path;

        Out(String path) {
            this.path = path;
        }

        private String at(String key) {
            return path + "/" + key;
        }

        <T> T required(String key, T value) {
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

        void nullableStr(String key, Optional<String> value) {
            Optional<String> v = required(key, value);
            if (v.isPresent()) {
                node.put(key, v.get());
            } else {
                node.putNull(key);
            }
        }

        void integer(String key, int value) {
            node.put(key, value);
        }

        void bool(String key, boolean value) {
            node.put(key, value);
        }

        void enumValue(String key, Enum<?> value) {
            node.put(key, enumName(required(key, value)));
        }

        void optEnum(String key, Optional<? extends Enum<?>> value) {
            required(key, value).ifPresent(v -> node.put(key, enumName(v)));
        }

        <T> void obj(String key, T value, BiFunction<T, String, ObjectNode> writer) {
            node.set(key, writer.apply(required(key, value), at(key)));
        }

        <T> void optObj(String key, Optional<T> value, BiFunction<T, String, ObjectNode> writer) {
            required(key, value).ifPresent(v -> node.set(key, writer.apply(v, at(key))));
        }

        <T> void list(String key, List<T> values, BiFunction<T, String, ObjectNode> writer) {
            required(key, values);
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

        void strList(String key, List<String> values) {
            required(key, values);
            ArrayNode array = node.putArray(key);
            for (int i = 0; i < values.size(); i++) {
                String item = values.get(i);
                if (item == null) {
                    throw new ContractJsonException(at(key) + "/" + i, "list element is null");
                }
                array.add(item);
            }
        }
    }

    /** Reads one JSON object strictly; {@link #done()} rejects properties no accessor asked for. */
    private static final class In {
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

        private static boolean asBool(JsonNode v, String at) {
            if (!v.isBoolean()) {
                throw new ContractJsonException(at, "expected a boolean but found " + describe(v));
            }
            return v.booleanValue();
        }

        private static <E extends Enum<E>> E asEnum(JsonNode v, String at, Class<E> type) {
            String text = asText(v, at);
            List<String> allowed = new ArrayList<>();
            for (E constant : type.getEnumConstants()) {
                if (enumName(constant).equals(text)) {
                    return constant;
                }
                allowed.add(enumName(constant));
            }
            throw new ContractJsonException(at, "unknown value '" + text + "'; expected one of " + allowed);
        }

        String str(String key) {
            return asText(requiredValue(key), at(key));
        }

        Optional<String> optStr(String key) {
            return value(key).map(v -> asText(v, at(key)));
        }

        /** A required property whose value may be JSON {@code null} (read as empty). */
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

        boolean bool(String key) {
            return asBool(requiredValue(key), at(key));
        }

        Optional<Boolean> optBool(String key) {
            return value(key).map(v -> asBool(v, at(key)));
        }

        <E extends Enum<E>> E enumValue(String key, Class<E> type) {
            return asEnum(requiredValue(key), at(key), type);
        }

        <E extends Enum<E>> Optional<E> optEnum(String key, Class<E> type) {
            return value(key).map(v -> asEnum(v, at(key), type));
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
