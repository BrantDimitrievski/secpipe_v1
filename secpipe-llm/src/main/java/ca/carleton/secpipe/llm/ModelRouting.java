package ca.carleton.secpipe.llm;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Finding.VerifierRef.CapabilityTier;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.LlmClient.ModelInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Role to model and tier mapping (ADR-10). The model chain for a role is, without duplicates:
 * {@code --model-for-role} override, then {@code --model} default, then the provider factory's default for the role.
 * The first entry is the primary model; the rest are fallbacks. A model's tier comes from the config's tier
 * overrides, else STRONG for roles that are strong by default and STANDARD otherwise.
 */
public final class ModelRouting {

    /** Model id reported by {@link #forRole} when no model is configured for a role. */
    public static final String UNCONFIGURED = "unconfigured";

    private final ProviderMode provider;
    private final Map<AgentRole, String> roleOverrides;
    private final Optional<String> defaultModel;
    private final Map<AgentRole, String> providerDefaults;
    private final Map<String, CapabilityTier> tierOverrides;

    public ModelRouting(ProviderMode provider, Map<AgentRole, String> roleOverrides, Optional<String> defaultModel,
                        Map<AgentRole, String> providerDefaults, Map<String, CapabilityTier> tierOverrides) {
        this.provider = provider;
        this.roleOverrides = Map.copyOf(roleOverrides);
        this.defaultModel = defaultModel;
        this.providerDefaults = Map.copyOf(providerDefaults);
        this.tierOverrides = Map.copyOf(tierOverrides);
    }

    public static ModelRouting from(SecPipeConfig config, Map<AgentRole, String> providerDefaults) {
        return new ModelRouting(config.llmProvider(), config.modelForRole(), config.defaultModel(), providerDefaults,
                config.tierOverrides());
    }

    public ProviderMode provider() {
        return provider;
    }

    /** The primary model and tier for a role (model id {@value #UNCONFIGURED} if none is known). */
    public ModelInfo forRole(AgentRole role) {
        List<ModelInfo> chain = chainFor(role);
        return chain.isEmpty() ? info(role, UNCONFIGURED) : chain.get(0);
    }

    /** Primary model first, then fallbacks. Empty when nothing is configured for the role. */
    public List<ModelInfo> chainFor(AgentRole role) {
        Set<String> ids = new LinkedHashSet<>();
        Optional.ofNullable(roleOverrides.get(role)).ifPresent(ids::add);
        defaultModel.ifPresent(ids::add);
        Optional.ofNullable(providerDefaults.get(role)).ifPresent(ids::add);
        List<ModelInfo> out = new ArrayList<>();
        for (String id : ids) {
            out.add(info(role, id));
        }
        return List.copyOf(out);
    }

    /** Primary assignment for every role, in role order (for run-metadata.json). */
    public List<RunMetadata.ModelAssignment> assignments() {
        List<RunMetadata.ModelAssignment> out = new ArrayList<>();
        for (AgentRole role : AgentRole.values()) {
            ModelInfo m = forRole(role);
            out.add(new RunMetadata.ModelAssignment(role, m.provider(), m.modelId(), m.tier()));
        }
        return List.copyOf(out);
    }

    private ModelInfo info(AgentRole role, String modelId) {
        CapabilityTier tier = tierOverrides.getOrDefault(modelId,
                role.strongByDefault() ? CapabilityTier.STRONG : CapabilityTier.STANDARD);
        return new ModelInfo(provider, modelId, tier);
    }
}
