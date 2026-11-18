package ca.carleton.secpipe.eval.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class BenchmarkScorecardTest {

    private static BenchmarkScorecard.Result score() {
        return BenchmarkScorecard.score(BenchmarkScorecardFixtures.expected(),
                ReportedSet.fromFindings(BenchmarkScorecardFixtures.findings()));
    }

    @Test
    void exactPerCweCounts() {
        var perCwe = score().perCwe();
        assertThat(perCwe.keySet()).containsExactly("22", "78", "89", "327", "328");
        assertThat(perCwe.get("22")).isEqualTo(new CweCounts(1, 0, 1, 0));
        assertThat(perCwe.get("78")).isEqualTo(new CweCounts(1, 1, 0, 0));
        assertThat(perCwe.get("89")).isEqualTo(new CweCounts(0, 0, 1, 1));
        assertThat(perCwe.get("327")).isEqualTo(new CweCounts(1, 0, 0, 0));
        assertThat(perCwe.get("328")).isEqualTo(new CweCounts(0, 0, 1, 1));
    }

    @Test
    void ratesAndAverage() {
        var result = score();
        assertThat(result.perCwe().get("78").tpr()).isEqualTo(100.0);
        assertThat(result.perCwe().get("78").fpr()).isEqualTo(100.0);
        assertThat(result.perCwe().get("78").score()).isEqualTo(0.0);
        assertThat(result.average()).isEqualTo(new BenchmarkScorecard.Rates(60.0, 20.0, 40.0));
    }

    @Test
    void familyMatchingCounts23As22And326As327() {
        var cases = score().cases();
        assertThat(cases).filteredOn(c -> c.test().equals("BenchmarkTest00001")).singleElement()
                .extracting(BenchmarkScorecard.Case::result).isEqualTo("TP");
        assertThat(cases).filteredOn(c -> c.test().equals("BenchmarkTest00007")).singleElement()
                .extracting(BenchmarkScorecard.Case::result).isEqualTo("TP");
    }

    @Test
    void wrongFamilyIsNotAMatch() {
        assertThat(score().cases()).filteredOn(c -> c.test().equals("BenchmarkTest00008")).singleElement()
                .extracting(BenchmarkScorecard.Case::result).isEqualTo("FN");
    }

    @Test
    void rejectedIsNotReported() {
        assertThat(score().cases()).filteredOn(c -> c.test().equals("BenchmarkTest00002")).singleElement()
                .extracting(BenchmarkScorecard.Case::result).isEqualTo("TN");
    }

    @Test
    void confirmedAndNeedsValidationAreReported() {
        assertThat(score().cases()).filteredOn(c -> c.test().equals("BenchmarkTest00003")).singleElement()
                .extracting(BenchmarkScorecard.Case::result).isEqualTo("TP");
        assertThat(score().cases()).filteredOn(c -> c.test().equals("BenchmarkTest00004")).singleElement()
                .extracting(BenchmarkScorecard.Case::result).isEqualTo("FP");
    }

    @Test
    void baselineDelta() {
        assertThat(score().baselineDelta()).isEqualTo(new BenchmarkScorecard.Rates(-22.4, -26.0, 16.2));
    }

    @Test
    void casesOutsideTheFiveCwesAreIgnored() {
        assertThat(score().cases()).hasSize(9).noneMatch(c -> c.test().equals("BenchmarkTest00010"));
    }

    @Test
    void jsonHasSortedKeysAndOneDecimalRates() {
        String json = BenchmarkScorecard.toJson(score());
        assertThat(json).startsWith("{\n  \"average\": {");
        assertThat(json.indexOf("\"average\"")).isLessThan(json.indexOf("\"baseline_delta\""));
        assertThat(json.indexOf("\"baseline_delta\"")).isLessThan(json.indexOf("\"per_cwe\""));
        assertThat(json).contains("\"tpr\": 60.0").contains("\"fpr\": -26.0").endsWith("}\n");
    }

    @Test
    void markdownHasFivePerCweRowsAndAnAverageRow() {
        String md = BenchmarkScorecard.toMarkdown(score());
        assertThat(md.lines().filter(l -> l.startsWith("| CWE-")).count()).isEqualTo(5);
        assertThat(md.lines().filter(l -> l.startsWith("| **Average**")).count()).isEqualTo(1);
    }

    @Test
    void perCaseCsvIsSortedWithHeader() {
        List<String> lines = BenchmarkScorecard.toCsv(score()).lines().toList();
        assertThat(lines.get(0)).isEqualTo("test,category,cwe,vulnerable,reported,result");
        assertThat(lines.get(1)).isEqualTo("BenchmarkTest00001,pathtraver,22,true,true,TP");
        assertThat(lines).hasSize(10);
    }

    @Test
    void malformedExpectedRowIsRejected() {
        assertThatThrownBy(() -> ExpectedResults.parse("BenchmarkTest1,sqli,true,89"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("line 1");
    }
}
