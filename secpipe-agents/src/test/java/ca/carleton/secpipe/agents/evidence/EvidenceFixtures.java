package ca.carleton.secpipe.agents.evidence;

import ca.carleton.secpipe.contracts.agent.AgentOutputs.Citation;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.DisproverResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.Step;
import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.llm.FakeLlmClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Shared data for the evidence tests: the sqli-min LoginServlet candidate, its survivor draft and fakes. */
final class EvidenceFixtures {

    static final String LOGIN = "src/main/java/com/shop/app/LoginServlet.java";
    static final String SCOPE = "com.shop.app.LoginServlet#doPost(HttpServletRequest, HttpServletResponse)";
    /** Fingerprint served by the catch-all fake testgen fixture. */
    static final String FP = "code:CWE-89:0000000000000000000000a1";
    /** Fingerprint whose testgen output is always malformed. */
    static final String FP_MALFORMED = "code:CWE-89:0000000000000000000000a9";
    /** Fingerprint served by the real sqli-min regression test fixture (sandbox test). */
    static final String FP_SQLI_MIN = "code:CWE-89:00000000000000000000beef";
    static final Path SQLI_MIN = Path.of("..", "fixtures", "e2e", "sqli-min").toAbsolutePath().normalize();

    private EvidenceFixtures() {
    }

    static Candidate candidate(String fp) {
        List<Finding.TraceStep> trace = List.of(
                new Finding.TraceStep(Finding.TraceKind.ENTRYPOINT, LOGIN, 18, SCOPE, "request parameter username"),
                new Finding.TraceStep(Finding.TraceKind.SINK, LOGIN, 21, SCOPE, "executeQuery with concatenated SQL"));
        return new Candidate(Optional.of(fp), Finding.Category.CODE, Finding.Origin.TOOL,
                List.of(new Finding.ToolSource("semgrep", "java.sqli", Optional.empty(), Optional.of(LOGIN),
                        Optional.of(21))),
                List.of("CWE-89"), List.of(), "SQL injection in LoginServlet.doPost", "username reaches executeQuery.",
                "The username parameter is concatenated into SQL.", trace,
                List.of(new Finding.Evidence(LOGIN, 21, "string concatenation into executeQuery")), Optional.empty(),
                Optional.of(SCOPE), Optional.empty(), Optional.of(Finding.Score5.HIGH), Optional.of(Finding.Level3.HIGH),
                Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }

    static DisproverResult draft(String fp) {
        return new DisproverResult(fp, Finding.Verdict.CONFIRMED, true, true, List.of(), Optional.empty(),
                Optional.of("username is concatenated into the SQL text"),
                Optional.of("username is bound as a query parameter"),
                List.of(new Step(Finding.TraceKind.ENTRYPOINT, LOGIN, 18, SCOPE, "request parameter", Optional.empty()),
                        new Step(Finding.TraceKind.SINK, LOGIN, 21, SCOPE, "executeQuery", Optional.empty())),
                List.of(new Citation(LOGIN, 21, "concatenated query", "st.executeQuery(")), List.of(),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty());
    }

    /** The record the disprover writes for a survivor (G.4 fix-up). */
    static Finding awaiting(String fp) {
        Candidate c = candidate(fp);
        Finding.VerifierRef ref = new Finding.VerifierRef(Finding.VerifierRef.Kind.LLM, Optional.of("disprover-0000aaaa"),
                Optional.of("fake"), Optional.of("fake-strong"), Optional.of(Finding.VerifierRef.CapabilityTier.STRONG),
                Optional.empty());
        Finding.SecPipeExt ext = new Finding.SecPipeExt(c.category(), c.origin(), c.toolSources(), c.cwe(), c.cve(),
                Optional.empty(), Optional.empty(), c.toolSeverityHint(), Optional.empty(), List.of(), List.of(),
                Optional.empty(), Optional.empty(), Optional.of(new Finding.Verification(Optional.of(ref),
                Optional.empty(), Optional.empty(), false)), List.of(), Optional.empty(), Optional.empty());
        return new Finding.NeedsValidation(fp, c.title(), c.message(), c.claimedRootCause(), c.trace(), c.evidence(),
                List.of("awaiting regression test evidence"),
                new Finding.ValidationPlan(Optional.of("run the regression test"), Optional.empty()), ext);
    }

    static PipelineState state(List<Candidate> candidates, Map<String, Finding> records,
                               Map<String, DisproverResult> drafts) {
        return new PipelineState("run-1", null, Optional.empty(), Optional.empty(), List.of(), List.of(), candidates,
                records, drafts, List.of(), Map.of(), 0, 0, false, Map.of(), Optional.empty(), List.of(),
                Optional.empty());
    }

    static TestEvidenceNode.Settings settings(ProviderMode mode, Path target, Path work) {
        return new TestEvidenceNode.Settings(mode, true, 25, false, target, work.resolve("work"), work.resolve("out"));
    }

    /** A private copy of fixtures/e2e/sqli-min (the fake sandbox never runs it). */
    static Path copyOfSqliMin(Path dir) {
        Path target = dir.resolve("target-copy");
        try {
            RegressionTestRun.copyTree(SQLI_MIN, target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return target;
    }

    static FakeLlmClient fake() {
        try {
            return FakeLlmClient.fromDir(Path.of(Objects.requireNonNull(
                    EvidenceFixtures.class.getResource("/llm-fixtures/evidence")).toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An LLM client with AI switched off. */
    record Off(LlmClient inner) implements LlmClient {
        @Override
        public ProviderMode mode() {
            return ProviderMode.NONE;
        }

        @Override
        public ModelInfo modelFor(AgentRole role) {
            return inner.modelFor(role);
        }

        @Override
        public Response complete(AgentRole role, Request request) throws LlmUnavailableException {
            throw new LlmUnavailableException("AI is off", null);
        }
    }

    static final class Log implements RunContext.RunLog {
        final List<String> lines = new ArrayList<>();

        @Override
        public void info(String msg) {
            lines.add("INFO " + msg);
        }

        @Override
        public void warn(String msg) {
            lines.add("WARN " + msg);
        }

        @Override
        public void debug(String msg) {
            lines.add("DEBUG " + msg);
        }
    }
}
