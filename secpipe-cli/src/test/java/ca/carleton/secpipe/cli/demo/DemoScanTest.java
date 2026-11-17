package ca.carleton.secpipe.cli.demo;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.cli.SecPipeCli;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.validators.SchemaRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoScanTest {

    private static final Path SQLI_MIN = Path.of("..", "fixtures", "e2e", "sqli-min");
    private static final Path SHOP = Path.of("..", "fixtures", "mvp-samples", "shop-samples");

    @Test
    void sqliMinYieldsSchemaValidCwe89FindingsAndAReport(@TempDir Path out) throws IOException {
        DemoScan.Outcome outcome = DemoScan.run(SQLI_MIN, "fixtures/e2e/sqli-min", out);

        assertThat(outcome.findings()).isNotEmpty();
        assertThat(outcome.findings()).allSatisfy(f -> {
            assertThat(f.verdict()).isEqualTo(Finding.Verdict.NEEDS_VALIDATION);
            assertThat(f.secpipe().cwe()).containsExactly("CWE-89");
            assertThat(f.fingerprint()).startsWith("code:CWE-89:");
            assertThat(f.secpipe().toolSources().get(0).tool()).isEqualTo("secpipe-demo-builtin");
        });
        assertThat(outcome.hits()).anySatisfy(h -> {
            assertThat(h.file()).isEqualTo("src/main/java/com/shop/app/LoginServlet.java");
            assertThat(h.method()).isEqualTo("executeQuery");
            assertThat(h.variables()).containsExactly("username");
        });
        String json = Files.readString(out.resolve("findings.json"), StandardCharsets.UTF_8);
        assertThat(SchemaRegistry.shared().validate("findings.schema.json", json).ok()).isTrue();
        assertThat(outcome.validation().ok()).isTrue();
        String report = Files.readString(out.resolve("report.md"), StandardCharsets.UTF_8);
        assertThat(report).contains(DemoReport.BANNER).contains("LoginServlet.java:21").contains("CWE-89");
    }

    @Test
    void parameterizedPreparedStatementIsNotFlagged(@TempDir Path target, @TempDir Path out) throws IOException {
        Files.writeString(target.resolve("Safe.java"), """
                import java.sql.*;
                class Safe {
                    ResultSet find(Connection c, String name) throws SQLException {
                        String sql = "select id from users where name = ?";
                        PreparedStatement ps = c.prepareStatement(sql);
                        ps.setString(1, name);
                        PreparedStatement ps2 = c.prepareStatement("select * from t where a = ? " + "and b = ?");
                        ps2.execute();
                        return ps.executeQuery();
                    }
                }
                """, StandardCharsets.UTF_8);

        DemoScan.Outcome outcome = DemoScan.run(target, "safe", out);

        assertThat(outcome.filesParsed()).isEqualTo(1);
        assertThat(outcome.findings()).isEmpty();
        assertThat(outcome.validation().ok()).isTrue();
    }

    @Test
    void concatenationThroughALocalVariableIsFlagged(@TempDir Path target, @TempDir Path out) throws IOException {
        Files.writeString(target.resolve("Bad.java"), """
                import java.sql.*;
                class Bad {
                    void run(Statement st, String id) throws SQLException {
                        String q = "delete from t where id = " + id;
                        st.executeUpdate(q);
                    }
                }
                """, StandardCharsets.UTF_8);

        DemoScan.Outcome outcome = DemoScan.run(target, "bad", out);

        assertThat(outcome.hits()).singleElement().satisfies(h -> {
            assertThat(h.viaVariable()).isEqualTo("q");
            assertThat(h.line()).isEqualTo(5);
            assertThat(h.scope()).isEqualTo("Bad#run");
        });
        assertThat(outcome.validation().ok()).isTrue();
    }

    @Test
    void findingsJsonIsByteIdenticalAcrossRuns(@TempDir Path out1, @TempDir Path out2) throws IOException {
        DemoScan.run(SQLI_MIN, "fixtures/e2e/sqli-min", out1);
        DemoScan.run(SQLI_MIN, "fixtures/e2e/sqli-min", out2);

        assertThat(Files.readAllBytes(out2.resolve("findings.json")))
                .isEqualTo(Files.readAllBytes(out1.resolve("findings.json")));
    }

    @Test
    void cliScanFollowsTheFrozenExitCodes(@TempDir Path out) {
        String fixture = SQLI_MIN.toString();
        assertThat(cli("scan", fixture, "--out", out.resolve("a").toString())).isEqualTo(0);
        assertThat(cli("scan", fixture, "--out", out.resolve("b").toString(), "--fail-on-needs-validation"))
                .isEqualTo(1);
        assertThat(cli("scan", out.resolve("missing").toString())).isEqualTo(2);
        assertThat(cli("scan", fixture, "--bogus")).isEqualTo(2);
        assertThat(Files.exists(out.resolve("a").resolve("report.md"))).isTrue();
    }

    @Test
    void entryPointPrintsAHumanSummaryForSqliMin(@TempDir Path out) {
        // main(String[]) is System.exit(run(args, System.out, System.err)); this is the same path java -jar takes.
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = SecPipeCli.run(new String[] {"scan", SQLI_MIN.toString(), "--out", out.toString()},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        String console = stdout.toString(StandardCharsets.UTF_8);
        assertThat(code).isZero();
        assertThat(stderr.size()).isZero();
        assertThat(console).contains("demo scan found 2 CWE-89 finding(s)")
                .contains(out.resolve("findings.json").toString())
                .contains(out.resolve("report.md").toString())
                .contains("top findings:")
                .contains("src/main/java/com/shop/app/LoginServlet.java:21");
        assertThat(out.resolve("findings.json")).exists();
        assertThat(out.resolve("report.md")).exists();
    }

    @Test
    void shopSamplesScanReportsACount(@TempDir Path out) {
        DemoScan.Outcome outcome = DemoScan.run(SHOP, "fixtures/mvp-samples/shop-samples", out);

        System.err.println("DEMO shop-samples: " + outcome.findings().size() + " CWE-89 finding(s) in "
                + outcome.filesParsed() + " parsed file(s), " + outcome.filesSkipped() + " skipped");
        assertThat(outcome.validation().ok()).isTrue();
    }

    private static int cli(String... args) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(sink, true, StandardCharsets.UTF_8);
        return SecPipeCli.run(args, ps, ps);
    }
}
