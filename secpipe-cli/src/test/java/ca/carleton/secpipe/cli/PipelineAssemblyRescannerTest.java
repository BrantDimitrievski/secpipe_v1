package ca.carleton.secpipe.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.config.ConfigResolver;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.core.detect.DetectRescanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelineAssemblyRescannerTest {

    static final Path FIXTURE = ScanCommandTest.FIXTURE;
    /** From fixtures/e2e/sqli-min/expected.json (no_ai_findings): LoginServlet and TagFilter. */
    static final String LOGIN_FP = "code:CWE-89:5063a6838d54b051366075cc";
    static final String TAG_FP = "code:CWE-89:6caa5fd138861f8176e43d77";

    @TempDir
    Path tmp;

    private RunContext context() {
        SecPipeConfig cfg = ConfigResolver.resolve(Map.of("target", FIXTURE.toString(),
                "out", tmp.resolve("out").toString(), "no-ai", "", "no-sandbox", ""), Map.of(), Optional.empty());
        return PipelineAssembly.build(cfg, Map.of(), Clock.systemUTC(), line -> { }).context();
    }

    private Path copyFixture() throws IOException {
        Path dest = tmp.resolve("copy");
        try (Stream<Path> walk = Files.walk(FIXTURE)) {
            for (Path p : walk.toList()) {
                Path to = dest.resolve(FIXTURE.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(p, to);
                }
            }
        }
        return dest;
    }

    private static List<String> fingerprints(List<Candidate> candidates) {
        return candidates.stream().map(c -> c.fingerprint().orElseThrow()).toList();
    }

    @Test
    void theAssembledContextUsesDetectRescanner() {
        assertThat(context().rescanner()).isInstanceOf(DetectRescanner.class);
    }

    @Test
    void rescanWithTheRunsAdaptersReproducesTheScanFingerprintsAndSeesAFix() throws Exception {
        RunContext ctx = context();
        Path copy = copyFixture();

        List<String> before = fingerprints(ctx.rescanner().rescan(copy, Set.of()));
        assertThat(before).contains(LOGIN_FP, TAG_FP).isSorted();

        Path login = copy.resolve("src/main/java/com/shop/app/LoginServlet.java");
        String src = Files.readString(login);
        String fixed = src.replace("conn.createStatement();", "conn.prepareStatement(\"select id from users where "
                + "username = ?\");").replace("Statement st", "java.sql.PreparedStatement st")
                .replace("st.executeQuery(\"select id from users where username = '\" + username + \"'\")",
                        "st.executeQuery()");
        assertThat(fixed).isNotEqualTo(src);
        Files.writeString(login, fixed);

        List<String> after = fingerprints(ctx.rescanner().rescan(copy, Set.of()));
        assertThat(after).doesNotContain(LOGIN_FP).contains(TAG_FP);
        assertThat(Files.readString(FIXTURE.resolve("src/main/java/com/shop/app/LoginServlet.java")))
                .isEqualTo(src);
    }
}
