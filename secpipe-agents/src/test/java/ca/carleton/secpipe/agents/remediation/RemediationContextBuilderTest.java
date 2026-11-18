package ca.carleton.secpipe.agents.remediation;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.agents.remediation.RemediationAgent.Input;
import ca.carleton.secpipe.agents.remediation.RemediationAgent.PreviousAttempt;
import ca.carleton.secpipe.agents.remediation.RemediationAgent.Snippet;
import ca.carleton.secpipe.contracts.finding.Finding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class RemediationContextBuilderTest {

    @TempDir
    Path tmp;

    private RemediationContextBuilder builder() {
        return new RemediationContextBuilder(RemediationAgentTest.index(RemediationAgentTest.copyOfSqliMin(tmp)));
    }

    @Test
    void cutsTheWholeEnclosingMethodOnceForAllTraceLines() {
        Input in = builder().build(RemediationAgentTest.confirmed(RemediationAgentTest.fp(1)),
                RemediationAgentTest.regressionTest(), List.of());

        assertThat(in.methods()).singleElement().satisfies(s -> {
            assertThat(s.path()).isEqualTo(RemediationAgentTest.LOGIN);
            assertThat(s.fromLine()).isEqualTo(16);
            assertThat(s.toLine()).isEqualTo(30);
            assertThat(s.content()).startsWith("    @Override").contains("executeQuery").endsWith("    }");
        });
        assertThat(in.previousAttempts()).isEmpty();
        assertThat(in.fingerprint()).isEqualTo(RemediationAgentTest.fp(1));
    }

    @Test
    void keepsOnlyTheLastAttemptsWithTrimmedLogs() {
        StringBuilder log = new StringBuilder();
        for (int i = 1; i <= 500; i++) {
            log.append("[INFO] line ").append(i).append("\r\n");
        }
        List<PreviousAttempt> previous = new ArrayList<>();
        for (int a = 1; a <= 5; a++) {
            previous.add(new PreviousAttempt(a, "--- a/x\n", "regression test still fails", log.toString()));
        }
        Input in = builder().build(RemediationAgentTest.confirmed(RemediationAgentTest.fp(1)),
                RemediationAgentTest.regressionTest(), previous);

        assertThat(in.previousAttempts()).extracting(PreviousAttempt::attempt).containsExactly(3, 4, 5);
        String trimmed = in.previousAttempts().get(0).log();
        assertThat(trimmed).startsWith("[log trimmed]").contains("[INFO] line 500").doesNotContain("[INFO] line 1\n")
                .doesNotContain("\r");
        assertThat(trimmed.length()).isLessThanOrEqualTo(RemediationContextBuilder.MAX_LOG_CHARS + 20);
        assertThat(trimmed.split("\n").length).isLessThanOrEqualTo(RemediationContextBuilder.MAX_LOG_LINES + 1);
    }

    @Test
    void shortLogIsKeptAsIs() {
        assertThat(RemediationContextBuilder.trimLog("[ERROR] one\n[ERROR] two")).isEqualTo("[ERROR] one\n[ERROR] two");
    }

    @Test
    void unknownLocationsAreSkippedAndNonJavaFilesUseAWindow() {
        Finding.Confirmed base = RemediationAgentTest.confirmed(RemediationAgentTest.fp(1));
        Finding.Confirmed other = new Finding.Confirmed(base.fingerprint(), base.title(), base.description(),
                base.rootCause(), base.intendedBehavior(),
                List.of(new Finding.TraceStep(Finding.TraceKind.SINK, "pom.xml", 5, "pom", "build"),
                        new Finding.TraceStep(Finding.TraceKind.SINK, "Missing.java", 3, "x", "y"),
                        new Finding.TraceStep(Finding.TraceKind.SINK, RemediationAgentTest.LOGIN, 999, "x", "y")),
                List.of(), List.of(), base.execution(), base.remediation(), base.severity(), base.confidence(),
                base.secpipe());

        List<Snippet> snippets = builder().build(other, RemediationAgentTest.regressionTest(), List.of()).methods();

        assertThat(snippets).singleElement().satisfies(s -> {
            assertThat(s.path()).isEqualTo("pom.xml");
            assertThat(s.fromLine()).isEqualTo(1);
            assertThat(s.toLine()).isEqualTo(20);
        });
    }
}
