package ca.carleton.secpipe.contracts.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.json.ContractJson.ContractJsonException;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** WO-C.2: ledger and run-metadata codecs, their examples, and their agreement with the schemas. */
class LedgerCodecTest {

    private static final String LEDGER_ID = "https://secpipe.dev/schemas/ledger.schema.json";
    private static final String RUN_ID = "https://secpipe.dev/schemas/run-metadata.schema.json";
    private static final Schema LEDGER_SCHEMA = schema(LEDGER_ID, "/schemas/ledger.schema.json");
    private static final Schema RUN_SCHEMA = schema(RUN_ID, "/schemas/run-metadata.schema.json");

    // ------------------------------------------------------------------------------------------------------------
    // Ledger
    // ------------------------------------------------------------------------------------------------------------

    @Test
    void ledgerValidExampleRoundTrips() {
        String input = resource("/examples/ledger-valid.json");
        List<LedgerUnit> units = LedgerCodec.read(input);
        assertEquals(1, units.size());
        LedgerUnit unit = units.get(0);
        assertEquals("cov:0123456789abcdef0123456789abcdef", unit.coverageId());
        assertEquals(Optional.of("CWE-89"), unit.cwe());
        assertEquals(Optional.empty(), unit.agentId());
        assertEquals(LedgerUnit.Status.CANDIDATE, unit.status());
        assertEquals(LedgerUnit.PriorStatus.NEW, unit.priorStatus());
        assertEquals(List.of("builtin", "semgrep"), unit.settledBy());

        String written = LedgerCodec.write(units);
        assertEquals(tree(input), tree(written), "written ledger must carry exactly the input's content");
        assertEquals(units, LedgerCodec.read(written));
        assertEquals(written, LedgerCodec.write(LedgerCodec.read(written)), "write must be byte-stable");
        assertTrue(written.endsWith("}\n]\n"), written);
        assertFalse(written.contains("\r"));
        assertValid(LEDGER_SCHEMA, input);
        assertValid(LEDGER_SCHEMA, written);
    }

    @Test
    void ledgerMaximalExampleRoundTripsAndIsWrittenSortedByCoverageId() {
        String input = resource("/examples/ledger-maximal.json");
        List<LedgerUnit> units = LedgerCodec.read(input);
        assertEquals(List.of("cov:ac4470e14a90e1abee94af8d477c58d1", "cov:7a27dc4c37fb370e1e1507fd5f945b7d"),
                units.stream().map(LedgerUnit::coverageId).toList(), "read keeps file order");
        assertEquals(Optional.of("request"), units.get(0).canonicalRefs().lifecycle());
        assertEquals(Optional.of("superseded by wave 1"), units.get(0).attempts().get(0).archivedReason());
        assertEquals(Optional.empty(), units.get(1).cwe());

        String written = LedgerCodec.write(units);
        JsonNode writtenTree = tree(written);
        assertEquals("cov:7a27dc4c37fb370e1e1507fd5f945b7d", writtenTree.get(0).get("coverage_id").stringValue());
        assertEquals("cov:ac4470e14a90e1abee94af8d477c58d1", writtenTree.get(1).get("coverage_id").stringValue());
        assertEquals(tree(input).get(1), writtenTree.get(0));
        assertEquals(tree(input).get(0), writtenTree.get(1));
        assertTrue(writtenTree.get(0).get("cwe").isNull(), "absent cwe is written as null, not omitted");
        assertTrue(writtenTree.get(0).get("agent_id").isNull(), "absent agent_id is written as null");
        assertTrue(writtenTree.get(0).get("attempts").isArray(), "required empty arrays are written");
        assertFalse(writtenTree.get(0).has("settled_by"), "empty optional settled_by is omitted");
        assertEquals(written, LedgerCodec.write(List.of(units.get(1), units.get(0))), "input order is irrelevant");
        assertValid(LEDGER_SCHEMA, input);
        assertValid(LEDGER_SCHEMA, written);
    }

