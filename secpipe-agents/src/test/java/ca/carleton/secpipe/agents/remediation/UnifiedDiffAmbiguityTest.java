package ca.carleton.secpipe.agents.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.carleton.secpipe.agents.remediation.UnifiedDiff.PatchException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** WO-G.12: a hunk whose context matches in more than one place is refused instead of guessed. */
class UnifiedDiffAmbiguityTest {

    /** Lines 1-9: x A B y A B z w v (the pair "A","B" occurs at lines 2 and 5). */
    private static final String FILE = "x\nA\nB\ny\nA\nB\nz\nw\nv\n";

    @TempDir
    Path root;

    private Path write(String text) throws IOException {
        Path p = root.resolve("src/F.java");
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    private static String diff(int oldStart) {
        return "--- a/src/F.java\n+++ b/src/F.java\n@@ -" + oldStart + ",2 +" + oldStart + ",2 @@\n A\n-B\n+b\n";
    }

    @Test
    void contextMatchingTwiceAwayFromTheHeaderIsRejectedAndNothingIsWritten() throws IOException {
        Path f = write(FILE);

        UnifiedDiff.DryRun dry = UnifiedDiff.dryRun(root, diff(8));
        assertThat(dry.applies()).isFalse();
        assertThat(dry.problems()).singleElement().asString()
                .contains("hunk 1: ambiguous context (matches at lines 2, 5)");
        assertThatThrownBy(() -> UnifiedDiff.apply(root, diff(8))).isInstanceOf(PatchException.class)
                .hasMessageContaining("ambiguous context");
        assertThat(Files.readString(f)).isEqualTo(FILE);
    }

    @Test
    void headerLineMatchIsPreferredEvenWhenTheContextAlsoMatchesElsewhere() throws IOException {
        Path f = write(FILE);
        UnifiedDiff.apply(root, diff(5));
        assertThat(Files.readAllLines(f)).containsExactly("x", "A", "B", "y", "A", "b", "z", "w", "v");

        write(FILE);
        UnifiedDiff.apply(root, diff(2));
        assertThat(Files.readAllLines(f)).containsExactly("x", "A", "b", "y", "A", "B", "z", "w", "v");
    }

    @Test
    void aSingleMatchAwayFromTheHeaderIsStillAccepted() throws IOException {
        Path f = write("pad\npad\npad\n" + "x\nA\nB\ny\n");
        UnifiedDiff.apply(root, diff(1));
        assertThat(Files.readAllLines(f)).containsExactly("pad", "pad", "pad", "x", "A", "b", "y");
    }

    @Test
    void ambiguityIsJudgedAfterThePreviousHunk() throws IOException {
        // the first hunk consumes the first "A","B"; the second only has the later copy left to match
        Path f = write(FILE);
        String two = "--- a/src/F.java\n+++ b/src/F.java\n"
                + "@@ -2,2 +2,2 @@\n A\n-B\n+b\n"
                + "@@ -9,2 +9,2 @@\n A\n-B\n+b2\n";
        UnifiedDiff.apply(root, two);
        assertThat(Files.readAllLines(f)).containsExactly("x", "A", "b", "y", "A", "b2", "z", "w", "v");
    }

    @Test
    void messageListsEveryMatchingLine() throws IOException {
        write("A\nB\nq\nA\nB\nq\nA\nB\n");
        assertThat(UnifiedDiff.dryRun(root, diff(20)).problems()).singleElement().asString()
                .contains("(matches at lines 1, 4, 7)");
    }
}
