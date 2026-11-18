package ca.carleton.secpipe.agents.evidence;

import ca.carleton.secpipe.agents.prompt.DefaultPromptRenderer;
import ca.carleton.secpipe.agents.runner.AgentOutcome;
import ca.carleton.secpipe.agents.runner.AgentRunner;
import ca.carleton.secpipe.agents.runner.GroundingContext;
import ca.carleton.secpipe.agents.testgen.TestGenAgent;
import ca.carleton.secpipe.agents.testgen.TestGenContextBuilder;
import ca.carleton.secpipe.agents.testgen.TestGenInput;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.DisproverResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.TestGenResult;
import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.TestEvidence;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Control;
import ca.carleton.secpipe.contracts.spi.SourceIndex;
import ca.carleton.secpipe.sandbox.BuildExtensionPreflight;
import ca.carleton.secpipe.sandbox.MavenWorkspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pipeline node {@code test_evidence} (WO-G.7). Runs after {@code disprove}; {@code requiresLlm()} is false so it also
 * runs with {@code llm-provider=none}.
 *
 * <ul>
 *   <li>For every survivor draft whose record is absent or {@code needs_validation} (the disprover writes
 *   "awaiting regression test evidence"), it generates a JUnit 5 regression test with {@link TestGenAgent} and runs it
 *   offline in the sandbox (all 13 controls). A failing assertion whose message contains the generated test's
 *   {@code expected_failure} makes the record {@code confirmed} with
 *   {@code observed_result}; everything else replaces it with a {@code needs_validation} record naming the exact
 *   blocker (sandbox unavailable, refused controls, prefetch incomplete, build extensions, test passed, unexpected
 *   failure, setup assumption failed, compile error twice, timeout, resource limit, test error, no test generated,
 *   sandbox-run budget).</li>
 *   <li>Every fingerprinted code candidate with neither a record nor a draft gets a {@code needs_validation} record
 *   with blocker {@code not verified: llm-provider=<mode>}, so unrefuted candidates are reported, never dropped.</li>
 * </ul>
 * A compile error is treated as invalid generator output: the test is generated once more by a fresh agent.
 */
public final class TestEvidenceNode implements PipelineNode {

    public static final String ID = "test_evidence";

    static final String SANDBOX_UNAVAILABLE = "sandbox unavailable";
    static final String NOT_VERIFIED = "not verified: llm-provider=";
    static final String TEST_PASSED = "regression test passed on the unpatched code; the vulnerability was not "
            + "reproduced";
    static final String COMPILE_TWICE = "regression test did not compile after 2 generations";
    static final String TIMEOUT = "sandbox wall-clock limit reached before the regression test finished; the result "
            + "is unknown";
    static final String RESOURCE_LIMIT = "resource limit: the sandbox killed the regression test (exit 137); the "
            + "result is unknown";
    static final String REFUSED = "sandbox refused: required controls not enforceable: ";
    static final String BUDGET = "sandbox run budget exhausted (--max-sandbox-runs=";
    static final String NO_ENTRYPOINT = "regression test failed as expected, but no trace from an entrypoint to the "
            + "sink is available to confirm it";

    static final String UNEXPECTED_FAILURE = "unexpected failure: ";
    static final String SETUP_ASSUMPTION = "setup assumption failed: ";

    /** Max generations per survivor: the first plus one regeneration after a compile error. */
    static final int MAX_GENERATIONS = 2;

    /** What the node needs from config and context (tests pass it directly; config may be absent in tests). */
    record Settings(ProviderMode mode, boolean sandboxEnabled, int maxSandboxRuns, boolean allowBuildExtensions,
                    Path target, Path workDir, Path outDir) {
        Settings {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(workDir, "workDir");
            Objects.requireNonNull(outDir, "outDir");
        }

        static Settings of(PipelineState state, RunContext ctx) {
            SecPipeConfig cfg = state.config();
            ProviderMode mode = ctx.llm().mode() == ProviderMode.NONE || cfg == null
                    ? ctx.llm().mode() : cfg.llmProvider();
            Path target = state.profile().map(p -> p.root()).orElseGet(() -> cfg == null ? Path.of(".") : cfg.target());
            return new Settings(mode, cfg == null || cfg.sandboxEnabled(),
                    cfg == null ? SecPipeConfig.DEFAULT_MAX_SANDBOX_RUNS : cfg.maxSandboxRuns(),
                    cfg != null && cfg.allowBuildExtensions(), target, ctx.workDir(), ctx.outDir());
        }
    }

    public TestEvidenceNode() {
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
        return true;
    }

