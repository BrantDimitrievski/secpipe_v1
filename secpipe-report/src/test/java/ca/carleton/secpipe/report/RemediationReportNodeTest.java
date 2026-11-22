package ca.carleton.secpipe.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;
import ca.carleton.secpipe.contracts.spi.Budget;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.report.patch.RemediationFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** WO-R.4: the production node reads the patches from outDir, so SARIF carries fixes[] and the combined patch. */
class RemediationReportNodeTest {

    /** A context that supports exactly what ReportNode touches. */
    private record Ctx(Path outDir, List<String> warnings) implements RunContext {
        @Override public Path workDir() { return outDir.resolve(".work"); }
        @Override public LlmClient llm() { return Fixtures.llm(Fixtures.ai()); }
        @Override public Optional<SandboxRunner> sandbox() { return Optional.empty(); }
        @Override public Budget budget() { return new FixedBudget(); }
        @Override public List<AnalyzerAdapter> adapters() { return List.of(); }
        @Override public Rescanner rescanner() { throw new UnsupportedOperationException(); }
        @Override public Clock clock() { return Clock.fixed(Instant.parse("2026-11-17T15:00:00Z"), ZoneOffset.UTC); }
        @Override public RunLog log() {
            return new RunLog() {
                @Override public void info(String msg) { }
                @Override public void warn(String msg) { }
                @Override public void debug(String msg) { }
            };
        }
    }

    private static Path outDirWithPatch(boolean withPatch) throws Exception {
        Path out = Files.createTempDirectory("secpipe-report-r4-");
        if (withPatch) {
            Path p = out.resolve("patches/code-CWE-89-3f9a1c0d.patch");
            Files.createDirectories(p.getParent());
            Files.writeString(p, RemediationFixtures.patch("patches/code-CWE-89-3f9a1c0d.patch").orElseThrow(),
                    StandardCharsets.UTF_8);
        }
        return out;
    }

    @Test
    void nodeEmitsSarifFixesAndTheCombinedPatchForAFixedRecordWithAPatchFile() throws Exception {
        Path out = outDirWithPatch(true);
        PipelineNode.Result r = new ReportNode().apply(Fixtures.state(), new Ctx(out, List.of()));
        assertEquals(Optional.empty(), r.delta().incompleteReason());
        String sarif = Files.readString(out.resolve("secpipe.sarif"), StandardCharsets.UTF_8);
        assertEquals(List.of(), SarifSchema.errors(sarif));
        int withFixes = 0;
        for (JsonNode res : JsonMapper.builder().build().readTree(sarif).get("runs").get(0).get("results")) {
            if (res.has("fixes")) {
                withFixes++;
                assertEquals(RemediationFixtures.FP_FIXED,
                        res.get("partialFingerprints").get("secpipe/v1").asString());
            }
        }
        assertEquals(1, withFixes);
        assertTrue(Files.isRegularFile(out.resolve("secpipe-fixes.patch")));
    }

    @Test
    void nodeEmitsNoFixesWhenThePatchFileIsMissing() throws Exception {
        Path out = outDirWithPatch(false);
        new ReportNode().apply(Fixtures.state(), new Ctx(out, List.of()));
        assertFalse(Files.readString(out.resolve("secpipe.sarif"), StandardCharsets.UTF_8).contains("\"fixes\""));
        assertFalse(Files.exists(out.resolve("secpipe-fixes.patch")));
    }
}
