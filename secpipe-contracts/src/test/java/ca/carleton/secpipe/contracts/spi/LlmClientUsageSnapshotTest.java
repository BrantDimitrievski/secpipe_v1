package ca.carleton.secpipe.contracts.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import org.junit.jupiter.api.Test;

class LlmClientUsageSnapshotTest {

    private static LlmClient clientWithMode(ProviderMode mode) {
        return new LlmClient() {
            @Override public ProviderMode mode() { return mode; }
            @Override public ModelInfo modelFor(AgentRole role) { throw new UnsupportedOperationException(); }
            @Override public Response complete(AgentRole role, Request request) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void defaultSnapshotReportsModeAndNoUsage() {
        RunMetadata.Ai ai = clientWithMode(ProviderMode.FAKE).usageSnapshot();
        assertEquals(ProviderMode.FAKE, ai.requestedMode());
        assertEquals(ProviderMode.FAKE, ai.effectiveMode());
        assertTrue(ai.fallbacks().isEmpty());
        assertTrue(ai.models().isEmpty());
        assertTrue(ai.usage().isEmpty());
        assertEquals(0, ai.guardrailDowngrades());
        assertTrue(ai.promptsHash().isEmpty());
    }

    @Test
    void defaultSnapshotForNoneMode() {
        RunMetadata.Ai ai = clientWithMode(ProviderMode.NONE).usageSnapshot();
        assertEquals(ProviderMode.NONE, ai.requestedMode());
        assertEquals(ProviderMode.NONE, ai.effectiveMode());
    }
}
