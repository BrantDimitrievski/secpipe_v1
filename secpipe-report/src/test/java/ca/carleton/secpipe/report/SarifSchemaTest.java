package ca.carleton.secpipe.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** WO-R.6: SARIF tests use the official OASIS schema, and the vendored copy is the pinned one. */
class SarifSchemaTest {

    /** sha256 of {@code sarif-2.1/schema/sarif-schema-2.1.0.json} at oasis-tcs/sarif-spec 1742a253. */
    private static final String PINNED_SHA256 = "c3b4bb2d6093897483348925aaa73af03b3e3f4bd4ca38cef26dcb4212a2682e";

    @Test
    void vendoredSchemaIsByteIdenticalToThePinnedOasisFile() throws IOException, NoSuchAlgorithmException {
        try (InputStream in = SarifSchemaTest.class.getResourceAsStream(Fixtures.BASE + SarifSchema.RESOURCE)) {
            byte[] bytes = in.readAllBytes();
            assertEquals(PINNED_SHA256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
    }

    @Test
    void everySarifFileUnderTheReportTestResourcesValidatesAgainstTheOfficialSchema() throws IOException,
            URISyntaxException {
        Path root = Path.of(SarifSchemaTest.class.getResource(Fixtures.BASE).toURI());
        List<String> found;
        try (Stream<Path> files = Files.walk(root)) {
            found = files.filter(Files::isRegularFile).filter(f -> f.getFileName().toString().endsWith(".sarif"))
                    .map(f -> root.relativize(f).toString().replace('\\', '/')).sorted().toList();
        }
        assertTrue(found.contains("golden/secpipe.sarif"), found.toString());
        assertTrue(found.contains("remediation/golden/secpipe.sarif"), found.toString());
        for (String sarif : found) {
            assertEquals(List.of(), SarifSchema.errors(Fixtures.resource(sarif)), sarif);
        }
    }
}
