package ca.carleton.secpipe.contracts.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.Confidence;
import ca.carleton.secpipe.contracts.finding.Finding.DependencyRef;
import ca.carleton.secpipe.contracts.finding.Finding.Evidence;
import ca.carleton.secpipe.contracts.finding.Finding.Execution;
import ca.carleton.secpipe.contracts.finding.Finding.Remediation;
import ca.carleton.secpipe.contracts.finding.Finding.Scored;
import ca.carleton.secpipe.contracts.finding.Finding.SecPipeExt;
import ca.carleton.secpipe.contracts.finding.Finding.Severity;
import ca.carleton.secpipe.contracts.finding.Finding.TestEvidence;
import ca.carleton.secpipe.contracts.finding.Finding.ToolSource;
import ca.carleton.secpipe.contracts.finding.Finding.TraceStep;
import ca.carleton.secpipe.contracts.finding.Finding.ValidationPlan;
import ca.carleton.secpipe.contracts.json.ContractJson.ContractJsonException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** WO-C.1: ContractJson / FindingCodec map the contract records to exactly the findings.schema.json shape. */
class FindingCodecTest {

    private static final String VALID = "/examples/findings-valid.json";
    private static final String CANONICAL = "/examples/findings-valid-canonical.json";
    private static final String MAXIMAL = "/examples/findings-maximal.json";
    private static final String MINIMAL = "/examples/findings-minimal.json";

    // ---------------------------------------------------------------------------------------------------------
    // Round trips
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void validExampleRoundTripsToTheSameJsonUpToTheTwoDocumentedNormalizations() {
        String input = resource(VALID);
        List<Finding> findings = ContractJson.readFindings(input);
        assertEquals(3, findings.size());

        JsonNode written = tree(ContractJson.writeFindings(findings));
        JsonNode expected = tree(input);
        // 1. An empty optional list is omitted: fix_attempts[0].new_fingerprints = [] disappears.
        ((ObjectNode) expected.at("/0/secpipe/fix_attempts/0")).remove("new_fingerprints");
        // 2. A primitive boolean the schema marks optional is always written: guardrail_applied defaults to false.
        ((ObjectNode) expected.at("/2/secpipe/verification")).put("guardrail_applied", false);
        // The example is not in fingerprint order; the writer sorts.
        List<JsonNode> sortedExpected = new ArrayList<>();
        expected.forEach(sortedExpected::add);
        sortedExpected.sort(Comparator.comparing(n -> n.get("fingerprint").stringValue()));
        List<JsonNode> actual = new ArrayList<>();
        written.forEach(actual::add);
        assertEquals(sortedExpected, actual);

        List<Finding> bySortedFingerprint = new ArrayList<>(findings);
        bySortedFingerprint.sort(Comparator.comparing(Finding::fingerprint));
        assertEquals(bySortedFingerprint, ContractJson.readFindings(ContractJson.writeFindings(findings)));
    }

    @Test
    void validExampleIsWrittenByteForByteAsTheCanonicalGoldenFile() {
        String golden = resource(CANONICAL);
        assertEquals(golden, ContractJson.writeFindings(ContractJson.readFindings(resource(VALID))));
        assertEquals(golden, ContractJson.writeFindings(ContractJson.readFindings(golden)));
    }

    @Test
    void maximalAndMinimalExamplesRoundTripWithoutAnyChange() {
        for (String name : List.of(MAXIMAL, MINIMAL)) {
            String input = resource(name);
            List<Finding> findings = ContractJson.readFindings(input);
            String written = ContractJson.writeFindings(findings);
            assertEquals(tree(input), tree(written), name);
            assertEquals(findings, ContractJson.readFindings(written), name);
        }
    }

    @Test
    void outputIsSortedByFingerprintAndIndependentOfInputOrder() {
        List<Finding> findings = new ArrayList<>(ContractJson.readFindings(resource(MAXIMAL)));
        findings.addAll(ContractJson.readFindings(resource(VALID)));
        String reference = ContractJson.writeFindings(findings);

        List<String> fingerprints = new ArrayList<>();
        tree(reference).forEach(n -> fingerprints.add(n.get("fingerprint").stringValue()));
        List<String> sorted = new ArrayList<>(fingerprints);
        Collections.sort(sorted);
        assertEquals(sorted, fingerprints);

        Collections.reverse(findings);
        assertEquals(reference, ContractJson.writeFindings(findings));
        Collections.rotate(findings, 2);
        assertEquals(reference, ContractJson.writeFindings(findings));
    }

