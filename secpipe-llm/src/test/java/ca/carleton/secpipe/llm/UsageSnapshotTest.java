package ca.carleton.secpipe.llm;

import static ca.carleton.secpipe.llm.TestSupport.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.finding.Finding.VerifierRef.CapabilityTier;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory.ProviderClient;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory.RawCompletion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** WO-CC.3: {@code usageSnapshot()} reports the run-metadata ai block. */
class UsageSnapshotTest {

    @TempDir
    Path tmp;

    private Path fixtures() throws Exception {
        for (String[] f : new String[][] {{"hunter", "100", "10"}, {"disprover", "50", "5"}}) {
            Path dir = Files.createDirectories(tmp.resolve("fx").resolve(f[0]));
            Files.writeString(dir.resolve("zz-default.json"), "{\"responses\":[{\"json\":{}}],\"inputTokens\":" + f[1]
                    + ",\"outputTokens\":" + f[2] + "}");
        }
        return tmp.resolve("fx");
    }

    @Test
    void fakeClientReportsBothRolesSortedWithCallCountsAndTokenSums() throws Exception {
        FakeLlmClient fake = FakeLlmClient.fromDir(fixtures());
        fake.complete(AgentRole.HUNTER, request("hunter.v1", "a"));
        fake.complete(AgentRole.HUNTER, request("hunter.v1", "b"));
        fake.complete(AgentRole.DISPROVER, request("disprover.v1", "c"));

        RunMetadata.Ai ai = fake.usageSnapshot();

        assertEquals(ProviderMode.FAKE, ai.requestedMode());
        assertEquals(ProviderMode.FAKE, ai.effectiveMode());
        assertEquals(List.of(
                new RunMetadata.Usage(AgentRole.DISPROVER, 1, 0, 0, 50, 5, Optional.empty()),
                new RunMetadata.Usage(AgentRole.HUNTER, 2, 0, 0, 200, 20, Optional.empty())), ai.usage());
        assertEquals(List.of(
                new RunMetadata.ModelAssignment(AgentRole.DISPROVER, ProviderMode.FAKE, "fake-disprover",
                        CapabilityTier.STRONG),
                new RunMetadata.ModelAssignment(AgentRole.HUNTER, ProviderMode.FAKE, "fake-hunter",
                        CapabilityTier.STANDARD)), ai.models());
        assertTrue(ai.fallbacks().isEmpty());
    }

    @Test
    void snapshotIsIdenticalForTheSameCalls() throws Exception {
        RunMetadata.Ai first = scripted();
        RunMetadata.Ai second = scripted();
        assertEquals(first, second);
    }

    private RunMetadata.Ai scripted() throws Exception {
        FakeLlmClient fake = FakeLlmClient.fromDir(fixtures());
        fake.complete(AgentRole.DISPROVER, request("disprover.v1", "c"));
        fake.complete(AgentRole.HUNTER, request("hunter.v1", "a"));
        return fake.usageSnapshot();
    }

    @Test
    void clientWithNoCallsReportsRequestedModeAndEmptyLists() throws Exception {
        RunMetadata.Ai ai = FakeLlmClient.fromDir(fixtures()).usageSnapshot();
        assertEquals(ProviderMode.FAKE, ai.effectiveMode());
        assertTrue(ai.usage().isEmpty());
        assertTrue(ai.models().isEmpty());
    }

    @Test
    void disabledClientHasEmptyBlockAndNoneMode() {
        RunMetadata.Ai ai = NoneLlmClient.disabled().usageSnapshot();
        assertEquals(ProviderMode.NONE, ai.requestedMode());
        assertEquals(ProviderMode.NONE, ai.effectiveMode());
        assertTrue(ai.usage().isEmpty());
        assertTrue(ai.models().isEmpty());
        assertTrue(ai.fallbacks().isEmpty());
    }

    @Test
    void unavailableFakeProviderIsEffectivelyNoneWithTheFallbackAndNoUsage() {
        SecPipeLlmClient c = LlmClients.assemble(TestSupport.config(ProviderMode.FAKE, Optional.empty(), Map.of()),
                Map.of(), new CallBudget(5, 0), Path.of("out"));
        RunMetadata.Ai ai = c.usageSnapshot();
        assertEquals(ProviderMode.FAKE, ai.requestedMode());
        assertEquals(ProviderMode.NONE, ai.effectiveMode());
        assertEquals(1, ai.fallbacks().size());
        assertEquals(ProviderMode.FAKE, ai.fallbacks().get(0).from());
        assertEquals(ProviderMode.NONE, ai.fallbacks().get(0).to());
        assertTrue(ai.usage().isEmpty());
    }

    @Test
    void routingClientReportsTheModelThatActuallyServedTheRole() throws Exception {
        ProviderClient provider = (modelId, req) -> {
            if (modelId.equals("primary")) {
                throw new ProviderStatusException(400, "bad request");
            }
            return new RawCompletion("{}", 7, 3);
        };
        ModelRouting routing = new ModelRouting(ProviderMode.ANTHROPIC, Map.of(AgentRole.HUNTER, "primary"),
                Optional.of("backup"), Map.of(), Map.of());
        RoutingLlmClient client = new RoutingLlmClient(routing, provider, new CallBudget(10, 0), Redactor.shapesOnly(),
                LlmClients.NO_LOG, new TestSupport.RecordingSleeper());
        client.complete(AgentRole.HUNTER, request("hunter.v1", "x"));

        RunMetadata.Ai ai = client.usageSnapshot();

        assertEquals(ProviderMode.ANTHROPIC, ai.effectiveMode());
        assertEquals(1, ai.models().size());
        assertEquals("backup", ai.models().get(0).model());
        assertEquals(ProviderMode.ANTHROPIC, ai.models().get(0).provider());
        assertEquals(2, ai.usage().get(0).calls());
        assertEquals(1, ai.usage().get(0).retries());
        assertEquals(7, ai.usage().get(0).inputTokens());
        assertEquals(3, ai.usage().get(0).outputTokens());
    }

    @Test
    void allCallsFailingMeansEffectiveModeNone() {
        ProviderClient provider = (modelId, req) -> {
            throw new ProviderStatusException(400, "bad request");
        };
        ModelRouting routing = new ModelRouting(ProviderMode.ANTHROPIC, Map.of(AgentRole.HUNTER, "m1"),
                Optional.empty(), Map.of(), Map.of());
        RoutingLlmClient client = new RoutingLlmClient(routing, provider, new CallBudget(10, 0), Redactor.shapesOnly(),
                LlmClients.NO_LOG, new TestSupport.RecordingSleeper());
        assertThrows(LlmClient.LlmUnavailableException.class,
                () -> client.complete(AgentRole.HUNTER, request("hunter.v1", "x")));

        RunMetadata.Ai ai = client.usageSnapshot();

        assertEquals(ProviderMode.ANTHROPIC, ai.requestedMode());
        assertEquals(ProviderMode.NONE, ai.effectiveMode());
        assertFalse(ai.usage().isEmpty());
    }
}
