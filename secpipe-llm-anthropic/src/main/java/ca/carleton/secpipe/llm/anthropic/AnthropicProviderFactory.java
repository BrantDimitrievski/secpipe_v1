package ca.carleton.secpipe.llm.anthropic;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** The {@code anthropic} provider (Messages API). Key: SECPIPE_API_KEY, ANTHROPIC_API_KEY or SECPIPE_LLM_KEY. */
public final class AnthropicProviderFactory implements LlmProviderFactory {

    static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    /** Default ids (owner decision 2026-11-22): large tier claude-opus-5-5, small tier claude-sonnet-5-5; --model overrides. */
    static final Map<AgentRole, String> DEFAULTS = defaults("claude-opus-5-5", "claude-sonnet-5-5");

    private static Map<AgentRole, String> defaults(String strong, String standard) {
        Map<AgentRole, String> m = new EnumMap<>(AgentRole.class);
        for (AgentRole role : AgentRole.values()) {
            m.put(role, role.strongByDefault() ? strong : standard);
        }
        return Map.copyOf(m);
    }

    @Override
    public ProviderMode mode() {
        return ProviderMode.ANTHROPIC;
    }

    @Override
    public Map<AgentRole, String> defaultModels() {
        return DEFAULTS;
    }

    @Override
    public Optional<String> probe(SecPipeConfig config, Map<String, String> env) {
        return AnthropicMessagesClient.key(env).isPresent() ? Optional.empty()
                : Optional.of("no API key for anthropic\nhint: set SECPIPE_API_KEY or ANTHROPIC_API_KEY");
    }

    @Override
    public ProviderClient create(SecPipeConfig config, Map<String, String> env) {
        return new AnthropicMessagesClient(config.llmBaseUrl().orElse(DEFAULT_BASE_URL), env);
    }
}
