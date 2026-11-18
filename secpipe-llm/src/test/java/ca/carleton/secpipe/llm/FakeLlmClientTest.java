package ca.carleton.secpipe.llm;

import static ca.carleton.secpipe.llm.TestSupport.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.finding.Finding.VerifierRef.CapabilityTier;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

class FakeLlmClientTest {

    private final FakeLlmClient fake = FakeLlmClient.fromDir(TestSupport.fixtures());

    @Test
    void returnsJsonResponseOfFirstMatchingCase() throws Exception {
        LlmClient.Response r = fake.complete(AgentRole.HUNTER, request("hunter.v1", "look at UserDao.java"));
        assertEquals("{\"candidates\":[{\"cwe\":\"CWE-89\",\"file\":\"src/main/java/app/UserDao.java\",\"line\":42}]}",
                r.text());
        assertEquals(120, r.inputTokens());
        assertEquals(40, r.outputTokens());
        assertEquals(1, r.attempts());
        assertEquals(Optional.of("a-sqli"), fake.calls().get(0).caseName());
    }

    @Test
    void fallsThroughToCatchAllInFileNameOrder() throws Exception {
        assertEquals(List.of("a-sqli", "b-malformed-then-ok", "c-outage", "zz-default"), fake.cases(AgentRole.HUNTER));
        LlmClient.Response r = fake.complete(AgentRole.HUNTER, request("hunter.v2", "UserDao.java"));
        assertEquals("{\"candidates\":[]}", r.text());
    }

    @Test
    void responsesAreServedInSequenceAndTheLastRepeats() throws Exception {
        LlmClient.Request req = request("hunter.v1", "RETRY_ME");
        assertEquals("Sure! Here are the findings: none", fake.complete(AgentRole.HUNTER, req).text());
        assertEquals("{\"candidates\":[]}", fake.complete(AgentRole.HUNTER, req).text());
        assertEquals("{\"candidates\":[]}", fake.complete(AgentRole.HUNTER, req).text());
    }

    @Test
    void errorCaseAndNoMatchThrowUnavailable() {
        LlmClient.LlmUnavailableException outage = assertThrows(LlmClient.LlmUnavailableException.class,
                () -> fake.complete(AgentRole.HUNTER, request("hunter.v1", "OUTAGE")));
        assertEquals("simulated provider outage", outage.getMessage());
        LlmClient.LlmUnavailableException none = assertThrows(LlmClient.LlmUnavailableException.class,
                () -> fake.complete(AgentRole.TESTGEN, request("testgen.v1", "x")));
        assertTrue(none.getMessage().contains("no fixture matched for role testgen"), none.getMessage());
    }

    @Test
    void withBudgetTakesOneCallPerCompletion() throws Exception {
        CallBudget budget = new CallBudget(1, 0);
        FakeLlmClient limited = fake.withBudget(budget);
        limited.complete(AgentRole.CRITIC, request("critic.v1", "x"));
        assertEquals(1, budget.used());
        assertThrows(LlmClient.BudgetExhaustedException.class,
                () -> limited.complete(AgentRole.CRITIC, request("critic.v1", "x")));
        assertEquals(1, limited.usage().snapshot().get(0).calls());
    }

    @Test
    void modelForReportsFakeProviderAndDefaultTier() {
        assertEquals(new LlmClient.ModelInfo(ProviderMode.FAKE, "fake-disprover", CapabilityTier.STRONG),
                fake.modelFor(AgentRole.DISPROVER));
        assertEquals(ProviderMode.FAKE, fake.mode());
    }

    @Test
    void rejectsUnknownKeysInCaseFiles() throws Exception {
        Path dir = Files.createTempDirectory("fake-llm");
        Files.createDirectories(dir.resolve("hunter"));
        Files.writeString(dir.resolve("hunter/typo.json"), "{\"respones\": [{\"text\": \"x\"}]}");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> FakeLlmClient.fromDir(dir));
        assertTrue(e.getMessage().contains("unknown case key \"respones\""), e.getMessage());
    }
}
