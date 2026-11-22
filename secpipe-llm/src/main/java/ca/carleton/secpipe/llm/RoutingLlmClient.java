package ca.carleton.secpipe.llm;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.pipeline.RunContext.RunLog;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.Budget;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory.ProviderClient;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory.RateLimitedException;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory.RawCompletion;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * A real provider behind the {@code LlmClient} contract.
 *
 * <p>Per request: walk the role's model chain ({@link ModelRouting#chainFor}). For each model make up to
 * {@value #MAX_RETRIES} retries (3 attempts) on retryable errors (429, 5xx, timeouts, transport failures) with
 * exponential backoff (1 s, 2 s, ... capped at 20 s; {@code Retry-After} wins). Any other error moves to the next
 * model at once. Every attempt first takes one call from the {@link Budget}; a transport failure (connection refused,
 * unknown host, connect timeout: the provider never saw the request) is refunded when the budget is a
 * {@link CallBudget}. No budget left gives {@code BudgetExhaustedException}; every model failing gives
 * {@code LlmUnavailableException}. Both carry only redacted text and never wrap the provider exception.
 */
public final class RoutingLlmClient implements SecPipeLlmClient {

    public static final int MAX_RETRIES = 2;
    static final Duration BASE_DELAY = Duration.ofSeconds(1);
    static final Duration MAX_DELAY = Duration.ofSeconds(20);
    private static final int MAX_ERROR_CHARS = 300;

    private final ModelRouting routing;
    private final ProviderClient provider;
    private final Budget budget;
    private final Redactor redactor;
    private final UsageLedger usage;
    private final RunLog log;
    private final Sleeper sleeper;

    public RoutingLlmClient(ModelRouting routing, ProviderClient provider, Budget budget, Redactor redactor,
                            RunLog log, Sleeper sleeper) {
        this.routing = routing;
        this.provider = provider;
        this.budget = budget;
        this.redactor = redactor;
        this.usage = new UsageLedger(redactor);
        this.log = log;
        this.sleeper = sleeper;
    }

    @Override
    public ProviderMode mode() {
        return routing.provider();
    }

    @Override
    public ModelInfo modelFor(AgentRole role) {
        return routing.forRole(role);
    }

    public ModelRouting routing() {
        return routing;
    }

    @Override
    public Response complete(AgentRole role, Request request) throws LlmUnavailableException, BudgetExhaustedException {
        List<ModelInfo> chain = routing.chainFor(role);
        if (chain.isEmpty()) {
            throw new LlmUnavailableException("no model configured for role " + role
                    + "; hint: set --model or --model-for-role", null);
        }
        int attempts = 0;
        String lastError = "";
        Optional<String> note = Optional.empty();
        for (int m = 0; m < chain.size(); m++) {
            ModelInfo model = chain.get(m);
            if (m > 0) {
                note = Optional.of("fell back from " + chain.get(m - 1).modelId() + " to " + model.modelId()
                        + ": " + lastError);
                log.warn("llm: " + note.get());
            }
            for (int retry = 0; retry <= MAX_RETRIES; retry++) {
                if (!budget.tryAcquire(role)) {
                    throw new BudgetExhaustedException("agent-call budget exhausted (" + budget.used() + "/"
                            + budget.max() + " calls); hint: raise --max-agent-calls");
                }
                attempts++;
                usage.recordCall(role, attempts > 1);
                try {
                    RawCompletion raw = provider.complete(model.modelId(), request);
                    usage.recordSuccess(role, model.modelId(), attempts, raw.inputTokens(), raw.outputTokens());
                    return new Response(raw.text(), model, raw.inputTokens(), raw.outputTokens(), attempts, note);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        throw new LlmUnavailableException("interrupted while calling " + model.modelId(), null);
                    }
                    lastError = describe(e);
                    UsageLedger.Outcome outcome = classify(e);
                    usage.recordFailure(role, model.modelId(), attempts, outcome, lastError);
                    log.warn("llm: " + role + " attempt " + attempts + " on " + model.modelId() + " failed: "
                            + lastError);
                    if (outcome == UsageLedger.Outcome.TRANSPORT_ERROR && budget instanceof CallBudget cb) {
                        cb.refund(role);
                    }
                    if (outcome == UsageLedger.Outcome.FATAL_ERROR || retry == MAX_RETRIES) {
                        break;
                    }
                    pause(backoff(retry, retryAfter(e)));
                }
            }
        }
        throw new LlmUnavailableException("AI provider unavailable: all models failed for " + role + " after "
                + attempts + " attempts; last error: " + lastError, null);
    }

    /** Delay before retry number {@code retry + 1}: Retry-After if given, else 1 s * 2^retry; at most 20 s. */
    static Duration backoff(int retry, Optional<Duration> retryAfter) {
        Duration d = retryAfter.orElse(BASE_DELAY.multipliedBy(1L << Math.min(retry, 20)));
        return d.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : d;
    }

    private void pause(Duration d) throws LlmUnavailableException {
        try {
            sleeper.sleep(d);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("interrupted during retry backoff", null);
        }
    }

    private static Optional<Duration> retryAfter(Exception e) {
        return e instanceof RateLimitedException rl ? rl.retryAfter() : Optional.empty();
    }

    static UsageLedger.Outcome classify(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof UnknownHostException
                    || t instanceof HttpConnectTimeoutException) {
                return UsageLedger.Outcome.TRANSPORT_ERROR;
            }
            if (t instanceof RateLimitedException || t instanceof HttpTimeoutException
                    || t instanceof SocketTimeoutException || t instanceof TimeoutException) {
                return UsageLedger.Outcome.RETRYABLE_ERROR;
            }
            if (t instanceof ProviderStatusException ps) {
                return ps.retryable() ? UsageLedger.Outcome.RETRYABLE_ERROR : UsageLedger.Outcome.FATAL_ERROR;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return UsageLedger.Outcome.FATAL_ERROR;
    }

    private String describe(Exception e) {
        String text = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        String clean = redactor.redact(text);
        return clean.length() > MAX_ERROR_CHARS ? clean.substring(0, MAX_ERROR_CHARS) + "..." : clean;
    }

    @Override
    public ModelInfo modelUsed(AgentRole role, String modelId) {
        return routing.chainFor(role).stream().filter(m -> m.modelId().equals(modelId)).findFirst()
                .orElseGet(() -> SecPipeLlmClient.super.modelUsed(role, modelId));
    }

    @Override
    public ProviderMode requestedMode() {
        return routing.provider();
    }

    @Override
    public List<RunMetadata.Fallback> fallbacks() {
        return List.of();
    }

    @Override
    public UsageLedger usage() {
        return usage;
    }
}
