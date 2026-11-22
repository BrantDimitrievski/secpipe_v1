package ca.carleton.secpipe.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.json.RunMetadataCodec;
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

/** WO-CC.3: the assembler path reports the same ai block the client snapshots. */
class RunMetadataAssemblerAiTest {

    private record Ctx(Path outDir, LlmClient llm) implements RunContext {
        @Override public Path workDir() { return outDir.resolve(".work"); }
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

    @Test
    void assemblerUsesTheGivenAiBlock() {
        RunMetadata m = RunMetadataAssembler.assemble(Fixtures.state(), Instant.parse("2026-11-17T15:00:00Z"),
                new FixedBudget(), false, Fixtures.ai());
        assertEquals(Fixtures.ai(), m.ai());
    }

    @Test
    void reportNodeWithoutAFrozenFileWritesTheClientsUsage() throws Exception {
        Path out = Files.createTempDirectory("secpipe-report-cc3-");
        new ReportNode().apply(Fixtures.state(), new Ctx(out, Fixtures.llm(Fixtures.ai())));
        RunMetadata written = RunMetadataCodec.read(out.resolve("run-metadata.json"));
        assertEquals(Fixtures.ai().usage(), written.ai().usage());
        assertEquals(Fixtures.ai().models(), written.ai().models());
        assertEquals(ProviderMode.FAKE, written.ai().effectiveMode());
        assertEquals(AgentRole.DISPROVER, written.ai().usage().get(0).role());
    }

    @Test
    void reportNodeKeepsTheFrozenFilesAiBlock() throws Exception {
        Path out = Files.createTempDirectory("secpipe-report-cc3-");
        RunMetadata frozen = RunMetadataAssembler.assemble(Fixtures.state(), Instant.parse("2026-11-17T14:59:00Z"),
                new FixedBudget(), false, Fixtures.ai());
        RunMetadataCodec.write(out.resolve("run-metadata.json"), frozen);
        RunMetadata.Ai other = new RunMetadata.Ai(ProviderMode.NONE, ProviderMode.NONE, List.of(), List.of(),
                List.of(), 0, Optional.empty());
        new ReportNode().apply(Fixtures.state(), new Ctx(out, Fixtures.llm(other)));
        assertEquals(Fixtures.ai(), RunMetadataCodec.read(out.resolve("run-metadata.json")).ai());
    }
}
