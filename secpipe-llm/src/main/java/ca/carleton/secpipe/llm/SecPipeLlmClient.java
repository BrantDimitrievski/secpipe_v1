package ca.carleton.secpipe.llm;

import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.LlmClient;

import java.util.List;

/** An {@link LlmClient} that also reports what run-metadata.json needs. Every client in this module implements it. */
public interface SecPipeLlmClient extends LlmClient {

    /** The provider the user asked for ({@code --llm-provider}); {@link #mode()} is the effective one. */
    ProviderMode requestedMode();

    /** Provider-level fallbacks taken at assembly (for example anthropic to none after a failed probe). */
    List<RunMetadata.Fallback> fallbacks();

    UsageLedger usage();
}
