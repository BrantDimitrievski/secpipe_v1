package ca.carleton.secpipe.llm;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.finding.Finding.VerifierRef.CapabilityTier;
import ca.carleton.secpipe.contracts.run.RunMetadata;

import java.util.List;
import java.util.Optional;

/** AI off ({@code --llm-provider none}) or unavailable. {@link #complete} always throws LlmUnavailableException. */
public final class NoneLlmClient implements SecPipeLlmClient {

    private final ProviderMode requested;
    private final List<RunMetadata.Fallback> fallbacks;
    private final UsageLedger usage = new UsageLedger(Redactor.shapesOnly());

    private NoneLlmClient(ProviderMode requested, List<RunMetadata.Fallback> fallbacks) {
        this.requested = requested;
        this.fallbacks = List.copyOf(fallbacks);
    }

    /** The user chose no AI. */
    public static NoneLlmClient disabled() {
        return new NoneLlmClient(ProviderMode.NONE, List.of());
    }

    /** The requested provider is unusable; {@code reason} must already be redacted. */
    public static NoneLlmClient unavailable(ProviderMode requested, String reason) {
        return new NoneLlmClient(requested, List.of(new RunMetadata.Fallback(requested, ProviderMode.NONE, reason)));
    }

    /** The fallback reason, if this client replaced an unusable provider. */
    public Optional<String> reason() {
        return fallbacks.stream().map(RunMetadata.Fallback::reason).findFirst();
    }

    @Override
    public ProviderMode mode() {
        return ProviderMode.NONE;
    }

    @Override
    public ModelInfo modelFor(AgentRole role) {
        return new ModelInfo(ProviderMode.NONE, "none", CapabilityTier.SMALL);
    }

    @Override
    public Response complete(AgentRole role, Request request) throws LlmUnavailableException {
        throw new LlmUnavailableException("AI provider unavailable: " + reason().orElse("--llm-provider none"), null);
    }

    @Override
    public ProviderMode requestedMode() {
        return requested;
    }

    @Override
    public List<RunMetadata.Fallback> fallbacks() {
        return fallbacks;
    }

    @Override
    public UsageLedger usage() {
        return usage;
    }
}
