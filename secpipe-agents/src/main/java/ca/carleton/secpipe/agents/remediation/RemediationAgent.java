package ca.carleton.secpipe.agents.remediation;

import ca.carleton.secpipe.agents.prompt.DefaultPromptRenderer;
import ca.carleton.secpipe.agents.runner.AgentOutcome;
import ca.carleton.secpipe.agents.runner.AgentRunner;
import ca.carleton.secpipe.agents.runner.GroundingContext;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.RemediationResult;
import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.spi.Agent;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.contracts.spi.SourceIndex;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Remediation agent (WO-G.10, PLAN Part 5 A10): proposes the smallest effective fix for one {@code confirmed}
 * record as a unified diff of at most 5 files. The agent writes nothing; the remediation loop (WO-M.1) applies the
 * diff to a sandbox scratch copy with {@link UnifiedDiff} after {@link PatchChecker} passes.
 *
 * <p>Use {@link #propose}: each attempt is a fresh agent run through {@link AgentRunner} (strict parse, schema,
 * grounding, at most {@link AgentRunner#MAX_ATTEMPTS} LLM calls). An otherwise valid result is still discarded, never
 * edited, when its diff fails {@link PatchChecker}, does not dry-run cleanly against the repository, lists other
 * files than it changes, or sets {@code dependency_change}; a fresh agent is then asked, within the same attempt
 * limit. Repo text (enclosing methods, the regression test, previous patches and logs) reaches the prompt only
 * through {@link Agent.PromptRenderer#untrusted}.
 */
public final class RemediationAgent implements Agent<RemediationAgent.Input, RemediationResult> {

    public static final String TEMPLATE_ID = "remediation.v1";
    public static final String SCHEMA_ID = "agent/remediation-result.schema.json";
    static final double TEMPERATURE = 0.2;
    static final int MAX_OUTPUT_TOKENS = 8000;

    /** The failing regression test the fix must make pass; {@code path} is repo-relative. */
    public record RegressionTest(String path, String source) {
        public RegressionTest {
            SourceIndex.requireRelativePath(path);
            Objects.requireNonNull(source, "source");
        }
    }

    /**
     * A previous failed attempt shown to the next fresh agent: its patch, the step that failed (for example
     * {@code refused: BUILD_FILE pom.xml}, {@code regression test still fails}, {@code build broken}) and the log.
     */
    public record PreviousAttempt(int attempt, String patch, String failingStep, String log) {
        public PreviousAttempt {
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be >= 1");
            }
            Objects.requireNonNull(patch, "patch");
            Objects.requireNonNull(failingStep, "failingStep");
            Objects.requireNonNull(log, "log");
        }
    }

    /** Lines {@code fromLine..toLine} (1-based, inclusive) of {@code path}, joined with {@code \n}. */
    public record Snippet(String path, int fromLine, int toLine, String content) {
        public Snippet {
            SourceIndex.requireRelativePath(path);
            Objects.requireNonNull(content, "content");
            if (fromLine < 1 || toLine < fromLine) {
                throw new IllegalArgumentException("bad line range " + fromLine + "-" + toLine + " for " + path);
            }
        }
    }

    /** Everything one remediation agent may see, built by {@link RemediationContextBuilder}. */
    public record Input(Finding.Confirmed record, RegressionTest regressionTest, List<Snippet> methods,
                        List<PreviousAttempt> previousAttempts) {
        public Input {
            Objects.requireNonNull(record, "record");
            Objects.requireNonNull(regressionTest, "regressionTest");
            methods = List.copyOf(methods);
            previousAttempts = List.copyOf(previousAttempts);
        }

        public String fingerprint() {
            return record.fingerprint();
        }
    }

    private final Optional<AgentRunner> runner;
    private final Optional<SourceIndex> sources;
    private final Optional<Path> repoRoot;
    private final AgentRunner.DiscardListener listener;

    /** A request-building agent only ({@link #buildRequest}); {@link #propose} needs the full constructor. */
    public RemediationAgent() {
        this.runner = Optional.empty();
        this.sources = Optional.empty();
        this.repoRoot = Optional.empty();
        this.listener = AgentRunner.DiscardListener.NONE;
    }

    /**
     * @param runner   the run's agent runner (fresh agent per attempt)
     * @param sources  source index of the target (context slices and grounding)
     * @param repoRoot pristine copy of the target, used read-only for the dry run
     * @param listener told about every discarded output, including checker rejections
     */
    public RemediationAgent(AgentRunner runner, SourceIndex sources, Path repoRoot,
                            AgentRunner.DiscardListener listener) {
        this.runner = Optional.of(Objects.requireNonNull(runner, "runner"));
        this.sources = Optional.of(Objects.requireNonNull(sources, "sources"));
        this.repoRoot = Optional.of(Objects.requireNonNull(repoRoot, "repoRoot"));
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public AgentRole role() {
        return AgentRole.REMEDIATION;
    }

    @Override
    public String outputSchemaId() {
        return SCHEMA_ID;
    }

    @Override
    public Class<RemediationResult> outputType() {
        return RemediationResult.class;
    }

    @Override
    public String promptTemplateId() {
        return TEMPLATE_ID;
    }

    /**
     * Proposes one checked fix. {@link AgentOutcome.Ok} carries a schema-valid, grounded result whose patch passed
     * {@link PatchChecker} and dry-runs cleanly against {@code repoRoot}. {@link AgentOutcome.Dropped} means no
     * acceptable patch (the loop records the attempt as {@code refused}, reason included);
     * {@link AgentOutcome.Unavailable} means AI is off, the budget is exhausted or the provider is down (no patch,
     * reason recorded).
     */
    public AgentOutcome<RemediationResult> propose(Finding.Confirmed record, RegressionTest regressionTest,
                                                   List<PreviousAttempt> previousAttempts) {
        AgentRunner r = runner.orElseThrow(() -> new IllegalStateException("RemediationAgent built without a runner"));
        SourceIndex index = sources.orElseThrow();
        Path root = repoRoot.orElseThrow();
        Input input = new RemediationContextBuilder(index).build(record, regressionTest, previousAttempts);
        GroundingContext grounding = GroundingContext.of(index, Set.of(record.fingerprint()));
        int attempts = 0;
        String lastReason = "";
        while (attempts < AgentRunner.MAX_ATTEMPTS) {
            AgentOutcome<RemediationResult> outcome = r.run(this, input, grounding);
            attempts += outcome.attempts();
            switch (outcome) {
                case AgentOutcome.Unavailable<RemediationResult> u -> {
                    return new AgentOutcome.Unavailable<>(u.reason(), attempts);
                }
                case AgentOutcome.Dropped<RemediationResult> d -> {
                    return new AgentOutcome.Dropped<>(dropReason(d.reason()), attempts);
                }
                case AgentOutcome.Ok<RemediationResult> ok -> {
                    List<String> problems = problems(ok.value(), root, regressionTest.path());
                    if (problems.isEmpty()) {
                        return new AgentOutcome.Ok<>(ok.value(), attempts, ok.agentId(), ok.model());
                    }
                    lastReason = "patch refused: " + String.join("; ", problems);
                    listener.discarded(role(), ok.agentId(), attempts, lastReason);
                }
            }
        }
        return new AgentOutcome.Dropped<>(dropReason(lastReason), attempts);
    }

    /** Every reason {@code result} may not be applied; empty means it passed every static check and the dry run. */
    static List<String> problems(RemediationResult result, Path root, String regressionTestPath) {
        List<String> out = new ArrayList<>();
        List<PatchChecker.Violation> violations = PatchChecker.check(result.patch(), root, regressionTestPath);
        violations.forEach(v -> out.add(v.toString()));
        if (result.dependencyChange().isPresent()) {
            out.add("DEPENDENCY_CHANGE: dependency upgrades are not allowed as code fixes");
        }
        if (!violations.isEmpty()) {
            return List.copyOf(out);
        }
        Set<String> changed = new TreeSet<>();
        UnifiedDiff.parse(result.patch()).forEach(fp -> changed.addAll(fp.paths()));
        if (!changed.equals(new TreeSet<>(result.filesChanged()))) {
            out.add("FILES_CHANGED: files_changed " + new TreeSet<>(result.filesChanged()) + " != diff paths "
                    + changed);
        }
        UnifiedDiff.DryRun dry = UnifiedDiff.dryRun(root, result.patch());
        dry.problems().forEach(p -> out.add("DRY_RUN: " + p));
        return List.copyOf(out);
    }

    @Override
    public LlmClient.Request buildRequest(Input input, PromptRenderer renderer) {
        Objects.requireNonNull(input, "input");
        String system = renderer.template(DefaultPromptRenderer.STANDING_RULES, Map.of()) + "\n"
                + renderer.template(DefaultPromptRenderer.OUTPUT_CONTRACT, Map.of("schema_id", SCHEMA_ID));
        RegressionTest test = input.regressionTest();
        StringBuilder methods = new StringBuilder();
        for (Snippet s : input.methods()) {
            methods.append(renderer.untrusted(s.path(), s.fromLine(), s.toLine(), s.content()));
        }
        Map<String, String> vars = new TreeMap<>();
        vars.put("fingerprint", input.fingerprint());
        vars.put("record", record(input.record()));
        vars.put("regression_test_path", test.path());
        vars.put("regression_test", renderer.untrusted(test.path(), 1, lineCount(test.source()), test.source()));
        vars.put("methods", methods.length() == 0 ? "(no slices available)\n" : methods.toString());
        vars.put("previous_attempts", previous(input.previousAttempts(), renderer));
        return new LlmClient.Request(system, renderer.template(TEMPLATE_ID, vars), SCHEMA_ID, MAX_OUTPUT_TOKENS,
                TEMPERATURE, TEMPLATE_ID);
    }

    /** Lower-case hex SHA-256 of the prompt template (for {@code ai.prompts_hash}). */
    public static String promptSha256() {
        return DefaultPromptRenderer.sha256(TEMPLATE_ID);
    }

    private static String previous(List<PreviousAttempt> attempts, PromptRenderer renderer) {
        if (attempts.isEmpty()) {
            return "(none: this is the first attempt)\n";
        }
        StringBuilder b = new StringBuilder();
        for (PreviousAttempt a : attempts) {
            // the failing step quotes build and test output, so it is untrusted text too (WO-M.3)
            b.append("Attempt ").append(a.attempt()).append(" failed at:\n");
            b.append(renderer.untrusted("secpipe-attempts/attempt-" + a.attempt() + ".step", 1,
                    lineCount(a.failingStep()), a.failingStep()));
            b.append("Its patch:\n");
            b.append(renderer.untrusted("secpipe-attempts/attempt-" + a.attempt() + ".diff", 1,
                    lineCount(a.patch()), a.patch()));
            b.append("Trimmed log:\n");
            b.append(renderer.untrusted("secpipe-attempts/attempt-" + a.attempt() + ".log", 1, lineCount(a.log()),
                    a.log()));
        }
        return b.toString();
    }

    /** The confirmed record: pipeline-written verdict fields and trace locations (no repo quotes). */
    private static String record(Finding.Confirmed r) {
        StringBuilder b = new StringBuilder("Confirmed record:\n");
        b.append("- Title: ").append(oneLine(r.title())).append('\n');
        b.append("- Weakness: ").append(String.join(", ", r.secpipe().cwe())).append('\n');
        b.append("- Root cause: ").append(oneLine(r.rootCause())).append('\n');
        b.append("- Intended behaviour: ").append(oneLine(r.intendedBehavior())).append('\n');
        b.append("- Suggested strategy: ").append(oneLine(r.remediation().strategy())).append('\n');
        b.append("- Trace:\n");
        for (Finding.TraceStep s : r.trace()) {
            b.append("  - ").append(s.kind().name().toLowerCase(Locale.ROOT)).append(' ').append(s.file())
                    .append(':').append(s.line()).append(" in ").append(oneLine(s.scope())).append(": ")
                    .append(oneLine(s.description())).append('\n');
        }
        Set<String> evidence = new LinkedHashSet<>();
        for (Finding.Evidence e : r.evidence()) {
            evidence.add(e.file() + ":" + e.line() + ": " + oneLine(e.description()));
        }
        if (!evidence.isEmpty()) {
            b.append("- Evidence:\n");
            evidence.forEach(e -> b.append("  - ").append(e).append('\n'));
        }
        return b.toString();
    }

    private static int lineCount(String s) {
        if (s.isEmpty()) {
            return 1;
        }
        int n = s.split("\n", -1).length;
        return s.endsWith("\n") ? Math.max(1, n - 1) : n;
    }

    private static String dropReason(String last) {
        return "no acceptable patch; last: " + last;
    }

    private static String oneLine(String s) {
        return s.replace("\r", "\\r").replace("\n", "\\n");
    }
}
