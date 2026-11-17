package ca.carleton.secpipe.validators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.json.ContractJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/** WO-V.1: schema + rules F1–F9, the error cap and the input limits. Fixtures live under {@code findings/}. */
class FindingsValidatorTest {

    private static final String TAG_FILTER = "src/main/java/com/shop/app/TagFilter.java";

    // ---------------------------------------------------------------------------------------------- whole files

    @Test
    void validFixtureHasNoErrors() {
        assertOk(validate("valid.json"));
    }

    @Test
    void contractExampleIsValidWithoutRoot() throws IOException {
        Path example = Path.of(System.getProperty("user.dir")).resolveSibling("secpipe-contracts")
                .resolve("src/test/resources/examples/findings-valid.json");
        String json = Files.readString(example, StandardCharsets.UTF_8);
        assertTrue(json.contains("te-0a1b2c3d4e5f"), "example should carry the F3 test evidence id");
        assertOk(FindingsValidator.validateJson(json));
    }

    @Test
    void recordApiAcceptsDecodedFindings() {
        List<Finding> findings = ContractJson.readFindings(resource("valid.json"));
        assertOk(FindingsValidator.validate(findings, Optional.empty(), CapPolicy.NONE));
    }

    // ---------------------------------------------------------------------------------------------- F1

    @Test
    void f1PassesDistinctFingerprints() {
        assertOk(validate("f1-pass.json"));
    }

    @Test
    void f1RejectsDuplicateFingerprint() {
        ValidationResult r = validate("f1-fail-duplicate.json");
        assertOnly(r, "F1");
        assertEquals("/1/fingerprint", r.errors().getFirst().path());
        assertEquals(Optional.of("code:CWE-78:aa11bb22cc33dd44ee55ff66"), r.errors().getFirst().fingerprint());
        assertTrue(r.errors().getFirst().message().startsWith("code:CWE-78:aa11bb22cc33dd44ee55ff66: "),
                "message names the fingerprint");
    }

    // ---------------------------------------------------------------------------------------------- F2

    @Test
    void f2PassesRejectedTraceWithoutEntrypoint() {
        assertOk(validate("f2-pass-rejected-without-entrypoint.json"));
    }

    @Test
    void f2RejectsConfirmedTraceWithoutEntrypoint() {
        ValidationResult r = validate("f2-fail-confirmed-no-entrypoint.json");
        assertOnly(r, "F2");
        assertEquals("/0/trace/0/kind", r.errors().getFirst().path());
    }

    @Test
    void f2RejectsTraceNotEndingInSink() {
        assertOnly(validate("f2-fail-rejected-not-sink.json"), "F2");
        assertOnly(validate("f2-fail-needs-validation-entrypoint-only.json"), "F2");
    }

    // ---------------------------------------------------------------------------------------------- F3

    @Test
    void f3PassesCodeAndDependencyEvidence() {
        assertOk(validate("f3-pass-code-test-evidence.json"));
        assertOk(validate("f3-pass-dependency-resolved.json"));
    }

    @Test
    void f3RejectsConfirmedCodeWithoutFailingPreFixTest() {
        assertOnly(validate("f3-fail-no-failing-pre-fix-test.json"), "F3");
        assertOnly(validate("f3-fail-evidence-wrong-phase.json"), "F3");
    }

    @Test
    void f3RejectsObservedResultNotStartingWithEvidenceId() {
        ValidationResult r = validate("f3-fail-observed-result-without-id.json");
        assertOnly(r, "F3");
        assertEquals("/0/execution/observed_result", r.errors().getFirst().path());
    }

    @Test
    void f3RejectsDependencyWithoutRefOrResolution() {
        assertOnly(validate("f3-fail-dependency-missing-ref.json"), "F3");
        assertOnly(validate("f3-fail-dependency-not-resolved.json"), "F3");
    }

    // ---------------------------------------------------------------------------------------------- F4

    @Test
    void f4PassesOverallEqualToMax() {
        assertOk(validate("f4-pass-equal-to-max.json"));
    }

    @Test
    void f4RejectsOverallAboveMax() {
        ValidationResult r = validate("f4-fail-above-max.json");
        assertOnly(r, "F4");
        assertEquals("/0/severity/overall_severity", r.errors().getFirst().path());
    }

