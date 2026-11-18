package ca.carleton.secpipe.agents.runner;

import ca.carleton.secpipe.agents.prompt.DefaultPromptRenderer;
import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.json.AgentOutputCodec;
import ca.carleton.secpipe.contracts.json.ContractJson.ContractJsonException;
import ca.carleton.secpipe.contracts.spi.Agent;
import ca.carleton.secpipe.contracts.spi.LlmClient;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The only way agents reach the LLM (WO-G.1, PLAN Part 5 "Rules common to every LLM agent").
 *
 * <p>Per attempt the runner starts a <em>fresh</em> agent: a new agent id ({@code <role>-<8hex>}), a request newly
 * built by {@link Agent#buildRequest} (fresh untrusted-block nonces, no conversation history), and one
 * {@link LlmClient#complete} call. The reply is decoded strictly by {@link AgentOutputCodec#read} (exactly one JSON
 * object, schema-valid) and then checked by {@link GroundingCheck}. Anything else is discarded, never repaired, and
 * the next attempt starts from scratch. After {@link #MAX_ATTEMPTS} discarded replies (1 + 2 fresh re-runs) the item
 * is {@link AgentOutcome.Dropped}.
 *
 * <p>Budget: the {@link LlmClient} takes one call from the run's {@code Budget} per attempt (RoutingLlmClient,
 * FakeLlmClient#withBudget) and throws {@code BudgetExhaustedException} when none is left; the runner turns that, a
 * provider outage, or {@code mode()==NONE} into {@link AgentOutcome.Unavailable}.
 */
public final class AgentRunner {

    /** Total LLM calls per item: the first attempt plus two fresh re-runs. */
    public static final int MAX_ATTEMPTS = 3;

    /** Told about every discarded reply, e.g. to call {@code UsageLedger.recordDiscardedOutput(role)} and log. */
    @FunctionalInterface
    public interface DiscardListener {
        void discarded(AgentRole role, String agentId, int attempt, String reason);

        DiscardListener NONE = (role, agentId, attempt, reason) -> { };
    }

    /** Strict decoder seam for tests; production uses {@link AgentOutputCodec#read}. */
    @FunctionalInterface
    interface OutputDecoder {
        <T> T decode(String text, Class<T> type);
    }

    private static final OutputDecoder STRICT = AgentOutputCodec::read;

    private final LlmClient llm;
    private final Agent.PromptRenderer renderer;
    private final DiscardListener listener;
    private final OutputDecoder decoder;
    private final SecureRandom random = new SecureRandom();

    /** A runner with a {@link DefaultPromptRenderer} and no discard listener. */
    public AgentRunner(LlmClient llm) {
        this(llm, new DefaultPromptRenderer(), DiscardListener.NONE);
    }

    public AgentRunner(LlmClient llm, Agent.PromptRenderer renderer, DiscardListener listener) {
        this(llm, renderer, listener, STRICT);
    }

    AgentRunner(LlmClient llm, Agent.PromptRenderer renderer, DiscardListener listener, OutputDecoder decoder) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /**
     * Runs {@code agent} on {@code input} until it returns one schema-valid, grounded output or the attempts or
     * budget run out. Never throws for model behaviour; programming errors (an agent whose output type is not an
     * agent output record) propagate as {@link IllegalArgumentException}.
     */
    public <I, O> AgentOutcome<O> run(Agent<I, O> agent, I input, GroundingContext grounding) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(grounding, "grounding");
        AgentRole role = agent.role();
        if (llm.mode() == ProviderMode.NONE) {
            return new AgentOutcome.Unavailable<>("AI is off (llm provider none)", 0);
        }
        String lastReason = "";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String agentId = newAgentId(role);
            LlmClient.Request request = agent.buildRequest(input, renderer);
            LlmClient.Response response;
            try {
                response = llm.complete(role, request);
            } catch (LlmClient.BudgetExhaustedException e) {
                return new AgentOutcome.Unavailable<>(e.getMessage(), attempt);
            } catch (LlmClient.LlmUnavailableException e) {
                return new AgentOutcome.Unavailable<>("LLM unavailable: " + e.getMessage(), attempt);
            }
            String reason;
            O output = null;
            if (response.text() == null) {
                reason = "malformed output: empty reply";
            } else {
                try {
                    output = decoder.decode(response.text(), agent.outputType());
                    reason = "";
                } catch (ContractJsonException e) {
                    reason = "malformed output: " + e.getMessage();
                }
            }
            if (output != null) {
                List<String> problems = GroundingCheck.problems(output, grounding);
                if (problems.isEmpty()) {
                    return new AgentOutcome.Ok<>(output, attempt, agentId, response.model());
                }
                reason = "ungrounded output: " + String.join("; ", problems);
            }
            lastReason = reason;
            listener.discarded(role, agentId, attempt, reason);
        }
        return new AgentOutcome.Dropped<>("agent output invalid " + MAX_ATTEMPTS + " times; last: " + lastReason,
                MAX_ATTEMPTS);
    }

    private String newAgentId(AgentRole role) {
        byte[] b = new byte[4];
        random.nextBytes(b);
        return role.name().toLowerCase(Locale.ROOT) + "-" + HexFormat.of().formatHex(b);
    }
}
