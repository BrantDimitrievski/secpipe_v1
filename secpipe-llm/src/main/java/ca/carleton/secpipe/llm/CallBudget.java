package ca.carleton.secpipe.llm;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.Budget;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The run-wide agent-call budget ({@code --max-agent-calls}) plus the separate sandbox-run budget. Thread-safe.
 *
 * <p>{@link #tryAcquire} takes from the role's reservation first, then from the unreserved pool, so one role can
 * never consume another role's reservation. {@link #refund} gives a call back after a transport failure (the
 * request never reached the provider).
 */
public final class CallBudget implements Budget {

    private final int max;
    private final int maxSandboxRuns;
    private final Map<AgentRole, Integer> reserved = new EnumMap<>(AgentRole.class);
    private int used;
    private int sandboxRunsUsed;

    public CallBudget(int max, int maxSandboxRuns) {
        if (max < 0 || maxSandboxRuns < 0) {
            throw new IllegalArgumentException("budget limits must be >= 0");
        }
        this.max = max;
        this.maxSandboxRuns = maxSandboxRuns;
    }

    @Override
    public int max() {
        return max;
    }

    @Override
    public synchronized int used() {
        return used;
    }

    @Override
    public synchronized int remaining() {
        return max - used;
    }

    @Override
    public synchronized int reserve(AgentRole role, int n) {
        int granted = Math.max(0, Math.min(n, freePool()));
        if (granted > 0) {
            reserved.merge(role, granted, Integer::sum);
        }
        return granted;
    }

    @Override
    public synchronized boolean tryAcquire(AgentRole role) {
        int own = reserved.getOrDefault(role, 0);
        if (own > 0) {
            reserved.put(role, own - 1);
            used++;
            return true;
        }
        if (freePool() > 0) {
            used++;
            return true;
        }
        return false;
    }

    /** Returns one call to the unreserved pool (transport failure: the provider never saw the request). */
    public synchronized void refund(AgentRole role) {
        if (used > 0) {
            used--;
        }
    }

    @Override
    public synchronized boolean exhausted() {
        return used >= max;
    }

    @Override
    public synchronized boolean tryAcquireSandboxRun() {
        if (sandboxRunsUsed >= maxSandboxRuns) {
            return false;
        }
        sandboxRunsUsed++;
        return true;
    }

    /** Why the budget is exhausted, or empty while calls remain. */
    public synchronized Optional<String> exhaustionReason() {
        if (!exhausted()) {
            return Optional.empty();
        }
        return Optional.of("agent-call budget exhausted (" + used + "/" + max + " calls; raise --max-agent-calls)");
    }

    /** Snapshot for run-metadata.json; reservation keys are lower_snake_case role names, sorted. */
    public synchronized RunMetadata.BudgetSnapshot snapshot() {
        Map<String, Integer> res = new TreeMap<>();
        reserved.forEach((role, n) -> res.put(role.name().toLowerCase(Locale.ROOT), n));
        return new RunMetadata.BudgetSnapshot(max, used, Map.copyOf(res), exhausted(), maxSandboxRuns,
                sandboxRunsUsed);
    }

    private int freePool() {
        int sum = 0;
        for (int n : reserved.values()) {
            sum += n;
        }
        return max - used - sum;
    }
}
