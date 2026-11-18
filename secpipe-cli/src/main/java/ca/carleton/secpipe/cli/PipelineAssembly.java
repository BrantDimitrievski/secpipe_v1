package ca.carleton.secpipe.cli;

import ca.carleton.secpipe.contracts.config.ConfigException;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.pipeline.GraphRunner;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.core.detect.DetectRescanner;
import ca.carleton.secpipe.core.engine.DefaultRunContext;
import ca.carleton.secpipe.core.engine.DefaultRunLog;
import ca.carleton.secpipe.core.engine.EngineValidators;
import ca.carleton.secpipe.core.engine.PlainGraphRunner;
import ca.carleton.secpipe.core.ledger.LedgerInvariants;
import ca.carleton.secpipe.core.scoring.ScoringPolicy;
import ca.carleton.secpipe.llm.CallBudget;
import ca.carleton.secpipe.llm.LlmClients;
import ca.carleton.secpipe.llm.Redactor;
import ca.carleton.secpipe.llm.SecPipeLlmClient;
import ca.carleton.secpipe.validators.FindingsValidator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * Assembles one run of the real pipeline (WO-K.13): the graph runner over {@code PipelineGraph} with the engine
 * validators, the {@link RunContext} services (LLM client, budget, sandbox, clock, redacting log) and the initial
 * state. The rescanner is {@link DetectRescanner} (WO-K.15): detect + normalize on a working copy with the run's
 * adapters (discovered through ServiceLoader, as the detect node does). {@code PipelineGraph} stays the only source of the topology; nodes load by FQCN.
 */
public final class PipelineAssembly {

    /** Directory under the output directory for engine scratch ({@code state.json}, recon facts, tool output). */
    public static final String WORK_DIR = ".work";

    private static final DateTimeFormatter RUN_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    /** What {@link #build} returns: run {@code runner.run(initial, context)}. */
    public record Assembled(GraphRunner runner, RunContext context, PipelineState initial) {
        public Assembled {
            Objects.requireNonNull(runner, "runner");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(initial, "initial");
        }
    }

    private PipelineAssembly() {
    }

    /** Assembles with the process environment, the system clock and a log on stderr. */
    public static Assembled build(SecPipeConfig config) {
        return build(config, System.getenv(), Clock.systemUTC(), System.err::println);
    }

    /**
     * Assembles a run.
     *
     * @param env     the environment: provider keys are read from it only at call time, by the LLM client
     * @param logSink receives formatted, redacted log lines
     * @throws ConfigException when the configuration asks for something this build cannot provide
     */
    public static Assembled build(SecPipeConfig config, Map<String, String> env, Clock clock,
                                  Consumer<String> logSink) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(env, "env");
        if (config.engine() != SecPipeConfig.GraphEngine.PLAIN) {
            throw new ConfigException("engine " + config.engine().name().toLowerCase(java.util.Locale.ROOT)
                    + " is not available in this build", "use --engine plain (the default)");
        }
        Redactor redactor = Redactor.forEnv(env);
        RunContext.RunLog log = new DefaultRunLog(config.verbosity(), line -> logSink.accept(redactor.redact(line)));

        Path out = config.outDir();
        CallBudget budget = new CallBudget(config.maxAgentCalls(), config.maxSandboxRuns());
        SecPipeLlmClient llm = LlmClients.assemble(config, env, budget, out, providers(), log,
                ca.carleton.secpipe.llm.Sleeper.REAL);
        Optional<SandboxRunner> sandbox = sandbox(config, log);

        RunContext ctx = DefaultRunContext.builder()
                .workDir(out.resolve(WORK_DIR))
                .outDir(out)
                .llm(llm)
                .sandbox(sandbox)
                .budget(budget)
                .clock(clock)
                .log(log)
                .rescanner(new DetectRescanner(config, List.of(), env, out.resolve(WORK_DIR), clock, log))
                .build();

        Path target = config.target();
        Optional<Path> root = Files.isDirectory(target) ? Optional.of(target) : Optional.empty();
        ScoringPolicy policy = new ScoringPolicy();
        EngineValidators validators = new EngineValidators(
                records -> FindingsValidator.validate(records, root, policy),
                LedgerInvariants::check);
        GraphRunner runner = new PlainGraphRunner(validators);
        return new Assembled(runner, ctx, initialState(config, clock));
    }

    /** The empty state every run starts from. */
    static PipelineState initialState(SecPipeConfig config, Clock clock) {
        return new PipelineState(runId(clock), config, Optional.empty(), Optional.empty(), List.of(), List.of(),
                List.of(), Map.of(), Map.of(), List.of(), Map.of(), 0, 0, false, Map.of(), Optional.empty(),
                List.of(), Optional.empty());
    }

    /** {@code run-<yyyyMMdd>T<HHmmss>Z-<6 hex>}, as run-metadata.schema.json requires. */
    static String runId(Clock clock) {
        byte[] random = new byte[3];
        new SecureRandom().nextBytes(random);
        return "run-" + RUN_ID_TIME.format(clock.instant()) + "-" + HexFormat.of().formatHex(random);
    }

    private static List<ca.carleton.secpipe.contracts.spi.LlmProviderFactory> providers() {
        return ServiceLoader.load(ca.carleton.secpipe.contracts.spi.LlmProviderFactory.class).stream()
                .map(ServiceLoader.Provider::get).toList();
    }

    /** The Docker sandbox when enabled and usable; otherwise empty (evidence then stays needs_validation). */
    static Optional<SandboxRunner> sandbox(SecPipeConfig config, RunContext.RunLog log) {
        if (!config.sandboxEnabled()) {
            log.info("sandbox: disabled by --no-sandbox");
            return Optional.empty();
        }
        Optional<SandboxRunner> runner = ServiceLoader.load(SandboxRunner.class).findFirst();
        if (runner.isEmpty()) {
            log.warn("sandbox: no sandbox runner installed; findings stay needs_validation");
            return Optional.empty();
        }
        try {
            SandboxRunner.Capabilities caps = runner.get().probe();
            if (!caps.available()) {
                log.warn("sandbox: unavailable (" + caps.reason().orElse("unknown reason")
                        + "); findings stay needs_validation");
                return Optional.empty();
            }
        } catch (RuntimeException e) {
            log.warn("sandbox: probe failed (" + e.getMessage() + "); findings stay needs_validation");
            return Optional.empty();
        }
        return runner;
    }
}