    @Test
    void ledgerReadingIsStrictAndPointsAtTheProblem() {
        String valid = resource("/examples/ledger-valid.json");
        assertError("/0/surprise", () -> LedgerCodec.read(valid.replace("\"wave\": 0,",
                "\"wave\": 0, \"surprise\": 1,")));
        assertError("/0/agent_id", () -> LedgerCodec.read(valid.replace("\"agent_id\": null,", "")));
        assertError("/0/cwe", () -> LedgerCodec.read(valid.replace("\"cwe\": \"CWE-89\",", "")));
        assertError("/0/status", () -> LedgerCodec.read(valid.replace("\"status\": \"candidate\"",
                "\"status\": \"done\"")));
        assertError("/0/wave", () -> LedgerCodec.read(valid.replace("\"wave\": 0,", "\"wave\": \"0\",")));
        assertError("/0/attempts", () -> LedgerCodec.read(valid.replace("\"attempts\": [],", "")));
        assertError("/0/canonical_refs/extra", () -> LedgerCodec.read(valid.replace(
                "\"attack_class\": \"ATTACK-CLASSES.md#Injection/CWE-89\" }",
                "\"attack_class\": \"ATTACK-CLASSES.md#Injection/CWE-89\", \"extra\": \"x\" }")));
        assertError("/0/surface", () -> LedgerCodec.read(valid.replace("\"surface\": \"POST /login\"",
                "\"surface\": null")));
        assertError("", () -> LedgerCodec.read(valid.replace("\"wave\": 0,", "\"wave\": 0, \"wave\": 1,")));
        assertError("", () -> LedgerCodec.read(valid + "[]"));
        assertError("", () -> LedgerCodec.read("{}"));
    }

    @Test
    void ledgerModuleRegistersThroughContractJsonMapper() {
        JsonMapper mapper = ContractJson.mapper(LedgerCodec.module(), RunMetadataCodec.module());
        LedgerUnit expected = LedgerCodec.read(resource("/examples/ledger-valid.json")).get(0);
        String unitJson = LedgerCodec.toTree(expected).toString();
        assertEquals(expected, mapper.readValue(unitJson, LedgerUnit.class));
        assertEquals(tree(unitJson), tree(mapper.writeValueAsString(expected)));
        assertEquals(LedgerCodec.fromTree(LedgerCodec.toTree(expected)), expected);
        RunMetadata metadata = RunMetadataCodec.read(resource("/examples/run-metadata-complete.json"));
        assertEquals(metadata, mapper.readValue(mapper.writeValueAsString(metadata), RunMetadata.class));
    }

