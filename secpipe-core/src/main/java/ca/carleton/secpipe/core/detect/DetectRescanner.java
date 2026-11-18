package ca.carleton.secpipe.core.detect;

import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;
import ca.carleton.secpipe.core.engine.DefaultRunContext;
import ca.carleton.secpipe.core.normalize.NormalizeNode;
import ca.carleton.secpipe.core.setup.SetupNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The real {@link RunContext.Rescanner} (WO-K.15): re-runs {@code setup} (profile and environment only),
 * {@code detect} and {@code normalize} on a patched working copy, with the run's configuration and adapters, so the
 * fingerprints compare with the original run's.
 *
 * <p>Analyzers only parse the copy. The rescan context has no sandbox, so adapters that need one (they would build
 * or run target code) are skipped; nothing in the target runs on the host. Adapter scratch goes under
 * {@code <workDir>/rescan/<n>/}; the working copy and the user's tree are never written.
 *
 * <p>The result is sorted by fingerprint. A non-empty {@code scopeFiles} keeps only candidates located in one of
 * those files (paths relative to the working copy, {@code /}-separated); an empty set keeps all.
 */
public final class DetectRescanner implements RunContext.Rescanner {

    private final SecPipeConfig config;
    private final List<AnalyzerAdapter> adapters;
    private final Map<String, String> env;
    private final Path workDir;
    private final Clock clock;
    private final RunContext.RunLog log;
    private final AtomicInteger sequence = new AtomicInteger();

    /**
     * @param adapters the run's adapters; empty means "discover through ServiceLoader", as {@link DetectNode} does
     * @param env      environment used only to record tools on {@code PATH} (never executed)
     * @param workDir  the run's engine scratch directory
     */
    public DetectRescanner(SecPipeConfig config, List<AnalyzerAdapter> adapters, Map<String, String> env,
                           Path workDir, Clock clock, RunContext.RunLog log) {
        this.config = Objects.requireNonNull(config, "config");
        this.adapters = List.copyOf(adapters);
        this.env = Map.copyOf(Objects.requireNonNull(env, "env"));
        this.workDir = Objects.requireNonNull(workDir, "workDir");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public List<Candidate> rescan(Path workingCopy, Set<String> scopeFiles) throws Exception {
        Objects.requireNonNull(workingCopy, "workingCopy");
        Set<String> scope = Set.copyOf(Objects.requireNonNull(scopeFiles, "scopeFiles"));
        if (!Files.isDirectory(workingCopy)) {
            throw new IllegalArgumentException("rescan working copy is not a directory: " + workingCopy);
        }
        Path scratch = workDir.resolve("rescan").resolve(Integer.toString(sequence.incrementAndGet()));
        RunContext ctx = DefaultRunContext.builder()
                .workDir(scratch)
                .outDir(scratch.resolve("out"))
                .adapters(adapters)
                .sandbox(Optional.empty())
                .clock(clock)
                .log(log)
                .build();

        PipelineState state = new PipelineState("rescan", forCopy(config, workingCopy), Optional.empty(),
                Optional.empty(), List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of(), Map.of(), 0, 0,
                false, Map.of(), Optional.empty(), List.of(), Optional.empty());

        StateDelta setup = run(new SetupNode(env), state, ctx);
        state = withSetup(state, setup);
        StateDelta detect = run(new DetectNode(), state, ctx);
        state = withCandidates(state, detect.addCandidates());
        StateDelta normalize = run(new NormalizeNode(), state, ctx);
        List<Candidate> normalized = normalize.replaceCandidates().orElse(List.of());

        List<Candidate> out = normalized.stream()
                .filter(c -> scope.isEmpty() || files(c).stream().anyMatch(scope::contains))
                .sorted(Comparator.comparing(c -> c.fingerprint().orElse("")))
                .toList();
        log.info("rescan: " + out.size() + " candidate(s) in " + workingCopy.getFileName()
                + (scope.isEmpty() ? "" : " (" + scope.size() + " file(s) in scope)"));
        return List.copyOf(out);
    }

    private static StateDelta run(PipelineNode node, PipelineState state, RunContext ctx) throws Exception {
        PipelineNode.Result r = node.apply(state, ctx);
        return r.delta();
    }

    /** The files a candidate is located in: its sink step and every tool source with a file. */
    static Set<String> files(Candidate c) {
        java.util.TreeSet<String> files = new java.util.TreeSet<>();
        if (!c.trace().isEmpty()) {
            Finding.TraceStep sink = c.trace().get(c.trace().size() - 1);
            if (!sink.file().isBlank()) {
                files.add(sink.file());
            }
        }
        c.toolSources().forEach(t -> t.file().ifPresent(files::add));
        return files;
    }

    private static PipelineState withSetup(PipelineState s, StateDelta d) {
        return new PipelineState(s.runId(), s.config(), d.environment(), d.profile(), List.of(), s.ledger(),
                s.candidates(), s.records(), s.survivorDrafts(), s.toolRuns(), s.stageTimingsMs(), s.hunterWave(),
                s.criticPasses(), s.criticSaysStop(), s.fixAttemptsByFingerprint(), s.prior(), s.notes(),
                s.incompleteReason());
    }

    private static PipelineState withCandidates(PipelineState s, List<Candidate> candidates) {
        return new PipelineState(s.runId(), s.config(), s.environment(), s.profile(), s.diffScope(), s.ledger(),
                List.copyOf(candidates), s.records(), s.survivorDrafts(), s.toolRuns(), s.stageTimingsMs(),
                s.hunterWave(), s.criticPasses(), s.criticSaysStop(), s.fixAttemptsByFingerprint(), s.prior(),
                s.notes(), s.incompleteReason());
    }

    /** The run's configuration pointed at the working copy: full scope, no prior run, no sandbox. */
    static SecPipeConfig forCopy(SecPipeConfig c, Path workingCopy) {
        return new SecPipeConfig(workingCopy, c.outDir(), c.profile(), SecPipeConfig.ScopeMode.FULL, c.baseRef(),
                c.headRef(), c.llmProvider(), c.defaultModel(), c.modelForRole(), c.tierOverrides(), c.llmBaseUrl(),
                c.tools(), c.excludeTools(), c.maxAgentCalls(), c.maxFixAttempts(), c.maxSandboxRuns(),
                c.enableFixes(), false, c.offline(), c.failOnSeverity(), c.failOnNeedsValidation(),
                c.failOnIncomplete(), c.formats(), Optional.empty(), c.printPrompts(), c.denyPaths(),
                c.maxSnippetLines(), c.maxFilesPerCall(), c.disabledNodes(), c.includeTests(),
                c.allowBuildExtensions(), c.acceptCodeqlTerms(), c.engine(), c.toolTimeout(), c.verbosity());
    }
}
