package ca.carleton.secpipe.eval.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.json.ContractJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkScorecardCliTest {

    @TempDir
    Path tmp;

    @Test
    void writesThreeFilesFromFindingsJson() throws IOException {
        Path csv = tmp.resolve("expected.csv");
        Files.writeString(csv, BenchmarkScorecardFixtures.resource("expected-mini.csv"));
        Path findings = tmp.resolve("findings.json");
        ContractJson.writeFindings(findings, BenchmarkScorecardFixtures.findings());
        Path out = tmp.resolve("out");

        int code = BenchmarkScorecard.run(new String[] {"--expected", csv.toString(), "--findings",
                findings.toString(), "--out", out.toString()});

        assertThat(code).isZero();
        assertThat(out.resolve("scorecard.json")).exists();
        assertThat(out.resolve("scorecard.md")).exists();
        assertThat(Files.readString(out.resolve("per-case.csv"))).contains("BenchmarkTest00004,cmdi,78,false,true,FP");
    }

    @Test
    void missingRequiredOptionIsAUsageError() {
        assertThat(BenchmarkScorecard.run(new String[] {"--out", tmp.toString()})).isEqualTo(2);
    }
}
