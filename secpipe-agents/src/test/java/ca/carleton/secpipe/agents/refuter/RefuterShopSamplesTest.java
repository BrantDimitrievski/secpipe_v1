package ca.carleton.secpipe.agents.refuter;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MVP shop samples: the known false positive TagFilter is refuted; every true positive is preserved. */
class RefuterShopSamplesTest {

    private final Refuter refuter = new Refuter();

    private Optional<Refutation> check(String cls, String cwe, String sinkText) {
        String src = RefuterFixtures.shopSource(cls);
        String file = "src/main/java/com/shop/app/" + cls + ".java";
        Candidate c = RefuterFixtures.candidate(file, cwe, RefuterFixtures.lineOf(src, sinkText),
                "00000000000000000000000a");
        return refuter.check(c, RefuterFixtures.parse(src));
    }

    @Test
    void tagFilterIsRefutedAsValueOverwritten() {
        Optional<Refutation> r = check("TagFilter", "CWE-89", "executeQuery(");
        assertTrue(r.isPresent());
        assertEquals(Refutation.VALUE_OVERWRITTEN, r.get().rule());
        String src = RefuterFixtures.shopSource("TagFilter");
        assertEquals(RefuterFixtures.lineOf(src, "tag = \"featured\""), r.get().controlLocation().line());
        assertEquals("src/main/java/com/shop/app/TagFilter.java", r.get().controlLocation().file());
    }

    @Test
    void archiveJobIsRefutedAsParameterized() {
        Optional<Refutation> r = check("ArchiveJob", "CWE-78", "new ProcessBuilder(");
        assertTrue(r.isPresent());
        assertEquals(Refutation.PARAMETERIZED, r.get().rule());
    }

    @ParameterizedTest
    @CsvSource({
        "OrderSearch, CWE-89, executeQuery(",
        "ReportSort, CWE-89, executeQuery(",
        "HostCheck, CWE-78, exec(",
        "ScriptRunner, CWE-78, new ProcessBuilder(",
        "AttachmentDownload, CWE-22, new File(",
        "LogViewer, CWE-22, new FileReader("})
    void truePositivesArePreserved(String cls, String cwe, String sinkText) {
        assertEquals(Optional.empty(), check(cls, cwe, sinkText), cls);
    }

    @Test
    void dependencyAndOutOfFamilyCandidatesAreNeverTouched() {
        String src = RefuterFixtures.shopSource("TagFilter");
        String file = "src/main/java/com/shop/app/TagFilter.java";
        Candidate base = RefuterFixtures.candidate(file, "CWE-89", RefuterFixtures.lineOf(src, "executeQuery("),
                "00000000000000000000000b");
        Candidate dependency = new Candidate(base.fingerprint(), Finding.Category.DEPENDENCY, base.origin(),
                base.toolSources(), base.cwe(), base.cve(), base.title(), base.message(), base.claimedRootCause(),
                base.trace(), base.evidence(), base.sinkStatement(), base.sinkScope(), base.dependency(),
                base.toolSeverityHint(), base.confidenceHint(), base.strongestControl(), base.smallestFix(),
                base.regressionCase(), base.ledgerUnits());
        Candidate otherCwe = new Candidate(base.fingerprint(), base.category(), base.origin(), base.toolSources(),
                List.of("CWE-79"), base.cve(), base.title(), base.message(), base.claimedRootCause(), base.trace(),
                base.evidence(), base.sinkStatement(), base.sinkScope(), base.dependency(), base.toolSeverityHint(),
                base.confidenceHint(), base.strongestControl(), base.smallestFix(), base.regressionCase(),
                base.ledgerUnits());
        assertTrue(refuter.check(base, RefuterFixtures.parse(src)).isPresent());
        assertEquals(Optional.empty(), refuter.check(dependency, RefuterFixtures.parse(src)));
        assertEquals(Optional.empty(), refuter.check(otherCwe, RefuterFixtures.parse(src)));
    }
}
