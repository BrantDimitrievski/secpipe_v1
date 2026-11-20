package ca.carleton.secpipe.agents.remediation;

import ca.carleton.secpipe.agents.prompt.DefaultPromptRenderer;
import ca.carleton.secpipe.agents.remediation.RemediationAgent.PreviousAttempt;
import ca.carleton.secpipe.agents.remediation.RemediationAgent.RegressionTest;
import ca.carleton.secpipe.agents.runner.AgentOutcome;
import ca.carleton.secpipe.agents.runner.AgentRunner;
import ca.carleton.secpipe.agents.runner.GroundingContext;
import ca.carleton.secpipe.agents.testgen.TestGenAgent;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.RemediationResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.TestGenResult;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.Adequacy;
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
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Promotion;
import ca.carleton.secpipe.contracts.spi.SourceIndex;
import ca.carleton.secpipe.sandbox.ArtifactPromoter;
import ca.carleton.secpipe.sandbox.BuildExtensionPreflight;
import ca.carleton.secpipe.sandbox.MavenWorkspace;

import java.io.ByteArrayInputStream;
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
import java.util.SortedSet;
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
 *
 * <p>WO-M.3: a regression test that is <b>skipped</b> after the patch is recorded as {@code still_present} with
 * strategy {@link FixClassifier#FIX_UNVERIFIED} (plus a run note); it is not sent to the agent as a failure and the
 * record is not retried. A sandbox that refuses or dies ({@link SuiteBaseline.NotRun}) during the regression test or
 * the suite gives a {@code sandbox_unavailable} attempt and stops that record. Every non-confirmed code record
 * without a status gets {@code remediation_status=not_attempted} whenever the node is enabled.
 *
 * <p>WO-M.2: the regression run of step 2 also measures JaCoCo coverage ({@link AdequacyCheck#run}). For a
 * {@code fixed} attempt the changed production lines are checked: all executed → {@code adequacy.adequate=true};
 * otherwise one extra testgen call writes a follow-up test, which runs with the regression test on a fresh patched
 * copy (one more sandbox run). Still not adequate is recorded in {@code fix_attempts[].adequacy} and a run note; the
 * attempt stays {@code fixed}.
 */
public final class RemediationLoopNode implements PipelineNode {

    public static final String ID = "remediate";
    /** Directory of the patches inside the output directory. */
    public static final String PATCH_DIR = "patches";
    /** Size cap for a promoted adequacy follow-up test (WO-M.4). */
    static final long FOLLOW_UP_TEST_MAX_BYTES = 256L * 1024;
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
        List<Finding> notAttempted = new ArrayList<>();
        for (Finding f : state.records().values()) {
            if (f.secpipe().category() != Finding.Category.CODE) {
                continue;
            }
            if (f instanceof Finding.Confirmed c) {
                todo.put(c.fingerprint(), c);
            } else if (f.secpipe().remediationStatus().isEmpty()) {
                notAttempted.add(withStatus(f, Finding.RemediationStatus.NOT_ATTEMPTED));
            }
        }
        if (todo.isEmpty() && notAttempted.isEmpty()) {
            return StateDelta.empty();
        }
        Run run = new Run(state, settings, services);
        notAttempted.forEach(f -> run.out.put(f.fingerprint(), f));
        if (!todo.isEmpty()) {
            run.run(todo);
        }
        return run.delta();
    }

    /** {@code f} with {@code remediation_status} set (non-confirmed records: never remediated). */
    static Finding withStatus(Finding f, Finding.RemediationStatus status) {
        Finding.SecPipeExt e = f.secpipe();
        Finding.SecPipeExt ext = new Finding.SecPipeExt(e.category(), e.origin(), e.toolSources(), e.cwe(),
                e.cve(), e.owasp(), e.dependency(), e.toolSeverityHint(), e.confidenceHint(), e.testEvidence(),
                e.fixAttempts(), Optional.of(status), e.recommendedRemediation(), e.verification(), e.ledgerUnits(),
                e.firstSeenRun(), e.carriedForwardFrom());
        return switch (f) {
            case Finding.Confirmed c -> new Finding.Confirmed(c.fingerprint(), c.title(), c.description(),
                    c.rootCause(), c.intendedBehavior(), c.trace(), c.evidence(), c.conditions(), c.execution(),
                    c.remediation(), c.severity(), c.confidence(), ext);
            case Finding.NeedsValidation n -> new Finding.NeedsValidation(n.fingerprint(), n.title(),
                    n.description(), n.claimedRootCause(), n.trace(), n.evidence(), n.blockers(),
                    n.validationPlan(), ext);
            case Finding.Rejected r -> new Finding.Rejected(r.fingerprint(), r.title(), r.description(),
                    r.claimedRootCause(), r.trace(), r.evidence(), r.reason(), ext);
        };
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
        private AgentRunner agentRunner;
        private AgentRunner.DiscardListener discardListener = AgentRunner.DiscardListener.NONE;

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
            agentRunner = runner;
            discardListener = listener;
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
                if (a.unverified()) {
                    attempts.add(FixClassifier.fixUnverified(n, Optional.of(patchArtifact),
                            Optional.of(fix.strategy()), a.classification()));
                    note("remediation of " + fp + " attempt " + n + ": " + FixClassifier.FIX_UNVERIFIED
                            + " (not retried)");
                    break;
                }
                FixAttempt entry = FixClassifier.toFixAttempt(n, Optional.of(patchArtifact),
                        Optional.of(fix.strategy()), a.classification());
                if (a.classification().outcome() == FixAttempt.Outcome.FIXED) {
                    entry = withAdequacy(entry, adequacy(record, fix.patch(), test, fqcn, a, n, dir));
                }
                attempts.add(entry);
                svc.log().info("remediate: " + fp + " attempt " + n + ": "
                        + a.classification().outcome().name().toLowerCase(Locale.ROOT));
                if (a.classification().outcome() == FixAttempt.Outcome.SANDBOX_UNAVAILABLE) {
                    note("remediation of " + fp + " stopped at attempt " + n + ": " + a.failingStep());
                    break;
                }
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
                return Optional.of(Attempt.of(FixClassifier.classify(obs.withPatchApplied(false)),
                        "refused: " + String.join("; ", problems), String.join("\n", problems), Map.of()));
            }
            List<String> touched = UnifiedDiff.apply(regressionCopy, patch);
            obs = obs.withPatchApplied(true);
            writeTest(regressionCopy, test);
            if (!svc.budget().tryAcquireSandboxRun()) {
                sandboxBudgetOut = true;
                return Optional.empty();
            }
            AdequacyCheck.CoverageRun covered = AdequacyCheck.run(runner, ws, regressionCopy, List.of(fqcn),
                    AdequacyCheck.includes(AdequacyCheck.changedLines(patch)),
                    "regression test " + fqcn + " (fix attempt " + n + ")");
            SuiteBaseline.TestRun reg = covered.tests();
            if (reg instanceof SuiteBaseline.BuildFailed bf) {
                return Optional.of(Attempt.of(FixClassifier.classify(obs.withCompiled(false)),
                        "build broken: " + bf.detail(), bf.logTail(), Map.of()));
            }
            obs = obs.withCompiled(true);
            FixClassifier.RegressionResult rr = FixClassifier.regressionResult(reg);
            obs = obs.withRegressionTest(rr);
            if (rr == FixClassifier.RegressionResult.SANDBOX_UNAVAILABLE) {
                return Optional.of(Attempt.of(FixClassifier.classify(obs.withSandboxLost()),
                        "sandbox unavailable during the regression test: " + describe(reg), describe(reg), Map.of()));
            }
            if (rr == FixClassifier.RegressionResult.SKIPPED) {
                return Optional.of(new Attempt(FixClassifier.classify(obs), FixClassifier.FIX_UNVERIFIED,
                        describe(reg), Map.of(), true, Optional.empty()));
            }
            if (rr != FixClassifier.RegressionResult.PASSED) {
                return Optional.of(Attempt.of(FixClassifier.classify(obs), "regression test did not pass ("
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
            if (suite instanceof SuiteBaseline.NotRun) {
                return Optional.of(Attempt.of(FixClassifier.classify(obs.withSandboxLost()),
                        "sandbox unavailable during the project tests: " + describe(suite), describe(suite),
                        Map.of()));
            }
            boolean suitePassed = suite instanceof SuiteBaseline.Completed done && baseline.suitePassed(done);
            obs = obs.withSuitePassed(suitePassed);
            if (!suitePassed) {
                String broken = suite instanceof SuiteBaseline.Completed done
                        ? "baseline tests no longer pass: " + String.join(", ", baseline.brokenTests(done))
                        : "project tests did not run: " + describe(suite);
                return Optional.of(Attempt.of(FixClassifier.classify(obs), "tests broken: " + broken,
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
                return Optional.of(Attempt.of(FixClassifier.classify(obs), "rescan failed: " + e.getMessage(),
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
            return Optional.of(new Attempt(c, step, step, rescanned, false, covered.coverage()));
        }

        /**
         * WO-M.2: adequacy of a {@code fixed} attempt. Measured from the coverage of the regression run; when changed
         * lines stay uncovered, one extra testgen call (LLM budget) writes a follow-up test, which runs next to the
         * regression test on a fresh patched copy with coverage (one sandbox run, {@code --max-sandbox-runs}). Both
         * tests must pass there; the suite and the rescan (steps 3-4) are not repeated because only test code was
         * added. Never changes the attempt's outcome: a limitation goes into the note.
         */
        private Adequacy adequacy(Finding.Confirmed record, String patch, RegressionTest test, String fqcn,
                                  Attempt a, int n, Path dir) throws InterruptedException {
            String fp = record.fingerprint();
            Map<String, SortedSet<Integer>> changed = AdequacyCheck.changedLines(patch);
            AdequacyCheck.Measurement first = AdequacyCheck.evaluate(changed, a.coverage());
            if (first.adequacy().adequate() || a.coverage().isEmpty() || first.uncovered().isEmpty()) {
                return logged(fp, n, first).adequacy();
            }
            String limit = "; one follow-up test was not run: ";
            if (svc.budget().exhausted() || agentRunner == null) {
                return logged(fp, n, first.withNote(first.adequacy().note().orElse("") + limit
                        + "agent-call budget exhausted (--max-agent-calls)")).adequacy();
            }
            Path regressionCopy = dir.resolve("attempt" + n + "-regression");
            SourceIndex patched = index(regressionCopy);
            AgentOutcome<TestGenResult> gen = new TestGenAgent().generate(agentRunner,
                    AdequacyCheck.followUpInput(record, first, test, patched),
                    GroundingContext.of(patched, Set.of(fp)), discardListener);
            if (!(gen instanceof AgentOutcome.Ok<TestGenResult> ok)) {
                String why = gen instanceof AgentOutcome.Unavailable<TestGenResult> u ? u.reason()
                        : ((AgentOutcome.Dropped<TestGenResult>) gen).reason();
                return logged(fp, n, first.withNote(first.adequacy().note().orElse("") + limit
                        + "testgen: " + why)).adequacy();
            }
            TestGenResult t = ok.value();
            String followFqcn = t.packageName().isBlank() ? t.className() : t.packageName() + "." + t.className();
            String followPath = "src/test/java/" + followFqcn.replace('.', '/') + ".java";
            if (followPath.equals(test.path())) {
                return logged(fp, n, first.withNote(first.adequacy().note().orElse("") + limit
                        + "the follow-up test reused the regression test's class name")).adequacy();
            }
            if (!svc.budget().tryAcquireSandboxRun()) {
                sandboxBudgetOut = true;
                return logged(fp, n, first.withNote(first.adequacy().note().orElse("") + limit
                        + SANDBOX_BUDGET + " (--max-sandbox-runs)")).adequacy();
            }
            Path copy = dir.resolve("attempt" + n + "-adequacy");
            SuiteBaseline.freshCopy(pristine, copy);
            UnifiedDiff.apply(copy, patch);
            writeTest(copy, test);
            writeTest(copy, new RegressionTest(followPath, t.source()));
            AdequacyCheck.CoverageRun rerun = AdequacyCheck.run(svc.sandbox().orElseThrow(), ws, copy,
                    List.of(fqcn, followFqcn), AdequacyCheck.includes(changed),
                    "regression test " + fqcn + " with follow-up " + followFqcn
                            + " (adequacy, fix attempt " + n + ")");
            boolean allPassed = rerun.tests() instanceof SuiteBaseline.Completed done && !done.tests().isEmpty()
                    && done.tests().values().stream().allMatch(s -> s == SuiteBaseline.TestStatus.PASSED);
            if (!allPassed) {
                return logged(fp, n, first.withNote(first.adequacy().note().orElse("")
                        + "; the follow-up test " + followFqcn + " did not pass on the patched code: "
                        + cap(describe(rerun.tests())).lines().findFirst().orElse(""))).adequacy();
            }
            promoteFollowUp(fp, n, t.source());
            AdequacyCheck.Measurement second = AdequacyCheck.evaluate(changed, rerun.coverage());
            if (!second.adequacy().adequate()) {
                second = second.withNote(second.adequacy().note().orElse("not adequate")
                        + "; still not adequate after one follow-up test (" + followFqcn + ")");
            } else {
                second = second.withNote("adequate with follow-up test " + followFqcn);
            }
            return logged(fp, n, second).adequacy();
        }

        /**
         * WO-M.4: the follow-up test that ran and passed is written next to the attempt's patch through
         * {@link ArtifactPromoter} (declared path, size cap) and noted, so the adequacy result can be reproduced from
         * the run output. A rejection is noted, never fatal.
         */
        private void promoteFollowUp(String fp, int n, String source) {
            notes.add(promoteFollowUpTest(settings.outDir(), fp, n, source));
        }

        private AdequacyCheck.Measurement logged(String fp, int n, AdequacyCheck.Measurement m) {
            Adequacy q = m.adequacy();
            svc.log().info("remediate: " + fp + " attempt " + n + " adequacy: adequate=" + q.adequate() + ", "
                    + q.changedLinesCovered() + "/" + q.changedLinesTotal() + " changed lines covered"
                    + q.note().map(x -> " (" + x + ")").orElse(""));
            if (!q.adequate()) {
                notes.add("adequacy limited for " + fp + " attempt " + n + ": " + q.note().orElse("not adequate"));
            }
            return m;
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
                    List.of(), Optional.of(Finding.RemediationStatus.NOT_ATTEMPTED), Optional.empty(),
                    Optional.empty(), List.of(), Optional.empty(), Optional.empty());
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
                           Map<String, Candidate> rescanned, boolean unverified,
                           Optional<JacocoXml.Coverage> coverage) {
        static Attempt of(FixClassifier.Classification classification, String failingStep, String log,
                          Map<String, Candidate> rescanned) {
            return new Attempt(classification, failingStep, log, rescanned, false, Optional.empty());
        }
    }

    /** {@code a} with {@code adequacy} set (WO-M.2). */
    static FixAttempt withAdequacy(FixAttempt a, Adequacy adequacy) {
        return new FixAttempt(a.attempt(), a.patchArtifact(), a.strategy(), a.outcome(), a.regressionTestPassed(),
                a.suitePassed(), a.rescanClean(), a.newFingerprints(), Optional.of(adequacy));
    }

    /** {@code <family>-<first 12 hash chars>} for code fingerprints, else a file-name-safe form. */
    /**
     * Promotes {@code source} to {@code <outDir>/patches/<fp>-attempt<n>-adequacy-test.java} and returns the run note:
     * the success note, or a "not promoted" note naming the reason (rejection, I/O error, or a path that does not fit
     * the 100-byte tar name). Never throws.
     */
    static String promoteFollowUpTest(Path outDir, String fp, int n, String source) {
        String rel = PATCH_DIR + "/" + shortId(fp) + "-attempt" + n + "-adequacy-test.java";
        String prefix = "adequacy follow-up test for " + fp + " attempt " + n;
        try {
            byte[] tar = AdequacyCheck.singleFileTar(rel, source.getBytes(StandardCharsets.UTF_8));
            ArtifactPromoter.Report report = ArtifactPromoter.promote(new ByteArrayInputStream(tar),
                    List.of(new Promotion(rel, FOLLOW_UP_TEST_MAX_BYTES)), outDir);
            return report.rejected().isEmpty() ? prefix + ": " + rel
                    : prefix + " not promoted: " + report.rejected().get(0).reason();
        } catch (IOException | RuntimeException e) {
            return prefix + " not promoted: " + e.getMessage();
        }
    }

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