    @Test
    void f4AppliesCapPolicy() {
        String json = resource("f4-pass-equal-to-max.json");
        assertOk(FindingsValidator.validateJson(json, Optional.empty(), f -> Optional.of(Finding.Score5.CRITICAL)));
        ValidationResult capped =
                FindingsValidator.validateJson(json, Optional.empty(), f -> Optional.of(Finding.Score5.INFORMATIONAL));
        assertOnly(capped, "F4");
        assertTrue(capped.errors().getFirst().message().contains("scoring cap informational"));
    }

    // ---------------------------------------------------------------------------------------------- F5

    @Test
    void f5PassesHintOnNeedsValidation() {
        assertOk(validate("f5-pass-hint-on-needs-validation.json"));
    }

    @Test
    void f5RejectsHintOutsideNeedsValidation() {
        assertOnly(validate("f5-fail-hint-on-confirmed.json"), "F5");
        assertOnly(validate("f5-fail-hint-on-rejected.json"), "F5");
    }

    @Test
    void f5RejectsSeverityOnNeedsValidation() {
        ValidationResult r = validate("f5-fail-needs-validation-with-severity.json");
        assertTrue(r.errors().stream().anyMatch(e -> e.code().equals("F5") && e.path().equals("/0/severity")),
                () -> "expected F5 at /0/severity, got " + r.errors());
    }

    // ---------------------------------------------------------------------------------------------- F6

    @Test
    void f6PassesGuardedOrNonToolRejections() {
        assertOk(validate("f6-pass-hunter-origin-without-disprover.json"));
        assertOk(validate("f6-pass-small-tier-with-deterministic.json"));
        assertOk(validate("f6-pass-small-tier-with-strong-second.json"));
    }

    @Test
    void f6RejectsToolRejectionWithoutDisprover() {
        assertOnly(validate("f6-fail-tool-rejected-without-disprover.json"), "F6");
    }

    @Test
    void f6RejectsSmallTierAsSoleReason() {
        assertOnly(validate("f6-fail-small-tier-alone.json"), "F6");
        assertOnly(validate("f6-fail-small-tier-with-small-second.json"), "F6");
    }

    // ---------------------------------------------------------------------------------------------- F7

    @Test
    void f7PassesWhenFilesExistUnderRoot(@TempDir Path root) throws IOException {
        touch(root, TAG_FILTER);
        assertOk(FindingsValidator.validateJson(resource("f7-pass.json"), Optional.of(root), CapPolicy.NONE));
    }

    @Test
    void f7RejectsMissingFileOnlyWithRoot(@TempDir Path root) throws IOException {
        touch(root, TAG_FILTER);
        String json = resource("f7-fail-missing-file.json");
        assertOk(FindingsValidator.validateJson(json));
        ValidationResult r = FindingsValidator.validateJson(json, Optional.of(root), CapPolicy.NONE);
        assertOnly(r, "F7");
        assertTrue(r.errors().getFirst().message().contains("Missing.java"));
    }

    @Test
    void f7RejectsAbsoluteAndDotDotPaths() {
        assertOnly(validate("f7-fail-drive-letter.json"), "F7");
        ValidationResult r = validate("f7-fail-backslash-dotdot.json");
        assertOnly(r, "F7");
        assertEquals("/0/secpipe/tool_sources/0/file", r.errors().getFirst().path());
    }

    @Test
    void f7ReportsUnreadableRoot(@TempDir Path tmp) {
        ValidationResult r = FindingsValidator.validateJson(resource("f7-pass.json"),
                Optional.of(tmp.resolve("does-not-exist")), CapPolicy.NONE);
        assertOnly(r, "F7");
        assertEquals("", r.errors().getFirst().path());
    }

    // ---------------------------------------------------------------------------------------------- F8

    @Test
    void f8PassesWhenLastAttemptFullyVerified() {
        assertOk(validate("f8-pass-last-attempt-fixed.json"));
    }

    @Test
    void f8RejectsFixedWithoutVerifiedLastAttempt() {
        assertOnly(validate("f8-fail-no-attempts.json"), "F8");
        ValidationResult r = validate("f8-fail-last-attempt-not-fixed.json");
        assertOnly(r, "F8");
        assertEquals("/0/secpipe/fix_attempts/1", r.errors().getFirst().path());
        assertOnly(validate("f8-fail-rescan-not-clean.json"), "F8");
        assertOnly(validate("f8-fail-suite-unknown.json"), "F8");
    }

