package ca.carleton.secpipe.llm;

/**
 * Thrown by a provider client (WO-L.2) for an HTTP error response. {@link RoutingLlmClient} retries 429 and 5xx
 * statuses and falls back to the next model for every other status. Rate limits with a {@code Retry-After} header
 * should use the contract's {@code LlmProviderFactory.RateLimitedException} instead.
 */
public class ProviderStatusException extends Exception {

    private final int status;

    public ProviderStatusException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** True for 429 and 5xx. */
    public boolean retryable() {
        return status == 429 || status >= 500;
    }
}
