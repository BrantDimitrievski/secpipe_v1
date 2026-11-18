package ca.carleton.secpipe.llm.openai;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory;

import java.util.Map;
import java.util.Optional;

/** The {@code openai-compatible} provider: any Chat Completions server at {@code --llm-base-url}. The key is optional. */
public final class OpenAiCompatibleProviderFactory implements LlmProviderFactory {

    @Override
    public ProviderMode mode() {
        return ProviderMode.OPENAI_COMPATIBLE;
    }

    /** No defaults: the server's model names are unknown, so {@code --model} / {@code --model-for} must be set. */
    @Override
    public Map<AgentRole, String> defaultModels() {
        return Map.of();
    }

    @Override
    public Optional<String> probe(SecPipeConfig config, Map<String, String> env) {
        if (config.llmBaseUrl().isEmpty() || config.llmBaseUrl().get().isBlank()) {
            return Optional.of("openai-compatible needs a base URL\nhint: pass --llm-base-url (or llm-base-url)");
        }
        return Optional.empty();
    }

    @Override
    public ProviderClient create(SecPipeConfig config, Map<String, String> env) {
        return new OpenAiChatClient(config.llmBaseUrl().orElseThrow(), env, "OPENAI_API_KEY", false, false);
    }
}