    // ---------------------------------------------------------------------------------------------- F9

    @Test
    void f9PassesDependencyShape() {
        assertOk(validate("f9-pass-dependency.json"));
    }

    @Test
    void f9RejectsWrongShapeForCategory() {
        assertOnly(validate("f9-fail-code-uppercase-hex.json"), "F9");
        assertOnly(validate("f9-fail-code-with-dependency-shape.json"), "F9");
        assertOnly(validate("f9-fail-dependency-with-code-shape.json"), "F9");
    }

    @Test
    void f9RejectsNonFamilyRootCwe() {
        ValidationResult r = validate("f9-fail-code-not-family-root.json");
        assertOnly(r, "F9");
        assertTrue(r.errors().getFirst().message().contains("CWE-78"));
    }

    // ---------------------------------------------------------------------------------------------- schema, limits

    @Test
    void schemaFailureSurfacesWithJsonPointer() {
        ValidationResult r = validate("schema-fail-line-zero.json");
        assertFalse(r.ok());
        assertTrue(r.errors().stream().allMatch(e -> e.code().startsWith("schema.")), () -> r.errors().toString());
        assertTrue(r.errors().stream()
                        .anyMatch(e -> e.code().equals("schema.minimum") && e.path().equals("/0/trace/0/line")),
                () -> "expected schema.minimum at /0/trace/0/line, got " + r.errors());
    }

    @Test
    void malformedJsonIsOneError() {
        ValidationResult r = FindingsValidator.validateJson("[{\"verdict\": ");
        assertEquals(List.of("json.malformed"), codes(r));
    }

    @Test
    void errorsAreCappedAtOneHundred() {
        JsonNode record = ContractJson.mapper().readTree(resource("f1-pass.json")).get(0);
        ArrayNode many = ContractJson.mapper().createArrayNode();
        for (int i = 0; i < 150; i++) {
            many.add(record.deepCopy());
        }
        ValidationResult r = FindingsValidator.validateJson(many.toString());
        assertEquals(ValidationResult.MAX_ERRORS, r.errors().size());
        assertEquals(Set.of("F1"), Set.copyOf(codes(r)));
        assertEquals("/1/fingerprint", r.errors().getFirst().path());
    }

    @Test
    void inputOverTwentyMebibytesIsRejected() {
        String json = "[" + " ".repeat(FindingsValidator.MAX_BYTES) + "]";
        assertEquals(List.of("limit.size"), codes(FindingsValidator.validateJson(json)));
        String atLimit = "[" + " ".repeat(FindingsValidator.MAX_BYTES - 2) + "]";
        assertOk(FindingsValidator.validateJson(atLimit));
    }

    @Test
    void moreThanTenThousandRecordsIsRejected() {
        String json = Collections.nCopies(FindingsValidator.MAX_RECORDS + 1, "{}").stream()
                .collect(Collectors.joining(",", "[", "]"));
        assertEquals(List.of("limit.records"), codes(FindingsValidator.validateJson(json)));
        Finding one = ContractJson.readFindings(resource("f1-pass.json")).getFirst();
        List<Finding> tooMany = Collections.nCopies(FindingsValidator.MAX_RECORDS + 1, one);
        assertEquals(List.of("limit.records"),
                codes(FindingsValidator.validate(tooMany, Optional.empty(), CapPolicy.NONE)));
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static ValidationResult validate(String fixture) {
        return FindingsValidator.validateJson(resource(fixture));
    }

    private static String resource(String fixture) {
        try (InputStream in = FindingsValidatorTest.class.getResourceAsStream("findings/" + fixture)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture findings/" + fixture);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void touch(Path root, String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class X {}\n");
    }

    private static List<String> codes(ValidationResult r) {
        return r.errors().stream().map(ValidationError::code).toList();
    }

    private static void assertOk(ValidationResult r) {
        assertTrue(r.ok(), () -> "expected no errors, got " + r.errors());
    }

    private static void assertOnly(ValidationResult r, String rule) {
        assertFalse(r.ok(), "expected a " + rule + " error");
        assertEquals(Set.of(rule), Set.copyOf(codes(r)), () -> "errors: " + r.errors());
    }
}
