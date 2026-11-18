package ca.carleton.secpipe.core.recon;

import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.RunProfile;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.core.engine.DefaultRunContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Shared test data for the recon tests. */
final class ReconFixtures {

    private ReconFixtures() { }

    static Path shopSamples() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("fixtures/mvp-samples/shop-samples");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("fixtures/mvp-samples/shop-samples not found above the working directory");
    }

    static SecPipeConfig config(Path target) {
        return new SecPipeConfig(target, Path.of("secpipe-out"), RunProfile.STANDARD,
                SecPipeConfig.ScopeMode.FULL, Optional.empty(), Optional.empty(), ProviderMode.NONE, Optional.empty(),
                Map.of(), Map.of(), Optional.empty(), Set.of(), Set.of(),
                SecPipeConfig.DEFAULT_MAX_AGENT_CALLS_STANDARD, SecPipeConfig.DEFAULT_MAX_FIX_ATTEMPTS,
                SecPipeConfig.DEFAULT_MAX_SANDBOX_RUNS, SecPipeConfig.FixMode.SUGGEST, true, true,
                Optional.empty(), false, false, Set.of(SecPipeConfig.OutputFormat.JSON), Optional.empty(), false,
                List.of(), SecPipeConfig.DEFAULT_MAX_SNIPPET_LINES, SecPipeConfig.DEFAULT_MAX_FILES_PER_CALL,
                Set.of(), false, false, false, SecPipeConfig.GraphEngine.PLAIN, Duration.ofSeconds(30),
                SecPipeConfig.Verbosity.NORMAL);
    }

    static PipelineState state(Path target, List<LedgerUnit> ledger) {
        return new PipelineState("run-1", config(target), Optional.empty(), Optional.empty(), List.of(), ledger,
                List.of(), Map.of(), Map.of(), List.of(), Map.of(), 0, 0, false, Map.of(), Optional.empty(), List.of(),
                Optional.empty());
    }

    static RunContext ctx(Path workDir) {
        return DefaultRunContext.builder().workDir(workDir).outDir(workDir.resolve("out")).build();
    }

    static void write(Path root, String rel, String content) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
