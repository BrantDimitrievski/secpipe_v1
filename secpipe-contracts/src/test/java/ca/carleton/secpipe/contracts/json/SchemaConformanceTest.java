package ca.carleton.secpipe.contracts.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.DependencyRef;
import ca.carleton.secpipe.contracts.finding.Finding.FixAttempt;
import ca.carleton.secpipe.contracts.finding.Finding.TestEvidence;
import ca.carleton.secpipe.contracts.finding.Finding.VerifierRef;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * WO-C.1: codec ⇔ schema agreement. Everything {@link ContractJson} writes validates against
 * {@code schemas/findings.schema.json}; every enum and every property the schema knows is one the codec writes.
 */
class SchemaConformanceTest {

    private static final String FINDINGS_ID = "https://secpipe.dev/schemas/findings.schema.json";
    private static final String SCHEMA_TEXT = FindingCodecTest.resource("/schemas/findings.schema.json");
    private static final JsonNode SCHEMA_TREE = ContractJson.mapper().readTree(SCHEMA_TEXT);
    private static final Schema SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                    builder -> builder
                            .schemaRegistryConfig(SchemaRegistryConfig.builder().locale(Locale.ENGLISH).build())
                            .schemas(Map.of(FINDINGS_ID, SCHEMA_TEXT)))
            .getSchema(SchemaLocation.of(FINDINGS_ID));

    @Test
    void writtenValidExampleConformsToTheSchema() {
        assertValid(ContractJson.writeFindings(ContractJson.readFindings(
                FindingCodecTest.resource("/examples/findings-valid.json"))));
    }

    @Test
    void canonicalMaximalAndMinimalExamplesConformAsReadAndAsWritten() {
        for (String name : List.of("findings-valid-canonical.json", "findings-maximal.json",
                "findings-minimal.json")) {
            String input = FindingCodecTest.resource("/examples/" + name);
            assertValid(input);
            assertValid(ContractJson.writeFindings(ContractJson.readFindings(input)));
        }
    }

    @Test
    void everyVerdictBuiltInJavaConformsToTheSchema() {
        List<Finding> all = new ArrayList<>(ContractJson.readFindings(
                FindingCodecTest.resource("/examples/findings-minimal.json")));
        all.add(FindingCodecTest.confirmed("built:confirmed"));
        String written = ContractJson.writeFindings(all);
        assertValid(written);
        TreeSet<String> verdicts = new TreeSet<>();
        ContractJson.mapper().readTree(written).forEach(n -> verdicts.add(n.get("verdict").stringValue()));
        assertEquals(new TreeSet<>(List.of("confirmed", "needs_validation", "rejected")), verdicts);
    }

    @Test
    void invalidExampleFailsTheSchema() {
        List<Error> errors = SCHEMA.validate(
                FindingCodecTest.resource("/examples/findings-invalid-nv-with-severity.json"), InputFormat.JSON);
        assertFalse(errors.isEmpty(), "a needs_validation record with severity must fail the schema");
        assertTrue(errors.stream().anyMatch(e -> e.getInstanceLocation().toString().startsWith("/0")),
                errors::toString);
    }

    @Test
    void writtenOutputMissingARequiredListWouldFailTheSchema() {
        // Guards the "always write conditions / tool_sources / cwe" rule: dropping one breaks conformance.
        String written = ContractJson.writeFindings(List.of(FindingCodecTest.confirmed("c:1")));
        JsonNode tree = ContractJson.mapper().readTree(written);
        for (String pointer : List.of("", "/secpipe")) {
            for (String key : pointer.isEmpty() ? List.of("conditions") : List.of("tool_sources", "cwe")) {
                JsonNode copy = tree.deepCopy();
                ((ObjectNode) copy.get(0).at(pointer)).remove(key);
                assertFalse(SCHEMA.validate(copy).isEmpty(), "schema must require " + pointer + "/" + key);
            }
        }
    }

    @Test
    void codecEnumSpellingsAreExactlyTheSchemaEnums() {
        Map<String, Class<? extends Enum<?>>> enums = new LinkedHashMap<>();
        enums.put("/$defs/score5/enum", Finding.Score5.class);
        enums.put("/$defs/traceStep/properties/kind/enum", Finding.TraceKind.class);
        enums.put("/$defs/condition/properties/kind/enum", Finding.ConditionKind.class);
        enums.put("/$defs/confidence/properties/score/enum", Finding.Level3.class);
        enums.put("/$defs/secpipe/properties/confidence_hint/enum", Finding.Level3.class);
        enums.put("/$defs/secpipe/properties/category/enum", Finding.Category.class);
        enums.put("/$defs/secpipe/properties/origin/enum", Finding.Origin.class);
        enums.put("/$defs/secpipe/properties/remediation_status/enum", Finding.RemediationStatus.class);
        enums.put("/$defs/dependency/properties/reachable/enum", DependencyRef.Reachability.class);
        enums.put("/$defs/testEvidence/properties/phase/enum", TestEvidence.Phase.class);
        enums.put("/$defs/testEvidence/properties/outcome/enum", TestEvidence.Outcome.class);
        enums.put("/$defs/fixAttempt/properties/outcome/enum", FixAttempt.Outcome.class);
        enums.put("/$defs/verifierRef/properties/kind/enum", VerifierRef.Kind.class);
        enums.put("/$defs/verifierRef/properties/tier/enum", VerifierRef.CapabilityTier.class);
        for (Map.Entry<String, Class<? extends Enum<?>>> e : enums.entrySet()) {
            List<String> schemaValues = new ArrayList<>();
            SCHEMA_TREE.at(e.getKey()).forEach(v -> schemaValues.add(v.stringValue()));
            List<String> codecValues = new ArrayList<>();
            for (Enum<?> constant : e.getValue().getEnumConstants()) {
                codecValues.add(constant.name().toLowerCase(Locale.ROOT));
            }
            assertEquals(schemaValues, codecValues, e.getKey());
        }
        List<String> verdictConsts = new ArrayList<>();
        for (String def : List.of("confirmed", "needsValidation", "rejected")) {
            verdictConsts.add(SCHEMA_TREE.at("/$defs/" + def + "/properties/verdict/const").stringValue());
        }
        List<String> verdicts = new ArrayList<>();
        for (Finding.Verdict v : Finding.Verdict.values()) {
            verdicts.add(v.name().toLowerCase(Locale.ROOT));
        }
        assertEquals(verdictConsts, verdicts);
    }

    @Test
    void maximalOutputWritesEveryPropertyTheSchemaDefinesInSchemaOrder() {
        JsonNode out = ContractJson.mapper().readTree(ContractJson.writeFindings(ContractJson.readFindings(
                FindingCodecTest.resource("/examples/findings-maximal.json"))));
        Map<String, String> instanceToDef = new LinkedHashMap<>();
        instanceToDef.put("/0", "rejected");
        instanceToDef.put("/1", "needsValidation");
        instanceToDef.put("/1/validation_plan", "needsValidation/properties/validation_plan");
        instanceToDef.put("/2", "confirmed");
        instanceToDef.put("/2/trace/0", "traceStep");
        instanceToDef.put("/2/evidence/0", "evidenceItem");
        instanceToDef.put("/2/conditions/0", "condition");
        instanceToDef.put("/2/execution", "execution");
        instanceToDef.put("/2/remediation", "remediation");
        instanceToDef.put("/2/remediation/code_changes/0", "remediation/properties/code_changes/items");
        instanceToDef.put("/2/severity", "severity");
        instanceToDef.put("/2/severity/impact", "scored");
        instanceToDef.put("/2/confidence", "confidence");
        instanceToDef.put("/2/secpipe", "secpipe");
        instanceToDef.put("/2/secpipe/tool_sources/0", "toolSource");
        instanceToDef.put("/2/secpipe/dependency", "dependency");
        instanceToDef.put("/2/secpipe/test_evidence/0", "testEvidence");
        instanceToDef.put("/2/secpipe/fix_attempts/0", "fixAttempt");
        instanceToDef.put("/2/secpipe/fix_attempts/1/adequacy", "fixAttempt/properties/adequacy");
        instanceToDef.put("/2/secpipe/verification", "secpipe/properties/verification");
        instanceToDef.put("/2/secpipe/verification/disprover", "verifierRef");
        Set<String> partial = Set.of("/2/secpipe/fix_attempts/0", "/2/secpipe/verification/disprover",
                "/2/secpipe/test_evidence/0");
        for (Map.Entry<String, String> e : instanceToDef.entrySet()) {
            JsonNode instance = out.at(e.getKey());
            JsonNode def = SCHEMA_TREE.at("/$defs/" + e.getValue() + "/properties");
            assertFalse(instance.isMissingNode() || def.isMissingNode(), e::toString);
            List<String> schemaOrder = List.copyOf(def.propertyNames());
            List<String> written = List.copyOf(instance.propertyNames());
            if (partial.contains(e.getKey())) {
                // These maximal instances leave out a few optional properties; order must still follow the schema.
                assertEquals(schemaOrder.stream().filter(written::contains).toList(), written, e.getKey());
            } else {
                assertEquals(schemaOrder, written, e.getKey());
            }
        }
    }

    private static void assertValid(String json) {
        List<Error> errors = SCHEMA.validate(json, InputFormat.JSON);
        assertTrue(errors.isEmpty(), () -> "schema errors: " + errors);
    }
}
