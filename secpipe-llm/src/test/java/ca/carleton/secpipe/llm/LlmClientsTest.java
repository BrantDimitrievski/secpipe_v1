package ca.carleton.secpipe.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class LlmClientsTest {

    private static final String KEY = "sk-INJECTED-assembly-key-0001";

    /** A factory whose probe fails with a message that (wrongly) contains the key. */
    private static final class StubFactory implements LlmProviderFactory {
        private final Optional<String> probe;

        StubFactory(Optional<String> probe) {
            this.probe = probe;
        }

        @Override public ProviderMode mode() { return ProviderMode.ANTHROPIC; }
        @Override public Map<AgentRole, String> defaultModels() { return Map.of(AgentRole.HUNTER, "stub-model"); }
        @Override public Optional<String> probe(SecPipeConfig c, Map<String, String> env) { return probe; }
        @Override public ProviderClient create(SecPipeConfig c, Map<String, String> env) {
            return (model, req) -> new RawCompletion("{\"model\":\"" + model + "\"}", 1, 1);
        }
    }

    private final TestSupport.CapturingLog log = new TestSupport.CapturingLog();

    private SecPipeLlmClient assemble(ProviderMode mode, Map<String, String> env, List<LlmProviderFactory> f) {
        return LlmClients.assemble(TestSupport.config(mode, Optional.empty(), Map.of()), env, new CallBudget(5, 0),
                Path.of("out"), f, log, d -> { });
    }

    @Test
    void noneModeGivesNoneClientThatThrowsUnavailable() {
        SecPipeLlmClient c = assemble(ProviderMode.NONE, Map.of(), List.of());
        assertEquals(ProviderMode.NONE, c.mode());
        assertTrue(c.fallbacks().isEmpty());
        assertThrows(LlmClient.LlmUnavailableException.class,
                () -> c.complete(AgentRole.HUNTER, TestSupport.request("h", "x")));
    }

    @Test
    void fakeModeNeedsOptInAndLoadsFixtureDir() throws Exception {
        SecPipeLlmClient refused = assemble(ProviderMode.FAKE, Map.of(), List.of());
        assertEquals(ProviderMode.NONE, refused.mode());
        assertEquals(ProviderMode.FAKE, refused.fallbacks().get(0).from());
        SecPipeLlmClient fake = assemble(ProviderMode.FAKE, Map.of(LlmClients.ALLOW_FAKE_ENV, "1",
                LlmClients.FAKE_DIR_ENV, TestSupport.fixtures().toString()), List.of());
        assertInstanceOf(FakeLlmClient.class, fake);
        assertEquals("{\"verdict\":\"keep\"}",
                fake.complete(AgentRole.CRITIC, TestSupport.request("critic.v1", "x")).text());
    }

    @Test
    void missingProviderModuleOrFailedProbeFallsBackToNoneWithRedactedReason() {
        SecPipeLlmClient missing = assemble(ProviderMode.OPENAI, Map.of(), List.of(new StubFactory(Optional.empty())));
        assertEquals(new RunMetadata.Fallback(ProviderMode.OPENAI, ProviderMode.NONE, "no provider module for openai"),
                missing.fallbacks().get(0));
        SecPipeLlmClient failed = assemble(ProviderMode.ANTHROPIC, Map.of("ANTHROPIC_API_KEY", KEY),
                List.of(new StubFactory(Optional.of("401 unauthorized for key " + KEY))));
        assertEquals(ProviderMode.NONE, failed.mode());
        SecretLeakAssertions.assertNoSecret(KEY, failed.fallbacks(), "fallbacks");
        SecretLeakAssertions.assertNoSecret(KEY, log.lines, "log");
    }

    @Test
    void workingProviderGivesRoutingClientWithFactoryDefaults() throws Exception {
        SecPipeLlmClient c = assemble(ProviderMode.ANTHROPIC, Map.of(), List.of(new StubFactory(Optional.empty())));
        assertInstanceOf(RoutingLlmClient.class, c);
        assertEquals("stub-model", c.modelFor(AgentRole.HUNTER).modelId());
        assertEquals("{\"model\":\"stub-model\"}", c.complete(AgentRole.HUNTER, TestSupport.request("h", "x")).text());
    }
}
