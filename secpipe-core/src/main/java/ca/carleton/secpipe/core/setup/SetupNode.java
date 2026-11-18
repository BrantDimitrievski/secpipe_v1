package ca.carleton.secpipe.core.setup;

import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.json.ContractJson;
import ca.carleton.secpipe.contracts.json.LedgerCodec;
import ca.carleton.secpipe.contracts.json.RunMetadataCodec;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.ProjectProfile;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.spi.EnvironmentInfo;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Graph node {@code setup} (WO-K.13, folded from WO-K.3): the first node of every run. It records the environment
 * (OS, JDK, Maven wrapper, Docker through the run's sandbox, GitHub Actions, analyzer tools on {@code PATH}), the
 * project profile skeleton ({@link ProjectProfiler}), the diff scope and the prior run, if one was given.
 *
 * <p>It only inspects files and environment variables; it never runs the target or a build tool (CLAUDE.md §7).
 * A target that is not a directory makes the run incomplete, with the reason, but the run still reaches report.
 *
 * <p>The lean pipeline has no incremental (diff) scanning: with {@code scope=diff} the node notes that the full
 * target is scanned and leaves {@code diffScope} empty, which every node reads as "all files".
 */
public final class SetupNode implements PipelineNode {

    /** Graph id. */
    public static final String ID = "setup";

    /** Note recorded when {@code --scope diff} is requested. */
    public static final String DIFF_SCOPE_NOTE = "setup: diff scope is not implemented in the CLI pipeline; "
            + "the full target was scanned";

    /** Executables looked up on {@code PATH} for the environment record (never executed). */
    static final List<String> TOOLS = List.of("docker", "git", "java", "mvn", "semgrep", "codeql", "joern",
            "spotbugs", "osv-scanner", "grype", "trivy");

    private final Map<String, String> env;

    /** Used by the engine (FQCN table in PipelineGraph): reads the process environment. */
    public SetupNode() {
        this(System.getenv());
    }

    /** For tests: reads {@code env} instead of the process environment. */
    public SetupNode(Map<String, String> env) {
        this.env = Map.copyOf(Objects.requireNonNull(env, "env"));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean requiresLlm() {
        return false;
    }

    @Override
    public boolean usesSandbox() {
        return false;
    }

    @Override
    public Result apply(PipelineState state, RunContext ctx) {
        SecPipeConfig config = state.config();
        List<String> notes = new ArrayList<>();
        Optional<String> incomplete = Optional.empty();

        Path target = config.target();
        if (!Files.isDirectory(target)) {
            incomplete = Optional.of("setup: scan target " + target + " is not a directory");
        }
        ProjectProfile profile = new ProjectProfiler(config.denyPaths()).profile(target);
        EnvironmentInfo environment = environment(profile.root(), ctx.sandbox());

        if (config.scope() == SecPipeConfig.ScopeMode.DIFF) {
            notes.add(DIFF_SCOPE_NOTE);
        }
        Optional<PipelineState.PriorRun> prior = Optional.empty();
        if (config.priorRunDir().isPresent()) {
            Path dir = config.priorRunDir().get();
            try {
                prior = Optional.of(readPrior(dir));
            } catch (RuntimeException e) {
                notes.add("setup: prior run in " + dir + " was not loaded: " + describe(e));
            }
        }
        ctx.log().info("setup: " + profile.sourceFiles().size() + " source files, " + profile.testSourceFiles().size()
                + " test files, build " + profile.buildTool().name().toLowerCase(java.util.Locale.ROOT)
                + ", docker " + (environment.dockerAvailable() ? "available" : "not used"));
        notes.forEach(n -> ctx.log().info(n));

        StateDelta e = StateDelta.empty();
        return Result.next(new StateDelta(Optional.of(environment), Optional.of(profile), Optional.of(List.of()),
                e.newLedgerUnits(), e.ledgerUpdates(), e.replaceCandidates(), e.addCandidates(), e.putRecords(),
                e.putSurvivorDrafts(), e.toolRuns(), e.timingsMs(), e.hunterWave(), e.criticPasses(),
                e.criticSaysStop(), e.fixAttemptsByFingerprint(), prior, List.copyOf(notes), incomplete));
    }

    /** The environment record; Docker counts as available only when the run has a usable sandbox. */
    EnvironmentInfo environment(Path root, Optional<SandboxRunner> sandbox) {
        Optional<String> dockerVersion = Optional.empty();
        if (sandbox.isPresent()) {
            try {
                dockerVersion = sandbox.get().probe().engineVersion();
            } catch (RuntimeException e) {
                dockerVersion = Optional.empty();
            }
        }
        String javaHome = System.getProperty("java.home");
        return new EnvironmentInfo(
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.arch", "unknown"),
                System.getProperty("java.version", "unknown"),
                javaHome == null ? Optional.empty() : Optional.of(Path.of(javaHome)),
                Optional.empty(),
                Files.isRegularFile(root.resolve("mvnw")),
                sandbox.isPresent(),
                dockerVersion,
                "true".equalsIgnoreCase(env.getOrDefault("GITHUB_ACTIONS", "")),
                toolsOnPath());
    }

    /** Tools from {@link #TOOLS} found as files on {@code PATH} (first match wins), sorted by name. */
    Map<String, Path> toolsOnPath() {
        Map<String, Path> found = new TreeMap<>();
        String path = env.getOrDefault("PATH", "");
        if (path.isBlank()) {
            return Map.of();
        }
        for (String tool : TOOLS) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                try {
                    Path candidate = Path.of(dir, tool);
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                        found.put(tool, candidate);
                        break;
                    }
                } catch (RuntimeException e) {
                    // an invalid PATH entry is skipped
                }
            }
        }
        return Map.copyOf(found);
    }

    /** Reads a previous run's findings, ledger and run id; any missing or invalid file throws. */
    static PipelineState.PriorRun readPrior(Path dir) {
        List<Finding> findings = ContractJson.readFindings(dir.resolve("findings.json"));
        List<LedgerUnit> ledger = LedgerCodec.read(dir.resolve("coverage-ledger.json"));
        String runId = RunMetadataCodec.read(dir.resolve("run-metadata.json")).runId();
        return new PipelineState.PriorRun(runId, ledger, findings, Map.of());
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m.lines().findFirst().orElse(m);
    }
}
