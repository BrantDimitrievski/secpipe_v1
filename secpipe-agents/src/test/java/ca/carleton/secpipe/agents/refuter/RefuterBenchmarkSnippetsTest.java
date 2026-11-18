package ca.carleton.secpipe.agents.refuter;

import ca.carleton.secpipe.contracts.finding.Candidate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 16 team-written OWASP-Benchmark-shaped snippets: 8 false positives refuted, 8 true positives kept. */
class RefuterBenchmarkSnippetsTest {

    private static final Pattern META = Pattern.compile("^// cwe=(CWE-\\d+)(?: rule=(\\S+))?");

    private final Refuter refuter = new Refuter();

    @ParameterizedTest
    @ValueSource(strings = {"RefutedSqliIfFold", "RefutedSqliTernary", "RefutedCmdiSwitch", "RefutedPathOverwritten",
        "RefutedSqliConstant", "RefutedPathDeadSink", "RefutedSqliPrepared", "RefutedCmdiArgList"})
    void falsePositiveIsRefutedByTheExpectedRule(String name) {
        String src = RefuterFixtures.resource("benchmark/refuted/" + name + ".java");
        Matcher m = META.matcher(src);
        assertTrue(m.find(), "metadata header");
        Optional<Refutation> r = refuter.check(RefuterFixtures.snippetCandidate(src, m.group(1)),
                RefuterFixtures.parse(src));
        assertTrue(r.isPresent(), name + " should be refuted");
        assertEquals(m.group(2), r.get().rule(), name);
        assertTrue(r.get().controlLocation().line() >= 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"KeptSqliIfFold", "KeptSqliTernary", "KeptCmdiSwitch", "KeptPathPartialOverwrite",
        "KeptSqliTryCatchOverwrite", "KeptSqliLoopOverwrite", "KeptCmdiShellBuilder", "KeptSqliPreparedConcat"})
    void truePositiveIsKept(String name) {
        String src = RefuterFixtures.resource("benchmark/kept/" + name + ".java");
        Matcher m = META.matcher(src);
        assertTrue(m.find(), "metadata header");
        Candidate c = RefuterFixtures.snippetCandidate(src, m.group(1));
        assertEquals(Optional.empty(), refuter.check(c, RefuterFixtures.parse(src)), name + " must pass through");
    }
}
