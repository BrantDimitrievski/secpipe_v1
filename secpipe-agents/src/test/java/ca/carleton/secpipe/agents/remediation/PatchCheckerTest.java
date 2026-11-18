package ca.carleton.secpipe.agents.remediation;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.agents.remediation.PatchChecker.Kind;
import ca.carleton.secpipe.agents.remediation.PatchChecker.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class PatchCheckerTest {

    static final String TEST = "src/test/java/secpipe/generated/LoginServletSqliSecurityTest.java";

    @TempDir
    Path root;

    /** A one-line replacement diff for {@code path}, adding {@code added}. */
    static String edit(String path, String... added) {
        StringBuilder b = new StringBuilder("--- a/").append(path).append("\n+++ b/").append(path).append('\n');
        b.append("@@ -1,1 +1,").append(added.length).append(" @@\n-old\n");
        for (String a : added) {
            b.append('+').append(a).append('\n');
        }
        return b.toString();
    }

    private List<Kind> kinds(String diff) {
        return check(diff).stream().map(Violation::kind).toList();
    }

    private List<Violation> check(String diff) {
        return PatchChecker.check(diff, root, TEST);
    }

    @Test
    void cleanSourceEditPasses() {
        assertThat(check(edit("src/main/java/com/shop/app/LoginServlet.java", "new"))).isEmpty();
    }

    @Test
    void unparseableDiffIsRejected() {
        assertThat(kinds("not a diff\n")).containsExactly(Kind.UNPARSEABLE);
    }

    @Test
    void moreThanFiveFilesIsRejected() {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            b.append(edit("src/main/java/F" + i + ".java", "x"));
        }
        assertThat(kinds(b.toString())).containsExactly(Kind.TOO_MANY_FILES);
        StringBuilder five = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            five.append(edit("src/main/java/F" + i + ".java", "x"));
        }
        assertThat(check(five.toString())).isEmpty();
    }

    @Test
    void pathsOutsideTheRepoAreRejected() {
        assertThat(kinds(edit("../evil/A.java", "x"))).containsExactly(Kind.OUTSIDE_REPO);
        assertThat(kinds("--- /etc/passwd\n+++ /etc/passwd\n@@ -1,1 +1,1 @@\n-old\n+x\n"))
                .containsExactly(Kind.OUTSIDE_REPO);
        assertThat(kinds(edit("src/main/../../../x.java", "x"))).containsExactly(Kind.OUTSIDE_REPO);
    }

    @Test
    void symlinkEscapeIsRejected() throws IOException {
        Path outside = Files.createTempDirectory("secpipe-outside");
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // file system without symlinks: nothing to escape through
        }
        assertThat(kinds(edit("link/A.java", "x"))).containsExactly(Kind.OUTSIDE_REPO);
    }

    @Test
    void regressionTestEditIsRejected() {
        assertThat(kinds(edit(TEST, "x"))).containsExactlyInAnyOrder(Kind.REGRESSION_TEST, Kind.TEST_SOURCE);
    }

    @Test
    void otherTestSourcesAreRejected() {
        assertThat(kinds(edit("module/src/test/java/OtherTest.java", "x"))).containsExactly(Kind.TEST_SOURCE);
    }

    @Test
    void buildFilesAreRejected() {
        for (String p : List.of("pom.xml", "module/pom.xml", "mvnw", "mvnw.cmd",
                ".mvn/wrapper/maven-wrapper.properties", ".mvn/extensions.xml", "build.gradle")) {
            assertThat(kinds(edit(p, "x"))).as(p).containsExactly(Kind.BUILD_FILE);
        }
    }

    @Test
    void suppressWarningsIsRejected() {
        assertThat(kinds(edit("src/main/java/A.java", "@SuppressWarnings(\"all\")", "void m() { }")))
                .containsExactly(Kind.SUPPRESS_WARNINGS);
    }

    @Test
    void emptyCatchBlocksAreRejected() {
        assertThat(kinds(edit("src/main/java/A.java", "try { run(); } catch (Exception e) { }")))
                .containsExactly(Kind.EMPTY_CATCH);
        assertThat(kinds(edit("src/main/java/A.java", "try {", "    run();", "} catch (SQLException e) {",
                "    // ignored", "}"))).containsExactly(Kind.EMPTY_CATCH);
        assertThat(check(edit("src/main/java/A.java", "try { run(); } catch (SQLException e) {",
                "    throw new IllegalStateException(e);", "}"))).isEmpty();
    }

    @Test
    void preExistingEmptyCatchInContextIsNotBlamedOnTheFix() {
        String diff = "--- a/src/main/java/A.java\n+++ b/src/main/java/A.java\n@@ -1,2 +1,2 @@\n"
                + " try { run(); } catch (Exception e) { }\n-int x = 1;\n+int x = 2;\n";
        assertThat(check(diff)).isEmpty();
    }
}
