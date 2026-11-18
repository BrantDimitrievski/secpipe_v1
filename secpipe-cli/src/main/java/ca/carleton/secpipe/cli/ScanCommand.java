package ca.carleton.secpipe.cli;

import ca.carleton.secpipe.contracts.config.ConfigException;
import ca.carleton.secpipe.contracts.config.ConfigResolver;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code secpipe scan [PATH=.] [options]} (PLAN §4.3). Options are handed to {@link ConfigResolver} unchanged, as the
 * raw {@code --flag value} map, so the resolver stays the only place that computes defaults and implications.
 * The fields below exist for parsing and {@code --help}; {@link #overrides} reads what was actually typed.
 */
@Command(name = "scan", mixinStandardHelpOptions = true, versionProvider = SecPipeCli.VersionProvider.class,
        sortOptions = false,
        description = "Run the pipeline on PATH and write findings.json, coverage-ledger.json, run-metadata.json, "
                + "secpipe.sarif and report.md to the output directory.")
final class ScanCommand implements Callable<Integer> {

    private final PrintStream out;
    private final PrintStream err;
    private final Map<String, String> env;

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Parameters(index = "0", arity = "0..1", paramLabel = "PATH", description = "Project to scan (default: .)")
    private String target;

    @Option(names = "--profile", paramLabel = "quick|standard|deep") private String profile;
    @Option(names = "--scope", paramLabel = "diff|full") private String scope;
    @Option(names = "--base-ref", paramLabel = "REF") private String baseRef;
    @Option(names = "--head-ref", paramLabel = "REF") private String headRef;
    @Option(names = "--llm-provider", paramLabel = "PROVIDER",
            description = "none|anthropic|openai|openai-compatible|ollama (default none). The API key is read "
                    + "only from SECPIPE_API_KEY (or ANTHROPIC_API_KEY, OPENAI_API_KEY, SECPIPE_LLM_KEY).")
    private String llmProvider;
    @Option(names = "--model", paramLabel = "ID") private String model;
    @Option(names = "--model-for", paramLabel = "ROLE=ID") private List<String> modelFor;
    @Option(names = "--model-tier", paramLabel = "ID=TIER") private List<String> modelTier;
    @Option(names = "--llm-base-url", paramLabel = "URL") private String llmBaseUrl;
    @Option(names = "--tools", paramLabel = "IDS") private List<String> tools;
    @Option(names = "--exclude-tools", paramLabel = "IDS") private List<String> excludeTools;
    @Option(names = "--max-agent-calls", paramLabel = "N") private String maxAgentCalls;
    @Option(names = "--max-fix-attempts", paramLabel = "N") private String maxFixAttempts;
    @Option(names = "--max-sandbox-runs", paramLabel = "N") private String maxSandboxRuns;
    @Option(names = "--enable-fixes", paramLabel = "false|suggest|pr") private String enableFixes;
    @Option(names = "--no-sandbox", description = "Do not use Docker; findings stay needs_validation")
    private boolean noSandbox;
    @Option(names = "--offline", description = "No network (implies --llm-provider none unless ollama)")
    private boolean offline;
    @Option(names = "--no-ai", description = "Same as --llm-provider none") private boolean noAi;
    @Option(names = "--fail-on", paramLabel = "SEVERITY",
            description = "critical|high|medium|low|informational|none (default high): exit 1 on a confirmed "
                    + "finding at or above it")
    private String failOn;
    @Option(names = "--fail-on-needs-validation") private boolean failOnNeedsValidation;
    @Option(names = "--fail-on-incomplete") private boolean failOnIncomplete;
    @Option(names = "--format", paramLabel = "LIST") private List<String> format;
    @Option(names = {"-o", "--out"}, paramLabel = "DIR", description = "Output directory (default secpipe-out)")
    private String outDir;
    @Option(names = "--prior-run", paramLabel = "DIR") private String priorRun;
    @Option(names = "--print-prompts") private boolean printPrompts;
    @Option(names = "--deny-path", paramLabel = "GLOB") private List<String> denyPath;
    @Option(names = "--max-snippet-lines", paramLabel = "N") private String maxSnippetLines;
    @Option(names = "--max-files-per-call", paramLabel = "N") private String maxFilesPerCall;
    @Option(names = "--disable-node", paramLabel = "ID") private List<String> disableNode;
    @Option(names = "--include-tests") private boolean includeTests;
    @Option(names = "--allow-build-extensions") private boolean allowBuildExtensions;
    @Option(names = "--accept-codeql-terms") private boolean acceptCodeqlTerms;
    @Option(names = "--engine", paramLabel = "plain|langgraph4j") private String engine;
    @Option(names = "--tool-timeout", paramLabel = "DURATION") private String toolTimeout;
    @Option(names = "--config", paramLabel = "FILE", description = "secpipe.yml with the same keys")
    private String config;
    @Option(names = {"-q", "--quiet"}, description = "Only warnings and errors") private boolean quiet;
    @Option(names = {"-v", "--verbose"}, description = "Debug log and stack traces") private boolean verbose;

    ScanCommand(PrintStream out, PrintStream err, Map<String, String> env) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.env = Map.copyOf(Objects.requireNonNull(env, "env"));
    }

    @Override
    public Integer call() {
        SecPipeConfig cfg;
        try {
            Optional<Path> yaml = config == null ? Optional.empty() : Optional.of(Path.of(config));
            cfg = ConfigResolver.resolve(overrides(spec.commandLine().getParseResult()), env, yaml);
        } catch (ConfigException e) {
            return usage(e.problem(), e.hint());
        } catch (InvalidPathException e) {
            return usage("invalid path: " + e.getInput(), "check the --config path");
        }
        if (cfg.enableFixes() == SecPipeConfig.FixMode.PR) {
            return usage("--enable-fixes pr needs the GitHub Action (not built)", "use suggest or false");
        }
        if (cfg.printPrompts()) {
            return usage("--print-prompts is not implemented in this build",
                    "omit --print-prompts; use --no-ai to make sure no prompt is sent");
        }
        if (!Files.isDirectory(cfg.target())) {
            return usage("scan target is not a directory: " + cfg.target(),
                    "pass the root of a Java/Maven project, e.g. 'secpipe scan fixtures/e2e/sqli-min'");
        }
        PipelineState end;
        try {
            PipelineAssembly.Assembled run = PipelineAssembly.build(cfg, env, Clock.systemUTC(), err::println);
            end = run.runner().run(run.initial(), run.context());
        } catch (ConfigException e) {
            return usage(e.problem(), e.hint());
        } catch (RuntimeException e) {
            err.println("error: the scan could not run: " + SecPipeCli.oneLine(e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage()));
            err.println("hint: check that " + cfg.outDir() + " is writable; re-run with -v for details");
            if (cfg.verbosity() == SecPipeConfig.Verbosity.VERBOSE) {
                StringWriter trace = new StringWriter();
                e.printStackTrace(new PrintWriter(trace));
                err.print(trace);
            }
            return SecPipeCli.EXIT_USAGE;
        }
        ScanOutcome outcome = ScanOutcome.of(cfg, end);
        if (cfg.verbosity() != SecPipeConfig.Verbosity.QUIET) {
            outcome.print(out);
        }
        return outcome.exitCode();
    }

    private int usage(String problem, String hint) {
        err.println("error: " + problem);
        err.println("hint: " + hint);
        return SecPipeCli.EXIT_USAGE;
    }

    /**
     * The options exactly as typed, keyed by long name ({@code -o}, {@code -q}, {@code -v} map to {@code out},
     * {@code quiet}, {@code verbose}); repeated options are joined with {@link ConfigResolver#REPEAT_SEPARATOR}.
     * Flags map to {@code ""}. {@code PATH} becomes {@code target}.
     */
    static Map<String, String> overrides(CommandLine.ParseResult parsed) {
        Map<String, String> map = new LinkedHashMap<>();
        for (CommandLine.Model.OptionSpec o : parsed.matchedOptions()) {
            if (o.usageHelp() || o.versionHelp()) {
                continue;
            }
            String key = o.longestName().replaceFirst("^-+", "");
            List<String> values = parsed.matchedOption(o.longestName()).originalStringValues();
            map.put(key, o.arity().max() == 0 ? "" : String.join(ConfigResolver.REPEAT_SEPARATOR, values));
        }
        if (parsed.hasMatchedPositional(0)) {
            map.put("target", parsed.matchedPositional(0).getValue());
        }
        return map;
    }
}
