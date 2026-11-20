package ca.carleton.secpipe.agents.testgen;

import ca.carleton.secpipe.agents.prompt.DefaultPromptRenderer;
import ca.carleton.secpipe.agents.runner.AgentOutcome;
import ca.carleton.secpipe.agents.runner.AgentRunner;
import ca.carleton.secpipe.agents.runner.GroundingContext;
import ca.carleton.secpipe.agents.testgen.TestGenInput.Snippet;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.Step;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.TestGenResult;
import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.spi.Agent;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.contracts.spi.SourceIndex;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Regression-test generator (WO-G.6, PLAN Part 5 A8): writes one JUnit 5 class per survivor that fails while the
 * vulnerability exists. The generated source is never compiled or run here; WO-G.7 runs it through
 * {@code SandboxRunner}.
 *
 * <p>Use {@link #generate}: it runs the agent through {@link AgentRunner} (strict parse, schema, grounding, fresh
 * re-runs) and then applies {@link GeneratedTestChecker}. A test with violations counts as malformed output: it is
 * reported to the discard listener and a fresh agent is asked, never edited. Across both kinds of discard, no new
 * agent is started once {@link AgentRunner#MAX_ATTEMPTS} LLM attempts have been spent.
 *
 * <p>The prompt text lives in {@code prompts/testgen.v1.md} (sections {@code ===SYSTEM===} and {@code ===USER===});
 * it is loaded by {@link DefaultPromptRenderer#load} and hashed by {@link DefaultPromptRenderer#sha256}, then split
 * into its two sections here.
 */
public final class TestGenAgent implements Agent<TestGenInput, TestGenResult> {

    public static final String TEMPLATE_ID = "testgen.v1";
    public static final String SCHEMA_ID = "agent/testgen-result.schema.json";
    static final String RESOURCE = "prompts/testgen.v1.md";
    /** Temperature for testgen (PLAN Part 5). */
    static final double TEMPERATURE = 0.2;
    static final int MAX_OUTPUT_TOKENS = 8000;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z0-9_]+)}}");
    private static final String SYSTEM_MARK = "===SYSTEM===\n";
    private static final String USER_MARK = "===USER===\n";

    private final String systemSection;
    private final String userSection;

    public TestGenAgent() {
        String text = DefaultPromptRenderer.load(TEMPLATE_ID);
        int s = text.indexOf(SYSTEM_MARK);
        int u = text.indexOf(USER_MARK);
        if (s < 0 || u < s) {
            throw new IllegalStateException(RESOURCE + " needs ===SYSTEM=== then ===USER=== sections");
        }
        this.systemSection = text.substring(s + SYSTEM_MARK.length(), u);
        this.userSection = text.substring(u + USER_MARK.length());
    }

    @Override
    public AgentRole role() {
        return AgentRole.TESTGEN;
    }

    @Override
    public String outputSchemaId() {
        return SCHEMA_ID;
    }

    @Override
    public Class<TestGenResult> outputType() {
        return TestGenResult.class;
    }

    @Override
    public String promptTemplateId() {
        return TEMPLATE_ID;
    }

    @Override
    public LlmClient.Request buildRequest(TestGenInput input, PromptRenderer renderer) {
        Objects.requireNonNull(input, "input");
        String system = renderer.template(DefaultPromptRenderer.STANDING_RULES, Map.of()) + "\n" + systemSection
                + "\n" + renderer.template(DefaultPromptRenderer.OUTPUT_CONTRACT, Map.of("schema_id", SCHEMA_ID));
        StringBuilder snippets = new StringBuilder();
        for (Snippet s : input.snippets()) {
            snippets.append(renderer.untrusted(s.path(), s.fromLine(), s.toLine(), s.content()));
        }
        Map<String, String> vars = new TreeMap<>();
        vars.put("fingerprint", input.fingerprint());
        vars.put("family", input.family());
        vars.put("record", record(input));
        vars.put("payloads", bullets(input.payloads(), "(none bundled for this family; choose one)"));
        vars.put("test_dependencies", bullets(input.testDependencies(), "(JUnit 5 only)"));
        vars.put("snippets", snippets.length() == 0 ? "(no slices available)\n" : snippets.toString());
        return new LlmClient.Request(system, fill(userSection, vars), SCHEMA_ID, MAX_OUTPUT_TOKENS, TEMPERATURE,
                TEMPLATE_ID);
    }

    /** {@link #generate(AgentRunner, TestGenInput, GroundingContext, AgentRunner.DiscardListener)} without listener. */
    public AgentOutcome<TestGenResult> generate(AgentRunner runner, TestGenInput input, SourceIndex sources) {
        return generate(runner, input, GroundingContext.of(sources, Set.of(input.fingerprint())),
                AgentRunner.DiscardListener.NONE);
    }

    /**
     * Generates one checked test. {@link AgentOutcome.Ok} carries a schema-valid, grounded result whose source
     * passed {@link GeneratedTestChecker#check(TestGenResult)}; {@link AgentOutcome.Dropped} means the caller records
     * {@code needs_validation} ("could not generate an executable regression test"); {@link AgentOutcome.Unavailable}
     * means AI is off, the budget is exhausted or the provider is down (the record keeps its blocker).
     */
    public AgentOutcome<TestGenResult> generate(AgentRunner runner, TestGenInput input, GroundingContext grounding,
                                                AgentRunner.DiscardListener listener) {
        Objects.requireNonNull(runner, "runner");
        Objects.requireNonNull(listener, "listener");
        int attempts = 0;
        String lastReason = "";
        while (attempts < AgentRunner.MAX_ATTEMPTS) {
            AgentOutcome<TestGenResult> outcome = runner.run(this, input, grounding,
                    AgentRunner.MAX_ATTEMPTS - attempts);
            attempts += outcome.attempts();
            switch (outcome) {
                case AgentOutcome.Unavailable<TestGenResult> u -> {
                    return new AgentOutcome.Unavailable<>(u.reason(), attempts);
                }
                case AgentOutcome.Dropped<TestGenResult> d -> {
                    return new AgentOutcome.Dropped<>(dropReason(d.reason()), attempts);
                }
                case AgentOutcome.Ok<TestGenResult> ok -> {
                    List<GeneratedTestChecker.Violation> violations = GeneratedTestChecker.check(ok.value());
                    if (violations.isEmpty()) {
                        return new AgentOutcome.Ok<>(ok.value(), attempts, ok.agentId(), ok.model());
                    }
                    lastReason = "generated test rejected: " + violations.stream().map(Object::toString)
                            .collect(Collectors.joining("; "));
                    listener.discarded(role(), ok.agentId(), attempts, lastReason);
                }
            }
        }
        return new AgentOutcome.Dropped<>(dropReason(lastReason), attempts);
    }

    /** Lower-case hex SHA-256 of the prompt template (for {@code ai.prompts_hash}). */
    public static String promptSha256() {
        return DefaultPromptRenderer.sha256(TEMPLATE_ID);
    }

    private static String dropReason(String last) {
        return "could not generate an executable regression test; last: " + last;
    }

    /** The provisional record: verdict fields and trace locations written by the disprover (no repo quotes). */
    private static String record(TestGenInput input) {
        StringBuilder b = new StringBuilder("Provisional record:\n");
        input.draft().rootCause().ifPresent(r -> b.append("- Root cause: ").append(oneLine(r)).append('\n'));
        input.draft().intendedBehavior().ifPresent(r -> b.append("- Intended behaviour: ").append(oneLine(r))
                .append('\n'));
        b.append("- Trace:\n");
        for (Step s : input.draft().trace()) {
            b.append("  - ").append(s.kind().name().toLowerCase(Locale.ROOT)).append(' ').append(s.file()).append(':')
                    .append(s.line()).append(" in ").append(oneLine(s.scope())).append(": ")
                    .append(oneLine(s.description())).append('\n');
        }
        return b.toString();
    }

    private static String bullets(List<String> items, String empty) {
        if (items.isEmpty()) {
            return "- " + empty + "\n";
        }
        StringBuilder b = new StringBuilder();
        for (String i : items) {
            b.append("- ").append(oneLine(i)).append('\n');
        }
        return b.toString();
    }

    private static String oneLine(String s) {
        return s.replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String fill(String text, Map<String, String> vars) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            if (v == null) {
                throw new IllegalArgumentException(RESOURCE + " needs variable {{" + m.group(1) + "}}");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }
}
