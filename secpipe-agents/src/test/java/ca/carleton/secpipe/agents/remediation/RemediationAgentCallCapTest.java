package ca.carleton.secpipe.agents.remediation;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.agents.prompt.DefaultPromptRenderer;
import ca.carleton.secpipe.agents.runner.AgentOutcome;
import ca.carleton.secpipe.agents.runner.AgentRunner;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.RemediationResult;
import ca.carleton.secpipe.llm.FakeLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** WO-G.12: {@code propose()} never makes more than {@link AgentRunner#MAX_ATTEMPTS} LLM calls, on any path. */
class RemediationAgentCallCapTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROSE = "Sure! Here is the fix, no JSON for you.";

    @TempDir
    Path tmp;

    private int runs;

    private static JsonNode fixtureJson(String name) throws IOException, URISyntaxException {
        Path p = Path.of(Objects.requireNonNull(RemediationAgentCallCapTest.class
                .getResource("/llm-fixtures/remediation/remediation/" + name + ".json")).toURI());
        JsonNode reply = JSON.readTree(Files.readString(p)).get("responses").get(0).get("json").deepCopy();
        ((ObjectNode) reply).put("fingerprint", RemediationAgentTest.fp(1));
        return reply;
    }

    /** A scripted client: the replies are served in order, the last one repeating. {@code null} means prose. */
    private FakeLlmClient script(List<JsonNode> replies) throws IOException {
        ArrayNode responses = JSON.createArrayNode();
        for (JsonNode r : replies) {
            ObjectNode o = JSON.createObjectNode();
            if (r == null) {
                o.put("text", PROSE);
            } else {
                o.set("json", r);
            }
            responses.add(o);
        }
        ObjectNode root = JSON.createObjectNode();
        root.set("responses", responses);
        Path dir = tmp.resolve("fixtures-" + ++runs);
        Files.createDirectories(dir.resolve("remediation"));
        Files.writeString(dir.resolve("remediation").resolve("script.json"), JSON.writeValueAsString(root));
        return FakeLlmClient.fromDir(dir);
    }

    private AgentOutcome<RemediationResult> propose(FakeLlmClient llm) {
        Path repo = RemediationAgentTest.copyOfSqliMin(tmp.resolve("repo-" + runs));
        AgentRunner runner = new AgentRunner(llm, new DefaultPromptRenderer(), AgentRunner.DiscardListener.NONE);
        return new RemediationAgent(runner, RemediationAgentTest.index(repo), repo, AgentRunner.DiscardListener.NONE)
                .propose(RemediationAgentTest.confirmed(RemediationAgentTest.fp(1)),
                        RemediationAgentTest.regressionTest(), List.of());
    }

    @Test
    void refusedThenMalformedForeverStopsAtThreeCalls() throws Exception {
        // before the fix: 1 call (refused patch) + a fresh run of 3 malformed replies = 4 calls
        FakeLlmClient llm = script(java.util.Arrays.asList(fixtureJson("forbidden-path"), null));
        AgentOutcome<RemediationResult> out = propose(llm);

        assertThat(out).isInstanceOf(AgentOutcome.Dropped.class);
        assertThat(llm.calls().size()).isLessThanOrEqualTo(AgentRunner.MAX_ATTEMPTS);
        assertThat(llm.calls()).hasSize(3);
        assertThat(out.attempts()).isEqualTo(3);
    }

    @Test
    void malformedTwiceThenRefusedStopsAtThreeCalls() throws Exception {
        FakeLlmClient llm = script(java.util.Arrays.asList(null, null, fixtureJson("test-file-edit")));
        AgentOutcome<RemediationResult> out = propose(llm);

        assertThat(out).isInstanceOf(AgentOutcome.Dropped.class);
        assertThat(((AgentOutcome.Dropped<RemediationResult>) out).reason()).contains("REGRESSION_TEST");
        assertThat(llm.calls()).hasSize(3);
        assertThat(out.attempts()).isEqualTo(3);
    }

    @Test
    void refusedTwiceThenAValidFixIsStillAcceptedWithinThreeCalls() throws Exception {
        FakeLlmClient llm = script(List.of(fixtureJson("forbidden-path"), fixtureJson("pom-then-valid"),
                fixtureJson("valid")));
        AgentOutcome<RemediationResult> out = propose(llm);

        assertThat(out).isInstanceOf(AgentOutcome.Ok.class);
        assertThat(llm.calls()).hasSize(3);
        assertThat(out.attempts()).isEqualTo(3);
    }

    @Test
    void refusedMalformedRefusedNeverReachesAFourthCall() throws Exception {
        FakeLlmClient llm = script(java.util.Arrays.asList(fixtureJson("forbidden-path"), null,
                fixtureJson("forbidden-path"), fixtureJson("valid")));
        AgentOutcome<RemediationResult> out = propose(llm);

        assertThat(out).isInstanceOf(AgentOutcome.Dropped.class);
        assertThat(llm.calls().size()).isLessThanOrEqualTo(AgentRunner.MAX_ATTEMPTS);
    }

    @Test
    void runnerHonoursACallBudgetAndRejectsOutOfRangeBudgets() throws Exception {
        FakeLlmClient llm = script(java.util.Arrays.asList((JsonNode) null));
        AgentRunner runner = new AgentRunner(llm);
        RemediationAgent agent = new RemediationAgent();
        Path repo = RemediationAgentTest.copyOfSqliMin(tmp.resolve("repo-budget"));
        var grounding = ca.carleton.secpipe.agents.runner.GroundingContext.of(RemediationAgentTest.index(repo));
        RemediationAgent.Input input = new RemediationContextBuilder(RemediationAgentTest.index(repo)).build(
                RemediationAgentTest.confirmed(RemediationAgentTest.fp(1)), RemediationAgentTest.regressionTest(),
                List.of());

        AgentOutcome<RemediationResult> out = runner.run(agent, input, grounding, 2);
        assertThat(out).isInstanceOf(AgentOutcome.Dropped.class);
        assertThat(out.attempts()).isEqualTo(2);
        assertThat(llm.calls()).hasSize(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runner.run(agent, input, grounding, 0))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runner.run(agent, input, grounding, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
