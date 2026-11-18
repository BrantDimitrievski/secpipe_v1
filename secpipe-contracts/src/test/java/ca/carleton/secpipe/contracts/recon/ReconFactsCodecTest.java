package ca.carleton.secpipe.contracts.recon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.json.ContractJson;
import ca.carleton.secpipe.contracts.json.ContractJson.ContractJsonException;
import ca.carleton.secpipe.contracts.json.ReconFactsCodec;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WO-CC.2: recon-facts.json codec — byte-stable round trip, strictness, and agreement with the schema. */
class ReconFactsCodecTest {

    private static final String ID = "https://secpipe.dev/schemas/recon-facts.schema.json";
    private static final List<String> EXAMPLES = List.of("recon-facts-valid.json", "recon-facts-empty.json");

    @Test
    void examplesAreSchemaValidAndRoundTripByteForByte() {
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                b -> b.schemas(Map.of(ID, resource(ReconFactsCodec.SCHEMA_RESOURCE))))
                .getSchema(SchemaLocation.of(ID));
        for (String name : EXAMPLES) {
            String text = resource("/examples/recon/" + name);
            assertEquals(List.of(), schema.validate(ContractJson.mapper().readTree(text)), name);
            assertEquals(text, ReconFactsCodec.write(ReconFactsCodec.read(text)), name);
        }
    }

    @Test
    void readsTheValidExampleIntoRecords() {
        ReconFacts facts = ReconFactsCodec.read(resource("/examples/recon/recon-facts-valid.json"));
        assertEquals(2, facts.entrySurfaces().size());
        assertEquals(List.of("CWE-22", "CWE-89"), List.copyOf(facts.sinksByFamily().keySet()));
        assertEquals(Location.of("src/main/java/com/acme/shop/FileStore.java", 12), facts.sinks("CWE-22").get(0));
        assertEquals(List.of(), facts.sinks("CWE-78"));
    }

    @Test
    void writeIsSortedAndIndependentOfInputOrder(@TempDir Path dir) throws IOException {
        Location x = Location.of("b/X.java", 5, "X#a()");
        Location y = Location.of("a/Y.java", 9);
        Location z = Location.of("a/Y.java", 2);
        Map<String, List<Location>> sinks1 = new LinkedHashMap<>();
        sinks1.put("CWE-89", List.of(x, y));
        sinks1.put("CWE-22", List.of(z));
        Map<String, List<Location>> sinks2 = new LinkedHashMap<>();
        sinks2.put("CWE-22", List.of(z, z));
        sinks2.put("CWE-89", List.of(y, x));
        String one = ReconFactsCodec.write(new ReconFacts(List.of(x, y, z), sinks1));
        String two = ReconFactsCodec.write(new ReconFacts(List.of(z, x, y, x), sinks2));
        assertEquals(one, two);
        assertTrue(one.indexOf("\"CWE-22\"") < one.indexOf("\"CWE-89\""));
        assertTrue(one.indexOf("\"line\": 2") < one.indexOf("\"line\": 9"));
        assertTrue(one.endsWith("}\n") && !one.contains("\r"));
        assertEquals(List.of(z, y, x), ReconFactsCodec.read(one).entrySurfaces());

        Path file = dir.resolve(ReconFactsCodec.FILE_NAME);
        ReconFactsCodec.write(file, new ReconFacts(List.of(x, y, z), sinks1));
        assertEquals(one, Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(ReconFactsCodec.read(one), ReconFactsCodec.read(file));
    }

    @Test
    void rejectsUnknownProperties() {
        assertRejected("{\"entry_surfaces\": [], \"sinks_by_family\": {}, \"extra\": 1}", "");
        assertRejected("{\"entry_surfaces\": [{\"file\": \"A.java\", \"line\": 1, \"col\": 2}], "
                + "\"sinks_by_family\": {}}", "/entry_surfaces/0");
    }

    @Test
    void rejectsNullsAndMissingRequiredProperties() {
        assertRejected("{\"entry_surfaces\": null, \"sinks_by_family\": {}}", "/entry_surfaces");
        assertRejected("{\"entry_surfaces\": [], \"sinks_by_family\": {\"CWE-89\": null}}",
                "/sinks_by_family/CWE-89");
        assertRejected("{\"entry_surfaces\": [{\"file\": \"A.java\", \"line\": 1, \"scope\": null}], "
                + "\"sinks_by_family\": {}}", "/entry_surfaces/0/scope");
        assertRejected("{\"entry_surfaces\": []}", "");
        assertRejected("null", "");
    }

    @Test
    void rejectsBadValuesInsteadOfRepairing() {
        assertRejected("{\"entry_surfaces\": [{\"file\": \"/abs/A.java\", \"line\": 1}], \"sinks_by_family\": {}}",
                "/entry_surfaces/0/file");
        assertRejected("{\"entry_surfaces\": [{\"file\": \"../A.java\", \"line\": 1}], \"sinks_by_family\": {}}",
                "/entry_surfaces/0/file");
        assertRejected("{\"entry_surfaces\": [{\"file\": \"A.java\", \"line\": 0}], \"sinks_by_family\": {}}",
                "/entry_surfaces/0/line");
        assertRejected("{\"entry_surfaces\": [{\"file\": \"A.java\", \"line\": \"3\"}], \"sinks_by_family\": {}}",
                "/entry_surfaces/0/line");
        assertRejected("{\"entry_surfaces\": [], \"sinks_by_family\": {\"sql\": []}}", "/sinks_by_family");
        assertRejected("{\"entry_surfaces\": [], \"sinks_by_family\": {}} {}", "");
        assertRejected("{\"entry_surfaces\": [], \"entry_surfaces\": [], \"sinks_by_family\": {}}", "");
    }

    @Test
    void recordRejectsBadFamilyKeysAndNulls() {
        assertThrows(IllegalArgumentException.class, () -> new ReconFacts(List.of(), Map.of("CWE-089", List.of())));
        assertThrows(IllegalArgumentException.class, () -> new ReconFacts(List.of(), Map.of("89", List.of())));
        assertThrows(NullPointerException.class, () -> new ReconFacts(null, Map.of()));
        assertThrows(NullPointerException.class, () -> new ReconFacts(List.of(), null));
        ReconFacts facts = new ReconFacts(List.of(Location.of("A.java", 1)), Map.of("CWE-78", List.of()));
        assertThrows(UnsupportedOperationException.class, () -> facts.entrySurfaces().clear());
        assertThrows(UnsupportedOperationException.class, () -> facts.sinksByFamily().clear());
        assertEquals("{\n  \"entry_surfaces\": [],\n  \"sinks_by_family\": {}\n}\n",
                ReconFactsCodec.write(ReconFacts.empty()));
    }

    private static void assertRejected(String json, String pointerPrefix) {
        ContractJsonException e = assertThrows(ContractJsonException.class, () -> ReconFactsCodec.read(json), json);
        assertTrue(e.path().startsWith(pointerPrefix), json + " -> " + e.getMessage());
    }

    private static String resource(String name) {
        try (InputStream in = ReconFactsCodecTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