    @Override
    public Result apply(PipelineState state, RunContext ctx) throws InterruptedException {
        return Result.next(evaluate(state, Settings.of(state, ctx), ctx.llm(), ctx.sandbox(), ctx.log()));
    }

    /** The node's work with its collaborators passed in (used by {@link #apply} and by tests). */
    static StateDelta evaluate(PipelineState state, Settings settings, LlmClient llm, Optional<SandboxRunner> sandbox,
                               RunContext.RunLog log) throws InterruptedException {
        Map<String, Candidate> byFp = new HashMap<>();
        for (Candidate c : state.candidates()) {
            c.fingerprint().ifPresent(fp -> byFp.putIfAbsent(fp, c));
        }
        Map<String, Finding> out = new TreeMap<>();

        Map<String, DisproverResult> todo = new TreeMap<>();
        for (Map.Entry<String, DisproverResult> e : state.survivorDrafts().entrySet()) {
            Finding prior = state.records().get(e.getKey());
            if (prior != null && prior.verdict() != Finding.Verdict.NEEDS_VALIDATION) {
                continue;
            }
            Candidate c = byFp.get(e.getKey());
            if (c == null || !EvidenceRecords.recordable(c)) {
                log.warn("test_evidence: no recordable candidate for survivor " + e.getKey() + "; skipped");
                continue;
            }
            todo.put(e.getKey(), e.getValue());
        }
        if (!todo.isEmpty()) {
            new Evaluation(state, settings, llm, sandbox, log, byFp, out).run(todo);
        }

        String notVerified = NOT_VERIFIED + settings.mode().cliName();
        for (Candidate c : state.candidates()) {
            if (c.category() != Finding.Category.CODE || c.fingerprint().isEmpty()) {
                continue;
            }
            String fp = c.fingerprint().get();
            if (state.records().containsKey(fp) || state.survivorDrafts().containsKey(fp) || out.containsKey(fp)) {
                continue;
            }
            if (!EvidenceRecords.recordable(c)) {
                log.warn("test_evidence: candidate " + fp + " has no trace ending at a sink; not recorded");
                continue;
            }
            out.put(fp, EvidenceRecords.needsValidation(c, Optional.empty(), notVerified, List.of()));
        }
        if (out.isEmpty()) {
            return StateDelta.empty();
        }
        StateDelta e = StateDelta.empty();
        return new StateDelta(e.environment(), e.profile(), e.diffScope(), e.newLedgerUnits(), e.ledgerUpdates(),
                e.replaceCandidates(), e.addCandidates(), List.copyOf(out.values()), e.putSurvivorDrafts(),
                e.toolRuns(), e.timingsMs(), e.hunterWave(), e.criticPasses(), e.criticSaysStop(),
                e.fixAttemptsByFingerprint(), e.prior(), e.notes(), e.incompleteReason());
    }

    /** One node invocation over the survivor drafts. */
    private static final class Evaluation {
        private final PipelineState state;
        private final Settings settings;
        private final LlmClient llm;
        private final Optional<SandboxRunner> sandbox;
        private final RunContext.RunLog log;
        private final Map<String, Candidate> byFp;
        private final Map<String, Finding> out;
        private int sandboxRuns;

        Evaluation(PipelineState state, Settings settings, LlmClient llm, Optional<SandboxRunner> sandbox,
                   RunContext.RunLog log, Map<String, Candidate> byFp, Map<String, Finding> out) {
            this.state = state;
            this.settings = settings;
            this.llm = llm;
            this.sandbox = sandbox;
            this.log = log;
            this.byFp = byFp;
            this.out = out;
        }

