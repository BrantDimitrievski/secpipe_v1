package ca.carleton.secpipe.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.config.SecPipeConfig.FixMode;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.json.ContractJson;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.report.patch.FixedPatches;
import ca.carleton.secpipe.report.patch.PatchSource;
import ca.carleton.secpipe.report.patch.RemediationFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RemediationReportTest {

    private static final ReportRenderer RENDERER = new DefaultReportRenderer();
    private static final PatchSource PATCHES = RemediationFixtures::patch;
    /** Exactly the run note WO-M.3 writes (RemediationLoopNode). */
    private static final String UNVERIFIED_NOTE = "remediation of " + RemediationFixtures.FP_UNVERIFIED
            + " attempt 1: regression test skipped after patch: fix unverified (not retried)";
    private static final String NOT_ATTEMPTED_NOTE = "remediation not attempted for "
            + RemediationFixtures.FP_NOT_ATTEMPTED + ": no regression test available";

    private static List<Finding> findings() {
        List<Finding> all = new ArrayList<>(Fixtures.findings());
        all.add(RemediationFixtures.stillPresent());
        all.add(RemediationFixtures.unverified());
        all.add(RemediationFixtures.notAttempted());
        return all;
    }

    private static ReportInputs inputs(List<Finding> findings, FixMode mode, List<String> notes) {
        PipelineState s = Fixtures.state(findings, Optional.empty(), notes);
        PipelineState withMode = new PipelineState(s.runId(), configWith(s.config(), mode), s.environment(),
                s.profile(), s.diffScope(), s.ledger(), s.candidates(), s.records(), s.survivorDrafts(),
                s.toolRuns(), s.stageTimingsMs(), s.hunterWave(), s.criticPasses(), s.criticSaysStop(),
                s.fixAttemptsByFingerprint(), s.prior(), s.notes(), s.incompleteReason());
        return new ReportInputs(withMode, Fixtures.metadata());
    }

    private static ReportInputs inputs() {
        return inputs(findings(), FixMode.SUGGEST, List.of(UNVERIFIED_NOTE, NOT_ATTEMPTED_NOTE,
                "remediation of " + RemediationFixtures.FP_STILL + " stopped: agent-call budget exhausted"));
    }

    private static SecPipeConfig configWith(SecPipeConfig c, FixMode mode) {
        return new SecPipeConfig(c.target(), c.outDir(), c.profile(), c.scope(), c.baseRef(), c.headRef(),
                c.llmProvider(), c.defaultModel(), c.modelForRole(), c.tierOverrides(), c.llmBaseUrl(), c.tools(),
                c.excludeTools(), c.maxAgentCalls(), c.maxFixAttempts(), c.maxSandboxRuns(), mode,
                c.sandboxEnabled(), c.offline(), c.failOnSeverity(), c.failOnNeedsValidation(),
                c.failOnIncomplete(), c.formats(), c.priorRunDir(), c.printPrompts(), c.denyPaths(),
                c.maxSnippetLines(), c.maxFilesPerCall(), c.disabledNodes(), c.includeTests(),
                c.allowBuildExtensions(), c.acceptCodeqlTerms(), c.engine(), c.toolTimeout(), c.verbosity());
    }

    private static ReportBundle bundle(ReportInputs in) {
        ReportBundle b = ReportBundle.render(in, RENDERER, Fixtures.allFormats(), PATCHES);
        assertTrue(b.ok(), () -> "bundle failed: " + b.incompleteReason());
        return b;
    }

    // ------------------------------------------------------------------------------------------ patch file

    @Test
    void onlyFixedPatchesReachTheCombinedPatchFile() {
        String patch = bundle(inputs()).files().get(ReportBundle.FIXES_PATCH);
        assertEquals(RemediationFixtures.patch("patches/code-CWE-89-3f9a1c0d.patch").orElseThrow(), patch);
        assertFalse(patch.contains("AdminServlet"));
        assertFalse(patch.contains("ExportServlet"));
    }

    @Test
    void noFixedRecordMeansNoPatchFileAndNoFixesInSarif() {
        List<Finding> none = List.of(RemediationFixtures.stillPresent(), RemediationFixtures.notAttempted());
        ReportBundle b = bundle(inputs(none, FixMode.SUGGEST, List.of()));
        assertFalse(b.files().containsKey(ReportBundle.FIXES_PATCH));
        assertFalse(b.files().get(ReportBundle.SARIF).contains("\"fixes\""));
    }

    @Test
    void writeBuildsThePatchFromTheOutputDirEvenWhenRenderedWithoutPatchAccess()
            throws IOException {
        Path dir = Files.createTempDirectory("secpipe-r4");
        ReportInputs in = inputs();
        ReportBundle b = ReportBundle.render(in, RENDERER, Fixtures.allFormats());
        assertFalse(b.files().containsKey(ReportBundle.FIXES_PATCH));
        Files.createDirectories(dir.resolve("patches"));
        for (String name : List.of("code-CWE-89-3f9a1c0d.patch", "code-CWE-89-b2b2b2b2-attempt2.patch")) {
            Files.writeString(dir.resolve("patches/" + name), RemediationFixtures.patch("patches/" + name)
                    .orElseThrow());
        }
        b.write(dir);
        assertEquals(RemediationFixtures.patch("patches/code-CWE-89-3f9a1c0d.patch").orElseThrow(),
                Files.readString(dir.resolve(FixedPatches.FILE_NAME)));

        // a stale file from an earlier run disappears when nothing is fixed any more
        ReportBundle none = ReportBundle.render(inputs(List.of(RemediationFixtures.stillPresent()), FixMode.SUGGEST,
                List.of()), RENDERER, Fixtures.allFormats());
        none.write(dir);
        assertFalse(Files.exists(dir.resolve(FixedPatches.FILE_NAME)));
    }

    // ------------------------------------------------------------------------------------------ markdown

    @Test
    void markdownRemediationSectionMatchesGolden() {
        String md = bundle(inputs()).files().get(ReportBundle.MARKDOWN);
        String section = md.substring(md.indexOf("## Remediation"), md.indexOf("## Coverage"));
        assertEquals(RemediationFixtures.resource("remediation/golden/remediation-section.md"), section);
    }

    @Test
    void markdownShowsFixedStillPresentUnverifiedAndNotAttempted() {
        String md = bundle(inputs()).files().get(ReportBundle.MARKDOWN);
        assertTrue(md.contains("- Outcome: fixed\n"));
        assertTrue(md.contains("- Patch (verified): [patches/code-CWE-89-3f9a1c0d.patch]"));
        assertTrue(md.contains("- Outcome: still_present\n"));
        assertTrue(md.contains("- Outcome: still_present (fix unverified"));
        assertTrue(md.contains("- Outcome: not_attempted\n"));
        assertTrue(md.contains("- Reason: no regression test available\n"));
        assertTrue(md.contains("- Stopped: agent-call budget exhausted\n"));
        assertTrue(md.contains("Patch (candidate, not verified)"));
    }

    @Test
    void unverifiedIsDetectedFromTheM3RunNoteAloneAndNotFromAnUnrelatedNote() {
        Finding.Confirmed plain = RemediationFixtures.stillPresent();
        String fp = plain.fingerprint();
        String note = "remediation of " + fp + " attempt 2: regression test skipped after patch: fix unverified"
                + " (not retried)";
        String withNote = bundle(inputs(List.of(plain), FixMode.SUGGEST, List.of(note))).files()
                .get(ReportBundle.MARKDOWN);
        assertTrue(withNote.contains("- Outcome: still_present (fix unverified"));
        String without = bundle(inputs(List.of(plain), FixMode.SUGGEST, List.of("finding " + fp + ": fix unverified")))
                .files().get(ReportBundle.MARKDOWN);
        assertTrue(without.contains("- Outcome: still_present\n"));
    }

    @Test
    void notAttemptedShowsTheDisabledReasonWhenFixesAreOff() {
        ReportInputs in = inputs(List.of(RemediationFixtures.notAttempted()), FixMode.FALSE, List.of());
        String md = bundle(in).files().get(ReportBundle.MARKDOWN);
        assertTrue(md.contains("- Reason: fixes are disabled (--enable-fixes false)\n"), md);
    }

    @Test
    void markdownWithoutConfirmedFindingsSaysSo() {
        ReportInputs in = inputs(List.of(Fixtures.findings().get(1)), FixMode.SUGGEST, List.of());
        assertTrue(bundle(in).files().get(ReportBundle.MARKDOWN).contains("No confirmed findings to remediate."));
    }

    // ------------------------------------------------------------------------------------------ SARIF

    @Test
    void sarifFixesAppearOnlyOnFixedResultsAndValidateAgainstTheSchema() {
        String sarif = bundle(inputs()).files().get(ReportBundle.SARIF);
        assertEquals(List.of(), SarifSchema.errors(sarif));
        JsonNode results = JsonMapper.builder().build().readTree(sarif).get("runs").get(0).get("results");
        int withFixes = 0;
        for (JsonNode r : results) {
            String fp = r.get("partialFingerprints").get("secpipe/v1").asString();
            if (r.has("fixes")) {
                withFixes++;
                assertEquals(RemediationFixtures.FP_FIXED, fp);
                JsonNode change = r.get("fixes").get(0).get("artifactChanges").get(0);
                assertEquals("src/main/java/com/example/vulnlab/LoginServlet.java",
                        change.get("artifactLocation").get("uri").asString());
                assertEquals(24, change.get("replacements").get(0).get("deletedRegion").get("startLine").asInt());
                assertEquals(30, change.get("replacements").get(0).get("deletedRegion").get("endLine").asInt());
                assertTrue(change.get("replacements").get(0).get("insertedContent").get("text").asString()
                        .contains("prepareStatement"));
            }
        }
        assertEquals(1, withFixes);
        assertEquals(RemediationFixtures.resource("remediation/golden/secpipe.sarif"), sarif);
    }

    // ---------------------------------------------------------------- determinism, round trip

    @Test
    void outputIsByteStableAndIndependentOfRecordOrder() {
        List<Finding> reversed = new ArrayList<>(findings());
        Collections.reverse(reversed);
        Map<String, String> a = bundle(inputs()).files();
        Map<String, String> b = bundle(inputs()).files();
        Map<String, String> c = bundle(inputs(reversed, FixMode.SUGGEST, inputs().state().notes())).files();
        assertEquals(a, b);
        assertEquals(a, c);
        assertTrue(a.containsKey(ReportBundle.FIXES_PATCH));
        assertEquals(RemediationFixtures.resource("remediation/golden/secpipe-fixes.patch"),
                a.get(ReportBundle.FIXES_PATCH));
    }

    @Test
    void fixAttemptsRoundTripInFindingsJson() {
        String json = bundle(inputs()).files().get(ReportBundle.FINDINGS);
        assertTrue(json.contains("\"fix_attempts\""));
        assertTrue(json.contains("\"remediation_status\": \"still_present\""));
        Finding back = ContractJson.readFindings(json).stream()
                .filter(f -> f.fingerprint().equals(RemediationFixtures.FP_STILL)).findFirst().orElseThrow();
        assertEquals(RemediationFixtures.stillPresent().secpipe().fixAttempts(), back.secpipe().fixAttempts());
        assertEquals(2, back.secpipe().fixAttempts().size());
        assertNotNull(back.secpipe().fixAttempts().get(0).patchArtifact().orElse(null));
        assertTrue(ContractJson.readFindings(json).stream()
                .filter(f -> f.fingerprint().equals(RemediationFixtures.FP_NOT_ATTEMPTED)).findFirst().orElseThrow()
                .secpipe().remediationStatus().isEmpty());
    }
}