    @Test
    void ledgerFileIoRoundTrips() throws IOException {
        Path dir = Files.createTempDirectory("secpipe-ledger");
        Path file = dir.resolve("coverage-ledger.json");
        try {
            List<LedgerUnit> units = LedgerCodec.read(resource("/examples/ledger-maximal.json"));
            LedgerCodec.write(file, units);
            assertEquals(LedgerCodec.write(units), Files.readString(file, StandardCharsets.UTF_8));
            assertEquals(units.size(), LedgerCodec.read(file).size());
        } finally {
            Files.deleteIfExists(file);
            Files.delete(dir);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Run metadata
    // ------------------------------------------------------------------------------------------------------------

    @Test
    void completeAndIncompleteRunMetadataExamplesAreSchemaValidAsReadAndAsWritten() {
        for (String name : List.of("run-metadata-complete.json", "run-metadata-incomplete.json")) {
            String input = resource("/examples/" + name);
            assertValid(RUN_SCHEMA, input);
            String written = RunMetadataCodec.write(RunMetadataCodec.read(input));
            assertValid(RUN_SCHEMA, written);
            assertEquals(written, RunMetadataCodec.write(RunMetadataCodec.read(written)), name + " byte-stable");
        }
    }

    @Test
    void completeRunMetadataWithIncompleteReasonIsSchemaInvalid() {
        String input = resource("/examples/run-metadata-invalid-complete-with-reason.json");
        RunMetadata metadata = RunMetadataCodec.read(input);  // the codec reads it; the schema rejects it
        assertEquals(RunMetadata.TerminalState.COMPLETE, metadata.terminalState());
        assertTrue(metadata.incompleteReason().isPresent());
        assertInvalid(RUN_SCHEMA, input);
        assertInvalid(RUN_SCHEMA, RunMetadataCodec.write(metadata));
        // Removing the reason makes it valid again, so the reason is what the schema rejects.
        String withoutReason = RunMetadataCodec.write(new RunMetadata(metadata.schemaVersion(), metadata.runId(),
                metadata.secpipeVersion(), metadata.startedAt(), metadata.finishedAt(), metadata.terminalState(),
                Optional.empty(), metadata.profile(), metadata.scope(), metadata.target(), metadata.ai(),
                metadata.budget(), metadata.tools(), metadata.stageTimingsMs(), metadata.sandbox(),
                metadata.priorRun(), metadata.counts(), metadata.validators()));
        assertValid(RUN_SCHEMA, withoutReason);
    }

    @Test
    void completeRunMetadataRoundTripsEveryField() {
        String input = resource("/examples/run-metadata-complete.json");
        RunMetadata m = RunMetadataCodec.read(input);
        assertEquals(Instant.parse("2026-11-17T14:41:52.250Z"), m.finishedAt());
        assertEquals(ProviderMode.OPENAI_COMPATIBLE, m.ai().requestedMode());
        assertEquals(ProviderMode.FAKE, m.ai().effectiveMode());
        assertEquals(AgentRole.DISPROVER, m.ai().models().get(1).role());
        assertEquals(Optional.of(0.25), m.ai().usage().get(0).estimatedCostUsd());
        assertEquals(Optional.empty(), m.ai().usage().get(1).estimatedCostUsd());
        assertEquals(Long.valueOf(610000L), m.stageTimingsMs().get("agents"));
        assertEquals(5, m.priorRun().orElseThrow().carriedForward());
        String written = RunMetadataCodec.write(m);
        assertEquals(tree(input), tree(written));
        assertTrue(written.contains("\"requested_mode\": \"openai-compatible\""), written);
    }

    @Test
    void incompleteRunMetadataKeepsRequiredEmptyContainersAndDefaultsOptionalCounters() {
        RunMetadata m = RunMetadataCodec.read(resource("/examples/run-metadata-incomplete.json"));
        assertEquals(Optional.of("agent-call budget exhausted before every ledger unit was settled"),
                m.incompleteReason());
        assertEquals(0, m.budget().maxSandboxRuns());
        assertEquals(0, m.ai().guardrailDowngrades());
        assertEquals(Optional.empty(), m.priorRun());
        assertEquals(RunMetadata.ValidatorStatus.NOT_RUN, m.validators().findings());
        JsonNode written = tree(RunMetadataCodec.write(m));
        for (String required : List.of("tools", "stage_timings_ms")) {
            assertTrue(written.has(required), required);
        }
        assertTrue(written.get("ai").get("fallbacks").isArray());
        assertTrue(written.get("budget").get("reserved").isObject());
        assertTrue(written.get("counts").get("ledger_units").isObject());
        assertFalse(written.get("counts").has("by_severity"));
        assertFalse(written.has("prior_run"));
    }

    @Test
    void mapValuedPropertiesAreWrittenWithSortedKeys() {
        RunMetadata base = RunMetadataCodec.read(resource("/examples/run-metadata-incomplete.json"));
        Map<String, Long> timings = new LinkedHashMap<>();
        timings.put("report", 3L);
        timings.put("agents", 2L);
        timings.put("setup", 1L);
        timings.put("detect", 4L);
        Map<String, Integer> reserved = new LinkedHashMap<>();
        reserved.put("remediation", 1);
        reserved.put("disprover", 2);
        Map<String, Integer> ledgerUnits = new LinkedHashMap<>();
        ledgerUnits.put("planned", 3);
        ledgerUnits.put("covered", 1);
        ledgerUnits.put("blocked", 2);
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        bySeverity.put("low", 1);
        bySeverity.put("critical", 2);
        bySeverity.put("high", 3);
        RunMetadata m = new RunMetadata(base.schemaVersion(), base.runId(), base.secpipeVersion(), base.startedAt(),
                base.finishedAt(), base.terminalState(), base.incompleteReason(), base.profile(), base.scope(),
                base.target(), base.ai(),
                new RunMetadata.BudgetSnapshot(5, 0, reserved, false, 0, 0),
                base.tools(), timings, base.sandbox(), base.priorRun(),
                new RunMetadata.Counts(0, 0, 0, ledgerUnits, bySeverity), base.validators());
        String written = RunMetadataCodec.write(m);

        assertTrue(written.contains("\"stage_timings_ms\": {\n    \"agents\": 2,\n    \"detect\": 4,\n"
                + "    \"report\": 3,\n    \"setup\": 1\n  }"), written);
        assertTrue(written.contains("\"reserved\": {\n      \"disprover\": 2,\n      \"remediation\": 1\n    }"),
                written);
        assertTrue(written.contains("\"ledger_units\": {\n      \"blocked\": 2,\n      \"covered\": 1,\n"
                + "      \"planned\": 3\n    }"), written);
        assertTrue(written.contains("\"by_severity\": {\n      \"critical\": 2,\n      \"high\": 3,\n"
                + "      \"low\": 1\n    }"), written);
        assertEquals(keys(tree(written).get("stage_timings_ms")), List.of("agents", "detect", "report", "setup"));
        // Reading back gives key-ordered maps, so a second write is byte-identical.
        assertEquals(written, RunMetadataCodec.write(RunMetadataCodec.read(written)));
        assertEquals(List.of("agents", "detect", "report", "setup"),
                new ArrayList<>(RunMetadataCodec.read(written).stageTimingsMs().keySet()));
        assertValid(RUN_SCHEMA, written);
    }

    @Test
    void runMetadataReadingIsStrictAndPointsAtTheProblem() {
        String valid = resource("/examples/run-metadata-incomplete.json");
        assertError("/surprise", () -> RunMetadataCodec.read(valid.replace("\"schema_version\": 1,",
                "\"schema_version\": 1, \"surprise\": true,")));
        assertError("/validators", () -> RunMetadataCodec.read(valid.replace(
                ",\n  \"validators\": { \"findings\": \"not_run\", \"ledger\": \"fail\" }", "")));
        assertError("/ai/requested_mode", () -> RunMetadataCodec.read(valid.replace(
                "\"requested_mode\": \"none\"", "\"requested_mode\": \"openai_compatible\"")));
        assertError("/started_at", () -> RunMetadataCodec.read(valid.replace("2026-11-17T15:00:00Z",
                "yesterday")));
        assertError("/stage_timings_ms/setup", () -> RunMetadataCodec.read(valid.replace(
                "\"stage_timings_ms\": {}", "\"stage_timings_ms\": { \"setup\": 1.5 }")));
        assertError("", () -> RunMetadataCodec.read("[]"));
    }

    @Test
    void writingRejectsNullComponentsWithAPointer() {
        RunMetadata base = RunMetadataCodec.read(resource("/examples/run-metadata-incomplete.json"));
        Map<String, Long> timings = new LinkedHashMap<>();
        timings.put("setup", null);
        RunMetadata bad = new RunMetadata(base.schemaVersion(), base.runId(), base.secpipeVersion(),
                base.startedAt(), base.finishedAt(), base.terminalState(), base.incompleteReason(), base.profile(),
                base.scope(), base.target(), base.ai(), base.budget(), base.tools(), timings, base.sandbox(),
                base.priorRun(), base.counts(), base.validators());
        assertError("/stage_timings_ms/setup", () -> RunMetadataCodec.write(bad));
        LedgerUnit unit = LedgerCodec.read(resource("/examples/ledger-valid.json")).get(0);
        LedgerUnit nullCwe = new LedgerUnit(unit.coverageId(), unit.canonicalRefs(), unit.surface(),
                unit.boundary(), unit.subsystem(), unit.attackClass(), null, unit.startingPaths(),
                unit.ordinaryAttackClassBlock(), unit.selectedCompanionBlocks(), unit.excludedBlocks(),
                unit.priorStatus(), unit.attempts(), unit.wave(), unit.status(), unit.agentId(),
                unit.reviewedPaths(), unit.localChecks(), unit.resultFingerprints(), unit.unresolved(),
                unit.settledBy());
        assertError("/0/cwe", () -> LedgerCodec.write(List.of(nullCwe)));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------------

    private static void assertError(String pointer, Runnable step) {
        ContractJsonException e = assertThrows(ContractJsonException.class, step::run);
        assertEquals(pointer, e.path(), () -> "wrong pointer for: " + e.getMessage());
    }

    private static List<String> keys(JsonNode object) {
        return new ArrayList<>(object.propertyNames());
    }

    private static JsonNode tree(String json) {
        return ContractJson.mapper().readTree(json);
    }

    private static Schema schema(String id, String resourceName) {
        String text = resource(resourceName);
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                        builder -> builder
                                .schemaRegistryConfig(SchemaRegistryConfig.builder().locale(Locale.ENGLISH).build())
                                .schemas(Map.of(id, text)))
                .getSchema(SchemaLocation.of(id));
    }

    private static void assertValid(Schema schema, String json) {
        List<Error> errors = schema.validate(json, InputFormat.JSON);
        assertTrue(errors.isEmpty(), () -> "schema errors: " + errors);
    }

    private static void assertInvalid(Schema schema, String json) {
        List<Error> errors = schema.validate(json, InputFormat.JSON);
        assertNotEquals(List.of(), errors, "expected schema errors");
    }

    private static String resource(String name) {
        try (InputStream in = LedgerCodecTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
