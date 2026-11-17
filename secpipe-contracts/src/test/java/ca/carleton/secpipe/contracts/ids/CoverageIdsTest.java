package ca.carleton.secpipe.contracts.ids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.json.LedgerCodec;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit.CanonicalRefs;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** WO-C.2: {@link CoverageIds#of} is contract (ADR-06); changing its output breaks incremental runs. */
class CoverageIdsTest {

    /** Frozen once (WO-C.2) from the work order's vector; also equal to {@code printf ... | sha256sum}. */
    private static final String GOLDEN_ID = "cov:41d911bbef77fadafc18c0397a0af4fa";

    private static final Pattern COVERAGE_ID = Pattern.compile("^cov:[0-9a-f]{32}$");
    private static final Pattern AGENT_ID = Pattern.compile("^[a-z][a-z0-9_-]*-[0-9a-f]{8}$");

    private static CanonicalRefs refs(String surface, String boundary, String subsystem, String attackClass) {
        return new CanonicalRefs(surface, boundary, subsystem, attackClass, Optional.empty());
    }

    private static CanonicalRefs golden() {
        return refs("a.java#GET /x", "a.java#check", "src/a", "ATTACK-CLASSES.md#Injection/CWE-89");
    }

    @Test
    void goldenVectorIsFrozen() {
        assertEquals(GOLDEN_ID, CoverageIds.of(golden()));
    }

    @Test
    void goldenVectorIsTheHashOfTheSortedCompactCanonicalJson() {
        String canonical = "{\"attack_class\":\"ATTACK-CLASSES.md#Injection/CWE-89\",\"boundary\":\"a.java#check\","
                + "\"subsystem\":\"src/a\",\"surface\":\"a.java#GET /x\"}";
        assertEquals("cov:" + sha256Hex(canonical).substring(0, 32), CoverageIds.of(golden()));
    }

    @Test
    void sameRefsGiveTheSameIdWhateverTheirJsonKeyOrder() {
        String unitTemplate = LedgerCodec.write(List.of(unitWith(golden())));
        String reordered = unitTemplate.replace(
                "\"canonical_refs\": {\n      \"surface\": \"a.java#GET /x\",\n      \"boundary\": \"a.java#check\","
                        + "\n      \"subsystem\": \"src/a\",\n"
                        + "      \"attack_class\": \"ATTACK-CLASSES.md#Injection/CWE-89\"\n",
                "\"canonical_refs\": {\n      \"attack_class\": \"ATTACK-CLASSES.md#Injection/CWE-89\",\n"
                        + "      \"subsystem\": \"src/a\",\n      \"boundary\": \"a.java#check\",\n"
                        + "      \"surface\": \"a.java#GET /x\"\n");
        assertNotEquals(unitTemplate, reordered, "the test must actually reorder the keys");

        CanonicalRefs fromReordered = LedgerCodec.read(reordered).get(0).canonicalRefs();
        CanonicalRefs fresh = refs(new String("a.java#GET /x"), "a.java#check", "src/a",
                "ATTACK-CLASSES.md#Injection/CWE-89");
        assertEquals(GOLDEN_ID, CoverageIds.of(fromReordered));
        assertEquals(GOLDEN_ID, CoverageIds.of(fresh));
        assertEquals(CoverageIds.of(fresh), CoverageIds.of(fresh));
    }

    @Test
    void composedAndDecomposedUnicodeGiveTheSameId() {
        String composed = "src/caf\u00e9";       // é as one code point
        String decomposed = "src/cafe\u0301";    // e + COMBINING ACUTE ACCENT
        assertNotEquals(composed, decomposed);
        String a = CoverageIds.of(refs("a.java#GET /x", "a.java#check", composed, "ATTACK-CLASSES.md#Injection"));
        String b = CoverageIds.of(refs("a.java#GET /x", "a.java#check", decomposed, "ATTACK-CLASSES.md#Injection"));
        assertEquals(a, b);
        // The hash input is the NFC form, encoded as UTF-8 (not \\u escapes).
        String canonical = "{\"attack_class\":\"ATTACK-CLASSES.md#Injection\",\"boundary\":\"a.java#check\","
                + "\"subsystem\":\"src/caf\u00e9\",\"surface\":\"a.java#GET /x\"}";
        assertEquals("cov:" + sha256Hex(canonical).substring(0, 32), a);
        String plainE = CoverageIds.of(refs("a.java#GET /x", "a.java#check", "src/cafe",
                "ATTACK-CLASSES.md#Injection"));
        assertNotEquals(plainE, a);
    }

    @Test
    void rejectsControlFormatAndSeparatorCharacters() {
        List<String> bad = List.of(
                "a\u0000b",      // NUL (Cc)
                "a\tb",          // TAB (Cc), inside the value
                "a\nb",          // LF (Cc)
                "a\u007fb",      // DEL (Cc)
                "a\u200bb",      // ZERO WIDTH SPACE (Cf)
                "a\u202eb",      // RIGHT-TO-LEFT OVERRIDE (Cf)
                "a\ufeffb",      // BOM / ZWNBSP (Cf)
                "a\uDB40\uDC01b", // U+E0001 LANGUAGE TAG (Cf, supplementary)
                "a\u2028b",      // LINE SEPARATOR (Zl)
                "a\u2029b",      // PARAGRAPH SEPARATOR (Zp)
                "a\uD800b");     // unpaired surrogate
        for (String value : bad) {
            assertThrows(IllegalArgumentException.class,
                    () -> CoverageIds.of(refs("a.java#GET /x", value, "src/a", "ATTACK-CLASSES.md#Injection")),
                    () -> "should reject " + HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_16BE)));
        }
    }

    @Test
    void rejectsEmptyNullAndUntrimmedRefs() {
        for (String value : List.of("", " a", "a ", "\u00a0a", "a\u3000", " ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> CoverageIds.of(refs(value, "a.java#check", "src/a", "ATTACK-CLASSES.md#Injection")),
                    () -> "should reject '" + value + "'");
        }
        assertThrows(IllegalArgumentException.class,
                () -> CoverageIds.of(refs("a.java#GET /x", "a.java#check", null, "ATTACK-CLASSES.md#Injection")));
        assertThrows(IllegalArgumentException.class, () -> CoverageIds.of(null));
        assertThrows(IllegalArgumentException.class, () -> CoverageIds.of(
                new CanonicalRefs("a.java#GET /x", "a.java#check", "src/a", "ATTACK-CLASSES.md#Injection", null)));
        // Inner spaces are fine.
        assertTrue(COVERAGE_ID.matcher(CoverageIds.of(golden())).matches());
    }

    @Test
    void lifecycleIsPartOfTheIdOnlyWhenPresentAndIsCheckedToo() {
        CanonicalRefs withLifecycle = new CanonicalRefs("a.java#GET /x", "a.java#check", "src/a",
                "ATTACK-CLASSES.md#Injection/CWE-89", Optional.of("request"));
        String canonical = "{\"attack_class\":\"ATTACK-CLASSES.md#Injection/CWE-89\",\"boundary\":\"a.java#check\","
                + "\"lifecycle\":\"request\",\"subsystem\":\"src/a\",\"surface\":\"a.java#GET /x\"}";
        assertEquals("cov:" + sha256Hex(canonical).substring(0, 32), CoverageIds.of(withLifecycle));
        assertNotEquals(GOLDEN_ID, CoverageIds.of(withLifecycle));
        assertThrows(IllegalArgumentException.class, () -> CoverageIds.of(new CanonicalRefs("a.java#GET /x",
                "a.java#check", "src/a", "ATTACK-CLASSES.md#Injection", Optional.of(" request"))));
    }

    @Test
    void quotesAndBackslashesAreEscapedAndNonAsciiIsRawUtf8() {
        CanonicalRefs tricky = refs("a.java#GET /\"q\"", "C:\\x#check", "src/\u65e5\u672c", "ATTACK-CLASSES.md#X");
        String canonical = "{\"attack_class\":\"ATTACK-CLASSES.md#X\",\"boundary\":\"C:\\\\x#check\","
                + "\"subsystem\":\"src/\u65e5\u672c\",\"surface\":\"a.java#GET /\\\"q\\\"\"}";
        assertEquals("cov:" + sha256Hex(canonical).substring(0, 32), CoverageIds.of(tricky));
    }

    @Test
    void differentRefsGiveDifferentIdsAndFieldsAreNotInterchangeable() {
        String swapped = CoverageIds.of(refs("a.java#check", "a.java#GET /x", "src/a",
                "ATTACK-CLASSES.md#Injection/CWE-89"));
        assertNotEquals(GOLDEN_ID, swapped);
        assertNotEquals(GOLDEN_ID, CoverageIds.of(refs("a.java#GET /x", "a.java#check", CoverageIds.QUICK_SUBSYSTEM,
                "ATTACK-CLASSES.md#Injection/CWE-89")));
    }

    @Test
    void exampleLedgerIdsMatchTheirCanonicalRefs() {
        List<LedgerUnit> units = LedgerCodec.read(resource("/examples/ledger-maximal.json"));
        assertEquals(2, units.size());
        for (LedgerUnit unit : units) {
            assertEquals(unit.coverageId(), CoverageIds.of(unit.canonicalRefs()));
        }
    }

    @Test
    void newAgentIdIsRoleDashEightLowerHexAndFreshPerCall() {
        SplittableRandom rng = new SplittableRandom(42);
        String first = CoverageIds.newAgentId("hunter", rng);
        String second = CoverageIds.newAgentId("hunter", rng);
        assertTrue(AGENT_ID.matcher(first).matches(), first);
        assertTrue(first.startsWith("hunter-"), first);
        assertEquals("hunter-".length() + 8, first.length());
        assertNotEquals(first, second);
        assertEquals(first, CoverageIds.newAgentId("hunter", new SplittableRandom(42)), "seeded rng is repeatable");
        assertTrue(AGENT_ID.matcher(CoverageIds.newAgentId("record_verifier", rng)).matches());
        // A zero draw still gives eight digits.
        assertEquals("critic-00000000", CoverageIds.newAgentId("critic", new FixedRandom(0)));
        assertEquals("critic-ffffffff", CoverageIds.newAgentId("critic", new FixedRandom(-1)));
        for (String role : List.of("", "Hunter", "1hunter", "hunter x", "hunter-\u00e9")) {
            assertThrows(IllegalArgumentException.class, () -> CoverageIds.newAgentId(role, rng),
                    () -> "should reject role '" + role + "'");
        }
        assertThrows(IllegalArgumentException.class, () -> CoverageIds.newAgentId(null, rng));
        assertThrows(IllegalArgumentException.class, () -> CoverageIds.newAgentId("hunter", null));
    }

    private record FixedRandom(int value) implements java.util.random.RandomGenerator {
        @Override
        public long nextLong() {
            return value;
        }

        @Override
        public int nextInt() {
            return value;
        }
    }

    private static LedgerUnit unitWith(CanonicalRefs refs) {
        return new LedgerUnit(CoverageIds.of(refs), refs, "GET /x", "check", "a", "Injection (SQL)",
                Optional.of("CWE-89"), List.of("a.java"), "ATTACK-CLASSES.md#Injection", List.of(), List.of(),
                LedgerUnit.PriorStatus.NEW, List.of(), 0, LedgerUnit.Status.PLANNED, Optional.empty(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String resource(String name) {
        try (InputStream in = CoverageIdsTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
