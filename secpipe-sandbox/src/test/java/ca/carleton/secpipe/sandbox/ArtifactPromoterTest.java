package ca.carleton.secpipe.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.spi.SandboxRunner.Promotion;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactPromoterTest {

    @TempDir
    Path out;

    private ArtifactPromoter.Report promote(ArtifactPromoterTars tar, Promotion... declared) throws IOException {
        return ArtifactPromoter.promote(new ByteArrayInputStream(tar.bytes()), List.of(declared), out);
    }

    private static String reasonFor(ArtifactPromoter.Report r, String path) {
        return r.rejected().stream().filter(x -> x.path().equals(path)).map(ArtifactPromoter.Rejection::reason)
            .findFirst().orElse("");
    }

    @Test
    void declaredRegularFileIsPromoted() throws IOException {
        var r = promote(new ArtifactPromoterTars().file("./target/report.xml", "<ok/>"),
            new Promotion("target/report.xml", 100));
        Path f = out.toRealPath().resolve("target/report.xml");
        assertEquals(List.of(f), r.promoted());
        assertEquals("<ok/>", Files.readString(f, StandardCharsets.UTF_8));
        assertTrue(r.rejected().isEmpty());
    }

    @Test
    void symlinkIsRejected() throws IOException {
        var r = promote(new ArtifactPromoterTars().symlink("out.txt", "/etc/passwd"), new Promotion("out.txt", 100));
        assertTrue(r.promoted().isEmpty());
        assertTrue(reasonFor(r, "out.txt").contains("symlink"), r.rejected().toString());
        assertFalse(Files.exists(out.resolve("out.txt")));
    }

    @Test
    void hardLinkIsRejected() throws IOException {
        var r = promote(new ArtifactPromoterTars().file("a.txt", "x").hardlink("out.txt", "a.txt"),
            new Promotion("out.txt", 100));
        assertTrue(r.promoted().isEmpty());
        assertTrue(reasonFor(r, "out.txt").contains("hard link"), r.rejected().toString());
    }

    @Test
    void oversizeFileIsRejected() throws IOException {
        var r = promote(new ArtifactPromoterTars().file("big.bin", "0123456789"), new Promotion("big.bin", 9));
        assertTrue(r.promoted().isEmpty());
        assertTrue(reasonFor(r, "big.bin").startsWith("oversize"), r.rejected().toString());
        assertFalse(Files.exists(out.resolve("big.bin")));
    }

    @Test
    void undeclaredFileIsRejectedAndLaterEntriesStillRead() throws IOException {
        var r = promote(new ArtifactPromoterTars().file("secret.txt", "nope").file("ok.txt", "yes"),
            new Promotion("ok.txt", 10));
        assertEquals("undeclared", reasonFor(r, "secret.txt"));
        assertFalse(Files.exists(out.resolve("secret.txt")));
        assertEquals("yes", Files.readString(out.resolve("ok.txt")));
    }

    @Test
    void directoryAndDuplicateAreRejected() throws IOException {
        var r = promote(new ArtifactPromoterTars().dir("d/").file("f", "1").file("f", "2"),
            new Promotion("d", 10), new Promotion("f", 10));
        assertTrue(reasonFor(r, "d").contains("directory"));
        assertEquals("duplicate entry", reasonFor(r, "f"));
        assertEquals("1", Files.readString(out.resolve("f")));
    }

    @Test
    void neverWritesThroughASymlinkInTheOutputDirectory() throws IOException {
        Path elsewhere = Files.createTempDirectory("promoter-elsewhere");
        Files.createSymbolicLink(out.resolve("target"), elsewhere);
        Path victim = Files.writeString(elsewhere.resolve("victim.txt"), "keep");
        Files.createSymbolicLink(out.resolve("link.txt"), victim);
        var r = promote(new ArtifactPromoterTars().file("target/victim.txt", "pwned").file("link.txt", "pwned"),
            new Promotion("target/victim.txt", 100), new Promotion("link.txt", 100));
        assertTrue(r.promoted().isEmpty(), r.toString());
        assertEquals("keep", Files.readString(victim));
    }

    @Test
    void missingDeclaredPathIsReported() throws IOException {
        var r = promote(new ArtifactPromoterTars(), new Promotion("never.txt", 1));
        assertEquals("missing", reasonFor(r, "never.txt"));
    }

    @Test
    void gnuLongNamesAndPaxPathsAreHonoured() throws IOException {
        String longName = "a/" + "b".repeat(120) + ".txt";
        var r = promote(new ArtifactPromoterTars().gnuLongFile(longName, "L").paxFile("p/x.txt", "P"),
            new Promotion(longName, 10), new Promotion("p/x.txt", 10));
        assertEquals(2, r.promoted().size(), r.toString());
        assertEquals("L", Files.readString(out.resolve(longName)));
        assertEquals("P", Files.readString(out.resolve("p/x.txt")));
    }

    @Test
    void traversalPathsCannotBeDeclared() {
        for (String bad : List.of("../x", "/etc/passwd", "a/../../x", "a//b", "./a", "C:/x", "-rf")) {
            assertTrue(ArtifactPromoter.checkRelativePath(bad).isPresent(), bad);
            assertThrows(IllegalArgumentException.class, () -> promote(new ArtifactPromoterTars(),
                new Promotion(bad, 1)), bad);
        }
        assertTrue(ArtifactPromoter.checkRelativePath("target/surefire-reports/TEST-x.xml").isEmpty());
    }
}
