package ca.carleton.secpipe.core.env;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.pipeline.ProjectProfile;
import ca.carleton.secpipe.contracts.pipeline.ProjectProfile.BuildTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectScannerTest {

    private static final Path FIXTURE = Path.of("..", "fixtures", "mvp-samples", "vulnerable-java-app");

    @TempDir
    Path tmp;

    private Path touch(String rel, String content) throws IOException {
        Path p = tmp.resolve(rel);
        Files.createDirectories(p.getParent());
        return Files.writeString(p, content);
    }

    @Test
    void mavenWithoutWrapper() throws IOException {
        touch("pom.xml", "<project/>");
        assertThat(ProjectScanner.detectBuildTool(tmp)).isEqualTo(BuildTool.MAVEN);
    }

    @Test
    void mavenWithWrapper() throws IOException {
        touch("pom.xml", "<project/>");
        touch("mvnw", "");
        assertThat(ProjectScanner.detectBuildTool(tmp)).isEqualTo(BuildTool.MAVEN_WRAPPER);
    }

    @Test
    void gradleWithoutWrapper() throws IOException {
        touch("build.gradle.kts", "");
        assertThat(ProjectScanner.detectBuildTool(tmp)).isEqualTo(BuildTool.GRADLE);
    }

    @Test
    void gradleWithWrapper() throws IOException {
        touch("settings.gradle", "");
        touch("gradlew.bat", "");
        assertThat(ProjectScanner.detectBuildTool(tmp)).isEqualTo(BuildTool.GRADLE_WRAPPER);
    }

    @Test
    void noBuildTool() throws IOException {
        touch("src/main/java/A.java", "class A {}");
        assertThat(ProjectScanner.detectBuildTool(tmp)).isEqualTo(BuildTool.NONE);
    }

    @Test
    void testSourceClassification() {
        assertThat(ProjectScanner.isTestSource("src/test/java/a/ATest.java")).isTrue();
        assertThat(ProjectScanner.isTestSource("mod/src/test/java/a/ATest.java")).isTrue();
        assertThat(ProjectScanner.isTestSource("test/A.java")).isTrue();
        assertThat(ProjectScanner.isTestSource("src/integrationTest/java/A.java")).isTrue();
        assertThat(ProjectScanner.isTestSource("src/it/A.java")).isTrue();
        assertThat(ProjectScanner.isTestSource("src/main/java/a/Contest.java")).isFalse();
        assertThat(ProjectScanner.isTestSource("src\\test\\java\\A.java")).isTrue();
    }

    @Test
    void windowsPathNormalization() {
        assertThat(ProjectScanner.normalize("src\\main\\java\\A.java")).isEqualTo("src/main/java/A.java");
        assertThat(ProjectScanner.normalize(".\\src\\A.java")).isEqualTo("src/A.java");
        assertThat(ProjectScanner.normalize("a/b.java")).isEqualTo("a/b.java");
    }

    @Test
    void scanSeparatesTestsAndIgnoresBuildDirs() throws IOException {
        touch("pom.xml", "<project><artifactId>spring-boot-starter</artifactId></project>");
        touch("src/main/java/a/A.java", "class A {\n}\n");
        touch("src/test/java/a/ATest.java", "class ATest {\n}\n");
        touch("target/classes/Gen.java", "class Gen {}");
        touch("node_modules/x/Y.java", "class Y {}");
        touch("sub/pom.xml", "<project/>");

        ProjectProfile p = ProjectScanner.scan(tmp, false);
        assertThat(p.sourceFiles()).containsExactly("src/main/java/a/A.java");
        assertThat(p.testSourceFiles()).containsExactly("src/test/java/a/ATest.java");
        assertThat(p.manifests()).containsExactly("pom.xml", "sub/pom.xml");
        assertThat(p.linesOfCode()).isEqualTo(2);
        assertThat(p.frameworks()).containsExactly("spring-boot");
        assertThat(p.buildTool()).isEqualTo(BuildTool.MAVEN);

        ProjectProfile withTests = ProjectScanner.scan(tmp, true);
        assertThat(withTests.sourceFiles()).containsExactly("src/main/java/a/A.java", "src/test/java/a/ATest.java");
        assertThat(withTests.linesOfCode()).isEqualTo(4);
    }

    @Test
    void vulnerableJavaAppFixture() throws IOException {
        ProjectProfile p = ProjectScanner.scan(FIXTURE, false);
        assertThat(p.buildTool()).isEqualTo(BuildTool.MAVEN);
        assertThat(p.sourceFiles()).hasSizeGreaterThanOrEqualTo(8);
        assertThat(p.sourceFiles()).allMatch(f -> f.startsWith("src/main/java/") && !f.contains("\\"));
        assertThat(p.manifests()).containsExactly("pom.xml");
        long expected = 0;
        for (String f : p.sourceFiles()) {
            try (var lines = Files.lines(FIXTURE.resolve(f))) {
                expected += lines.count();
            }
        }
        assertThat(p.linesOfCode()).isPositive().isEqualTo(expected);
        assertThat(List.copyOf(p.sourceFiles())).isSorted();
    }
}
