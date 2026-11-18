package ca.carleton.secpipe.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.spi.SandboxRunner.Capabilities;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Control;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Limits;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Network;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Promotion;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Result;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Spec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Live Docker checks (WO-X.1 [DOCKER] acceptance). Runs only with {@code -Psandbox}; CI builds the runner image. */
@Tag("sandbox")
class DockerSandboxRunnerSandboxTest {

    private static final Set<Control> ALL = EnumSet.allOf(Control.class);
    private static final Limits SMALL = new Limits(1.0, 256, 128, 16, 64, Duration.ofSeconds(60));

    private final DockerCli cli = DockerCli.system();
    private final DockerSandboxRunner runner = new DockerSandboxRunner(cli, DockerSandboxRunner.defaultImage(),
        EnvAllowlist.DEFAULT);

    @Test
    void probeReportsAllThirteenControlsEnforcedOnLinux() {
        Capabilities c = runner.probe();
        assertTrue(c.available(), c.reason().orElse(""));
        assertEquals(ALL, c.enforceable().isEmpty() ? Set.of() : EnumSet.copyOf(c.enforceable()),
            c.reason().orElse(""));
    }

    @Test
    void runCopiesScratchInAndPromotesOnlyDeclaredFiles(@TempDir Path scratch) throws Exception {
        Files.writeString(scratch.resolve("in.txt"), "hello");
        List<String> cmd = List.of("sh", "-c", "cat in.txt > out.txt && echo extra > undeclared.txt "
            + "&& ln -s /etc/passwd link.txt && id -u");
        Result r = runner.run(new Spec("", cmd, scratch, List.of(), Map.of("LANG", "C.UTF-8"), SMALL, Network.NONE,
            ALL, List.of(new Promotion("out.txt", 100), new Promotion("link.txt", 100)), "x1 sandbox happy"));
        assertEquals(0, r.exitCode(), r.stderrTail());
        assertEquals("10001", r.stdoutTail().strip());
        assertEquals("hello", Files.readString(scratch.resolve("out.txt")));
        assertFalse(Files.exists(scratch.resolve("undeclared.txt")));
        assertFalse(Files.exists(scratch.resolve("link.txt"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals(ALL, EnumSet.copyOf(r.enforced()));
    }

    @Test
    void imageJunitConsoleJarMatchesTheInRepoPin(@TempDir Path scratch) throws Exception {
        Path pin = Path.of("..", "sandbox-images", "runner", "junit-platform-console-standalone.sha256");
        String expected = Files.readString(pin).strip();
        Result r = runner.run(new Spec("", List.of("sh", "-c", "sha256sum \"$(readlink -f \"$JUNIT_CONSOLE_JAR\")\""),
            scratch, List.of(), Map.of(), SMALL, Network.NONE, ALL, List.of(), "x1 junit pin"));
        assertEquals(0, r.exitCode(), r.stderrTail());
        assertEquals(expected, r.stdoutTail().strip().split("\\s+")[0], "sha256 of the image's JUnit console jar");
    }

    @Test
    void timedOutContainerIsKilledAndGone(@TempDir Path scratch) throws Exception {
        Limits quick = new Limits(1.0, 256, 128, 16, 64, Duration.ofSeconds(5));
        String purpose = "x1 timeout " + System.nanoTime();
        Result r = runner.run(new Spec("", List.of("sh", "-c", "trap '' TERM; sleep 120"), scratch, List.of(),
            Map.of(), quick, Network.NONE, ALL, List.of(), purpose));
        assertTrue(r.timedOut());
        String prefix = DockerSandboxRunner.containerName(purpose);
        prefix = prefix.substring(0, prefix.lastIndexOf('-'));
        DockerCli.Outcome ps = cli.run(List.of("docker", "ps", "-a", "-q", "--filter", "name=" + prefix),
            Duration.ofSeconds(30));
        assertEquals(0, ps.exitCode(), ps.stderrTail());
        assertEquals("", ps.stdoutTail().strip(), "docker ps -a by name must be empty");
    }
}
