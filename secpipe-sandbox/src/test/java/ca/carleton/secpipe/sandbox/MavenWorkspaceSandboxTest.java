package ca.carleton.secpipe.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ca.carleton.secpipe.contracts.spi.SandboxRunner.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Live two-phase Maven (WO-X.2 [DOCKER] acceptance). Runs only with {@code -Psandbox}; the prefetch needs network.
 *
 * <p>Work directories are not deleted: the cache is written by the container user (uid 10001), so the host user may
 * not be able to remove it. CI runners are ephemeral.
 */
@Tag("sandbox")
class MavenWorkspaceSandboxTest {

    /** Checkout of OWASP BenchmarkJava (pinned by the spike at a452b3a); the test is skipped when unset. */
    static final String BENCHMARK_ENV = "SECPIPE_SPIKE_BENCHMARK";

    private final DockerSandboxRunner runner = new DockerSandboxRunner(DockerCli.system(),
        DockerSandboxRunner.defaultImage(), EnvAllowlist.DEFAULT);

    @Test
    void mvpVulnerableJavaAppPrefetchesThenCompilesOffline() throws Exception {
        Path target = Path.of("..", "fixtures", "mvp-samples", "vulnerable-java-app").toAbsolutePath().normalize();
        MavenWorkspace ws = new MavenWorkspace(runner, work("mvp"), MavenWorkspace.Options.defaults());

        MavenWorkspace.Outcome out = ws.prepare(target);

        assertThat(out).as("%s", out).isInstanceOf(MavenWorkspace.Prepared.class);
        Result compile = runner.run(ws.offlineSpec(MavenWorkspace.offlineCommand("compile"), "x2 offline compile"));
        assertThat(compile.exitCode()).as(compile.stdoutTail() + compile.stderrTail()).isZero();
        assertThat(compile.timedOut()).isFalse();
    }

    @Test
    void owaspBenchmarkPrefetchesThenCompilesOfflineOrNamesTheBlocker() throws Exception {
        String dir = System.getenv(BENCHMARK_ENV);
        assumeTrue(dir != null && !dir.isBlank(), BENCHMARK_ENV + " not set");
        MavenWorkspace ws = new MavenWorkspace(runner, work("benchmark"),
            MavenWorkspace.Options.defaults().withAllowBuildExtensions(true));

        MavenWorkspace.Outcome out = ws.prepare(Path.of(dir));

        if (out instanceof MavenWorkspace.PrefetchIncomplete pi) {
            assertThat(pi.blocker()).isEqualTo(MavenWorkspace.BLOCKER);
            assertThat(pi.reason()).isNotBlank();
            return;
        }
        assertThat(out).as("%s", out).isInstanceOf(MavenWorkspace.Prepared.class);
        Result compile = runner.run(ws.offlineSpec(MavenWorkspace.offlineCommand("compile"), "x2 benchmark compile"));
        assertThat(compile.exitCode()).as(compile.stdoutTail() + compile.stderrTail()).isZero();
    }

    private static Path work(String name) throws IOException {
        return Files.createTempDirectory("secpipe-x2-" + name);
    }
}
