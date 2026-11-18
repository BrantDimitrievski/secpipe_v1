package ca.carleton.secpipe.llm;

import static ca.carleton.secpipe.llm.TestSupport.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.finding.Finding.VerifierRef.CapabilityTier;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory.ProviderClient;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory.RateLimitedException;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory.RawCompletion;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class RoutingLlmClientTest {

    private static final String KEY = "sk-ant-api03-INJECTEDTESTKEY0123456789";
    private static final Map<String, String> ENV = Map.of("ANTHROPIC_API_KEY", KEY);

    /** Plays back a script of results; records the model id of every call. */
    private static final class Scripted implements ProviderClient {
        final Deque<Object> script = new ArrayDeque<>();
        final List<String> models = new ArrayList<>();

        Scripted then(Object outcome) {
            script.add(outcome);
            return this;
        }

        @Override
        public RawCompletion complete(String modelId, LlmClient.Request request) throws Exception {
            models.add(modelId);
            Object next = script.isEmpty() ? new RawCompletion("{}", 1, 1) : script.poll();
            if (next instanceof Exception e) {
                throw e;
            }
            return (RawCompletion) next;
        }
    }

    private final TestSupport.RecordingSleeper sleeper = new TestSupport.RecordingSleeper();
    private final TestSupport.CapturingLog log = new TestSupport.CapturingLog();

    private RoutingLlmClient client(Scripted provider, CallBudget budget, String... models) {
        ModelRouting routing = new ModelRouting(ProviderMode.ANTHROPIC, Map.of(AgentRole.HUNTER, models[0]),
                models.length > 1 ? Optional.of(models[1]) : Optional.empty(), Map.of(), Map.of());
        return new RoutingLlmClient(routing, provider, budget, Redactor.forEnv(ENV), log, sleeper);
    }

    private static RateLimitedException rateLimited() {
        return new RateLimitedException("429 too many requests", Optional.empty());
    }

    @Test
    void retriesRateLimitsWithExponentialBackoff() throws Exception {
        Scripted p = new Scripted().then(rateLimited()).then(new ProviderStatusException(503, "overloaded"))
                .then(new RawCompletion("{\"ok\":true}", 10, 5));
        CallBudget budget = new CallBudget(10, 0);
        LlmClient.Response r = client(p, budget, "m1").complete(AgentRole.HUNTER, request("hunter.v1", "x"));
        assertEquals("{\"ok\":true}", r.text());
        assertEquals(3, r.attempts());
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), sleeper.delays);
        assertEquals(3, budget.used());
        assertTrue(r.fallbackNote().isEmpty());
    }

    @Test
    void retryAfterIsHonouredAndCapped() throws Exception {
        Scripted p = new Scripted().then(new RateLimitedException("429", Optional.of(Duration.ofSeconds(7))))
                .then(new RateLimitedException("429", Optional.of(Duration.ofMinutes(5))))
                .then(new RawCompletion("{}", 0, 0));
        client(p, new CallBudget(10, 0), "m1").complete(AgentRole.HUNTER, request("hunter.v1", "x"));
        assertEquals(List.of(Duration.ofSeconds(7), Duration.ofSeconds(20)), sleeper.delays);
    }

    @Test
    void fourRateLimitedAttemptsConsumeExactlyFourBudgetCalls() throws Exception {
        Scripted p = new Scripted().then(rateLimited()).then(rateLimited()).then(rateLimited()).then(rateLimited())
                .then(new RawCompletion("{}", 0, 0));
        CallBudget budget = new CallBudget(4, 0);
        assertThrows(LlmClient.BudgetExhaustedException.class,
                () -> client(p, budget, "primary", "backup").complete(AgentRole.HUNTER, request("hunter.v1", "x")));
        assertEquals(List.of("primary", "primary", "primary", "backup"), p.models);
        assertEquals(4, budget.used());
    }

    @Test
    void fallsBackToNextModelAfterRetriesAreExhausted() throws Exception {
        Scripted p = new Scripted().then(rateLimited()).then(rateLimited()).then(rateLimited())
                .then(new RawCompletion("{\"from\":\"backup\"}", 3, 4));
        CallBudget budget = new CallBudget(10, 0);
        LlmClient.Response r = client(p, budget, "primary", "backup")
                .complete(AgentRole.HUNTER, request("hunter.v1", "x"));
        assertEquals("backup", r.model().modelId());
        assertEquals(4, r.attempts());
        assertEquals(4, budget.used());
        assertTrue(r.fallbackNote().orElseThrow().contains("fell back from primary to backup"));
    }

    @Test
    void nonRetryableErrorFallsBackImmediately() throws Exception {
        Scripted p = new Scripted().then(new ProviderStatusException(404, "model not found"))
                .then(new RawCompletion("{}", 0, 0));
        LlmClient.Response r = client(p, new CallBudget(10, 0), "gone", "backup")
                .complete(AgentRole.HUNTER, request("hunter.v1", "x"));
        assertEquals(List.of("gone", "backup"), p.models);
        assertEquals(2, r.attempts());
        assertTrue(sleeper.delays.isEmpty());
    }

    @Test
    void transportFailuresAreRefunded() throws Exception {
        Scripted p = new Scripted().then(new ConnectException("connection refused"))
                .then(new RawCompletion("{}", 0, 0));
        CallBudget budget = new CallBudget(10, 0);
        LlmClient.Response r = client(p, budget, "m1").complete(AgentRole.HUNTER, request("hunter.v1", "x"));
        assertEquals(2, r.attempts());
        assertEquals(1, budget.used());
    }

    @Test
    void allModelsFailingGivesUnavailableAndUsageCounts() {
        Scripted p = new Scripted();
        for (int i = 0; i < 6; i++) {
            p.then(new ProviderStatusException(500, "boom"));
        }
        RoutingLlmClient c = client(p, new CallBudget(10, 0), "a", "b");
        LlmClient.LlmUnavailableException e = assertThrows(LlmClient.LlmUnavailableException.class,
                () -> c.complete(AgentRole.HUNTER, request("hunter.v1", "x")));
        assertTrue(e.getMessage().startsWith("AI provider unavailable"), e.getMessage());
        assertEquals(6, c.usage().snapshot().get(0).calls());
        assertEquals(5, c.usage().snapshot().get(0).retries());
        assertEquals(6, c.usage().attempts().size());
    }

    @Test
    void injectedKeyNeverAppearsInExceptionsUsageRecordsOrLogs() {
        Scripted p = new Scripted()
                .then(new RateLimitedException("quota exceeded for " + KEY, Optional.empty()))
                .then(new ProviderStatusException(401, "invalid x-api-key " + KEY))
                .then(new IllegalStateException("Authorization: Bearer " + KEY));
        RoutingLlmClient c = client(p, new CallBudget(10, 0), "a", "b");
        LlmClient.LlmUnavailableException e = assertThrows(LlmClient.LlmUnavailableException.class,
                () -> c.complete(AgentRole.HUNTER, request("hunter.v1", "x")));
        SecretLeakAssertions.assertNoSecret(KEY, e);
        SecretLeakAssertions.assertNoSecret(KEY, c.usage().attempts(), "usage attempts");
        SecretLeakAssertions.assertNoSecret(KEY, c.usage().snapshot(), "usage snapshot");
        SecretLeakAssertions.assertNoSecret(KEY, log.lines, "log");
        assertTrue(log.lines.stream().anyMatch(l -> l.contains(Redactor.MASK)), "expected redacted log lines");
        assertTrue(c.usage().attempts().get(0).error().orElseThrow().contains(Redactor.MASK));
    }

    @Test
    void routingMapsRolesToModelsAndTiers() {
        ModelRouting r = new ModelRouting(ProviderMode.OPENAI, Map.of(AgentRole.HUNTER, "cheap"), Optional.of("dflt"),
                Map.of(AgentRole.DISPROVER, "strong-model"), Map.of("cheap", CapabilityTier.SMALL));
        assertEquals(new LlmClient.ModelInfo(ProviderMode.OPENAI, "cheap", CapabilityTier.SMALL),
                r.forRole(AgentRole.HUNTER));
        assertEquals(List.of("dflt", "strong-model"),
                r.chainFor(AgentRole.DISPROVER).stream().map(LlmClient.ModelInfo::modelId).toList());
        assertEquals(CapabilityTier.STRONG, r.forRole(AgentRole.DISPROVER).tier());
        assertEquals(CapabilityTier.STANDARD, r.forRole(AgentRole.CRITIC).tier());
        assertEquals(AgentRole.values().length, r.assignments().size());
    }

    @Test
    void noConfiguredModelGivesUnavailableWithoutCallingProvider() {
        Scripted p = new Scripted();
        RoutingLlmClient c = new RoutingLlmClient(
                new ModelRouting(ProviderMode.OPENAI, Map.of(), Optional.empty(), Map.of(), Map.of()),
                p, new CallBudget(10, 0), Redactor.shapesOnly(), log, sleeper);
        assertThrows(LlmClient.LlmUnavailableException.class, () -> c.complete(AgentRole.CRITIC, request("c", "x")));
        assertTrue(p.models.isEmpty());
        assertEquals(ModelRouting.UNCONFIGURED, c.modelFor(AgentRole.CRITIC).modelId());
    }
}
