package ca.carleton.secpipe.contracts.spi;

import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;

import java.util.Map;
import java.util.Optional;

/** One per provider module, discovered with ServiceLoader. Creates a single-provider client; routing is secpipe-llm's job. */
public interface LlmProviderFactory {

    ProviderMode mode();

    /** Default model id per role for this provider (ADR-10), overridable by config. */
    Map<ca.carleton.secpipe.contracts.agent.AgentRole, String> defaultModels();

    /** Cheap credential/permission probe; empty = usable, otherwise the reason it is not (recorded as a fallback reason). */
    Optional<String> probe(SecPipeConfig config, Map<String, String> env);

    /** Raw provider call surface used by RoutingLlmClient. Implementations read keys from env at call time only. */
    ProviderClient create(SecPipeConfig config, Map<String, String> env);

    interface ProviderClient {
        /** Returns raw model text plus token counts. Throws RateLimitedException for HTTP 429 / quota responses. */
        RawCompletion complete(String modelId, LlmClient.Request request) throws RateLimitedException, Exception;
    }

    record RawCompletion(String text, long inputTokens, long outputTokens) { }

    class RateLimitedException extends Exception {
        private final Optional<java.time.Duration> retryAfter;
        public RateLimitedException(String message, Optional<java.time.Duration> retryAfter) { super(message); this.retryAfter = retryAfter; }
        public Optional<java.time.Duration> retryAfter() { return retryAfter; }
    }
}
