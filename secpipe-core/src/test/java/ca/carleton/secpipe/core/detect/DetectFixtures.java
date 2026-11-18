package ca.carleton.secpipe.core.detect;

import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.RunProfile;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.ProjectProfile;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;
import ca.carleton.secpipe.contracts.spi.EnvironmentInfo;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.core.engine.DefaultRunContext;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Shared test data for the detect tests. */
final class DetectFixtures {

    private static final String SERVICES = "META-INF/services/";
    private static final String LOCAL_SERVICES = "ca/carleton/secpipe/core/detect/META-INF/services/";

    private DetectFixtures() { }

    /**
     * A class loader whose {@code META-INF/services} lookups resolve to this package's test resources, so the tests
     * exercise the real {@link java.util.ServiceLoader} without a module-wide service file.
     */
    static ClassLoader servicesLoader() {
        ClassLoader parent = DetectFixtures.class.getClassLoader();
        return new ClassLoader(parent) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (name.startsWith(SERVICES)) {
                    return parent.getResources(LOCAL_SERVICES + name.substring(SERVICES.length()));
                }
                return parent.getResources(name);
            }
        };
    }

    static List<AnalyzerAdapter> fakes() {
        return AdapterSelector.discover(servicesLoader());
    }

    static SecPipeConfig config(Set<String> tools, Set<String> exclude, Duration timeout) {
        return new SecPipeConfig(Path.of("target-project"), Path.of("secpipe-out"), RunProfile.STANDARD,
                SecPipeConfig.ScopeMode.FULL, Optional.empty(), Optional.empty(), ProviderMode.NONE, Optional.empty(),
                Map.of(), Map.of(), Optional.empty(), tools, exclude,
                SecPipeConfig.DEFAULT_MAX_AGENT_CALLS_STANDARD, SecPipeConfig.DEFAULT_MAX_FIX_ATTEMPTS,
                SecPipeConfig.DEFAULT_MAX_SANDBOX_RUNS, SecPipeConfig.FixMode.SUGGEST, true, true,
                Optional.empty(), false, false, Set.of(SecPipeConfig.OutputFormat.JSON), Optional.empty(), false,
                List.of(), SecPipeConfig.DEFAULT_MAX_SNIPPET_LINES, SecPipeConfig.DEFAULT_MAX_FILES_PER_CALL,
                Set.of(), false, false, false, SecPipeConfig.GraphEngine.PLAIN, timeout,
                SecPipeConfig.Verbosity.NORMAL);
    }

    static SecPipeConfig config() {
        return config(Set.of(), Set.of(), Duration.ofSeconds(30));
    }

    static EnvironmentInfo environment() {
        return new EnvironmentInfo("linux", "amd64", "21", Optional.empty(), Optional.of("3.9.9"), true, false,
                Optional.empty(), false, Map.of());
    }

    static ProjectProfile profile() {
        return new ProjectProfile(Path.of("target-project"), "java", ProjectProfile.BuildTool.MAVEN_WRAPPER,
                Set.of(), List.of("src/main/java/A.java", "src/main/java/B.java", "src/main/java/C.java"), List.of(),
                List.of("pom.xml"), 100);
    }

    static PipelineState state(SecPipeConfig config, List<LedgerUnit> ledger) {
        return new PipelineState("run-1", config, Optional.of(environment()), Optional.of(profile()), List.of(),
                ledger, List.of(), Map.of(), Map.of(), List.of(), Map.of(), 0, 0, false, Map.of(), Optional.empty(),
                List.of(), Optional.empty());
    }

    static RunContext ctx(Path workDir, Optional<SandboxRunner> sandbox) {
        return DefaultRunContext.builder().workDir(workDir).outDir(workDir.resolve("out")).sandbox(sandbox).build();
    }

    static LedgerUnit unit(String hexDigit, String cwe, List<String> startingPaths, LedgerUnit.Status status) {
        return new LedgerUnit("cov:" + hexDigit.repeat(32),
                new LedgerUnit.CanonicalRefs("http", "request", "core", "injection", Optional.empty()),
                "http", "request", "core", "injection", Optional.of(cwe), startingPaths, "INJECTION.md#sql",
                List.of(), List.of(), LedgerUnit.PriorStatus.NEW, List.of(), 0, status, Optional.empty(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }
}