    @Test
    void outputUsesLfTwoSpaceIndentAndAFinalNewline() {
        String out = ContractJson.writeFindings(ContractJson.readFindings(resource(MINIMAL)));
        assertFalse(out.contains("\r"), "no CR characters");
        assertTrue(out.endsWith("]\n"), "final newline");
        assertTrue(out.startsWith("[\n  {\n    \"verdict\": \"confirmed\",\n"), out);
        assertTrue(out.contains("\"conditions\": [],"), "empty required list written as []");
    }

    @Test
    void emptyFindingsListIsAnEmptyArray() {
        assertEquals("[]\n", ContractJson.writeFindings(List.of()));
        assertEquals(List.of(), ContractJson.readFindings("[]"));
    }

    @Test
    void readsAndWritesFiles() throws IOException {
        Path dir = Files.createTempDirectory("contract-json");
        Path file = dir.resolve("findings.json");
        List<Finding> findings = ContractJson.readFindings(resource(MAXIMAL));
        ContractJson.writeFindings(file, findings);
        assertEquals(ContractJson.writeFindings(findings), Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(findings, ContractJson.readFindings(file));
        Files.delete(file);
        Files.delete(dir);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Every verdict, built in Java
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void confirmedIsWrittenWithRequiredEmptyListsAndWithoutEmptyOptionals() {
        Finding confirmed = confirmed("c:1");
        JsonNode json = FindingCodec.toTree(confirmed);

        assertEquals(List.of("verdict", "fingerprint", "title", "description", "root_cause", "intended_behavior",
                "trace", "evidence", "conditions", "execution", "remediation", "severity", "confidence", "secpipe"),
                List.copyOf(json.propertyNames()));
        assertEquals("confirmed", json.get("verdict").stringValue());
        assertTrue(json.get("conditions").isArray() && json.get("conditions").isEmpty());
        assertEquals(List.of("category", "origin", "tool_sources", "cwe"),
                List.copyOf(json.get("secpipe").propertyNames()));
        assertEquals(List.of("strategy"), List.copyOf(json.get("remediation").propertyNames()));
        assertEquals("critical", json.at("/severity/impact/score").stringValue());
        assertEquals(confirmed, FindingCodec.findingFromTree(json));
    }

    @Test
    void needsValidationIsWrittenWithoutSeverity() {
        Finding nv = new Finding.NeedsValidation("n:1", "t", "d", "c", trace(), evidence(),
                List.of("No verifier configured"), new ValidationPlan(Optional.of("Re-run"), Optional.empty()),
                secpipe());
        JsonNode json = FindingCodec.toTree(nv);

        assertEquals(List.of("verdict", "fingerprint", "title", "description", "claimed_root_cause", "trace",
                "evidence", "blockers", "validation_plan", "secpipe"), List.copyOf(json.propertyNames()));
        assertEquals("needs_validation", json.get("verdict").stringValue());
        assertEquals(List.of("local"), List.copyOf(json.get("validation_plan").propertyNames()));
        assertEquals(nv, FindingCodec.findingFromTree(json));
    }

    @Test
    void rejectedIsWrittenWithReason() {
        Finding rejected = new Finding.Rejected("r:1", "t", "d", "c", trace(), evidence(), "overwritten", secpipe());
        JsonNode json = FindingCodec.toTree(rejected);

        assertEquals(List.of("verdict", "fingerprint", "title", "description", "claimed_root_cause", "trace",
                "evidence", "reason", "secpipe"), List.copyOf(json.propertyNames()));
        assertEquals("rejected", json.get("verdict").stringValue());
        assertEquals(rejected, FindingCodec.findingFromTree(json));
    }

    @Test
    void enumsAreWrittenAsLowerCaseSnakeNames() {
        SecPipeExt ext = new SecPipeExt(Finding.Category.DEPENDENCY, Finding.Origin.CARRIED_FORWARD,
                List.of(new ToolSource("osv", "GHSA-x", Optional.of(Finding.Score5.INFORMATIONAL), Optional.empty(),
                        Optional.empty())),
                List.of("CWE-1"), List.of(), Optional.empty(),
                Optional.of(new DependencyRef("pkg:maven/a/b@1", "a", "b", "1", Optional.empty(), Optional.empty(),
                        List.of(), DependencyRef.Reachability.UNKNOWN)),
                Optional.empty(), Optional.of(Finding.Level3.HIGH),
                List.of(new TestEvidence("te-000000000000", TestEvidence.Phase.POST_FIX, Optional.empty(),
                        Optional.empty(), TestEvidence.Outcome.FAILED_AS_EXPECTED, 1, Optional.empty(),
                        Optional.empty(), Optional.empty())),
                List.of(), Optional.of(Finding.RemediationStatus.NOT_ATTEMPTED), Optional.empty(), Optional.empty(),
                List.of(), Optional.empty(), Optional.empty());
        Finding f = new Finding.NeedsValidation("n:2", "t", "d", "c",
                List.of(new TraceStep(Finding.TraceKind.ENTRYPOINT, "A.java", 3, "A#m()", "s")), evidence(),
                List.of("b"), new ValidationPlan(Optional.empty(), Optional.of("p")), ext);
        JsonNode s = FindingCodec.toTree(f).get("secpipe");

        assertEquals("needs_validation", FindingCodec.toTree(f).get("verdict").stringValue());
        assertEquals("entrypoint", FindingCodec.toTree(f).at("/trace/0/kind").stringValue());
        assertEquals("dependency", s.get("category").stringValue());
        assertEquals("carried_forward", s.get("origin").stringValue());
        assertEquals("informational", s.at("/tool_sources/0/tool_severity").stringValue());
        assertEquals("unknown", s.at("/dependency/reachable").stringValue());
        assertEquals("high", s.get("confidence_hint").stringValue());
        assertEquals("post_fix", s.at("/test_evidence/0/phase").stringValue());
        assertEquals("failed_as_expected", s.at("/test_evidence/0/outcome").stringValue());
        assertEquals("not_attempted", s.get("remediation_status").stringValue());
        assertEquals(f, FindingCodec.findingFromTree(FindingCodec.toTree(f)));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Strict reading
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void unknownTopLevelPropertyIsAnError() {
        ContractJsonException e = readError(mutate(MINIMAL, a -> ((ObjectNode) a.get(1)).put("severity", "low")));
        assertEquals("/1/severity", e.path());
        assertTrue(e.getMessage().contains("unknown property 'severity'"), e.getMessage());
    }

    @Test
    void unknownNestedPropertyIsAnError() {
        ContractJsonException e = readError(mutate(MAXIMAL,
                a -> ((ObjectNode) a.at("/2/secpipe/fix_attempts/1/adequacy")).put("lines", 1)));
        assertEquals("/2/secpipe/fix_attempts/1/adequacy/lines", e.path());
        assertEquals(Optional.of("lines"), e.property());
    }

    @Test
    void invalidSchemaExampleIsRejectedByTheCodecToo() {
        ContractJsonException e = readError(resource("/examples/findings-invalid-nv-with-severity.json"));
        assertEquals("/0/severity", e.path());
    }

    @Test
    void missingRequiredPropertyIsAnError() {
        assertEquals("/0/secpipe/cwe", readError(mutate(MINIMAL,
                a -> ((ObjectNode) a.at("/0/secpipe")).remove("cwe"))).path());
        assertEquals("/0/conditions", readError(mutate(MINIMAL,
                a -> ((ObjectNode) a.get(0)).remove("conditions"))).path());
        assertEquals("/2/reason", readError(mutate(MINIMAL,
                a -> ((ObjectNode) a.get(2)).remove("reason"))).path());
        assertEquals("/1/verdict", readError(mutate(MINIMAL,
                a -> ((ObjectNode) a.get(1)).remove("verdict"))).path());
    }

    @Test
    void nullWrongTypesAndUnknownEnumSpellingsAreErrors() {
        assertTrue(readError(mutate(MINIMAL, a -> ((ObjectNode) a.get(0)).putNull("title")))
                .getMessage().contains("must not be null"));
        assertEquals("/0/trace/0/line", readError(mutate(MINIMAL,
                a -> ((ObjectNode) a.at("/0/trace/0")).put("line", "1"))).path());
        assertEquals("/0/trace/0/line", readError(mutate(MINIMAL,
                a -> ((ObjectNode) a.at("/0/trace/0")).put("line", 1.5))).path());
        assertEquals("/0/trace/0/line", readError(mutate(MINIMAL,
                a -> ((ObjectNode) a.at("/0/trace/0")).put("line", 5_000_000_000L))).path());
        assertEquals("/0/verdict", readError(mutate(MINIMAL,
                a -> ((ObjectNode) a.get(0)).put("verdict", "CONFIRMED"))).path());
        assertEquals("/0/verdict", readError(mutate(MINIMAL,
                a -> ((ObjectNode) a.get(0)).put("verdict", "maybe"))).path());
        assertEquals("/0/secpipe/tool_sources", readError(mutate(MINIMAL,
                a -> ((ObjectNode) a.at("/0/secpipe")).put("tool_sources", "none"))).path());
        assertEquals("/1/blockers/0", readError(mutate(MINIMAL,
                a -> ((ArrayNode) a.at("/1/blockers")).set(0, a.numberNode(3)))).path());
    }

    @Test
    void malformedDocumentsAreErrors() {
        assertEquals("", readError("{\"verdict\": \"confirmed\"}").path());
        assertEquals("", readError("[").path());
        assertEquals("", readError("[] []").path());
        assertTrue(readError("[{\"verdict\": \"rejected\", \"verdict\": \"rejected\"}]")
                .getMessage().contains("malformed JSON"));
        assertEquals("/0", readError("[1]").path());
    }

    @Test
    void schemaOptionalPrimitivesGetDefaultsOnRead() {
        String json = mutate(MAXIMAL, a -> {
            ((ObjectNode) a.at("/0/secpipe/verification")).remove("guardrail_applied");
            ((ObjectNode) a.at("/2/secpipe/dependency")).remove("reachable");
        });
        List<Finding> findings = ContractJson.readFindings(json);
        assertFalse(findings.get(0).secpipe().verification().orElseThrow().guardrailApplied());
        assertEquals(DependencyRef.Reachability.UNKNOWN,
                findings.get(2).secpipe().dependency().orElseThrow().reachable());
    }

    @Test
    void writingANullRequiredComponentIsAnError() {
        Finding broken = new Finding.Rejected("r:1", null, "d", "c", trace(), evidence(), "r", secpipe());
        ContractJsonException e = assertThrows(ContractJsonException.class,
                () -> ContractJson.writeFindings(List.of(broken)));
        assertEquals("/0/title", e.path());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Shared mapper
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void mapperUsesTheCodecForContractTypes() {
        List<Finding> findings = ContractJson.readFindings(resource(MAXIMAL));
        String viaMapper = ContractJson.mapper().writeValueAsString(findings);
        assertEquals(tree(resource(MAXIMAL)), tree(viaMapper));

        List<Finding> back = ContractJson.mapper().readValue(viaMapper, new TypeReference<List<Finding>>() { });
        assertEquals(findings, back);
        Finding.Rejected rejected = ContractJson.mapper()
                .readValue(tree(viaMapper).get(0).toString(), Finding.Rejected.class);
        assertEquals(findings.get(0), rejected);
        TraceStep step = ContractJson.mapper().readValue(
                "{\"kind\":\"sink\",\"file\":\"A.java\",\"line\":2,\"scope\":\"A#m()\",\"description\":\"d\"}",
                TraceStep.class);
        assertEquals(new TraceStep(Finding.TraceKind.SINK, "A.java", 2, "A#m()", "d"), step);
    }

    @Test
    void mapperRejectsAVerdictThatDoesNotMatchTheRequestedRecord() {
        String nv = tree(resource(MINIMAL)).get(1).toString();
        DatabindException e = assertThrows(DatabindException.class,
                () -> ContractJson.mapper().readValue(nv, Finding.Confirmed.class));
        assertInstanceOf(ContractJsonException.class, e.getCause());
        assertThrows(DatabindException.class, () -> ContractJson.mapper().readValue(
                "{\"kind\":\"sink\",\"file\":\"A.java\",\"line\":2,\"scope\":\"s\",\"description\":\"d\",\"x\":1}",
                TraceStep.class));
    }

    @Test
    void mapperWithExtraModulesKeepsTheContractConfiguration() {
        assertSame(ContractJson.mapper(), ContractJson.mapper(new JacksonModule[0]));
        JsonMapper extended = ContractJson.mapper(new SimpleModule("extra"));
        Finding f = confirmed("c:9");
        assertEquals(FindingCodec.toTree(f), extended.valueToTree(f));
        record Plain(String someField, Optional<String> absent, List<String> none, Finding.Score5 level) { }
        assertEquals("{\"some_field\":\"x\",\"level\":\"informational\"}",
                extended.writeValueAsString(new Plain("x", Optional.empty(), List.of(), Finding.Score5.INFORMATIONAL)));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Candidates
    // ---------------------------------------------------------------------------------------------------------

    @Test
    void candidatesRoundTripAndOmitEmptyOptionals() {
        Candidate bare = new Candidate(Optional.empty(), Finding.Category.CODE, Finding.Origin.TOOL,
                List.of(new ToolSource("builtin", "SP-JAVA-SQLI", Optional.empty(), Optional.of("A.java"),
                        Optional.of(4))),
                List.of("CWE-89"), List.of(), "SQLi", "concatenated query", "concat",
                trace(), evidence(), Optional.of("stmt.executeQuery(q)"), Optional.of("A#m()"), Optional.empty(),
                Optional.of(Finding.Score5.HIGH), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), List.of());
        Candidate full = new Candidate(Optional.of("code:CWE-89:abc"), Finding.Category.DEPENDENCY,
                Finding.Origin.HUNTER, List.of(), List.of(), List.of("CVE-2020-1"), "t", "m", "c", trace(),
                evidence(), Optional.empty(), Optional.empty(),
                Optional.of(new DependencyRef("pkg:maven/a/b@1", "a", "b", "1", Optional.of("2"),
                        Optional.of("runtime"), List.of("a:b:1"), DependencyRef.Reachability.NO)),
                Optional.empty(), Optional.of(Finding.Level3.LOW), Optional.of("none"), Optional.of("bind"),
                Optional.of("q=' OR 1=1"), List.of("cov:0123456789abcdef0123456789abcdef"));

        String out = ContractJson.writeCandidates(List.of(bare, full));
        assertEquals(List.of(bare, full), ContractJson.readCandidates(out));
        JsonNode first = tree(out).get(0);
        assertEquals(List.of("category", "origin", "tool_sources", "cwe", "title", "message", "claimed_root_cause",
                "trace", "evidence", "sink_statement", "sink_scope", "tool_severity_hint"),
                List.copyOf(first.propertyNames()));
        assertEquals("tool", first.get("origin").stringValue());
        assertTrue(out.endsWith("]\n") && !out.contains("\r"));
    }

    @Test
    void candidateWithUnknownPropertyIsAnError() {
        Candidate c = new Candidate(Optional.empty(), Finding.Category.CODE, Finding.Origin.TOOL, List.of(),
                List.of(), List.of(), "t", "m", "c", trace(), evidence(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), List.of());
        ArrayNode array = (ArrayNode) tree(ContractJson.writeCandidates(List.of(c)));
        ((ObjectNode) array.get(0)).put("verdict", "confirmed");
        ContractJsonException e = assertThrows(ContractJsonException.class,
                () -> ContractJson.readCandidates(array.toString()));
        assertEquals("/0/verdict", e.path());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    static Finding confirmed(String fingerprint) {
        return new Finding.Confirmed(fingerprint, "t", "d", "rc", "ib", trace(), evidence(), List.of(),
                new Execution("anon", List.of("p"), List.of("run"), "observed"),
                new Remediation("bind parameters", List.of()),
                new Severity(new Scored(Finding.Score5.HIGH, "r"), new Scored(Finding.Score5.CRITICAL, "r"),
                        Finding.Score5.CRITICAL),
                new Confidence(Finding.Level3.HIGH, "r"), secpipe());
    }

    private static List<TraceStep> trace() {
        return List.of(new TraceStep(Finding.TraceKind.SINK, "src/A.java", 7, "A#m()", "sink"));
    }

    private static List<Evidence> evidence() {
        return List.of(new Evidence("src/A.java", 7, "line"));
    }

    private static SecPipeExt secpipe() {
        return new SecPipeExt(Finding.Category.CODE, Finding.Origin.TOOL, List.of(), List.of(), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty());
    }

    private static ContractJsonException readError(String json) {
        return assertThrows(ContractJsonException.class, () -> ContractJson.readFindings(json));
    }

    private static String mutate(String resourceName, Consumer<ArrayNode> change) {
        ArrayNode array = (ArrayNode) tree(resource(resourceName));
        change.accept(array);
        return array.toString();
    }

    private static JsonNode tree(String json) {
        return ContractJson.mapper().readTree(json);
    }

    static String resource(String name) {
        try (InputStream in = FindingCodecTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
