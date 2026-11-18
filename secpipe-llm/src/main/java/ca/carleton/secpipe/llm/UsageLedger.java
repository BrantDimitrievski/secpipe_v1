package ca.carleton.secpipe.llm;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.run.RunMetadata;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-role usage accounting (calls, retries, discarded outputs, tokens) plus an attempt log. Thread-safe. Every
 * error text is passed through the {@link Redactor} before it is stored.
 */
public final class UsageLedger {

    /** One provider attempt. {@code error} is already redacted. */
    public record Attempt(AgentRole role, String modelId, int attempt, Outcome outcome, Optional<String> error) { }

    public enum Outcome { OK, RETRYABLE_ERROR, TRANSPORT_ERROR, FATAL_ERROR }

    private static final class Counters {
        int calls;
        int retries;
        int discarded;
        long in;
        long out;
    }

    private final Redactor redactor;
    private final Map<AgentRole, Counters> counters = new EnumMap<>(AgentRole.class);
    private final List<Attempt> attempts = new ArrayList<>();

    public UsageLedger(Redactor redactor) {
        this.redactor = redactor;
    }

    /** Counts one call; {@code retry} is true for any attempt after the first for the same request. */
    public synchronized void recordCall(AgentRole role, boolean retry) {
        Counters c = counters.computeIfAbsent(role, r -> new Counters());
        c.calls++;
        if (retry) {
            c.retries++;
        }
    }

    public synchronized void recordSuccess(AgentRole role, String modelId, int attempt, long inputTokens,
                                           long outputTokens) {
        Counters c = counters.computeIfAbsent(role, r -> new Counters());
        c.in += inputTokens;
        c.out += outputTokens;
        attempts.add(new Attempt(role, modelId, attempt, Outcome.OK, Optional.empty()));
    }

    public synchronized void recordFailure(AgentRole role, String modelId, int attempt, Outcome outcome,
                                           String error) {
        attempts.add(new Attempt(role, modelId, attempt, outcome, Optional.of(redactor.redact(error))));
    }

    /** Called by the agent runner (WO-G.1) when it discards a malformed output. */
    public synchronized void recordDiscardedOutput(AgentRole role) {
        counters.computeIfAbsent(role, r -> new Counters()).discarded++;
    }

    /** Usage per role with any activity, in role order; cost is not estimated here. */
    public synchronized List<RunMetadata.Usage> snapshot() {
        List<RunMetadata.Usage> out = new ArrayList<>();
        counters.forEach((role, c) -> out.add(
                new RunMetadata.Usage(role, c.calls, c.retries, c.discarded, c.in, c.out, Optional.empty())));
        return List.copyOf(out);
    }

    public synchronized List<Attempt> attempts() {
        return List.copyOf(attempts);
    }
}
