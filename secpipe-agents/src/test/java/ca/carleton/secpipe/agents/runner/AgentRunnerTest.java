package ca.carleton.secpipe.agents.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.carleton.secpipe.agents.prompt.DefaultPromptRenderer;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.CriticResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.DisproverResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.HunterResult;
import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.json.AgentOutputCodec;
import ca.carleton.secpipe.contracts.spi.Agent;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.contracts.spi.SourceIndex;
import ca.carleton.secpipe.llm.CallBudget;
import ca.carleton.secpipe.llm.FakeLlmClient;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

class AgentRunnerTest {

    private static final String CONTROLLER = "src/main/java/com/shop/app/OrderController.java";
    private static final String REPOSITORY = "src/main/java/com/shop/app/OrderRepository.java";
    private static final String TAG_FILTER = "src/main/java/com/shop/app/TagFilter.java";
    private static final String FINGERPRINT = "code:CWE-89:0f0e0d0c0b0a090807060504";

    private static final SourceIndex SOURCES = new MemorySourceIndex(Map.of(
            CONTROLLER, 40, REPOSITORY, 60, TAG_FILTER, 20));
    private static final GroundingContext GROUNDING = GroundingContext.of(SOURCES);

    /** A test agent: its user prompt carries a case marker plus one untrusted repo block. */
    private record TestAgent<O>(AgentRole role, Class<O> outputType, String outputSchemaId, String marker,
                                List<LlmClient.Request> built) implements Agent<String, O> {

        static <O> TestAgent<O> of(AgentRole role, Class<O> type, String schema, String marker) {
            return new TestAgent<>(role, type, schema, marker, Collections.synchronizedList(new ArrayList<>()));
        }

        @Override
        public String promptTemplateId() {
            return "test.v1";
        }

        @Override
        public LlmClient.Request buildRequest(String input, PromptRenderer renderer) {
            String system = renderer.template(DefaultPromptRenderer.STANDING_RULES, Map.of()) + "\n"
                    + renderer.template(DefaultPromptRenderer.OUTPUT_CONTRACT, Map.of("schema_id", outputSchemaId));
            String user = "Task " + marker + "\n" + renderer.untrusted(CONTROLLER, 1, 2, input);
            LlmClient.Request r = new LlmClient.Request(system, user, outputSchemaId, 2000, 0.0, promptTemplateId());
            built.add(r);
            return r;
        }
    }

    private static TestAgent<HunterResult> hunter(String marker) {
        return TestAgent.of(AgentRole.HUNTER, HunterResult.class, "agent/hunter-result.schema.json", marker);
    }

