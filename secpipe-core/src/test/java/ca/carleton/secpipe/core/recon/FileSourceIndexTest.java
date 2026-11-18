package ca.carleton.secpipe.core.recon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSourceIndexTest {

    @TempDir
    Path root;

    @Test
    void scanListsFilesSortedAndSkipsBuildOutputAndHiddenDirs() throws Exception {
        ReconFixtures.write(root, "src/main/java/b/B.java", "class B {}\n");
        ReconFixtures.write(root, "src/main/java/a/A.java", "class A {}\n");
        ReconFixtures.write(root, "pom.xml", "<project/>\n");
        ReconFixtures.write(root, "target/classes/A.class", "x");
        ReconFixtures.write(root, ".git/config", "x");
        ReconFixtures.write(root, ".hidden/x.java", "x");

        FileSourceIndex index = FileSourceIndex.scan(root);

        assertThat(index.files()).containsExactly("pom.xml", "src/main/java/a/A.java", "src/main/java/b/B.java");
        assertThat(index.exists("src/main/java/a/A.java")).isTrue();
        assertThat(index.exists("target/classes/A.class")).isFalse();
    }

    @Test
    void linesAndLineReadWithoutTerminators() throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.write(root.resolve("src/X.java"), "﻿one\r\ntwo\rthree\n".getBytes(StandardCharsets.UTF_8));
        FileSourceIndex index = FileSourceIndex.scan(root);

        assertThat(index.lines("src/X.java")).contains(List.of("one", "two", "three"));
        assertThat(index.line("src/X.java", 1)).contains("one");
        assertThat(index.line("src/X.java", 3)).contains("three");
        assertThat(index.line("src/X.java", 0)).isEmpty();
        assertThat(index.line("src/X.java", 4)).isEmpty();
    }

    @Test
    void missingFilesAreEmptyAndBadPathsAreRejected() throws Exception {
        ReconFixtures.write(root, "a/B.java", "class B {}\n");
        FileSourceIndex index = FileSourceIndex.of(root, List.of("a/B.java"));

        assertThat(index.lines("a/C.java")).isEmpty();
        assertThat(index.line("a/C.java", 1)).isEmpty();
        assertThat(index.exists("a/C.java")).isFalse();
        assertThatThrownBy(() -> index.lines("../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.exists("/abs/B.java")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.line("a\\B.java", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileSourceIndex.of(root, List.of("a/./B.java")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void indexedFileDeletedFromDiskYieldsEmptyNotAnError() throws Exception {
        ReconFixtures.write(root, "gone.txt", "x\n");
        FileSourceIndex index = FileSourceIndex.scan(root);
        Files.delete(root.resolve("gone.txt"));

        assertThat(index.files()).containsExactly("gone.txt");
        assertThat(index.lines("gone.txt")).isEmpty();
    }

    @Test
    void scanOfMissingRootIsEmpty() {
        assertThat(FileSourceIndex.scan(root.resolve("nope")).files()).isEmpty();
    }
}
