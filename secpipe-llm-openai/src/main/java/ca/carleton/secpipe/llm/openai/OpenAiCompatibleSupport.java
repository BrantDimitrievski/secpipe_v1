package ca.carleton.secpipe.llm.openai;

import ca.carleton.secpipe.contracts.agent.AgentRole;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Key lookup and default-model helpers shared by the two factories. Never stores a key value. */
final class OpenAiCompatibleSupport {

    private OpenAiCompatibleSupport() { }

    static Map<AgentRole, String> defaults(String strong, String standard) {
        Map<AgentRole, String> m = new EnumMap<>(AgentRole.class);
        for (AgentRole role : AgentRole.values()) {
            m.put(role, role.strongByDefault() ? strong : standard);
        }
        return Map.copyOf(m);
    }

    /** First non-blank of SECPIPE_API_KEY, the provider variable, SECPIPE_LLM_KEY. */
    static Optional<String> key(Map<String, String> env, String providerVariable) {
        for (String name : new String[] {"SECPIPE_API_KEY", providerVariable, "SECPIPE_LLM_KEY"}) {
            String v = env.get(name);
            if (v != null && !v.isBlank()) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    static Optional<String> keyProbe(Map<String, String> env, String providerVariable, String provider) {
        return key(env, providerVariable).isPresent() ? Optional.empty()
                : Optional.of("no API key for " + provider + "\nhint: set SECPIPE_API_KEY or " + providerVariable);
    }
}
