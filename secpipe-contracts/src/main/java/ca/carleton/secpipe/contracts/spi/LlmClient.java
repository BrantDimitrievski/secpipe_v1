package ca.carleton.secpipe.contracts.spi;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.finding.Finding.VerifierRef.CapabilityTier;

import java.util.Optional;

/**
 * The only LLM surface the pipeline sees (ADR-10). Implementations: secpipe-llm RoutingLlmClient (real providers via
 * LangChain4j, fallback chain, budget accounting, privacy filter) and FakeLlmClient (CI). Callers must handle
 * mode()==NONE by skipping LLM work. complete() never returns prose-repaired JSON: the raw text is returned and the
 * caller (AgentRunner) discards anything that is not exactly one JSON object.
 */
public interface LlmClient {

    ProviderMode mode();

    ModelInfo modelFor(AgentRole role);

    Response complete(AgentRole role, Request request) throws LlmUnavailableException, BudgetExhaustedException;

    record ModelInfo(ProviderMode provider, String modelId, CapabilityTier tier) { }

    /** jsonSchemaId names a schema under schemas/agent/ for providers that support structured output. */
    record Request(String systemPrompt, String userPrompt, String jsonSchemaId, int maxOutputTokens, double temperature,
                   String promptTemplateId) { }

    record Response(String text, ModelInfo model, long inputTokens, long outputTokens, int attempts,
                    Optional<String> fallbackNote) { }

    class LlmUnavailableException extends Exception {
        public LlmUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    class BudgetExhaustedException extends Exception {
        public BudgetExhaustedException(String message) { super(message); }
    }
}
