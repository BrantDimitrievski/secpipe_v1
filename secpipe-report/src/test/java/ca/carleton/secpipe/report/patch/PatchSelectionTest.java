package ca.carleton.secpipe.report.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.finding.Finding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PatchSelectionTest {

    private static final String DIFF = """
            --- a/src/Main.java
            +++ b/src/Main.java
            @@ -3,3 +3,4 @@
             keep
            -old
            +new1
            +new2
             tail
            \\ No newline at end of file
            @@ -20 +21 @@
            -x
            +y
            """;

    @Test
    void parsesModifiedFilesAndHunks() {
        var files = DiffFiles.parse(DIFF).orElseThrow();
        assertEquals(1, files.size());
        assertEquals("src/Main.java", files.getFirst().path());
        assertEquals(2, files.getFirst().hunks().size());
        DiffFiles.Hunk first = files.getFirst().hunks().getFirst();
        assertEquals(3, first.oldStart());
        assertEquals(3, first.oldCount());
        assertEquals("keep\nnew1\nnew2\ntail\n", first.newText());
        assertEquals(20, files.getFirst().hunks().get(1).oldStart());
        assertEquals("y\n", files.getFirst().hunks().get(1).newText());
    }

    @Test
    void newFilesDeletedFilesAndGarbageGiveNoFixDescription() {
        assertTrue(DiffFiles.parse("--- /dev/null\n+++ b/A.java\n@@ -0,0 +1 @@\n+x\n").isEmpty());
        assertTrue(DiffFiles.parse("--- a/A.java\n+++ /dev/null\n@@ -1 +0,0 @@\n-x\n").isEmpty());
        assertTrue(DiffFiles.parse("not a diff").isEmpty());
        assertTrue(DiffFiles.parse("--- a/A.java\n+++ b/A.java\n@@ -1,2 +1,2 @@\n x\n").isEmpty());
    }

    @Test
    void onlyFixedRecordsContributeAndOnlyTheirLastAttemptsPatch() {
        Finding fixed = RemediationFixtures.fixed();
        Finding still = RemediationFixtures.stillPresent();
        Finding none = RemediationFixtures.notAttempted();
        Map<String, String> files = Map.of(
                "patches/code-CWE-89-3f9a1c0d.patch", "PATCH-FIXED\n",
                "patches/code-CWE-89-b2b2b2b2-attempt2.patch", "PATCH-STILL\n");
        var fixes = FixedPatches.fixes(java.util.List.of(still, none, fixed), a -> Optional.ofNullable(files.get(a)));
        assertEquals(1, fixes.size());
        assertEquals(fixed.fingerprint(), fixes.getFirst().finding().fingerprint());
        assertEquals("patches/code-CWE-89-3f9a1c0d.patch", fixes.getFirst().artifact());

        Optional<String> combined = FixedPatches.combine(java.util.List.of(still, none, fixed),
                a -> Optional.ofNullable(files.get(a)));
        assertEquals(Optional.of("PATCH-FIXED\n"), combined);
    }

    @Test
    void noFixedRecordMeansNoPatchAndPatchesAreOrderedByFingerprint() {
        assertTrue(FixedPatches.combine(java.util.List.of(RemediationFixtures.stillPresent()),
                a -> Optional.of("x\n")).isEmpty());
        Finding a = RemediationFixtures.fixedAs("code:CWE-89:00000000000000000000000a", "patches/a.patch");
        Finding b = RemediationFixtures.fixedAs("code:CWE-89:00000000000000000000000b", "patches/b.patch");
        String out = FixedPatches.combine(java.util.List.of(b, a),
                p -> Optional.of(p.contains("a.patch") ? "A" : "B\n")).orElseThrow();
        assertEquals("A\nB\n", out);
    }

    @Test
    void patchSourceReadsOnlyInsideTheOutputDirectory() throws IOException {
        Path dir = Files.createTempDirectory("secpipe-r4").resolve("out");
        Files.createDirectories(dir);
        Files.createDirectories(dir.resolve("patches"));
        Files.writeString(dir.resolve("patches/p.patch"), "ok\n");
        Files.writeString(dir.getParent().resolve("outside.patch"), "secret\n");
        PatchSource src = PatchSource.fromDir(dir);
        assertEquals(Optional.of("ok\n"), src.read("patches/p.patch"));
        assertTrue(src.read("patches/missing.patch").isEmpty());
        assertTrue(src.read("../outside.patch").isEmpty());
        assertTrue(src.read("/etc/passwd").isEmpty());
        assertFalse(FixedPatches.isSafeRelative("a/../b"));
        assertTrue(FixedPatches.isSafeRelative("patches/x.patch"));
    }

    @Test
    void verifiedNeedsTheLastAttemptFixedAndThePatchFileToExist() {
        Finding stale = RemediationFixtures.fixedStatusLastAttemptStillPresent();
        assertTrue(FixedPatches.verified(stale, a -> Optional.of("x\n")).isEmpty());
        assertTrue(FixedPatches.verified(RemediationFixtures.sandboxUnavailable(), a -> Optional.of("x\n")).isEmpty());
        Finding fixed = RemediationFixtures.fixed();
        assertTrue(FixedPatches.verified(fixed, a -> Optional.of("x\n")).isPresent());
        assertTrue(FixedPatches.verified(fixed, PatchSource.NONE).isEmpty());
        assertTrue(FixedPatches.verified(RemediationFixtures.fixedAs("code:CWE-89:0000000000000000000000aa",
                "../escape.patch"), a -> Optional.of("x\n")).isEmpty());
    }
}
