package ca.carleton.secpipe.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
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
    void everyGoldenSarifFileValidatesAgainstTheOfficialSchema() {
        for (String golden : List.of("golden/secpipe.sarif", "remediation/golden/secpipe.sarif")) {
            assertEquals(List.of(), SarifSchema.errors(Fixtures.resource(golden)), golden);
        }
    }
}