        void run(Map<String, DisproverResult> todo) throws InterruptedException {
            Optional<String> blocker = sandboxBlocker();
            MavenWorkspace ws = null;
            Path pristine = null;
            if (blocker.isEmpty()) {
                ws = new MavenWorkspace(sandbox.orElseThrow(), settings.workDir().resolve("evidence-workspace"),
                        MavenWorkspace.Options.defaults().withAllowBuildExtensions(settings.allowBuildExtensions()));
                try {
                    switch (ws.prepare(settings.target())) {
                        case MavenWorkspace.Prepared p -> pristine = p.scratchCopy();
                        case MavenWorkspace.PrefetchIncomplete pi -> blocker = Optional.of(pi.blocker() + ": "
                                + pi.reason() + (pi.missing().isEmpty() ? "" : " (missing: "
                                + String.join(", ", pi.missing()) + ")"));
                        case MavenWorkspace.ExtensionsRefused er -> blocker = Optional.of(
                                BuildExtensionPreflight.BLOCKER + ": " + er.message());
                    }
                } catch (SandboxRunner.SandboxRefusedException e) {
                    blocker = Optional.of(REFUSED + RegressionTestRun.names(e.missing()));
                } catch (IllegalArgumentException e) {
                    blocker = Optional.of("not a Maven project: regression tests need a Maven build (pom.xml)");
                } catch (UncheckedIOException e) {
                    blocker = Optional.of("sandbox workspace could not be prepared: " + e.getMessage());
                }
            }
            if (blocker.isPresent()) {
                log.warn("test_evidence: regression tests not run: " + blocker.get());
                for (String fp : todo.keySet()) {
                    out.put(fp, notRun(fp, blocker.get()));
                }
                return;
            }
            SourceIndex sources = EvidenceSourceIndex.scan(settings.target());
            TestGenContextBuilder builder = new TestGenContextBuilder(sources);
            AgentRunner.DiscardListener listener = (role, agentId, attempt, reason) ->
                    log.warn("discarded " + agentId + " output (attempt " + attempt + "): " + reason);
            AgentRunner runner = new AgentRunner(llm, new DefaultPromptRenderer(), listener);
            for (Map.Entry<String, DisproverResult> e : todo.entrySet()) {
                out.put(e.getKey(), evidence(e.getKey(), e.getValue(), ws, pristine, sources, builder, runner,
                        listener));
            }
        }

        private Optional<String> sandboxBlocker() {
            if (!settings.sandboxEnabled() || sandbox.isEmpty()) {
                return Optional.of(SANDBOX_UNAVAILABLE);
            }
            SandboxRunner.Capabilities caps = sandbox.get().probe();
            if (!caps.available()) {
                return Optional.of(SANDBOX_UNAVAILABLE + caps.reason().map(r -> ": " + r).orElse(""));
            }
            Set<Control> missing = EnumSet.allOf(Control.class);
            missing.removeAll(caps.enforceable());
            return missing.isEmpty() ? Optional.empty() : Optional.of(REFUSED + RegressionTestRun.names(missing));
        }

        private Finding notRun(String fp, String blocker) {
            TestEvidence te = new TestEvidence(TestEvidenceId.of(fp, TestEvidence.Phase.PRE_FIX, 1),
                    TestEvidence.Phase.PRE_FIX, Optional.empty(), Optional.empty(), TestEvidence.Outcome.NOT_RUN, 0,
                    Optional.empty(), Optional.of(blocker), Optional.empty());
            return nv(fp, blocker, List.of(te));
        }

        private Finding nv(String fp, String blocker, List<TestEvidence> evidence) {
            return EvidenceRecords.needsValidation(byFp.get(fp), Optional.ofNullable(state.records().get(fp)),
                    blocker, evidence);
        }

