package ca.carleton.secpipe.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildExtensionPreflightTest {

    private static final String PLAIN_POM = "<project><build><plugins><plugin><artifactId>x</artifactId>"
        + "</plugin></plugins></build></project>\n";

    private static List<String> paths(List<BuildExtensionPreflight.Finding> findings) {
        return findings.stream().map(BuildExtensionPreflight.Finding::path).toList();
    }

    @Test
    void plainProjectHasNoFindings(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), PLAIN_POM);
        assertThat(BuildExtensionPreflight.check(root)).isEmpty();
    }

    @Test
    void mvnExtensionsXmlIsFlagged(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), PLAIN_POM);
        Files.createDirectories(root.resolve(".mvn"));
        Files.writeString(root.resolve(".mvn/extensions.xml"), "<extensions/>");
        assertThat(paths(BuildExtensionPreflight.check(root))).containsExactly(".mvn/extensions.xml");
    }

    @Test
    void pluginExtensionsInNestedModuleAreFlaggedAndSorted(@TempDir Path root) throws Exception {
        String ext = "<project><build><plugins><plugin><extensions>\n  true\n</extensions></plugin></plugins>"
            + "</build></project>";
        Files.writeString(root.resolve("pom.xml"), ext);
        Files.createDirectories(root.resolve("mod-b"));
        Files.writeString(root.resolve("mod-b/pom.xml"), ext);
        Files.createDirectories(root.resolve("mod-a"));
        Files.writeString(root.resolve("mod-a/pom.xml"), PLAIN_POM);
        List<BuildExtensionPreflight.Finding> f = BuildExtensionPreflight.check(root);
        assertThat(paths(f)).containsExactly("mod-b/pom.xml", "pom.xml");
        assertThat(BuildExtensionPreflight.message(f)).contains("mod-b/pom.xml")
            .contains("\nhint: ").contains("--allow-build-extensions").contains("remote parent POM");
    }

    @Test
    void commentedOutOrFalseExtensionsAreIgnored(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><!-- <extensions>true</extensions> -->"
            + "<build><plugins><plugin><extensions>false</extensions></plugin></plugins></build></project>");
        assertThat(BuildExtensionPreflight.check(root)).isEmpty();
    }

    @Test
    void pomsUnderTargetAreIgnored(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), PLAIN_POM);
        Files.createDirectories(root.resolve("target/classes"));
        Files.writeString(root.resolve("target/classes/pom.xml"), "<extensions>true</extensions>");
        assertThat(BuildExtensionPreflight.check(root)).isEmpty();
    }

    @Test
    void standardBuildExtensionsBlockIsFlagged(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
            <project>
              <build>
                <extensions>
                  <extension>
                    <groupId>kr.motd.maven</groupId>
                    <artifactId>os-maven-plugin</artifactId>
                    <version>1.7.1</version>
                  </extension>
                </extensions>
              </build>
            </project>
            """);
        List<BuildExtensionPreflight.Finding> f = BuildExtensionPreflight.check(root);
        assertThat(paths(f)).containsExactly("pom.xml");
        assertThat(f.get(0).reason()).contains("<build><extensions>");
        assertThat(BuildExtensionPreflight.declaresExtensions("<build><extensions><extension "
            + "x=\"1\"/></extensions></build>")).isTrue();
    }

    @Test
    void emptyOrCommentedBuildExtensionsAreIgnored(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><build><extensions>\n</extensions>"
            + "<extensions><!-- <extension><artifactId>x</artifactId></extension> --></extensions>"
            + "</build><!-- <build><extensions><extension/></extensions></build> --></project>");
        assertThat(BuildExtensionPreflight.check(root)).isEmpty();
    }
}
