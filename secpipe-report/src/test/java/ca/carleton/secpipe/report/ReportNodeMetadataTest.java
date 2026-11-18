package ca.carleton.secpipe.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.json.RunMetadataCodec;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;
import ca.carleton.secpipe.contracts.spi.Budget;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** WO-R.5: the default report node keeps finalize's frozen run-metadata.json. */
class ReportNodeMetadataTest {

    private record Ctx(Path outDir) implements RunContext {
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
                @Override public void warn(String msg) { }
                @Override public void debug(String msg) { }
            };
        }
    }

    /** Finalize-style metadata: incomplete with a cause, and a failed ledger validator. */
    private static RunMetadata frozen() {
        RunMetadata m = RunMetadataAssembler.assemble(Fixtures.state(), Instant.parse("2026-11-17T14:59:00Z"),
                new FixedBudget(), false);
        RunMetadata inc = ReportNode.amended(m, "budget exhausted");
        return new RunMetadata(inc.schemaVersion(), inc.runId(), inc.secpipeVersion(), inc.startedAt(),
                inc.finishedAt(), inc.terminalState(), inc.incompleteReason(), inc.profile(), inc.scope(),
                inc.target(), inc.ai(), inc.budget(), inc.tools(), inc.stageTimingsMs(), inc.sandbox(),
                inc.priorRun(), inc.counts(),
                new RunMetadata.Validators(RunMetadata.ValidatorStatus.PASS, RunMetadata.ValidatorStatus.FAIL));
    }

    private static PipelineState badState() {
        List<Finding> bad = Fixtures.findings().stream()
                .map(f -> f instanceof Finding.NeedsValidation nv
                        ? new Finding.NeedsValidation("not-a-fingerprint", nv.title(), nv.description(),
                                nv.claimedRootCause(), nv.trace(), nv.evidence(), nv.blockers(),
                                nv.validationPlan(), nv.secpipe())
                        : f)
                .toList();
        return Fixtures.state(bad, Optional.empty(), List.of());
    }

    @Test
    void frozenFileIsPreservedByteForByteOnSuccess() throws Exception {
        Path out = Files.createTempDirectory("secpipe-report-");
        Path file = out.resolve("run-metadata.json");
        RunMetadataCodec.write(file, frozen());
        byte[] before = Files.readAllBytes(file);
        new ReportNode().apply(Fixtures.state(), new Ctx(out));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)));
        assertTrue(Files.exists(out.resolve("findings.json")));
    }

    @Test
    void reportFailureAppendsReasonKeepsValidatorsAndSetsIncomplete() throws Exception {
        Path out = Files.createTempDirectory("secpipe-report-");
        Path file = out.resolve("run-metadata.json");
        RunMetadata base = frozen();
        RunMetadataCodec.write(file, base);
        new ReportNode().apply(badState(), new Ctx(out));
        RunMetadata after = RunMetadataCodec.read(file);
        assertEquals(RunMetadata.TerminalState.INCOMPLETE, after.terminalState());
        String reason = after.incompleteReason().orElseThrow();
        assertTrue(reason.startsWith("budget exhausted; report validation failed"), reason);
        assertEquals(base.validators(), after.validators());
        assertEquals(base.counts(), after.counts());
    }

    @Test
    void fallsBackToAssemblerWhenFileIsMissing() throws Exception {
        Path out = Files.createTempDirectory("secpipe-report-");
        new ReportNode().apply(Fixtures.state(), new Ctx(out));
        RunMetadata written = RunMetadataCodec.read(out.resolve("run-metadata.json"));
        assertEquals(Fixtures.RUN_ID, written.runId());
        assertEquals(RunMetadata.ValidatorStatus.PASS, written.validators().findings());
        assertEquals(RunMetadata.TerminalState.COMPLETE, written.terminalState());
    }
}
