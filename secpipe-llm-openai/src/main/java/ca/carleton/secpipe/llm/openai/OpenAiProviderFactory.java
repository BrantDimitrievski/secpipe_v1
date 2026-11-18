package ca.carleton.secpipe.llm.openai;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory;

import java.util.Map;
import java.util.Optional;

/** The {@code openai} provider: Chat Completions on api.openai.com (or {@code --llm-base-url}). */
public final class OpenAiProviderFactory implements LlmProviderFactory {

    static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    /** Default ids, unverified against the live model list on 2026-11-17 (no network); override with --model. */
    static final Map<AgentRole, String> DEFAULTS = OpenAiCompatibleSupport.defaults("gpt-5", "gpt-5-mini");

    @Override
    public ProviderMode mode() {
        return ProviderMode.OPENAI;
    }

    @Override
    public Map<AgentRole, String> defaultModels() {
        return DEFAULTS;
    }

    @Override
    public Optional<String> probe(SecPipeConfig config, Map<String, String> env) {
        return OpenAiCompatibleSupport.keyProbe(env, "OPENAI_API_KEY", "openai");
    }

    @Override
    public ProviderClient create(SecPipeConfig config, Map<String, String> env) {
        return new OpenAiChatClient(config.llmBaseUrl().orElse(DEFAULT_BASE_URL), env, "OPENAI_API_KEY", true, true);
    }
}
