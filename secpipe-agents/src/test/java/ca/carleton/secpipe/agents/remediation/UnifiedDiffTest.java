package ca.carleton.secpipe.agents.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.carleton.secpipe.agents.remediation.UnifiedDiff.FilePatch;
import ca.carleton.secpipe.agents.remediation.UnifiedDiff.PatchException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class UnifiedDiffTest {

    @TempDir
    Path root;

    private Path write(String rel, String text) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    private static String numbered(int n, String eol) {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            b.append("line ").append(i).append(eol);
        }
        return b.toString();
    }

    static final String MULTI = """
            --- a/src/A.java
            +++ b/src/A.java
            @@ -2,3 +2,3 @@
             line 2
            -line 3
            +LINE THREE
             line 4
            @@ -14,4 +14,5 @@
             line 14
             line 15
            +inserted
             line 16
             line 17
            """;

    @Test
    void parsesFilesAndHunksStrippingPrefixes() {
        List<FilePatch> patches = UnifiedDiff.parse(MULTI);
        assertThat(patches).singleElement().satisfies(fp -> {
            assertThat(fp.oldPath()).isEqualTo("src/A.java");
            assertThat(fp.path()).isEqualTo("src/A.java");
            assertThat(fp.hunks()).hasSize(2);
            assertThat(fp.hunks().get(1).newCount()).isEqualTo(5);
        });
    }

    @Test
    void appliesMultipleHunks() throws IOException {
        Path a = write("src/A.java", numbered(20, "\n"));
        assertThat(UnifiedDiff.dryRun(root, MULTI).applies()).isTrue();
        assertThat(UnifiedDiff.apply(root, MULTI)).containsExactly("src/A.java");
        List<String> lines = Files.readAllLines(a);
        assertThat(lines).hasSize(21);
        assertThat(lines.get(2)).isEqualTo("LINE THREE");
        assertThat(lines.get(15)).isEqualTo("inserted");
        assertThat(Files.readString(a)).endsWith("line 20\n");
    }

    @Test
    void crlfFileKeepsCrlfAndCrlfDiffAppliesToLfFile() throws IOException {
        Path crlf = write("src/A.java", numbered(20, "\r\n"));
        UnifiedDiff.apply(root, MULTI);
        String text = Files.readString(crlf);
        assertThat(text).contains("line 2\r\nLINE THREE\r\nline 4\r\n").doesNotContain("\r\r");
        assertThat(text.replace("\r\n", "")).doesNotContain("\n");

        Path lf = write("src/B.java", numbered(20, "\n"));
        String crlfDiff = MULTI.replace("src/A.java", "src/B.java").replace("\n", "\r\n");
        UnifiedDiff.apply(root, crlfDiff);
        assertThat(Files.readString(lf)).doesNotContain("\r").contains("line 2\nLINE THREE\nline 4\n");
    }

    @Test
    void mismatchedContextIsRejectedAndNothingIsWritten() throws IOException {
        String original = numbered(20, "\n").replace("line 15\n", "line fifteen\n");
        Path a = write("src/A.java", original);
        UnifiedDiff.DryRun dry = UnifiedDiff.dryRun(root, MULTI);
        assertThat(dry.applies()).isFalse();
        assertThat(dry.problems()).singleElement().asString().contains("hunk 2").contains("fuzz 0");
        assertThatThrownBy(() -> UnifiedDiff.apply(root, MULTI)).isInstanceOf(PatchException.class);
        assertThat(Files.readString(a)).isEqualTo(original);
    }

    @Test
    void hunkFoundAtAnOffsetWithExactContext() throws IOException {
        Path a = write("src/A.java", "extra\nextra\n" + numbered(20, "\n"));
        UnifiedDiff.apply(root, MULTI);
        assertThat(Files.readAllLines(a).get(4)).isEqualTo("LINE THREE");
    }

    @Test
    void createsAndDeletesFiles() throws IOException {
        write("src/Old.java", "gone\n");
        String diff = """
                --- /dev/null
                +++ b/src/New.java
                @@ -0,0 +1,2 @@
                +class New {
                +}
                --- a/src/Old.java
                +++ /dev/null
                @@ -1,1 +0,0 @@
                -gone
                """;
        assertThat(UnifiedDiff.apply(root, diff)).containsExactly("src/New.java", "src/Old.java");
        assertThat(Files.readString(root.resolve("src/New.java"))).isEqualTo("class New {\n}\n");
        assertThat(root.resolve("src/Old.java")).doesNotExist();
    }

    @Test
    void malformedDiffsAreRejected() {
        assertThatThrownBy(() -> UnifiedDiff.parse("hello\n")).isInstanceOf(PatchException.class);
        assertThatThrownBy(() -> UnifiedDiff.parse("--- a/x\n+++ b/x\n")).hasMessageContaining("no hunks");
        assertThatThrownBy(() -> UnifiedDiff.parse("--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@\n-a\n+b\n"))
                .hasMessageContaining("header says");
    }

    @Test
    void pathsOutsideTheRootAreRefused() throws IOException {
        Path repo = Files.createDirectories(root.resolve("repo"));
        Path outside = write("outside.txt", "x\n");
        String diff = "--- a/../outside.txt\n+++ b/../outside.txt\n@@ -1,1 +1,1 @@\n-x\n+y\n";
        assertThat(UnifiedDiff.dryRun(repo, diff).problems()).singleElement().asString().contains("..");
        assertThatThrownBy(() -> UnifiedDiff.apply(repo, diff)).isInstanceOf(PatchException.class);
        assertThat(Files.readString(outside)).isEqualTo("x\n");
    }

    @Test
    void noNewlineMarkerIsHonoured() throws IOException {
        Path a = write("a.txt", "one\ntwo");
        String diff = "--- a/a.txt\n+++ b/a.txt\n@@ -1,2 +1,2 @@\n one\n-two\n\\ No newline at end of file\n+TWO\n"
                + "\\ No newline at end of file\n";
        UnifiedDiff.apply(root, diff);
        assertThat(Files.readString(a)).isEqualTo("one\nTWO");
    }
}
