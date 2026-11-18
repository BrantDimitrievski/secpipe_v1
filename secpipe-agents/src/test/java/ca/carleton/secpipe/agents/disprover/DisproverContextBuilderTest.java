package ca.carleton.secpipe.agents.disprover;

import static ca.carleton.secpipe.agents.disprover.DisproverFixtures.FILE;
import static ca.carleton.secpipe.agents.disprover.DisproverFixtures.SCOPE;
import static ca.carleton.secpipe.agents.disprover.DisproverFixtures.SOURCES;
import static ca.carleton.secpipe.agents.disprover.DisproverFixtures.fp;
import static ca.carleton.secpipe.agents.disprover.DisproverFixtures.toolCandidate;
import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.recon.Location;
import ca.carleton.secpipe.contracts.recon.ReconFacts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class DisproverContextBuilderTest {

    @Test
    void slicesCoverTheEnclosingMethodAndCitedLinesAndHideToolDetails() {
        DisproverInput in = new DisproverContextBuilder(SOURCES, Optional.empty()).build(toolCandidate(1, "X"));

        assertThat(in.fingerprint()).isEqualTo(fp(1));
        assertThat(in.slices()).singleElement().satisfies(s -> {
            assertThat(s.path()).isEqualTo(FILE);
            assertThat(s.fromLine()).isEqualTo(1);
            assertThat(s.toLine()).isEqualTo(21);
            assertThat(s.content()).contains("tag = \"featured\";");
        });
        assertThat(in.truncated()).isFalse();
        assertThat(in.controlCatalogue()).contains("PreparedStatement");
        assertThat(in.firstStepIsEntry()).isEmpty();
        assertThat(in.lastStepIsSink()).isEmpty();
        assertThat(in.toString()).doesNotContain("semgrep").doesNotContain("java.sqli");
    }

    @Test
    void enclosingMethodIsShownEvenBeyondTheLineRadius() {
        List<String> lines = new ArrayList<>();
        lines.add("class Big {");
        lines.add("    void run(String p) {");                 // line 2: method start
        for (int i = 0; i < 40; i++) {
            lines.add("        int x" + i + " = " + i + ";");
        }
        lines.add("        sink(p);");                          // line 43
        lines.add("    }");                                     // line 44
        lines.add("    void other() { }");
        lines.add("}");
        MemorySourceIndex idx = new MemorySourceIndex(Map.of("src/Big.java", lines));
        Candidate c = withTrace(toolCandidate(9, "X"), List.of(
                new Finding.TraceStep(Finding.TraceKind.SINK, "src/Big.java", 43, "Big#run(String)", "sink")));

        DisproverInput in = new DisproverContextBuilder(idx, Optional.empty()).build(c);

        assertThat(in.slices()).singleElement().satisfies(s -> {
            assertThat(s.fromLine()).isEqualTo(2);
            assertThat(s.toLine()).isEqualTo(46);
        });
    }

    @Test
    void reconFactsMarkEntrySurfaceAndKnownSink() {
        ReconFacts facts = new ReconFacts(List.of(Location.of(FILE, 14, SCOPE)),
                Map.of("CWE-89", List.of(Location.of(FILE, 19))));
        DisproverInput in = new DisproverContextBuilder(SOURCES, Optional.of(facts)).build(toolCandidate(1, "X"));

        assertThat(in.firstStepIsEntry()).contains(true);
        assertThat(in.lastStepIsSink()).contains(true);

        DisproverInput none = new DisproverContextBuilder(SOURCES, Optional.of(ReconFacts.empty()))
                .build(toolCandidate(1, "X"));
        assertThat(none.firstStepIsEntry()).contains(false);
        assertThat(none.lastStepIsSink()).contains(false);
    }

    @Test
    void contextIsCappedInSizeAndSinkComesFirst() {
        List<String> lines = new ArrayList<>();
        String wide = "x".repeat(2_000);
        for (int i = 0; i < 400; i++) {
            lines.add("// " + wide);
        }
        MemorySourceIndex idx = new MemorySourceIndex(Map.of("src/Wide.txt", lines));
        Candidate c = withTrace(toolCandidate(9, "X"), List.of(
                new Finding.TraceStep(Finding.TraceKind.ENTRYPOINT, "src/Wide.txt", 20, "W#a()", "entry"),
                new Finding.TraceStep(Finding.TraceKind.SINK, "src/Wide.txt", 300, "W#b()", "sink")));

        DisproverInput in = new DisproverContextBuilder(idx, Optional.empty()).build(c);

        int chars = in.slices().stream().mapToInt(s -> s.content().length()).sum();
        assertThat(chars).isLessThanOrEqualTo(DisproverContextBuilder.MAX_CONTEXT_CHARS);
        assertThat(in.truncated()).isTrue();
        assertThat(in.slices().get(0).fromLine()).isEqualTo(285);
    }

    @Test
    void unknownFamilyGetsTheGenericCatalogueAndBadIdsAreIgnored() {
        assertThat(DisproverContextBuilder.controlCatalogue(List.of("../x", "CWE-99999")))
                .contains("No family-specific catalogue");
        assertThat(DisproverContextBuilder.controlCatalogue(List.of("CWE-22"))).contains("startsWith(baseDir)");
    }

    private static Candidate withTrace(Candidate c, List<Finding.TraceStep> trace) {
        return new Candidate(c.fingerprint(), c.category(), c.origin(), c.toolSources(), c.cwe(), c.cve(), c.title(),
                c.message(), c.claimedRootCause(), trace, List.of(), c.sinkStatement(), c.sinkScope(), c.dependency(),
                c.toolSeverityHint(), c.confidenceHint(), c.strongestControl(), c.smallestFix(), c.regressionCase(),
                c.ledgerUnits());
    }
}
