package ca.carleton.secpipe.cli.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the shaded secpipe.jar exactly as in docs/DEMO.md (failsafe runs this after package). */
class DemoJarIT {

    @Test
    void shadedJarScansSqliMinEndToEnd(@TempDir Path out) throws IOException, InterruptedException {
        Path jar = Path.of("target", "secpipe.jar");
        assertThat(jar).exists();
        String java = ProcessHandle.current().info().command().orElse("java");
        Process p = new ProcessBuilder(List.of(java, "-jar", jar.toString(), "scan",
                Path.of("..", "fixtures", "e2e", "sqli-min").toString(), "--out", out.toString()))
                .redirectErrorStream(true)
                .start();
        String console = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        System.err.println("DEMO jar console:\n" + console);

        // PLAN §4.3: needs_validation findings alone do not fail the run.
        assertThat(p.exitValue()).as(console).isZero();
        assertThat(console).contains("top findings:");
        assertThat(out.resolve("report.md")).exists();
        String findings = Files.readString(out.resolve("findings.json"), StandardCharsets.UTF_8);
        assertThat(findings).contains("\"CWE-89\"");
    }
}