        private Finding evidence(String fp, DisproverResult draft, MavenWorkspace ws, Path pristine,
                                 SourceIndex sources, TestGenContextBuilder builder, AgentRunner runner,
                                 AgentRunner.DiscardListener listener) throws InterruptedException {
            Candidate c = byFp.get(fp);
            TestGenInput input = builder.build(family(fp, c), draft, List.of(
                    "JUnit 5 (org.junit.jupiter)", "the target project's own compile, provided and test dependencies"));
            GroundingContext grounding = GroundingContext.of(sources, Set.of(fp));
            List<TestEvidence> evidence = new ArrayList<>();
            for (int attempt = 1; attempt <= MAX_GENERATIONS; attempt++) {
                if (sandboxRuns >= settings.maxSandboxRuns()) {
                    return nv(fp, BUDGET + settings.maxSandboxRuns() + ")", evidence);
                }
                AgentOutcome<TestGenResult> gen = new TestGenAgent().generate(runner, input, grounding, listener);
                TestGenResult test;
                String agentId;
                switch (gen) {
                    case AgentOutcome.Unavailable<TestGenResult> u -> {
                        return nv(fp, "regression test not generated: " + u.reason(), evidence);
                    }
                    case AgentOutcome.Dropped<TestGenResult> d -> {
                        return nv(fp, d.reason(), evidence);
                    }
                    case AgentOutcome.Ok<TestGenResult> ok -> {
                        test = ok.value();
                        agentId = ok.agentId();
                    }
                }
                String id = TestEvidenceId.of(fp, TestEvidence.Phase.PRE_FIX, attempt);
                Path scratch = settings.workDir().resolve("evidence").resolve(id);
                sandboxRuns++;
                RegressionTestRun.Observation obs = RegressionTestRun.run(sandbox.orElseThrow(), ws, pristine, scratch,
                        test);
                log.info("test_evidence: " + fp + " attempt " + attempt + ": " + obs.kind() + " (" + obs.detail()
                        + ")");
                Optional<String> artifact = obs.report().flatMap(r -> promoteReport(r, id));
                TestEvidence.Outcome outcome = outcome(obs.kind());
                Optional<String> observed = obs.kind() == RegressionTestRun.Kind.FAILED_AS_EXPECTED
                        ? Optional.of(id + ": " + RegressionTestRun.fqcn(test) + " failed as expected on the "
                        + "unpatched code: " + obs.detail()) : Optional.empty();
                String blocker = switch (obs.kind()) {
                    case FAILED_AS_EXPECTED -> NO_ENTRYPOINT;
                    case UNEXPECTED_FAILURE -> UNEXPECTED_FAILURE + obs.detail();
                    case SKIPPED -> SETUP_ASSUMPTION + obs.detail();
                    case PASSED -> TEST_PASSED;
                    case COMPILE_ERROR -> COMPILE_TWICE;
                    case TIMEOUT -> TIMEOUT;
                    case RESOURCE_LIMIT -> RESOURCE_LIMIT;
                    case REFUSED -> REFUSED + RegressionTestRun.names(obs.missing());
                    case ERROR -> "regression test errored without a failing assertion: " + obs.detail();
                };
                TestEvidence te = new TestEvidence(id, TestEvidence.Phase.PRE_FIX,
                        Optional.of(RegressionTestRun.testFile(test)), Optional.of(RegressionTestRun.fqcn(test)),
                        outcome, obs.kind() == RegressionTestRun.Kind.REFUSED ? 0 : 1, observed,
                        observed.isPresent() ? Optional.empty() : Optional.of(blocker), artifact);
                evidence.add(te);
                switch (obs.kind()) {
                    case FAILED_AS_EXPECTED -> {
                        Optional<Finding.Confirmed> confirmed = EvidenceRecords.confirmed(c, draft,
                                Optional.ofNullable(state.records().get(fp)), test, te, evidence);
                        return confirmed.<Finding>map(f -> f).orElseGet(() -> nv(fp, NO_ENTRYPOINT, evidence));
                    }
                    case COMPILE_ERROR -> {
                        if (attempt < MAX_GENERATIONS) {
                            listener.discarded(AgentRole.TESTGEN, agentId, attempt, "generated test did not compile "
                                    + "in the sandbox: " + obs.detail());
                            continue;
                        }
                        return nv(fp, COMPILE_TWICE, evidence);
                    }
                    default -> {
                        return nv(fp, blocker, evidence);
                    }
                }
            }
            return nv(fp, COMPILE_TWICE, evidence);
        }

        /** Copies the already promoted JUnit report from the host scratch copy to {@code <out>/evidence/<id>.xml}. */
        private Optional<String> promoteReport(Path report, String id) {
            try {
                Path dir = settings.outDir().resolve("evidence");
                Files.createDirectories(dir);
                Files.copy(report, dir.resolve(id + ".xml"), StandardCopyOption.REPLACE_EXISTING,
                        LinkOption.NOFOLLOW_LINKS);
                return Optional.of("evidence/" + id + ".xml");
            } catch (IOException e) {
                log.warn("test_evidence: could not keep the JUnit report " + id + ": " + e.getMessage());
                return Optional.empty();
            }
        }
    }

    static TestEvidence.Outcome outcome(RegressionTestRun.Kind kind) {
        return switch (kind) {
            case FAILED_AS_EXPECTED -> TestEvidence.Outcome.FAILED_AS_EXPECTED;
            case UNEXPECTED_FAILURE -> TestEvidence.Outcome.ERROR;
            case SKIPPED -> TestEvidence.Outcome.NOT_RUN;
            case PASSED -> TestEvidence.Outcome.PASSED;
            case COMPILE_ERROR -> TestEvidence.Outcome.COMPILE_ERROR;
            case TIMEOUT -> TestEvidence.Outcome.TIMEOUT;
            case RESOURCE_LIMIT -> TestEvidence.Outcome.ERROR;
            case REFUSED -> TestEvidence.Outcome.NOT_RUN;
            case ERROR -> TestEvidence.Outcome.ERROR;
        };
    }

    private static String family(String fp, Candidate c) {
        String[] parts = fp.split(":");
        if (parts.length >= 2 && parts[1].startsWith("CWE-")) {
            return parts[1];
        }
        return c.cwe().isEmpty() ? "CWE-20" : c.cwe().get(0);
    }
}
