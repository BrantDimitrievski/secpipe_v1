package ca.carleton.secpipe.core.env;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.spi.EnvironmentInfo;
import ca.carleton.secpipe.core.env.DoctorReport.Check;
import ca.carleton.secpipe.core.env.DoctorReport.Status;
import ca.carleton.secpipe.core.util.Proc;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvironmentDetectorTest {

    @TempDir
    Path tmp;

    private Map<String, String> emptyPathEnv() throws IOException {
        Path empty = Files.createDirectory(tmp.resolve("emptybin"));
        return Map.of("PATH", empty.toString());
    }

    @Test
    void missingDockerBinaryReportsUnavailableWithHint() throws IOException {
        Map<String, String> env = emptyPathEnv();
        EnvironmentInfo info = new EnvironmentDetector().detect(env);
        assertThat(info.dockerAvailable()).isFalse();
        assertThat(info.dockerVersion()).isEmpty();
        assertThat(info.toolsOnPath()).isEmpty();

        Check docker = new EnvironmentDetector().doctor(env, null).checks().stream()
                .filter(c -> c.id().equals("docker")).findFirst().orElseThrow();
        assertThat(docker.status()).isEqualTo(Status.WARN);
        assertThat(docker.hint()).contains("install Docker");
    }

    @Test
    void toolsOnPathAreFoundAndOthersAreNot() throws IOException {
        Path bin = Files.createDirectory(tmp.resolve("bin"));
        Path semgrep = Files.createFile(bin.resolve("semgrep"));
        assertThat(semgrep.toFile().setExecutable(true)).isTrue();
        EnvironmentInfo info = new EnvironmentDetector(false).detect(Map.of("PATH", bin.toString()));
        assertThat(info.toolsOnPath()).containsOnlyKeys("semgrep");
        assertThat(info.toolsOnPath().get("semgrep")).isEqualTo(semgrep);
    }

    @Test
    void windowsPathextIsHonoured() throws IOException {
        Path bin = Files.createDirectory(tmp.resolve("winbin"));
        Path exe = Files.createFile(bin.resolve("trivy.exe"));
        Map<String, String> env = Map.of("Path", bin.toString(), "PATHEXT", ".EXE;.CMD");
        assertThat(Proc.which("trivy", env, true)).contains(exe);
        assertThat(Proc.which("trivy", env, false)).isEmpty();
        assertThat(Proc.which("grype", env, true)).isEmpty();
    }

    @Test
    void githubActionsFlagComesFromEnv() throws IOException {
        Map<String, String> env = Map.of("PATH", emptyPathEnv().get("PATH"), "GITHUB_ACTIONS", "true");
        assertThat(new EnvironmentDetector().detect(env).githubActions()).isTrue();
    }

    @Test
    void hostJavaVersionIsReportedAndMeetsMinimum() throws IOException {
        EnvironmentInfo info = new EnvironmentDetector().detect(emptyPathEnv());
        assertThat(info.javaVersion()).isEqualTo(Runtime.version().toString());
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(EnvironmentDetector.MIN_JAVA);
        DoctorReport r = new EnvironmentDetector().doctor(emptyPathEnv(), null);
        assertThat(r.checks()).anyMatch(c -> c.id().equals("java.host") && c.status() == Status.OK);
    }

    @Test
    void javaFeatureParsing() {
        assertThat(EnvironmentDetector.parseJavaFeature("openjdk version \"21.0.2\" 2024-01-16")).isEqualTo(21);
        assertThat(EnvironmentDetector.parseJavaFeature("java version \"1.8.0_392\"")).isEqualTo(8);
        assertThat(EnvironmentDetector.parseJavaFeature("garbage")).isEqualTo(-1);
    }

    @Test
    void missingJavaHomeFolderFailsWithHint() throws IOException {
        Map<String, String> env = Map.of("PATH", emptyPathEnv().get("PATH"),
                "JAVA_HOME", tmp.resolve("nope").toString());
        DoctorReport r = new EnvironmentDetector().doctor(env, null);
        Check c = r.checks().stream().filter(x -> x.id().equals("java.home")).findFirst().orElseThrow();
        assertThat(c.status()).isEqualTo(Status.FAIL);
        assertThat(c.hint()).isNotBlank();
        assertThat(r.ready()).isFalse();
        assertThat(r.failures()).isEqualTo(1);
    }

    @Test
    void javaHomeWithoutJavaBinaryFails() throws IOException {
        Path home = Files.createDirectory(tmp.resolve("jdk"));
        Map<String, String> env = Map.of("PATH", emptyPathEnv().get("PATH"), "JAVA_HOME", home.toString());
        Check c = new EnvironmentDetector().doctor(env, null).checks().stream()
                .filter(x -> x.id().equals("java.home")).findFirst().orElseThrow();
        assertThat(c.status()).isEqualTo(Status.FAIL);
        assertThat(c.detail()).contains("bin/java");
    }

    @Test
    void wrapperVersionIsReadFromPropertiesWithoutExecutingWrapper() throws IOException {
        Path proj = Files.createDirectory(tmp.resolve("proj"));
        // If the wrapper were executed this marker file would be created.
        Path marker = tmp.resolve("EXECUTED");
        Files.writeString(proj.resolve("mvnw"), "#!/bin/sh\ntouch " + marker + "\n");
        assertThat(proj.resolve("mvnw").toFile().setExecutable(true)).isTrue();
        Files.createDirectories(proj.resolve(".mvn/wrapper"));
        Files.writeString(proj.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=https\\://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/"
                        + "apache-maven-3.9.9-bin.zip\n");
        EnvironmentInfo info = new EnvironmentDetector().detect(emptyPathEnv(), proj);
        assertThat(info.mavenWrapper()).isTrue();
        assertThat(info.mavenVersion()).isEqualTo(Optional.of("3.9.9"));
        DoctorReport r = new EnvironmentDetector().doctor(emptyPathEnv(), proj);
        assertThat(r.checks()).anyMatch(c -> c.id().equals("maven.wrapper") && c.detail().contains("3.9.9"));
        assertThat(marker).doesNotExist();
    }

    @Test
    void procCapsOutputAndTimesOut() throws IOException {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path src = tmp.resolve("Big.java");
        Files.writeString(src, "public class Big { public static void main(String[] a) throws Exception {"
                + " if (a.length > 0) { Thread.sleep(60000); }"
                + " byte[] b = new byte[2_000_000]; java.util.Arrays.fill(b, (byte) 'x'); System.out.write(b); "
                + "System.out.flush(); } }");
        Proc.Result big = Proc.run(List.of(javaBin, src.toString()), null, Duration.ofSeconds(60));
        assertThat(big.ok()).isTrue();
        assertThat(big.stdout().length()).isEqualTo(Proc.MAX_OUTPUT_BYTES);

        Proc.Result slow = Proc.run(List.of(javaBin, src.toString(), "sleep"), null, Duration.ofSeconds(3));
        assertThat(slow.timedOut()).isTrue();
        assertThat(slow.ok()).isFalse();
    }
}
