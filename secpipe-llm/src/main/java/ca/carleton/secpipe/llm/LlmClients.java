package ca.carleton.secpipe.llm;

import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.pipeline.RunContext.RunLog;
import ca.carleton.secpipe.contracts.spi.Budget;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/** The single LLM assembly point. The engine calls {@link #assemble} once per run and puts the result in RunContext. */
public final class LlmClients {

    /** Must be "1" for {@code --llm-provider fake}. */
    public static final String ALLOW_FAKE_ENV = "SECPIPE_ALLOW_FAKE_LLM";
    /** Fixture directory for {@code --llm-provider fake} (see {@link FakeLlmClient} for the layout). */
    public static final String FAKE_DIR_ENV = "SECPIPE_FAKE_LLM_DIR";

    /** A RunLog that drops everything. */
    public static final RunLog NO_LOG = new RunLog() {
        @Override public void info(String msg) { }
        @Override public void warn(String msg) { }
        @Override public void debug(String msg) { }
    };

    private LlmClients() { }

    /**
     * Assembles the run's LLM client using provider factories found with {@link ServiceLoader}, no logging and real
     * sleeping. {@code outDir} is accepted for the frozen signature and is currently unused.
     */
    public static SecPipeLlmClient assemble(SecPipeConfig config, Map<String, String> env, Budget budget,
                                            Path outDir) {
        List<LlmProviderFactory> factories = ServiceLoader.load(LlmProviderFactory.class).stream()
                .map(ServiceLoader.Provider::get).toList();
        return assemble(config, env, budget, outDir, factories, NO_LOG, Sleeper.REAL);
    }

    /**
     * Full form. Never throws for an unusable provider: it returns a {@link NoneLlmClient} whose
     * {@link SecPipeLlmClient#fallbacks()} names the reason (redacted).
     * <ul>
     *   <li>{@code none}: {@link NoneLlmClient#disabled()}.</li>
     *   <li>{@code fake}: {@link FakeLlmClient#fromDir} on {@value #FAKE_DIR_ENV}, with the budget; requires
     *       {@value #ALLOW_FAKE_ENV}=1.</li>
     *   <li>anything else: the matching factory; its {@code probe} must pass; then a {@link RoutingLlmClient}.</li>
     * </ul>
     */
    public static SecPipeLlmClient assemble(SecPipeConfig config, Map<String, String> env, Budget budget,
                                            Path outDir, List<LlmProviderFactory> factories, RunLog log,
                                            Sleeper sleeper) {
        Redactor redactor = Redactor.forEnv(env);
        ProviderMode mode = config.llmProvider();
        if (mode == ProviderMode.NONE) {
            return NoneLlmClient.disabled();
        }
        if (mode == ProviderMode.FAKE) {
            if (!"1".equals(env.get(ALLOW_FAKE_ENV))) {
                return unavailable(mode, "fake provider requires " + ALLOW_FAKE_ENV + "=1", log);
            }
            String dir = env.get(FAKE_DIR_ENV);
            if (dir == null || dir.isBlank()) {
                return unavailable(mode, "fake provider requires " + FAKE_DIR_ENV, log);
            }
            return FakeLlmClient.fromDir(Path.of(dir)).withBudget(budget);
        }
        Optional<LlmProviderFactory> factory = factories.stream().filter(f -> f.mode() == mode).findFirst();
        if (factory.isEmpty()) {
            return unavailable(mode, "no provider module for " + mode.cliName(), log);
        }
        Optional<String> problem;
        try {
            problem = factory.get().probe(config, env);
        } catch (RuntimeException e) {
            problem = Optional.of("probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (problem.isPresent()) {
            return unavailable(mode, redactor.redact(problem.get()), log);
        }
        ModelRouting routing = ModelRouting.from(config, factory.get().defaultModels());
        return new RoutingLlmClient(routing, factory.get().create(config, env), budget, redactor, log, sleeper);
    }

    private static NoneLlmClient unavailable(ProviderMode mode, String reason, RunLog log) {
        log.warn("llm: " + mode.cliName() + " unavailable, continuing without AI: " + reason);
        return NoneLlmClient.unavailable(mode, reason);
    }
}
