package ca.carleton.secpipe.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;
import ca.carleton.secpipe.contracts.spi.Budget;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** WO-R.1: the graph node writes the five files under outDir, or nothing and an incomplete reason. */
class ReportNodeTest {

    /** A context that supports exactly what ReportNode touches. */
    private record Ctx(Path outDir, List<String> warnings) implements RunContext {
        @Override public Path workDir() { return outDir.resolve(".work"); }
        @Override public LlmClient llm() { throw new UnsupportedOperationException(); }
        @Override public Optional<SandboxRunner> sandbox() { return Optional.empty(); }
        @Override public Budget budget() { return new FixedBudget(); }
        @Override public List<AnalyzerAdapter> adapters() { return List.of(); }
        @Override public Rescanner rescanner() { throw new UnsupportedOperationException(); }
        @Override public Clock clock() { return Clock.fixed(Instant.parse("2026-11-17T15:00:00Z"), ZoneOffset.UTC); }
        @Override public RunLog log() {
            return new RunLog() {
                @Override public void info(String msg) { }
                @Override public void warn(String msg) { warnings.add(msg); }
                @Override public void debug(String msg) { }
            };
        }
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void nodeWritesTheFiveFilesUnderOutDir() throws Exception {
        Path out = Files.createTempDirectory("secpipe-report-");
        PipelineNode.Result result = new ReportNode().apply(Fixtures.state(), new Ctx(out, new ArrayList<>()));
        assertEquals(PipelineGraph.NEXT, result.label());
        assertEquals(Optional.empty(), result.delta().incompleteReason());
        assertEquals(List.of("coverage-ledger.json", "findings.json", "report.md", "run-metadata.json",
                "secpipe.sarif"), names(out));
        String findings = Files.readString(out.resolve("findings.json"), StandardCharsets.UTF_8);
        assertEquals(Fixtures.resource("golden/findings.json"), findings);
    }

    @Test
    void nodeWritesNothingAndReportsWhyWhenFindingsAreInvalid() throws Exception {
        List<Finding> bad = Fixtures.findings().stream()
                .map(f -> f instanceof Finding.NeedsValidation nv
                        ? new Finding.NeedsValidation("not-a-fingerprint", nv.title(), nv.description(),
                                nv.claimedRootCause(), nv.trace(), nv.evidence(), nv.blockers(),
                                nv.validationPlan(), nv.secpipe())
                        : f)
                .toList();
        PipelineState state = Fixtures.state(bad, Optional.empty(), List.of());
        Path out = Files.createTempDirectory("secpipe-report-");
        List<String> warnings = new ArrayList<>();
        PipelineNode.Result result = new ReportNode().apply(state, new Ctx(out, warnings));
        assertEquals(PipelineGraph.NEXT, result.label());
        assertTrue(result.delta().incompleteReason().orElseThrow().startsWith("report validation failed"));
        assertEquals(List.of(), names(out));
        assertEquals(1, warnings.size());
    }

    @Test
    void nodeIsNotAnLlmOrSandboxNodeAndIsRegisteredInTheGraph() {
        ReportNode node = new ReportNode();
        assertEquals("report", node.id());
        assertFalse(node.requiresLlm());
        assertFalse(node.usesSandbox());
        assertEquals("ca.carleton.secpipe.report.ReportNode", PipelineGraph.NODES.get("report"));
    }
}
