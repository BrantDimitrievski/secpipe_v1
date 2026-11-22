package ca.carleton.secpipe.llm;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.LlmClient;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** An {@link LlmClient} that also reports what run-metadata.json needs. Every client in this module implements it. */
public interface SecPipeLlmClient extends LlmClient {

    /** The provider the user asked for ({@code --llm-provider}); {@link #mode()} is the effective one. */
    ProviderMode requestedMode();

    /** Provider-level fallbacks taken at assembly (for example anthropic to none after a failed probe). */
    List<RunMetadata.Fallback> fallbacks();

    UsageLedger usage();

    /** The model info to report for {@code modelId}, which served {@code role}. Routing clients know the tier. */
    default ModelInfo modelUsed(AgentRole role, String modelId) {
        ModelInfo declared = modelFor(role);
        return declared.modelId().equals(modelId) ? declared
                : new ModelInfo(declared.provider(), modelId, declared.tier());
    }

    /**
     * Usage per role (sorted by role name), the model that actually served each role, fallbacks, and the effective
     * mode: the requested mode when a call succeeded or none was needed, {@code none} when the provider was
     * unavailable from the start or every call failed. Deterministic and free of keys and prompts.
     */
    @Override
    default RunMetadata.Ai usageSnapshot() {
        UsageLedger ledger = usage();
        List<RunMetadata.Usage> usage = ledger.snapshot().stream()
                .sorted(Comparator.comparing(u -> u.role().name())).toList();
        Map<AgentRole, String> used = ledger.modelsUsed();
        List<RunMetadata.ModelAssignment> models = usage.stream().map(u -> {
            ModelInfo info = used.containsKey(u.role()) ? modelUsed(u.role(), used.get(u.role())) : modelFor(u.role());
            return new RunMetadata.ModelAssignment(u.role(), info.provider(), info.modelId(), info.tier());
        }).toList();
        boolean needed = usage.stream().anyMatch(u -> u.calls() > 0);
        ProviderMode effective = mode() == ProviderMode.NONE || (needed && !ledger.anySuccess())
                ? ProviderMode.NONE : requestedMode();
        return new RunMetadata.Ai(requestedMode(), effective, List.copyOf(fallbacks()), models, usage, 0,
                Optional.empty());
    }
}
