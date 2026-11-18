package ca.carleton.secpipe.agents.testgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import ca.carleton.secpipe.agents.testgen.TestGenInput.Snippet;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.DisproverResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.Step;
import ca.carleton.secpipe.contracts.finding.Finding;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

class TestGenContextBuilderTest {

    private static final String FP = "code:CWE-89:00000000000000000000a001";

    @Test
    void windowsAroundCitedLinesAreMergedAndPayloadsComeFromTheBundle() {
        TestGenInput in = new TestGenContextBuilder(TestGenAgentTest.SOURCES, Payloads.bundled(), 2, 8)
                .build("CWE-89", TestGenAgentTest.draft(FP), List.of());

        assertThat(in.fingerprint()).isEqualTo(FP);
        assertThat(in.payloads()).contains("' OR '1'='1", "admin'--");
        // lines 18 and 21 with radius 2 -> 16-20 and 19-23 merge into one 16-23 slice
        assertThat(in.snippets()).singleElement().satisfies(s -> {
            assertThat(s.path()).isEqualTo(TestGenAgentTest.LOGIN);
            assertThat(s.fromLine()).isEqualTo(16);
            assertThat(s.toLine()).isEqualTo(23);
            assertThat(s.content()).startsWith("    @Override").contains("executeQuery");
        });
    }

    @Test
    void distantLinesGiveSeparateSlicesAndUnknownLocationsAreSkipped() {
        DisproverResult base = TestGenAgentTest.draft(FP);
        List<Step> trace = new ArrayList<>(base.trace());
        trace.add(0, new Step(Finding.TraceKind.ENTRYPOINT, TestGenAgentTest.LOGIN, 3, "x", "import",
                Optional.empty()));
        trace.add(new Step(Finding.TraceKind.PROPAGATION, "src/main/java/Missing.java", 4, "y", "gone",
                Optional.empty()));
        DisproverResult d = new DisproverResult(FP, base.verdict(), true, true, List.of(), Optional.empty(),
                base.rootCause(), base.intendedBehavior(), trace, base.evidence(), List.of(), Optional.empty(),
                List.of(), Optional.empty(), Optional.empty());

        TestGenInput in = new TestGenContextBuilder(TestGenAgentTest.SOURCES, Payloads.bundled(), 1, 8)
                .build("CWE-1234", d, List.of("junit"));

        assertThat(in.snippets()).extracting(Snippet::fromLine, Snippet::toLine)
                .containsExactly(tuple(2, 4), tuple(17, 22));
        assertThat(in.payloads()).isEmpty();
    }
}
