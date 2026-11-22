package ca.carleton.secpipe.core.finalize;

import static ca.carleton.secpipe.core.scoring.ScoringFixtures.rejected;
import static ca.carleton.secpipe.core.scoring.ScoringFixtures.state;
import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.finding.Finding.VerifierRef.CapabilityTier;
import ca.carleton.secpipe.contracts.json.RunMetadataCodec;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.core.engine.DefaultRunContext;
import ca.carleton.secpipe.validators.SchemaRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WO-CC.3: finalize freezes the {@code ai} block reported by {@code RunContext.llm().usageSnapshot()}. */
class FinalizeNodeAiTest {

    @TempDir
    Path tmp;

    private static LlmClient llm(RunMetadata.Ai ai) {
        return new LlmClient() {
            @Override public ProviderMode mode() { return ai.effectiveMode(); }
            @Override public ModelInfo modelFor(AgentRole role) { throw new UnsupportedOperationException(); }
            @Override public Response complete(AgentRole role, Request request) {
                throw new UnsupportedOperationException();
            }
            @Override public RunMetadata.Ai usageSnapshot() { return ai; }
        };
    }

    private RunMetadata runWith(LlmClient client) throws Exception {
        RunContext ctx = DefaultRunContext.builder().workDir(tmp).outDir(tmp.resolve("out")).llm(client)
                .clock(Clock.fixed(Instant.parse("2026-11-30T12:00:00Z"), ZoneOffset.UTC)).build();
        new FinalizeNode().apply(state(List.of(rejected("fp-a")), List.of(), Optional.empty()), ctx);
        String json = Files.readString(tmp.resolve("out").resolve(FinalizeNode.RUN_METADATA));
        assertThat(SchemaRegistry.shared().validate("run-metadata.schema.json", json).errors()).isEmpty();
        return RunMetadataCodec.read(json);
    }

    @Test
    void metadataCarriesTheClientsUsageSnapshot() throws Exception {
        RunMetadata.Ai ai = new RunMetadata.Ai(ProviderMode.FAKE, ProviderMode.FAKE, List.of(),
                List.of(new RunMetadata.ModelAssignment(AgentRole.HUNTER, ProviderMode.FAKE, "fake-hunter",
                        CapabilityTier.STANDARD)),
                List.of(new RunMetadata.Usage(AgentRole.HUNTER, 3, 1, 0, 300, 90, Optional.empty())), 0,
                Optional.empty());
        RunMetadata m = runWith(llm(ai));
        assertThat(m.ai().usage()).containsExactlyElementsOf(ai.usage());
        assertThat(m.ai().models()).containsExactlyElementsOf(ai.models());
        assertThat(m.ai().effectiveMode()).isEqualTo(ProviderMode.FAKE);
    }

    @Test
    void effectiveModeNoneIsKeptWhenTheProviderWasUnavailable() throws Exception {
        RunMetadata.Ai ai = new RunMetadata.Ai(ProviderMode.FAKE, ProviderMode.NONE,
                List.of(new RunMetadata.Fallback(ProviderMode.FAKE, ProviderMode.NONE, "fake provider requires env")),
                List.of(), List.of(), 0, Optional.empty());
        RunMetadata m = runWith(llm(ai));
        assertThat(m.ai().requestedMode()).isEqualTo(ProviderMode.FAKE);
        assertThat(m.ai().effectiveMode()).isEqualTo(ProviderMode.NONE);
        assertThat(m.ai().fallbacks()).hasSize(1);
        assertThat(m.ai().usage()).isEmpty();
    }
}
