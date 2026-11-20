package ca.carleton.secpipe.eval.remediation;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.json.ContractJson;
import ca.carleton.secpipe.eval.remediation.RemediationScorecard.AppFindings;
import ca.carleton.secpipe.eval.remediation.RemediationScorecard.Row;
import ca.carleton.secpipe.eval.remediation.RemediationScorecard.Totals;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemediationScorecardTest {

    @TempDir
    Path tmp;

    private static String text(String app) throws IOException {
        try (InputStream in = RemediationScorecardTest.class.getResourceAsStream(app + "/findings.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<AppFindings> apps() throws IOException {
        return List.of(new AppFindings("shop-samples", ContractJson.readFindings(text("shop-samples"))),
                new AppFindings("vulnerable-java-app", ContractJson.readFindings(text("vulnerable-java-app"))));
    }

    private static Row row(List<Row> rows, int n) {
        return rows.stream().filter(r -> r.fingerprint().endsWith(String.format("%024x", n))).findFirst().orElseThrow();
    }

    @Test
    void extractsFixedStillPresentUnverifiedAndNotAttemptedRows() throws IOException {
        List<Row> rows = RemediationScorecard.rows(apps(), "out");

        assertThat(rows).hasSize(7);
        assertThat(rows).extracting(Row::app).isSortedAccordingTo(String::compareTo);
        Row fixed = row(rows, 1);
        assertThat(fixed.finalStatus()).isEqualTo("fixed");
        assertThat(fixed.attempts()).isEqualTo(2);
        assertThat(fixed.preFix()).isEqualTo("failed_as_expected");
        assertThat(fixed.postFix()).isEqualTo("passed");
        assertThat(fixed.suite()).isEqualTo("pass");
        assertThat(fixed.rescan()).isEqualTo("clean");
        assertThat(fixed.patch()).isEqualTo("[attempt 2](out/vulnerable-java-app/patches/p2.patch)");
        assertThat(row(rows, 2).finalStatus()).isEqualTo("still_present");
        assertThat(row(rows, 2).attempts()).isEqualTo(3);
        assertThat(row(rows, 3).finalStatus()).isEqualTo("fix unverified");
        Row none = row(rows, 4);
        assertThat(none.finalStatus()).isEqualTo("not_attempted");
        assertThat(none.attempts()).isZero();
        assertThat(none.patch()).isEqualTo("-");
    }

    @Test
    void inadequateAndSandboxUnavailableAreShownDistinctly() throws IOException {
        List<Row> rows = RemediationScorecard.rows(apps(), "");

        assertThat(row(rows, 5).adequacy()).isEqualTo("1/4 lines (adequacy limited)");
        assertThat(row(rows, 5).finalStatus()).isEqualTo("fixed");
        assertThat(row(rows, 1).adequacy()).isEqualTo("3/3 lines");
        assertThat(row(rows, 6).finalStatus()).isEqualTo("sandbox unavailable");
        assertThat(row(rows, 6).patch()).contains("shop-samples/patches/p1.patch");
        assertThat(row(rows, 7).newFindings()).isEqualTo(1);
    }

    @Test
    void totalsSeparateUnverifiedAndSandboxUnavailableFromFailures() throws IOException {
        Totals t = RemediationScorecard.totals(apps());

        assertThat(t.confirmed()).isEqualTo(7);
        assertThat(t.fixed()).isEqualTo(2);
        assertThat(t.attempts()).isEqualTo(9);
        assertThat(t.failedAttempts()).isEqualTo(5);
        assertThat(t.unverifiedAttempts()).isEqualTo(1);
        assertThat(t.sandboxUnavailableAttempts()).isEqualTo(1);
        assertThat(t.notAttempted()).isEqualTo(1);
        assertThat(t.introducedFindings()).isEqualTo(1);
        String md = RemediationScorecard.toMarkdown(apps(), "", false);
        assertThat(md).contains("| Fix rate (fixed / confirmed) | 28.6% |")
                .contains("| Attempts per fix (all attempts / fixed) | 4.50 |")
                .contains("| Introduced findings | 1 |");
    }

    @Test
    void cliOutputIsByteStableAndAppComesFromDirectory() throws IOException {
        Path a = tmp.resolve("vulnerable-java-app");
        Path b = tmp.resolve("shop-samples");
        Files.createDirectories(a);
        Files.createDirectories(b);
        Files.writeString(a.resolve("findings.json"), text("vulnerable-java-app"));
        Files.writeString(b.resolve("findings.json"), text("shop-samples"));
        Path o1 = tmp.resolve("one.md");
        Path o2 = tmp.resolve("two.md");

        assertThat(RemediationScorecard.run(new String[] {"--out", o1.toString(), a.resolve("findings.json").toString(),
                b.resolve("findings.json").toString()})).isZero();
        assertThat(RemediationScorecard.run(new String[] {"--out", o2.toString(), b.resolve("findings.json").toString(),
                a.resolve("findings.json").toString()})).isZero();

        assertThat(Files.readString(o1)).isEqualTo(Files.readString(o2)).contains("| shop-samples | code:CWE-89:");
    }

    @Test
    void missingArgumentsIsAUsageError() {
        assertThat(RemediationScorecard.run(new String[] {"--out", tmp.resolve("x.md").toString()})).isEqualTo(2);
    }
}
