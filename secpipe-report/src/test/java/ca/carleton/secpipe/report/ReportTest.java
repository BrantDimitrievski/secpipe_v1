package ca.carleton.secpipe.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.config.SecPipeConfig.OutputFormat;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.json.ContractJson;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** WO-R.1: golden files, SARIF schema, determinism and validation-first behaviour of the report writers. */
class ReportTest {

    private static final ReportRenderer RENDERER = new DefaultReportRenderer();

    private static ReportBundle bundle(ReportInputs inputs) {
        ReportBundle b = ReportBundle.render(inputs, RENDERER, Fixtures.allFormats());
        assertTrue(b.ok(), () -> "bundle failed: " + b.incompleteReason());
        return b;
    }

    private static void golden(String file) {
        assertEquals(Fixtures.resource("golden/" + file), bundle(Fixtures.inputs()).files().get(file),
                "golden mismatch for " + file);
    }

    // ------------------------------------------------------------------------------------------ golden files

    @Test
    void findingsJsonMatchesGolden() {
        golden(ReportBundle.FINDINGS);
    }

    @Test
    void ledgerJsonMatchesGolden() {
        golden(ReportBundle.LEDGER);
    }

    @Test
    void runMetadataJsonMatchesGolden() {
        golden(ReportBundle.METADATA);
    }

    @Test
    void sarifMatchesGolden() {
        golden(ReportBundle.SARIF);
    }

    @Test
    void markdownMatchesGolden() {
        golden(ReportBundle.MARKDOWN);
    }

    // ------------------------------------------------------------------------------------------ determinism

    @Test
    void twoRendersOfTheSameInputAreByteIdenticalForAllFiveFiles() {
        Map<String, String> first = bundle(Fixtures.inputs()).files();
        Map<String, String> second = bundle(Fixtures.inputs()).files();
        assertEquals(Set.of("findings.json", "coverage-ledger.json", "run-metadata.json", "secpipe.sarif",
                "report.md"), first.keySet());
        assertEquals(first, second);
    }

    @Test
    void recordInsertionOrderDoesNotChangeAnyFile() {
        Map<String, String> forward = bundle(Fixtures.inputs()).files();
        Map<String, String> reversed = bundle(new ReportInputs(Fixtures.reversedState(), Fixtures.metadata())).files();
        assertEquals(forward, reversed);
    }

    @Test
    void everyFileIsLfOnlyWithOneFinalNewline() {
        bundle(Fixtures.inputs()).files().forEach((name, text) -> {
            assertFalse(text.contains("\r"), name + " contains CR");
            assertTrue(text.endsWith("\n"), name + " lacks a final newline");
            assertFalse(text.endsWith("\n\n"), name + " ends with a blank line");
        });
    }

    @Test
    void sarifAndMarkdownCarryNoTimestamps() {
        Map<String, String> files = bundle(Fixtures.inputs()).files();
        for (String name : List.of(ReportBundle.SARIF, ReportBundle.MARKDOWN, ReportBundle.FINDINGS,
                ReportBundle.LEDGER)) {
            assertFalse(files.get(name).contains("2026-11-17T14"), name + " leaks a run timestamp");
        }
        assertTrue(files.get(ReportBundle.METADATA).contains("2026-11-17T14:30:05Z"));
    }

    // ------------------------------------------------------------------------------------------ SARIF

    @Test
    void sarifValidatesAgainstTheSchema() {
        assertEquals(List.of(), SarifSchema.errors(bundle(Fixtures.inputs()).files().get(ReportBundle.SARIF)));
    }

    @Test
    void sarifSchemaRejectsBrokenDocuments() {
        assertFalse(SarifSchema.errors("{\"version\":\"2.0.0\",\"runs\":[]}").isEmpty());
        assertFalse(SarifSchema.errors("{\"version\":\"2.1.0\",\"runs\":[{\"tool\":{\"driver\":{\"name\":\"x\"}},"
                + "\"results\":[{\"message\":{\"text\":\"m\"},\"level\":\"fatal\"}]}]}").isEmpty());
    }

    @Test
    void sarifHasOneRulePerCweFamilyAndFingerprintsAndSuppressions() {
        JsonNode run = ContractJson.mapper().readTree(bundle(Fixtures.inputs()).files().get(ReportBundle.SARIF))
                .path("runs").get(0);
        // Fixture CWEs: 89, 89 (rejected), 78 -> two rules, sorted by id.
        JsonNode rules = run.path("tool").path("driver").path("rules");
        assertEquals(2, rules.size());
        assertEquals("CWE-78", rules.get(0).path("id").stringValue());
        assertEquals("CWE-89", rules.get(1).path("id").stringValue());

        JsonNode results = run.path("results");
        assertEquals(3, results.size());
        int suppressed = 0;
        for (JsonNode r : results) {
            String fingerprint = r.path("partialFingerprints").path("secpipe/v1").stringValue();
            assertTrue(fingerprint.startsWith("code:CWE-"), fingerprint);
            assertEquals(r.path("ruleId").stringValue(), rules.get(r.path("ruleIndex").asInt()).path("id")
                    .stringValue());
            boolean rejected = "rejected".equals(r.path("properties").path("verdict").stringValue());
            assertEquals(rejected, r.has("suppressions"), fingerprint);
            suppressed += r.has("suppressions") ? 1 : 0;
        }
        assertEquals(1, suppressed);
        assertEquals("code:CWE-89:0f0e0d0c0b0a090807060504",
                results.get(1).path("partialFingerprints").path("secpipe/v1").stringValue());
        assertTrue(results.get(1).has("suppressions"));
        // sorted by fingerprint: code:CWE-78 < code:CWE-89:0f0e < code:CWE-89:3f9a
        assertEquals("code:CWE-78:aa11bb22cc33dd44ee55ff66",
                results.get(0).path("partialFingerprints").path("secpipe/v1").stringValue());
    }

