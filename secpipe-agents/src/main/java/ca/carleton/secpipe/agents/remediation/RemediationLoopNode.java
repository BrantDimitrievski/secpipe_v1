package ca.carleton.secpipe.agents.remediation;

import ca.carleton.secpipe.agents.prompt.DefaultPromptRenderer;
import ca.carleton.secpipe.agents.remediation.RemediationAgent.PreviousAttempt;
import ca.carleton.secpipe.agents.remediation.RemediationAgent.RegressionTest;
import ca.carleton.secpipe.agents.runner.AgentOutcome;
import ca.carleton.secpipe.agents.runner.AgentRunner;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.RemediationResult;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.FixAttempt;
import ca.carleton.secpipe.contracts.finding.Finding.TestEvidence;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.spi.Budget;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Control;
import ca.carleton.secpipe.contracts.spi.SourceIndex;
import ca.carleton.secpipe.sandbox.BuildExtensionPreflight;
import ca.carleton.secpipe.sandbox.MavenWorkspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Pipeline node {@code remediate} (WO-M.1, FR-13/FR-14): for every {@code confirmed} code record it asks
 * {@link RemediationAgent} for a fix and verifies it, up to {@code --max-fix-attempts} times, until one is
 * {@code fixed}. Runs only when {@code --enable-fixes} is not {@code false} (the default {@code suggest} runs it).
 *
 * <p>Once per run: {@link MavenWorkspace#prepare} (prefetch, then everything offline) and the
 * {@link SuiteBaseline} (passing project tests + detect fingerprints of the unpatched copy). Per attempt, each step
 * on a <b>fresh</b> NIO copy of the pristine workspace, never the user's tree:
 * <ol>
 *   <li>{@link PatchChecker} and a {@link UnifiedDiff} dry run, then apply; otherwise {@code refused};</li>
 *   <li>offline compile plus the regression test in the sandbox (one run): a build failure is
 *   {@code build_broken}; the regression test must PASS (a skip is never a pass), otherwise
 *   {@code still_present};</li>
 *   <li>the whole project suite on a second fresh patched copy (one run): every baseline-passing test must still
 *   pass, otherwise {@code tests_broken};</li>
 *   <li>{@code ctx.rescanner()} on that copy (touched files plus the record's trace files): the original
 *   fingerprint still present is {@code still_present}; a new fingerprint is {@code newly_introduced_findings} and
 *   becomes a new {@code needs_validation} record ({@value #INTRODUCED_PREFIX}{@code <patch>; not validated});</li>
 *   <li>all passed: {@code fixed}.</li>
 * </ol>
 * The failing step and a trimmed log go into the next attempt's prompt. Every attempt's patch is written to
 * {@code <out>/patches/<fingerprint-short>-attempt<N>.patch} (from memory; nothing is copied out of scratch).
 * Agent calls count against the LLM budget (the client enforces it); every sandbox run (baseline, regression,
 * suite) takes one {@link Budget#tryAcquireSandboxRun()}. When a budget runs out the record keeps its status
 * (attempts made so far are kept) and the run notes it. Without a usable sandbox a confirmed record gets
 * {@code remediation_status=not_attempted} with one {@code sandbox_unavailable} attempt.
 */
public final class RemediationLoopNode implements PipelineNode {

    public static final String ID = "remediate";
    /** Directory of the patches inside the output directory. */
    public static final String PATCH_DIR = "patches";
    static final String INTRODUCED_PREFIX = "introduced by candidate fix ";
    static final String INTRODUCED_SUFFIX = "; not validated";
    static final String SANDBOX_UNAVAILABLE = "sandbox unavailable";
    static final String PLAN_LOCAL = "Review the candidate fix named in the blocker and the cited code by hand, or "
            + "re-run SecPipe on a tree with that fix applied.";
    private static final int MAX_LOG_CHARS = 4000;
    private static final long MAX_INDEXED_FILE_BYTES = 1024L * 1024;
    private static final Set<String> SKIPPED_DIRS = Set.of(".git", "target");

    /** Where the regression test of a confirmed record comes from. */
    @FunctionalInterface
    interface RegressionTestSource {
        Optional<RegressionTest> find(Finding.Confirmed record, Path workDir);
    }

    /**
     * The test that confirmed the record (WO-G.7): its last {@code pre_fix}/{@code failed_as_expected} evidence
     * names the test file, which the evidence node left in {@code <work>/evidence/<id>/}.
     */
    static final RegressionTestSource FROM_EVIDENCE = (record, workDir) -> {
        List<TestEvidence> pre = record.secpipe().testEvidence().stream()
                .filter(t -> t.phase() == TestEvidence.Phase.PRE_FIX
                        && t.outcome() == TestEvidence.Outcome.FAILED_AS_EXPECTED && t.testFile().isPresent())
                .toList();
        if (pre.isEmpty()) {
            return Optional.empty();
        }
        TestEvidence te = pre.get(pre.size() - 1);
        String file = te.testFile().orElseThrow();
        try {
            SourceIndex.requireRelativePath(file);
            Path dir = workDir.resolve("evidence").resolve(te.id()).normalize();
            Path p = dir.resolve(file).normalize();
            if (!p.startsWith(dir) || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            return Optional.of(new RegressionTest(file, Files.readString(p, StandardCharsets.UTF_8)));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    };

    /** What the node needs from config and context (tests pass it directly). */
    record Settings(SecPipeConfig.FixMode enableFixes, int maxFixAttempts, boolean sandboxEnabled,
                    boolean allowBuildExtensions, Path target, Path workDir, Path outDir) {
        Settings {
            Objects.requireNonNull(enableFixes, "enableFixes");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(workDir, "workDir");
            Objects.requireNonNull(outDir, "outDir");
            if (maxFixAttempts < 1) {
                throw new IllegalArgumentException("maxFixAttempts must be >= 1");
            }
        }

        static Settings of(PipelineState state, RunContext ctx) {
            SecPipeConfig cfg = state.config();
            Path target = state.profile().map(p -> p.root())
                    .orElseGet(() -> cfg == null ? Path.of(".") : cfg.target());
            return new Settings(cfg == null ? SecPipeConfig.FixMode.SUGGEST : cfg.enableFixes(),
                    cfg == null ? SecPipeConfig.DEFAULT_MAX_FIX_ATTEMPTS : Math.max(1, cfg.maxFixAttempts()),
                    cfg == null || cfg.sandboxEnabled(), cfg != null && cfg.allowBuildExtensions(), target,
                    ctx.workDir(), ctx.outDir());
        }
    }

    /** Collaborators of one node run (tests pass fakes). */
    record Services(LlmClient llm, Optional<SandboxRunner> sandbox, RunContext.Rescanner rescanner, Budget budget,
                    RunContext.RunLog log, RegressionTestSource tests) {
        Services {
            Objects.requireNonNull(llm, "llm");
            Objects.requireNonNull(sandbox, "sandbox");
            Objects.requireNonNull(rescanner, "rescanner");
            Objects.requireNonNull(budget, "budget");
            Objects.requireNonNull(log, "log");
            Objects.requireNonNull(tests, "tests");
        }
    }

    public RemediationLoopNode() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean requiresLlm() {
        return true;
    }

    @Override
    public boolean usesSandbox() {
        return true;
    }

    @Override
    public Result apply(PipelineState state, RunContext ctx) throws InterruptedException {
        return Result.next(evaluate(state, Settings.of(state, ctx), new Services(ctx.llm(), ctx.sandbox(),
                ctx.rescanner(), ctx.budget(), ctx.log(), FROM_EVIDENCE)));
    }

    /** The node's work with its collaborators passed in. */
    static StateDelta evaluate(PipelineState state, Settings settings, Services services)
            throws InterruptedException {
        if (settings.enableFixes() == SecPipeConfig.FixMode.FALSE) {
            return StateDelta.empty();
        }
        Map<String, Finding.Confirmed> todo = new TreeMap<>();
        for (Finding f : state.records().values()) {
            if (f instanceof Finding.Confirmed c && c.secpipe().category() == Finding.Category.CODE) {
                todo.put(c.fingerprint(), c);
            }
        }
        if (todo.isEmpty()) {
            return StateDelta.empty();
        }
        Run run = new Run(state, settings, services);
        run.run(todo);
        return run.delta();
    }

    /** One node invocation. */
    private static final class Run {
        private final PipelineState state;
        private final Settings settings;
        private final Services svc;
        private final Map<String, Finding> out = new TreeMap<>();
        private final Map<String, Integer> attemptsByFp = new TreeMap<>();
        private final List<String> notes = new ArrayList<>();
        private MavenWorkspace ws;
        private Path pristine;
        private SuiteBaseline.Baseline baseline;
        private boolean sandboxBudgetOut;

        Run(PipelineState state, Settings settings, Services svc) {
            this.state = state;
            this.settings = settings;
            this.svc = svc;
        }

        void run(Map<String, Finding.Confirmed> todo) throws InterruptedException {
            Map<String, RegressionTest> tests = new TreeMap<>();
            for (Finding.Confirmed c : todo.values()) {
                Optional<RegressionTest> t = svc.tests().find(c, settings.workDir())
                        .filter(rt -> rt.path().startsWith("src/test/java/") && rt.path().endsWith(".java"));
                if (t.isPresent()) {
                    tests.put(c.fingerprint(), t.get());
                }
            }
            Optional<String> blocker = sandboxBlocker();
            if (blocker.isEmpty() && tests.isEmpty() && !todo.isEmpty()) {
                for (Finding.Confirmed c : todo.values()) {
                    note("remediation not attempted for " + c.fingerprint() + ": no regression test available");
                    put(c, List.of());
                }
                return;
            }
            if (blocker.isEmpty()) {
                blocker = prepare();
            }
            if (blocker.isPresent()) {
                note("remediation not attempted: " + blocker.get());
                for (Finding.Confirmed c : todo.values()) {
                    if (blocker.get().startsWith(SANDBOX_BUDGET)) {
                        continue;
                    }
                    put(c, List.of(FixClassifier.sandboxUnavailable(1)));
                }
                return;
            }
            SourceIndex sources = index(pristine);
            AgentRunner.DiscardListener listener = (role, agentId, attempt, reason) ->
                    svc.log().warn("remediate: discarded " + agentId + " output (attempt " + attempt + "): "
                            + reason);
            AgentRunner runner = new AgentRunner(svc.llm(), new DefaultPromptRenderer(), listener);
            RemediationAgent agent = new RemediationAgent(runner, sources, pristine, listener);
            for (Finding.Confirmed c : todo.values()) {
                RegressionTest test = tests.get(c.fingerprint());
                if (test == null) {
                    note("remediation not attempted for " + c.fingerprint() + ": no regression test available");
                    put(c, List.of());
                    continue;
                }
                remediate(c, test, agent);
            }
        }

        private static final String SANDBOX_BUDGET = "sandbox run budget exhausted";

        private Optional<String> sandboxBlocker() {
            if (!settings.sandboxEnabled() || svc.sandbox().isEmpty()) {
                return Optional.of(SANDBOX_UNAVAILABLE);
            }
            SandboxRunner.Capabilities caps = svc.sandbox().get().probe();
            if (!caps.available()) {
                return Optional.of(SANDBOX_UNAVAILABLE + caps.reason().map(r -> ": " + r).orElse(""));
            }
            Set<Control> missing = EnumSet.allOf(Control.class);
            missing.removeAll(caps.enforceable());
            return missing.isEmpty() ? Optional.empty()
                    : Optional.of("sandbox refused: required controls not enforceable: " + names(missing));
        }

        /** Workspace (prefetch once) and suite baseline; empty when both are ready. */
        private Optional<String> prepare() throws InterruptedException {
            SandboxRunner runner = svc.sandbox().orElseThrow();
            Path root = settings.workDir().resolve("remediation");
            ws = new MavenWorkspace(runner, root.resolve("workspace"),
                    MavenWorkspace.Options.defaults().withAllowBuildExtensions(settings.allowBuildExtensions()));
            try {
                switch (ws.prepare(settings.target())) {
                    case MavenWorkspace.Prepared p -> pristine = p.scratchCopy();
                    case MavenWorkspace.PrefetchIncomplete pi -> {
                        return Optional.of(pi.blocker() + ": " + pi.reason());
                    }
                    case MavenWorkspace.ExtensionsRefused er -> {
                        return Optional.of(BuildExtensionPreflight.BLOCKER + ": " + er.message());
                    }
                }
            } catch (SandboxRunner.SandboxRefusedException e) {
                return Optional.of("sandbox refused: required controls not enforceable: " + names(e.missing()));
            } catch (IllegalArgumentException e) {
                return Optional.of("not a Maven project: fixes are verified with a Maven build (pom.xml)");
            } catch (UncheckedIOException e) {
                return Optional.of("sandbox workspace could not be prepared: " + e.getMessage());
            }
            if (!svc.budget().tryAcquireSandboxRun()) {
                sandboxBudgetOut = true;
                return Optional.of(SANDBOX_BUDGET + " before the suite baseline (--max-sandbox-runs)");
            }
            SuiteBaseline.Outcome outcome = SuiteBaseline.capture(runner, ws, pristine, root.resolve("baseline"),
                    svc.rescanner(), root);
            return switch (outcome) {
                case SuiteBaseline.Captured cap -> {
                    baseline = cap.baseline();
                    svc.log().info("remediate: suite baseline has " + baseline.passingTests().size()
                            + " passing tests and " + baseline.fingerprints().size() + " fingerprints");
                    yield Optional.empty();
                }
                case SuiteBaseline.Unusable u -> Optional.of(u.reason());
            };
        }

        private void remediate(Finding.Confirmed record, RegressionTest test, RemediationAgent agent)
                throws InterruptedException {
            String fp = record.fingerprint();
            String fqcn = fqcn(test.path());
            List<FixAttempt> attempts = new ArrayList<>();
            List<PreviousAttempt> previous = new ArrayList<>();
            Path dir = settings.workDir().resolve("remediation").resolve(shortId(fp));
            for (int n = 1; n <= settings.maxFixAttempts(); n++) {
                if (sandboxBudgetOut) {
                    note("remediation of " + fp + " stopped: " + SANDBOX_BUDGET + " (--max-sandbox-runs)");
                    break;
                }
                if (svc.budget().exhausted()) {
                    note("remediation of " + fp + " stopped: agent-call budget exhausted (--max-agent-calls)");
                    break;
                }
                AgentOutcome<RemediationResult> proposal = agent.propose(record, test, previous);
                if (proposal instanceof AgentOutcome.Unavailable<RemediationResult> u) {
                    note("remediation of " + fp + " stopped: " + u.reason());
                    break;
                }
                if (proposal instanceof AgentOutcome.Dropped<RemediationResult> d) {
                    attempts.add(FixClassifier.toFixAttempt(n, Optional.empty(), Optional.empty(),
                            FixClassifier.classify(FixClassifier.Observations.start().withPatchApplied(false))));
                    previous.add(new PreviousAttempt(n, "", "refused: " + d.reason(), d.reason()));
                    continue;
                }
                RemediationResult fix = ((AgentOutcome.Ok<RemediationResult>) proposal).value();
                String patchArtifact = PATCH_DIR + "/" + shortId(fp) + "-attempt" + n + ".patch";
                Optional<Attempt> result = attempt(n, fp, fix.patch(), test, fqcn, record, dir);
                if (result.isEmpty()) {
                    note("remediation of " + fp + " stopped: " + SANDBOX_BUDGET + " (--max-sandbox-runs)");
                    break;
                }
                Attempt a = result.get();
                writePatch(patchArtifact, fix.patch());
                attempts.add(FixClassifier.toFixAttempt(n, Optional.of(patchArtifact), Optional.of(fix.strategy()),
                        a.classification()));
                svc.log().info("remediate: " + fp + " attempt " + n + ": "
                        + a.classification().outcome().name().toLowerCase(Locale.ROOT));
                for (String newFp : a.classification().newFingerprints()) {
                    introduced(newFp, a.rescanned().get(newFp), patchArtifact);
                }
                if (a.classification().outcome() == FixAttempt.Outcome.FIXED) {
                    break;
                }
                previous.add(new PreviousAttempt(n, fix.patch(), a.failingStep(), cap(a.log())));
            }
            if (attempts.isEmpty()) {
                return; // budget or AI unavailable before any attempt: the record keeps its status
            }
            put(record, attempts);
        }

        /** One verified attempt; empty when the sandbox-run budget ran out. */
        private Optional<Attempt> attempt(int n, String fp, String patch, RegressionTest test, String fqcn,
                                          Finding.Confirmed record, Path dir) throws InterruptedException {
            SandboxRunner runner = svc.sandbox().orElseThrow();
            FixClassifier.Observations obs = FixClassifier.Observations.start();
            Path regressionCopy = dir.resolve("attempt" + n + "-regression");
            SuiteBaseline.freshCopy(pristine, regressionCopy);
            List<String> problems = applyProblems(regressionCopy, patch, test.path());
            if (!problems.isEmpty()) {
                return Optional.of(new Attempt(FixClassifier.classify(obs.withPatchApplied(false)),
                        "refused: " + String.join("; ", problems), String.join("\n", problems), Map.of()));
            }
            List<String> touched = UnifiedDiff.apply(regressionCopy, patch);
            obs = obs.withPatchApplied(true);
            writeTest(regressionCopy, test);
            if (!svc.budget().tryAcquireSandboxRun()) {
                sandboxBudgetOut = true;
                return Optional.empty();
            }
            SuiteBaseline.TestRun reg = SuiteBaseline.runTests(runner, ws, regressionCopy, Optional.of(fqcn),
                    "regression test " + fqcn + " (fix attempt " + n + ")");
            if (reg instanceof SuiteBaseline.BuildFailed bf) {
                return Optional.of(new Attempt(FixClassifier.classify(obs.withCompiled(false)),
                        "build broken: " + bf.detail(), bf.logTail(), Map.of()));
            }
            obs = obs.withCompiled(true);
            FixClassifier.RegressionResult rr = FixClassifier.regressionResult(reg);
            obs = obs.withRegressionTest(rr);
            if (rr != FixClassifier.RegressionResult.PASSED) {
                return Optional.of(new Attempt(FixClassifier.classify(obs), "regression test did not pass ("
                        + rr.name().toLowerCase(Locale.ROOT) + "): the vulnerability is still present",
                        describe(reg), Map.of()));
            }
            Path suiteCopy = dir.resolve("attempt" + n + "-suite");
            SuiteBaseline.freshCopy(pristine, suiteCopy);
            UnifiedDiff.apply(suiteCopy, patch);
            if (!svc.budget().tryAcquireSandboxRun()) {
                sandboxBudgetOut = true;
                return Optional.empty();
            }
            SuiteBaseline.TestRun suite = SuiteBaseline.runTests(runner, ws, suiteCopy, Optional.empty(),
                    SuiteBaseline.PURPOSE_PREFIX + " (fix attempt " + n + ")");
            boolean suitePassed = suite instanceof SuiteBaseline.Completed done && baseline.suitePassed(done);
            obs = obs.withSuitePassed(suitePassed);
            if (!suitePassed) {
                String broken = suite instanceof SuiteBaseline.Completed done
                        ? "baseline tests no longer pass: " + String.join(", ", baseline.brokenTests(done))
                        : "project tests did not run: " + describe(suite);
                return Optional.of(new Attempt(FixClassifier.classify(obs), "tests broken: " + broken,
                        broken, Map.of()));
            }
            Set<String> scope = new TreeSet<>(touched);
            record.trace().forEach(s -> scope.add(s.file()));
            Map<String, Candidate> rescanned = new TreeMap<>();
            try {
                for (Candidate c : svc.rescanner().rescan(suiteCopy, Set.copyOf(scope))) {
                    c.fingerprint().ifPresent(f -> rescanned.putIfAbsent(f, c));
                }
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                return Optional.of(new Attempt(FixClassifier.classify(obs), "rescan failed: " + e.getMessage(),
                        String.valueOf(e.getMessage()), Map.of()));
            }
            obs = obs.withRescan(FixClassifier.Rescan.of(rescanned.keySet(), fp, baseline));
            FixClassifier.Classification c = FixClassifier.classify(obs);
            String step = switch (c.outcome()) {
                case STILL_PRESENT -> "rescan: the original finding " + fp + " is still detected";
                case NEWLY_INTRODUCED_FINDINGS -> "rescan: the fix introduced new findings "
                        + String.join(", ", c.newFingerprints());
                default -> "none";
            };
            return Optional.of(new Attempt(c, step, step, rescanned));
        }

        private List<String> applyProblems(Path copy, String patch, String testPath) {
            List<String> out = new ArrayList<>();
            try {
                PatchChecker.check(patch, copy, testPath).forEach(v -> out.add(v.toString()));
                if (out.isEmpty()) {
                    UnifiedDiff.dryRun(copy, patch).problems().forEach(p -> out.add("DRY_RUN: " + p));
                }
            } catch (RuntimeException e) {
                out.add("patch could not be read: " + e.getMessage());
            }
            return out;
        }

        private void writeTest(Path copy, RegressionTest test) {
            try {
                Path p = copy.resolve(test.path());
                Files.createDirectories(p.getParent());
                Files.writeString(p, test.source(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot write the regression test into " + copy, e);
            }
        }

        private void writePatch(String artifact, String patch) {
            try {
                Path p = settings.outDir().resolve(artifact);
                Files.createDirectories(p.getParent());
                Files.writeString(p, patch, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot write " + artifact, e);
            }
        }

        /** A new {@code needs_validation} record for a fingerprint the candidate fix introduced. */
        private void introduced(String newFp, Candidate c, String patchArtifact) {
            if (c == null || state.records().containsKey(newFp) || out.containsKey(newFp)) {
                return;
            }
            if (c.trace().isEmpty() || c.trace().get(c.trace().size() - 1).kind() != Finding.TraceKind.SINK) {
                note("finding " + newFp + " introduced by candidate fix " + patchArtifact
                        + " has no trace ending at a sink; not recorded");
                return;
            }
            List<Finding.Evidence> evidence = c.evidence();
            if (evidence.isEmpty()) {
                Finding.TraceStep sink = c.trace().get(c.trace().size() - 1);
                evidence = List.of(new Finding.Evidence(sink.file(), sink.line(),
                        blank(sink.description()) ? "sink" : sink.description()));
            }
            Set<String> files = new TreeSet<>();
            c.trace().forEach(s -> files.add(s.file()));
            evidence.forEach(e -> files.add(e.file()));
            c.toolSources().forEach(t -> t.file().ifPresent(files::add));
            for (String f : files) {
                if (!existsInTarget(f)) {
                    note("finding " + newFp + " introduced by candidate fix " + patchArtifact + " is in " + f
                            + ", which is not in the scanned tree; not recorded");
                    return;
                }
            }
            String title = blank(c.title()) ? "candidate " + newFp : c.title();
            String description = blank(c.message()) ? title : c.message();
            String rootCause = blank(c.claimedRootCause()) ? description : c.claimedRootCause();
            Finding.SecPipeExt ext = new Finding.SecPipeExt(c.category(), c.origin(), c.toolSources(), c.cwe(),
                    c.cve(), Optional.empty(), c.dependency(), c.toolSeverityHint(), c.confidenceHint(), List.of(),
                    List.of(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(),
                    Optional.empty());
            out.put(newFp, new Finding.NeedsValidation(newFp, title, description, rootCause, c.trace(),
                    List.copyOf(evidence), List.of(INTRODUCED_PREFIX + patchArtifact + INTRODUCED_SUFFIX),
                    new Finding.ValidationPlan(Optional.of(PLAN_LOCAL), Optional.empty()), ext));
        }

        private boolean existsInTarget(String file) {
            try {
                SourceIndex.requireRelativePath(file);
            } catch (IllegalArgumentException e) {
                return false;
            }
            Path root = settings.target().toAbsolutePath().normalize();
            Path p = root.resolve(file).normalize();
            return p.startsWith(root) && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS);
        }

        private void put(Finding.Confirmed c, List<FixAttempt> attempts) {
            Finding.SecPipeExt e = c.secpipe();
            Finding.SecPipeExt ext = new Finding.SecPipeExt(e.category(), e.origin(), e.toolSources(), e.cwe(),
                    e.cve(), e.owasp(), e.dependency(), e.toolSeverityHint(), e.confidenceHint(), e.testEvidence(),
                    List.copyOf(attempts), Optional.of(FixClassifier.remediationStatus(attempts)),
                    e.recommendedRemediation(), e.verification(), e.ledgerUnits(), e.firstSeenRun(),
                    e.carriedForwardFrom());
            out.put(c.fingerprint(), new Finding.Confirmed(c.fingerprint(), c.title(), c.description(),
                    c.rootCause(), c.intendedBehavior(), c.trace(), c.evidence(), c.conditions(), c.execution(),
                    c.remediation(), c.severity(), c.confidence(), ext));
            attemptsByFp.put(c.fingerprint(), (int) attempts.stream()
                    .filter(a -> a.outcome() != FixAttempt.Outcome.SANDBOX_UNAVAILABLE).count());
        }

        private void note(String msg) {
            svc.log().warn("remediate: " + msg);
            notes.add(msg);
        }

        StateDelta delta() {
            if (out.isEmpty() && notes.isEmpty()) {
                return StateDelta.empty();
            }
            StateDelta e = StateDelta.empty();
            return new StateDelta(e.environment(), e.profile(), e.diffScope(), e.newLedgerUnits(), e.ledgerUpdates(),
                    e.replaceCandidates(), e.addCandidates(), List.copyOf(out.values()), e.putSurvivorDrafts(),
                    e.toolRuns(), e.timingsMs(), e.hunterWave(), e.criticPasses(), e.criticSaysStop(),
                    Map.copyOf(attemptsByFp), e.prior(), List.copyOf(notes), e.incompleteReason());
        }
    }

    /** What one attempt observed: classification, failing step, log for the next prompt, rescanned candidates. */
    private record Attempt(FixClassifier.Classification classification, String failingStep, String log,
                           Map<String, Candidate> rescanned) {
    }

    /** {@code <family>-<first 12 hash chars>} for code fingerprints, else a file-name-safe form. */
    static String shortId(String fingerprint) {
        String[] parts = fingerprint.split(":");
        if (parts.length == 3 && parts[0].equals("code")) {
            return parts[1] + "-" + parts[2].substring(0, Math.min(12, parts[2].length()));
        }
        String safe = fingerprint.replaceAll("[^A-Za-z0-9.-]", "-");
        return safe.length() > 60 ? safe.substring(0, 60) : safe;
    }

    /** {@code src/test/java/a/b/C.java} → {@code a.b.C}. */
    static String fqcn(String testPath) {
        String rel = testPath.substring("src/test/java/".length(), testPath.length() - ".java".length());
        return rel.replace('/', '.');
    }

    private static String describe(SuiteBaseline.TestRun run) {
        return switch (run) {
            case SuiteBaseline.Completed done -> "exit " + done.exitCode() + "; " + done.sorted().entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue().name().toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(", "));
            case SuiteBaseline.BuildFailed bf -> bf.detail() + "\n" + bf.logTail();
            case SuiteBaseline.NotRun nr -> nr.reason().name().toLowerCase(Locale.ROOT) + ": "
                    + nr.detail();
        };
    }

    private static String cap(String s) {
        String t = s == null ? "" : s;
        return t.length() > MAX_LOG_CHARS ? t.substring(t.length() - MAX_LOG_CHARS) : t;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String names(Set<Control> controls) {
        return controls.stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
    }

    /** Read-only index over the text files of the pristine workspace (context slices and grounding). */
    static SourceIndex index(Path root) {
        Map<String, Path> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(x -> Files.isRegularFile(x, LinkOption.NOFOLLOW_LINKS)).toList()) {
                Path rel = root.relativize(p);
                boolean skipped = false;
                for (Path seg : rel) {
                    if (SKIPPED_DIRS.contains(seg.toString())) {
                        skipped = true;
                        break;
                    }
                }
                if (!skipped && Files.size(p) <= MAX_INDEXED_FILE_BYTES) {
                    files.put(rel.toString().replace('\\', '/'), p);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot index " + root, e);
        }
        Map<String, Optional<List<String>>> cache = new LinkedHashMap<>();
        return new SourceIndex() {
            @Override
            public List<String> files() {
                return List.copyOf(files.keySet());
            }

            @Override
            public boolean exists(String path) {
                return files.containsKey(SourceIndex.requireRelativePath(path));
            }

            @Override
            public synchronized Optional<List<String>> lines(String path) {
                String rel = SourceIndex.requireRelativePath(path);
                return cache.computeIfAbsent(rel, k -> {
                    Path p = files.get(k);
                    if (p == null) {
                        return Optional.empty();
                    }
                    try {
                        return Optional.of(List.copyOf(Files.readAllLines(p, StandardCharsets.UTF_8)));
                    } catch (IOException | UncheckedIOException e) {
                        return Optional.empty();
                    }
                });
            }

            @Override
            public Optional<String> line(String path, int oneBasedLine) {
                return lines(path).filter(l -> oneBasedLine >= 1 && oneBasedLine <= l.size())
                        .map(l -> l.get(oneBasedLine - 1));
            }
        };
    }
}