    private static FakeLlmClient fake() {
        try {
            return FakeLlmClient.fromDir(Path.of(Objects.requireNonNull(
                    AgentRunnerTest.class.getResource("/llm-fixtures/runner")).toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class RecordingListener implements AgentRunner.DiscardListener {
        final List<String> agentIds = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();
        final List<Integer> attempts = new ArrayList<>();

        @Override
        public void discarded(AgentRole role, String agentId, int attempt, String reason) {
            agentIds.add(agentId);
            reasons.add(reason);
            attempts.add(attempt);
        }
    }

    private static final String SOURCE = "String id = request.getParameter(\"id\");\nrepo.byId(id);\n";

    @Test
    void validGroundedOutputOnFirstCallIsOk() {
        FakeLlmClient llm = fake();
        AgentOutcome<HunterResult> out = new AgentRunner(llm).run(hunter("CASE_OK"), SOURCE, GROUNDING);

        assertThat(out).isInstanceOf(AgentOutcome.Ok.class);
        AgentOutcome.Ok<HunterResult> ok = (AgentOutcome.Ok<HunterResult>) out;
        assertThat(ok.attempts()).isEqualTo(1);
        assertThat(ok.value().status()).isEqualTo(HunterResult.Status.CANDIDATE);
        assertThat(ok.value().candidates()).hasSize(1);
        assertThat(ok.agentId()).matches("hunter-[0-9a-f]{8}");
        assertThat(ok.model().modelId()).isEqualTo("fake-hunter");
        assertThat(llm.calls()).hasSize(1);
    }

    @Test
    void malformedOutputIsDiscardedAndAFreshAgentSucceeds() {
        FakeLlmClient llm = fake();
        RecordingListener listener = new RecordingListener();
        AgentOutcome<HunterResult> out = new AgentRunner(llm, new DefaultPromptRenderer(), listener)
                .run(hunter("CASE_MALFORMED_THEN_OK"), SOURCE, GROUNDING);

        assertThat(out).isInstanceOf(AgentOutcome.Ok.class);
        assertThat(out.attempts()).isEqualTo(2);
        assertThat(llm.calls()).hasSize(2);
        assertThat(listener.reasons).singleElement().asString().startsWith("malformed output:");
        assertThat(((AgentOutcome.Ok<HunterResult>) out).agentId()).isNotEqualTo(listener.agentIds.get(0));
    }

    @Test
    void malformedThreeTimesIsDropped() {
        FakeLlmClient llm = fake();
        RecordingListener listener = new RecordingListener();
        AgentOutcome<HunterResult> out = new AgentRunner(llm, new DefaultPromptRenderer(), listener)
                .run(hunter("CASE_MALFORMED_ALWAYS"), SOURCE, GROUNDING);

        assertThat(out).isInstanceOf(AgentOutcome.Dropped.class);
        assertThat(out.attempts()).isEqualTo(AgentRunner.MAX_ATTEMPTS).isEqualTo(3);
        assertThat(((AgentOutcome.Dropped<HunterResult>) out).reason())
                .startsWith("agent output invalid 3 times; last: malformed output:");
        assertThat(llm.calls()).hasSize(3);
        assertThat(listener.attempts).containsExactly(1, 2, 3);
    }

    @Test
    void eachAttemptIsAFreshCallAndMalformedTextNeverReachesTheCodecTwice() {
        FakeLlmClient llm = fake();
        List<String> decoded = new ArrayList<>();
        AgentRunner.OutputDecoder recording = new AgentRunner.OutputDecoder() {
            @Override
            public <T> T decode(String text, Class<T> type) {
                decoded.add(text);
                return AgentOutputCodec.read(text, type);
            }
        };
        RecordingListener listener = new RecordingListener();
        TestAgent<HunterResult> agent = hunter("CASE_MALFORMED_ALWAYS");
        AgentOutcome<HunterResult> out = new AgentRunner(llm, new DefaultPromptRenderer(), listener, recording)
                .run(agent, SOURCE, GROUNDING);

        assertThat(out).isInstanceOf(AgentOutcome.Dropped.class);
        // one LLM call, one freshly built request and one decode per attempt
        assertThat(llm.calls()).hasSize(3);
        assertThat(agent.built()).hasSize(3);
        assertThat(decoded).hasSize(3).doesNotHaveDuplicates();
        // each decoded text is exactly the reply of that attempt's own call (fixture serves 3 distinct replies)
        assertThat(decoded.get(0)).startsWith("Here you go:");
        assertThat(decoded.get(1)).startsWith("```json");
        assertThat(decoded.get(2)).startsWith("{").endsWith("}");
        // fresh agent ids and fresh untrusted-block nonces per attempt; requests sent are the ones built
        assertThat(new HashSet<>(listener.agentIds)).hasSize(3);
        assertThat(agent.built().stream().map(LlmClient.Request::userPrompt).distinct()).hasSize(3);
        assertThat(llm.calls().stream().map(FakeLlmClient.Call::request).toList()).isEqualTo(agent.built());
    }

    @Test
    void fencedJsonIsNeverRepaired() {
        FakeLlmClient llm = fake();
        AgentOutcome<HunterResult> out = new AgentRunner(llm).run(hunter("CASE_FENCED"), SOURCE, GROUNDING);

        assertThat(out).isInstanceOf(AgentOutcome.Dropped.class);
        assertThat(llm.calls()).hasSize(3);
    }

    @Test
    void schemaInvalidObjectIsDiscardedAndReRun() {
        FakeLlmClient llm = fake();
        RecordingListener listener = new RecordingListener();
        AgentOutcome<HunterResult> out = new AgentRunner(llm, new DefaultPromptRenderer(), listener)
                .run(hunter("CASE_SCHEMA_INVALID_THEN_OK"), SOURCE, GROUNDING);

        assertThat(out).isInstanceOf(AgentOutcome.Ok.class);
        assertThat(out.attempts()).isEqualTo(2);
        assertThat(listener.reasons).singleElement().asString().contains("local_checks");
    }

    @Test
    void ungroundedOutputIsDiscardedAndReRun() {
        FakeLlmClient llm = fake();
        RecordingListener listener = new RecordingListener();
        AgentOutcome<HunterResult> out = new AgentRunner(llm, new DefaultPromptRenderer(), listener)
                .run(hunter("CASE_UNGROUNDED_THEN_OK"), SOURCE, GROUNDING);

        assertThat(out).isInstanceOf(AgentOutcome.Ok.class);
        assertThat(out.attempts()).isEqualTo(2);
        assertThat(listener.reasons).containsExactly("ungrounded output: file "
                + "src/main/java/com/shop/app/Missing.java is not in the source index");
    }

    @Test
    void lineOutOfRangeEveryTimeIsDropped() {
        AgentOutcome<HunterResult> out = new AgentRunner(fake())
                .run(hunter("CASE_UNGROUNDED_LINE"), SOURCE, GROUNDING);

        assertThat(out).isInstanceOf(AgentOutcome.Dropped.class);
        assertThat(((AgentOutcome.Dropped<HunterResult>) out).reason())
                .endsWith("ungrounded output: line 999 is out of range in " + REPOSITORY);
    }

    @Test
    void callBudgetOfOneMakesExactlyOneCall() {
        FakeLlmClient llm = fake();
        AgentOutcome<HunterResult> out = new AgentRunner(llm)
                .run(hunter("CASE_MALFORMED_THEN_OK"), SOURCE, GROUNDING, 1);

        assertThat(out).isInstanceOf(AgentOutcome.Dropped.class);
        assertThat(out.attempts()).isEqualTo(1);
        assertThat(((AgentOutcome.Dropped<HunterResult>) out).reason())
                .startsWith("agent output invalid 1 times; last: malformed output:");
        assertThat(llm.calls()).hasSize(1);
    }

    @Test
    void callBudgetOfTwoAllowsOneFreshReRunOnly() {
        FakeLlmClient ok = fake();
        assertThat(new AgentRunner(ok).run(hunter("CASE_MALFORMED_THEN_OK"), SOURCE, GROUNDING, 2))
                .isInstanceOf(AgentOutcome.Ok.class);
        assertThat(ok.calls()).hasSize(2);

        FakeLlmClient never = fake();
        assertThat(new AgentRunner(never).run(hunter("CASE_MALFORMED_ALWAYS"), SOURCE, GROUNDING, 2))
                .isInstanceOf(AgentOutcome.Dropped.class);
        assertThat(never.calls()).hasSize(2);
    }

    @Test
    void callBudgetOutsideOneToMaxAttemptsIsRejectedBeforeAnyCall() {
        FakeLlmClient llm = fake();
        AgentRunner runner = new AgentRunner(llm);

        assertThatThrownBy(() -> runner.run(hunter("CASE_OK"), SOURCE, GROUNDING, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1.." + AgentRunner.MAX_ATTEMPTS);
        assertThatThrownBy(() -> runner.run(hunter("CASE_OK"), SOURCE, GROUNDING, AgentRunner.MAX_ATTEMPTS + 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1.." + AgentRunner.MAX_ATTEMPTS);
        assertThat(llm.calls()).isEmpty();
    }

    @Test
    void criticStartingPathMustBeIndexed() {
        TestAgent<CriticResult> ok = TestAgent.of(AgentRole.CRITIC, CriticResult.class,
                "agent/critic-result.schema.json", "CASE_CRITIC_OK");
        TestAgent<CriticResult> bad = TestAgent.of(AgentRole.CRITIC, CriticResult.class,
                "agent/critic-result.schema.json", "CASE_CRITIC_UNGROUNDED");

        assertThat(new AgentRunner(fake()).run(ok, SOURCE, GROUNDING)).isInstanceOf(AgentOutcome.Ok.class);
        AgentOutcome<CriticResult> out = new AgentRunner(fake()).run(bad, SOURCE, GROUNDING);
        assertThat(out).isInstanceOf(AgentOutcome.Dropped.class);
        assertThat(((AgentOutcome.Dropped<CriticResult>) out).reason())
                .contains("file src/main/java/com/shop/app/Ghost.java is not in the source index");
    }

    @Test
    void citedFingerprintMustBeInTheInput() {
        TestAgent<DisproverResult> agent = TestAgent.of(AgentRole.DISPROVER, DisproverResult.class,
                "agent/disprover-result.schema.json", "CASE_DISPROVER");

        AgentOutcome<DisproverResult> known = new AgentRunner(fake())
                .run(agent, SOURCE, GroundingContext.of(SOURCES, Set.of(FINGERPRINT)));
        assertThat(known).isInstanceOf(AgentOutcome.Ok.class);

        AgentOutcome<DisproverResult> unknown = new AgentRunner(fake())
                .run(agent, SOURCE, GroundingContext.of(SOURCES, Set.of("code:CWE-79:aaaaaaaaaaaaaaaaaaaaaaaa")));
        assertThat(unknown).isInstanceOf(AgentOutcome.Dropped.class);
        assertThat(((AgentOutcome.Dropped<DisproverResult>) unknown).reason())
                .contains("fingerprint " + FINGERPRINT + " is not in the agent input");
    }

    @Test
    void budgetExhaustedBeforeTheFirstCallIsUnavailable() {
        FakeLlmClient base = fake();
        FakeLlmClient llm = base.withBudget(new CallBudget(0, 0));
        AgentOutcome<HunterResult> out = new AgentRunner(llm).run(hunter("CASE_OK"), SOURCE, GROUNDING);

        assertThat(out).isInstanceOf(AgentOutcome.Unavailable.class);
        assertThat(((AgentOutcome.Unavailable<HunterResult>) out).reason()).contains("budget exhausted");
        assertThat(base.calls()).isEmpty();
    }

    @Test
    void budgetExhaustedDuringReRunsIsUnavailableNotDropped() {
        FakeLlmClient base = fake();
        FakeLlmClient llm = base.withBudget(new CallBudget(2, 0));
        AgentOutcome<HunterResult> out = new AgentRunner(llm)
                .run(hunter("CASE_MALFORMED_ALWAYS"), SOURCE, GROUNDING);

        assertThat(out).isInstanceOf(AgentOutcome.Unavailable.class);
        assertThat(out.attempts()).isEqualTo(3);
        assertThat(base.calls()).hasSize(2);
    }

    @Test
    void providerOutageIsUnavailable() {
        AgentOutcome<HunterResult> out = new AgentRunner(fake()).run(hunter("CASE_OUTAGE"), SOURCE, GROUNDING);

        assertThat(out).isInstanceOf(AgentOutcome.Unavailable.class);
        assertThat(((AgentOutcome.Unavailable<HunterResult>) out).reason())
                .isEqualTo("LLM unavailable: simulated provider outage");
    }

    @Test
    void aiOffMakesNoCall() {
        List<LlmClient.Request> sent = new ArrayList<>();
        LlmClient none = new LlmClient() {
            @Override
            public ProviderMode mode() {
                return ProviderMode.NONE;
            }

            @Override
            public ModelInfo modelFor(AgentRole role) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Response complete(AgentRole role, Request request) {
                sent.add(request);
                throw new UnsupportedOperationException();
            }
        };
        TestAgent<HunterResult> agent = hunter("CASE_OK");
        AgentOutcome<HunterResult> out = new AgentRunner(none).run(agent, SOURCE, GROUNDING);

        assertThat(out).isEqualTo(new AgentOutcome.Unavailable<HunterResult>("AI is off (llm provider none)", 0));
        assertThat(sent).isEmpty();
        assertThat(agent.built()).isEmpty();
    }

    @Test
    void repoTextReachesTheModelOnlyInsideAnUntrustedBlock() {
        FakeLlmClient llm = fake();
        new AgentRunner(llm).run(hunter("CASE_OK"), SOURCE, GROUNDING);

        String user = llm.calls().get(0).request().userPrompt();
        int open = user.indexOf("<untrusted_repo_data nonce=");
        int close = user.indexOf("</untrusted_repo_data nonce=");
        int src = user.indexOf("request.getParameter");
        assertThat(open).isNotNegative();
        assertThat(src).isGreaterThan(open).isLessThan(close);
        assertThat(llm.calls().get(0).request().systemPrompt()).contains("untrusted data")
                .contains("agent/hunter-result.schema.json");
    }

    @Test
    void groundingCheckRejectsNonAgentOutputs() {
        assertThatThrownBy(() -> GroundingCheck.problems("not a record", GROUNDING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** In-memory {@link SourceIndex}: path to line count; line n reads "line n". */
    private static final class MemorySourceIndex implements SourceIndex {
        private final TreeMap<String, Integer> files;

        MemorySourceIndex(Map<String, Integer> files) {
            this.files = new TreeMap<>(files);
        }

        @Override
        public List<String> files() {
            return List.copyOf(files.keySet());
        }

        @Override
        public boolean exists(String path) {
            return files.containsKey(SourceIndex.requireRelativePath(path));
        }

        @Override
        public Optional<List<String>> lines(String path) {
            Integer n = files.get(SourceIndex.requireRelativePath(path));
            if (n == null) {
                return Optional.empty();
            }
            List<String> out = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                out.add("line " + i);
            }
            return Optional.of(List.copyOf(out));
        }

        @Override
        public Optional<String> line(String path, int oneBasedLine) {
            return lines(path).filter(l -> oneBasedLine >= 1 && oneBasedLine <= l.size())
                    .map(l -> l.get(oneBasedLine - 1));
        }
    }
}
