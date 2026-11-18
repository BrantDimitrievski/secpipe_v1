package ca.carleton.secpipe.core.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.config.ConfigResolver;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.ProjectProfile;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.core.engine.DefaultRunContext;
import ca.carleton.secpipe.core.engine.NodeLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SetupNodeTest {

    @TempDir
    Path tmp;

    private Path project() throws IOException {
        Path root = tmp.resolve("proj");
        write(root.resolve("pom.xml"), "<project><dependency>javax.servlet</dependency>"
                + "<dependency>junit-jupiter</dependency></project>");
        write(root.resolve("src/main/java/a/A.java"), "package a;\nclass A {\n}\n");
        write(root.resolve("src/main/java/a/B.java"), "package a;\nclass B {}\n");
        write(root.resolve("src/test/java/a/ATest.java"), "package a;\nclass ATest {}\n");
        write(root.resolve("src/main/java/secrets/Key.java"), "class Key {}\n");
        write(root.resolve("src/main/java/gen/Gen.java"), "class Gen {}\n");
        write(root.resolve("prod.env"), "X=1\n");
        write(root.resolve("target/classes/Old.java"), "class Old {}\n");
        return root;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static SecPipeConfig config(Map<String, String> overrides) {
        return ConfigResolver.resolve(overrides, Map.of(), Optional.empty());
    }

    private StateDelta run(SecPipeConfig config, Map<String, String> env) {
        PipelineState state = new PipelineState("run-1", config, Optional.empty(), Optional.empty(), List.of(),
                List.of(), List.of(), Map.of(), Map.of(), List.of(), Map.of(), 0, 0, false, Map.of(), Optional.empty(),
                List.of(), Optional.empty());
        DefaultRunContext ctx = DefaultRunContext.builder().workDir(tmp.resolve("out/.work"))
                .outDir(tmp.resolve("out")).build();
        PipelineNode.Result result = new SetupNode(env).apply(state, ctx);
        assertThat(result.label()).isEqualTo(PipelineGraph.NEXT);
        return result.delta();
    }

    @Test
    void profilesSourcesTestsManifestsAndBuildTool() throws IOException {
        Path root = project();
        StateDelta d = run(config(Map.of("target", root.toString())), Map.of());

        ProjectProfile p = d.profile().orElseThrow();
        assertThat(p.root()).isEqualTo(root.toAbsolutePath().normalize());
        assertThat(p.sourceFiles()).containsExactly("src/main/java/a/A.java", "src/main/java/a/B.java",
                "src/main/java/gen/Gen.java");
        assertThat(p.testSourceFiles()).containsExactly("src/test/java/a/ATest.java");
        assertThat(p.manifests()).containsExactly("pom.xml");
        assertThat(p.buildTool()).isEqualTo(ProjectProfile.BuildTool.MAVEN);
        assertThat(p.frameworks()).containsExactlyInAnyOrder("servlet", "junit5");
        assertThat(p.linesOfCode()).isEqualTo(3 + 2 + 1);
        assertThat(d.incompleteReason()).isEmpty();
        assertThat(d.diffScope()).contains(List.of());
    }

    @Test
    void builtInAndConfiguredDenyGlobsHideFiles() throws IOException {
        Path root = project();
        StateDelta d = run(config(Map.of("target", root.toString(), "deny-path", "**/gen/**")), Map.of());

        assertThat(d.profile().orElseThrow().sourceFiles())
                .containsExactly("src/main/java/a/A.java", "src/main/java/a/B.java")
                .noneMatch(f -> f.contains("secrets"));
        ProjectProfiler profiler = new ProjectProfiler(List.of());
        assertThat(profiler.denied("prod.env")).isTrue();
        assertThat(profiler.denied("conf/server.pem")).isTrue();
        assertThat(profiler.denied("home/.ssh/id_rsa.pub")).isTrue();
        assertThat(profiler.denied("src/main/java/a/A.java")).isFalse();
    }

    @Test
    void wrapperWinsOverPomAndNoManifestMeansNone() throws IOException {
        Path root = project();
        write(root.resolve("mvnw"), "#!/bin/sh\n");
        assertThat(ProjectProfiler.buildTool(root)).isEqualTo(ProjectProfile.BuildTool.MAVEN_WRAPPER);
        assertThat(ProjectProfiler.buildTool(tmp)).isEqualTo(ProjectProfile.BuildTool.NONE);
        StateDelta d = run(config(Map.of("target", root.toString())), Map.of());
        assertThat(d.environment().orElseThrow().mavenWrapper()).isTrue();
    }

    @Test
    void environmentRecordsJdkGithubActionsAndToolsOnPathWithoutDocker() throws IOException {
        Path bin = tmp.resolve("bin");
        write(bin.resolve("semgrep"), "#!/bin/sh\n");
        bin.resolve("semgrep").toFile().setExecutable(true);
        write(bin.resolve("not-a-tool"), "#!/bin/sh\n");
        Map<String, String> env = new HashMap<>();
        env.put("PATH", bin.toString());
        env.put("GITHUB_ACTIONS", "true");

        StateDelta d = run(config(Map.of("target", project().toString())), env);

        var e = d.environment().orElseThrow();
        assertThat(e.javaVersion()).isEqualTo(System.getProperty("java.version"));
        assertThat(e.githubActions()).isTrue();
        assertThat(e.dockerAvailable()).isFalse();
        assertThat(e.toolsOnPath()).containsOnlyKeys("semgrep");
    }

    @Test
    void missingTargetMakesTheRunIncompleteWithoutThrowing() {
        StateDelta d = run(config(Map.of("target", tmp.resolve("nope").toString())), Map.of());

        assertThat(d.incompleteReason()).hasValueSatisfying(r -> assertThat(r).contains("is not a directory"));
        assertThat(d.profile().orElseThrow().sourceFiles()).isEmpty();
    }

    @Test
    void diffScopeFallsBackToTheFullTargetWithANote() throws IOException {
        StateDelta d = run(config(Map.of("target", project().toString(), "scope", "diff", "base-ref", "main")),
                Map.of());

        assertThat(d.notes()).contains(SetupNode.DIFF_SCOPE_NOTE);
        assertThat(d.diffScope()).contains(List.of());
    }

    @Test
    void unreadablePriorRunIsANoteNotAFailure() throws IOException {
        Path prior = Files.createDirectories(tmp.resolve("prior"));
        StateDelta d = run(config(Map.of("target", project().toString(), "prior-run", prior.toString())), Map.of());

        assertThat(d.prior()).isEmpty();
        assertThat(d.notes()).anyMatch(n -> n.startsWith("setup: prior run in "));
        assertThat(d.incompleteReason()).isEmpty();
    }

    @Test
    void engineLoadsSetupFromThePipelineGraph() {
        assertThat(new NodeLoader().load("setup")).hasValueSatisfying(n -> {
            assertThat(n).isInstanceOf(SetupNode.class);
            assertThat(n.requiresLlm()).isFalse();
            assertThat(n.usesSandbox()).isFalse();
        });
    }
}