    // ------------------------------------------------------------------------------------------ markdown

    @Test
    void markdownHasOneSectionPerFindingAndACoverageSummary() {
        String md = bundle(Fixtures.inputs()).files().get(ReportBundle.MARKDOWN);
        assertEquals(3, md.lines().filter(l -> l.startsWith("### ")).count());
        assertTrue(md.contains("## Summary"));
        assertTrue(md.contains("## Coverage"));
        assertTrue(md.contains("Totals: 1 confirmed, 1 needs validation, 1 rejected."));
    }

    @Test
    void markdownEscapesUntrustedText() {
        List<Finding> findings = Fixtures.findings().stream()
                .map(f -> f instanceof Finding.NeedsValidation nv
                        ? new Finding.NeedsValidation(nv.fingerprint(), "<script>alert(1)</script> | [x](http://e)",
                                "line one\n\n# injected heading\n`tick`", nv.claimedRootCause(), nv.trace(),
                                nv.evidence(), nv.blockers(), nv.validationPlan(), nv.secpipe())
                        : f)
                .toList();
        PipelineState state = Fixtures.state(findings, Optional.empty(), List.of());
        String md = MarkdownWriter.render(new ReportInputs(state, Fixtures.metadata()));
        assertFalse(md.contains("<script>"));
        assertTrue(md.contains("\\<script\\>"));
        assertFalse(md.lines().anyMatch(l -> l.startsWith("# injected")));
        assertTrue(md.contains("\\[x\\]"));
    }

    // ------------------------------------------------------------------------------------------ validation first

    @Test
    void invalidFindingsProduceNoFilesAndAnIncompleteReason() {
        List<Finding> bad = Fixtures.findings().stream()
                .map(f -> f instanceof Finding.Confirmed c
                        ? new Finding.Confirmed(c.fingerprint(), c.title(), c.description(), c.rootCause(),
                                c.intendedBehavior(), List.of(c.trace().getLast()), c.evidence(), c.conditions(),
                                c.execution(), c.remediation(), c.severity(), c.confidence(), c.secpipe())
                        : f)
                .toList();
        ReportInputs inputs = new ReportInputs(Fixtures.state(bad, Optional.empty(), List.of()),
                Fixtures.metadata());
        ReportBundle b = ReportBundle.render(inputs, RENDERER, Fixtures.allFormats());
        assertFalse(b.ok());
        assertTrue(b.files().isEmpty());
        assertTrue(b.incompleteReason().orElseThrow().contains("F2"), b.incompleteReason().orElseThrow());
    }

    @Test
    void formatsSelectOptionalFilesButTheThreeJsonFilesAreAlwaysWritten() {
        ReportBundle b = ReportBundle.render(Fixtures.inputs(), RENDERER, Set.of(OutputFormat.MARKDOWN));
        assertEquals(Set.of("findings.json", "coverage-ledger.json", "run-metadata.json", "report.md"),
                b.files().keySet());
    }

    @Test
    void emptyRunStillRendersValidFiles() {
        PipelineState empty = new PipelineState(Fixtures.RUN_ID, Fixtures.config(Fixtures.allFormats()),
                Optional.empty(), Optional.empty(), List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of(),
                Map.of(), 0, 0, false, Map.of(), Optional.empty(), List.of(), Optional.empty());
        ReportBundle b = bundle(new ReportInputs(empty, Fixtures.metadata()));
        assertEquals(0, ContractJson.readFindings(b.files().get(ReportBundle.FINDINGS)).size());
        assertEquals(List.of(), SarifSchema.errors(b.files().get(ReportBundle.SARIF)));
        assertTrue(b.files().get(ReportBundle.MARKDOWN).contains("No findings."));
    }

    // ------------------------------------------------------------------------------------------ metadata

    @Test
    void assemblerDerivesCountsAndTerminalStateFromTheState() {
        PipelineState state = Fixtures.state(Fixtures.findings(), Optional.of("validator rejected delta"),
                List.of());
        RunMetadata meta = RunMetadataAssembler.assemble(state, java.time.Instant.parse("2026-11-17T15:00:00Z"),
                new FixedBudget(), false, Fixtures.ai());
        assertEquals(RunMetadata.TerminalState.INCOMPLETE, meta.terminalState());
        assertEquals(Optional.of("validator rejected delta"), meta.incompleteReason());
        assertEquals(1, meta.counts().confirmed());
        assertEquals(1, meta.counts().needsValidation());
        assertEquals(1, meta.counts().rejected());
        assertEquals(Map.of("critical", 1), meta.counts().bySeverity());
        assertEquals(Map.of("candidate", 1), meta.counts().ledgerUnits());
        ReportBundle b = ReportBundle.render(new ReportInputs(state, meta), RENDERER, Fixtures.allFormats());
        assertTrue(b.ok(), () -> String.valueOf(b.incompleteReason()));
        assertTrue(b.files().get(ReportBundle.MARKDOWN).contains("- Incomplete because: validator rejected delta"));
    }
}
