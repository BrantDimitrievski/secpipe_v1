package ca.carleton.secpipe.eval.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BenchmarkScorecardSarifTest {

    private static BenchmarkScorecard.Result score() {
        return BenchmarkScorecard.score(BenchmarkScorecardFixtures.expected(),
                ReportedSet.fromSarif(BenchmarkScorecardFixtures.resource("sarif-mini.json")));
    }

    @Test
    void sarifGivesTheSameCountsAsFindings() {
        var viaFindings = BenchmarkScorecard.score(BenchmarkScorecardFixtures.expected(),
                ReportedSet.fromFindings(BenchmarkScorecardFixtures.findings()));
        assertThat(score().perCwe()).isEqualTo(viaFindings.perCwe());
    }

    @Test
    void suppressedResultIsNotReported() {
        assertThat(score().cases()).filteredOn(c -> c.test().equals("BenchmarkTest00002")).singleElement()
                .extracting(BenchmarkScorecard.Case::result).isEqualTo("TN");
    }

    @Test
    void cweComesFromTheRuleWhenTheResultHasNone() {
        assertThat(score().cases()).filteredOn(c -> c.test().equals("BenchmarkTest00003")).singleElement()
                .extracting(BenchmarkScorecard.Case::result).isEqualTo("TP");
    }

    @Test
    void unionOfSourcesReportsEither() {
        ReportedSet both = ReportedSet.fromSarif(BenchmarkScorecardFixtures.resource("sarif-mini.json"))
                .union(ReportedSet.fromFindings(BenchmarkScorecardFixtures.findings()));
        assertThat(both.contains("BenchmarkTest00001", "CWE-22")).isTrue();
        assertThat(both.contains("BenchmarkTest00005", "CWE-89")).isFalse();
    }
}
