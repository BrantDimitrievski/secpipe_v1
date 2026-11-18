package ca.carleton.secpipe.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Capabilities;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Control;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Network;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Result;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.SandboxRefusedException;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Spec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenWorkspaceTest {

    /** Scripted runner: records every spec and answers with the queued results in order. */
    static final class FakeRunner implements SandboxRunner {
        final List<Spec> specs = new ArrayList<>();
        final Deque<Result> results = new ArrayDeque<>();
        Set<Control> refuse = Set.of();

        FakeRunner then(int exit, String stdout) {
            results.add(new Result(exit, false, stdout, "", List.of(), Duration.ofSeconds(1), Set.of()));
            return this;
        }

        @Override
        public Capabilities probe() {
            return new Capabilities(true, EnumSet.allOf(Control.class), Optional.empty(), Optional.of("test"));
        }

        @Override
        public Result run(Spec spec) throws SandboxRefusedException {
            specs.add(spec);
            if (!refuse.isEmpty()) {
                throw new SandboxRefusedException("refused", refuse);
            }
            return results.removeFirst();
        }
    }

    private static Path project(Path dir) throws IOException {
        Path target = dir.resolve("target-project");
        Files.createDirectories(target.resolve("src/main/java/x"));
        Files.writeString(target.resolve("pom.xml"), "<project/>");
        Files.writeString(target.resolve("src/main/java/x/A.java"), "class A {}");
        Files.createDirectories(target.resolve(".git"));
        Files.writeString(target.resolve(".git/config"), "secret-ish");
        Files.createDirectories(target.resolve("target/classes"));
        Files.writeString(target.resolve("target/classes/A.class"), "stale");
        return target;
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = MavenWorkspaceTest.class.getResourceAsStream("prefetch/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void completePrefetchReturnsPreparedAndUsesTwoPhaseSpecs(@TempDir Path dir) throws Exception {
        FakeRunner fake = new FakeRunner().then(0, "").then(0, "");
        MavenWorkspace ws = new MavenWorkspace(fake, dir.resolve("work"), MavenWorkspace.Options.defaults());

        MavenWorkspace.Outcome out = ws.prepare(project(dir));

        assertThat(out).isEqualTo(new MavenWorkspace.Prepared(ws.cacheDir(), ws.scratchDir()));
        assertThat(fake.specs).hasSize(2);
        Spec prefetch = fake.specs.get(0);
        assertThat(prefetch.network()).isEqualTo(Network.PREFETCH_ONLY);
        assertThat(prefetch.env()).isEmpty();
        assertThat(prefetch.mounts()).containsExactly(
            new SandboxRunner.Mount(ws.cacheDir(), MavenWorkspace.CACHE_MOUNT, false));
        assertThat(prefetch.required()).doesNotContain(Control.NETWORK_NONE, Control.READ_ONLY_TOOLCHAIN,
            Control.SCRATCH_ONLY_WRITES).contains(Control.NON_ROOT_USER, Control.WALL_CLOCK_LIMIT,
            Control.ENV_ALLOWLIST);
        assertThat(prefetch.command()).containsSequence("dependency:go-offline", "dependency:resolve-plugins",
            "dependency:resolve").contains("-Dmaven.repo.local=/cache/m2").doesNotContain("-o");
        Spec dry = fake.specs.get(1);
        assertThat(dry.network()).isEqualTo(Network.NONE);
        assertThat(dry.required()).isEqualTo(EnumSet.allOf(Control.class));
        assertThat(dry.mounts()).containsExactly(
            new SandboxRunner.Mount(ws.cacheDir(), MavenWorkspace.CACHE_MOUNT, true));
        assertThat(dry.command()).isEqualTo(MavenWorkspace.offlineCommand("dependency:build-classpath"));
    }

    @Test
    void scratchCopyExcludesGitAndTargetAndLeavesSourceUntouched(@TempDir Path dir) throws Exception {
        Path target = project(dir);
        MavenWorkspace ws = new MavenWorkspace(new FakeRunner().then(0, "").then(0, ""), dir.resolve("work"),
            MavenWorkspace.Options.defaults());
        ws.prepare(target);
        assertThat(ws.scratchDir().resolve("pom.xml")).exists();
        assertThat(ws.scratchDir().resolve("src/main/java/x/A.java")).hasContent("class A {}");
        assertThat(ws.scratchDir().resolve(".git")).doesNotExist();
        assertThat(ws.scratchDir().resolve("target")).doesNotExist();
        assertThat(target.resolve("target/classes/A.class")).exists();
        assertThat(ws.cacheDir()).isDirectory();
    }

    @Test
    void offlineResolutionFailureIsNamedPrefetchIncompleteBlocker(@TempDir Path dir) throws Exception {
        FakeRunner fake = new FakeRunner().then(0, "").then(1, fixture("benchmark-api-util-missing.log"));
        MavenWorkspace ws = new MavenWorkspace(fake, dir.resolve("work"), MavenWorkspace.Options.defaults());

        MavenWorkspace.Outcome out = ws.prepare(project(dir));

        assertThat(out).isInstanceOf(MavenWorkspace.PrefetchIncomplete.class);
        MavenWorkspace.PrefetchIncomplete pi = (MavenWorkspace.PrefetchIncomplete) out;
        assertThat(pi.blocker()).isEqualTo("prefetch-incomplete");
        assertThat(pi.missing()).containsExactly("org.apache.directory.api:api-util:jar:2.1.7");
        assertThat(pi.reason()).startsWith("offline dependency check failed with exit 1");
    }

    @Test
    void failedPrefetchIsIncompleteEvenIfDryCheckPasses(@TempDir Path dir) throws Exception {
        FakeRunner fake = new FakeRunner().then(1, "[ERROR] network unreachable").then(0, "");
        MavenWorkspace ws = new MavenWorkspace(fake, dir.resolve("work"), MavenWorkspace.Options.defaults());
        MavenWorkspace.Outcome out = ws.prepare(project(dir));
        assertThat(out).isInstanceOf(MavenWorkspace.PrefetchIncomplete.class);
        assertThat(((MavenWorkspace.PrefetchIncomplete) out).reason())
            .isEqualTo("prefetch failed with exit 1: [ERROR] network unreachable");
    }

    @Test
    void timedOutDryCheckIsIncompleteNotFailure(@TempDir Path dir) throws Exception {
        FakeRunner fake = new FakeRunner().then(0, "");
        fake.results.add(new Result(-1, true, "", "", List.of(), Duration.ofMinutes(5), Set.of()));
        MavenWorkspace.Outcome out = new MavenWorkspace(fake, dir.resolve("work"), MavenWorkspace.Options.defaults())
            .prepare(project(dir));
        assertThat(out).isEqualTo(new MavenWorkspace.PrefetchIncomplete(List.of(),
            "offline dependency check timed out"));
    }

    @Test
    void parsesPluginAndArtifactCoordinatesWithoutTheProjectItself() throws Exception {
        assertThat(MavenWorkspace.missingArtifacts(fixture("plugin-missing.log"))).containsExactly(
            "org.apache.maven.plugins:maven-compiler-plugin:3.13.0",
            "org.apache.maven.plugins:maven-compiler-plugin:jar:3.13.0");
        assertThat(MavenWorkspace.missingArtifacts(fixture("benchmark-api-util-missing.log")))
            .doesNotContain("org.owasp:benchmark:war:1.2");
        assertThat(MavenWorkspace.missingArtifacts("[INFO] g:a:jar:1 downloaded")).isEmpty();
    }

    @Test
    void buildExtensionsAreRefusedBeforeAnySandboxRunUnlessAllowed(@TempDir Path dir) throws Exception {
        Path target = project(dir);
        Files.createDirectories(target.resolve(".mvn"));
        Files.writeString(target.resolve(".mvn/extensions.xml"), "<extensions/>");
        FakeRunner refused = new FakeRunner();
        MavenWorkspace.Outcome out = new MavenWorkspace(refused, dir.resolve("w1"), MavenWorkspace.Options.defaults())
            .prepare(target);
        assertThat(out).isInstanceOf(MavenWorkspace.ExtensionsRefused.class);
        assertThat(((MavenWorkspace.ExtensionsRefused) out).message()).contains(".mvn/extensions.xml");
        assertThat(refused.specs).isEmpty();
        assertThat(dir.resolve("w1")).doesNotExist();

        FakeRunner allowed = new FakeRunner().then(0, "").then(0, "");
        MavenWorkspace.Outcome ok = new MavenWorkspace(allowed, dir.resolve("w2"),
            MavenWorkspace.Options.defaults().withAllowBuildExtensions(true)).prepare(target);
        assertThat(ok).isInstanceOf(MavenWorkspace.Prepared.class);
        assertThat(allowed.specs).hasSize(2);
    }

    @Test
    void sandboxRefusalPropagatesWithTheMissingControls(@TempDir Path dir) throws Exception {
        FakeRunner fake = new FakeRunner();
        fake.refuse = Set.of(Control.PID_LIMIT);
        MavenWorkspace ws = new MavenWorkspace(fake, dir.resolve("work"), MavenWorkspace.Options.defaults());
        Path target = project(dir);
        SandboxRefusedException e = assertThrows(SandboxRefusedException.class, () -> ws.prepare(target));
        assertThat(e.missing()).containsExactly(Control.PID_LIMIT);
    }

    @Test
    void offlineCommandUsesScratchRepoAndReadOnlyTail() {
        assertThat(MavenWorkspace.offlineCommand("compile", "-DskipTests")).containsExactly("mvn", "-o", "-B", "-q",
            "-Dmaven.repo.local=/scratch/m2", "-Dmaven.repo.local.tail=/cache/m2", "compile", "-DskipTests");
    }

    @Test
    void rejectsNonMavenTarget(@TempDir Path dir) {
        MavenWorkspace ws = new MavenWorkspace(new FakeRunner(), dir.resolve("work"),
            MavenWorkspace.Options.defaults());
        assertThatThrownBy(() -> ws.prepare(dir)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no pom.xml");
    }
}
