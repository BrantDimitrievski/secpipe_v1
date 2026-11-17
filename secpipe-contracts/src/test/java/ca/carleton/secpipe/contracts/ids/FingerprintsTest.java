package ca.carleton.secpipe.contracts.ids;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * WO-C.5: pins the ADR-06 fingerprint algorithm. The golden {@code code:} hashes were computed independently with
 * Python ({@code json.dumps(d, sort_keys=True, separators=(",", ":"), ensure_ascii=False)} then SHA-256), so a
 * change to any golden value here is a contract change.
 */
class FingerprintsTest {

    private static final Pattern ID_PATTERN = Pattern.compile(Fingerprints.PATTERN);

    private static final String DAO = "src/main/java/com/acme/UserDao.java";
    private static final String DAO_SCOPE = "com.acme.UserDao#find(java.lang.String)";
    private static final String SQL_SINK = "stmt.executeQuery(\"SELECT * FROM users WHERE name = '\" + name + \"'\")";

    // ---------------------------------------------------------------- golden vectors

    static Stream<Arguments> codeGoldenVectors() {
        return Stream.of(
                Arguments.of("CWE-89", DAO, DAO_SCOPE, SQL_SINK, 0, "code:CWE-89:599d9b6b5ffa4cf7a2baea35"),
                // CWE-23 is reported by some tools; it collapses into the CWE-22 family.
                Arguments.of("CWE-23", "src/main/java/com/acme/FileController.java",
                        "com.acme.FileController#download(java.lang.String)", "new File(baseDir, fileName)", 0,
                        "code:CWE-22:8caa143da5790aeb2bd78873"),
                Arguments.of("CWE-78", "src/main/java/com/acme/Shell.java", "com.acme.Shell#run(java.lang.String[])",
                        "Runtime.getRuntime().exec(cmd)", 1, "code:CWE-78:ce00f51bd698998e65f92596"),
                // Messy input: comments and whitespace are normalized away before hashing.
                Arguments.of("CWE-502", "src/main/java/com/acme/Session.java",
                        "com.acme.Session#load(java.io.InputStream)",
                        "new   ObjectInputStream(in).readObject() // unsafe",
                        0, "code:CWE-502:c5127572e3e467bc763cd28a"),
                // Unknown CWE is its own family; non-ASCII and a backslash escape inside the literal are kept.
                Arguments.of("CWE-79", "src/main/java/com/acme/Greeter.java",
                        "com.acme.Greeter#hello(javax.servlet.http.HttpServletResponse,java.lang.String)",
                        "resp.getWriter().write(\"<p>H\u00e9llo \\t\" + who + \"</p>\")",
                        0, "code:CWE-79:08d55187007ce5d9d7f18005"),
                // An escaped quote inside a string literal must not end the literal.
                Arguments.of("CWE-259", "src/main/java/com/acme/Db.java", "com.acme.Db#connect()",
                        "DriverManager.getConnection(url,   \"admin\", // user\n \"s3cr3t\\\"!\")", 2,
                        "code:CWE-798:89012d49dc48fbb0f0f3e079"));
    }

    @ParameterizedTest
    @MethodSource("codeGoldenVectors")
    void codeMatchesGoldenVectors(String cwe, String file, String scope, String sink, int occurrence, String expected) {
        assertThat(Fingerprints.code(cwe, file, scope, sink, occurrence)).isEqualTo(expected);
    }

    @Test
    void messyGoldenSinkNormalizesToTheHashedText() {
        String messy = "DriverManager.getConnection(url,   \"admin\", // user\n \"s3cr3t\\\"!\")";
        assertThat(Fingerprints.normalizeStatement(messy))
                .isEqualTo("DriverManager.getConnection(url, \"admin\", \"s3cr3t\\\"!\")");
    }

    static Stream<Arguments> dependencyGoldenVectors() {
        return Stream.of(
                Arguments.of("pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1",
                        List.of("GHSA-jfh8-c2jp-5v3q", "CVE-2021-44228"),
                        "dep:pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1:CVE-2021-44228"),
                Arguments.of("pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.9.8",
                        List.of("OSV-2020-111", "GHSA-5ww9-j83m-q7qx", "GHSA-zzzz-zzzz-zzzz"),
                        "dep:pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.9.8:GHSA-5ww9-j83m-q7qx"),
                Arguments.of("pkg:maven/org.example/lib@1.0", List.of("OSV-2024-1", "PYSEC-1"),
                        "dep:pkg:maven/org.example/lib@1.0:OSV-2024-1"),
                Arguments.of("pkg:maven/a/b@1.0?type=jar#x y", List.of("CVE-2020-1", "CVE-2020-2"),
                        "dep:pkg:maven/a/b@1.0+3Ftype+3Djar+23x+20y:CVE-2020-1"),
                // Non-ASCII is percent-encoded per UTF-8 byte; an encoded vuln id is encoded the same way.
                Arguments.of("pkg:maven/caf\u00e9/x@1", List.of("weird id"),
                        "dep:pkg:maven/caf+C3+A9/x@1:weird+20id"));
    }

    @ParameterizedTest
    @MethodSource("dependencyGoldenVectors")
    void dependencyMatchesGoldenVectors(String purl, List<String> ids, String expected) {
        assertThat(Fingerprints.dependency(purl, ids)).isEqualTo(expected).matches(ID_PATTERN);
    }

    @Test
    void cweFamilyGoldenTable() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("CWE-22", "CWE-22"), Map.entry("CWE-36", "CWE-22"), Map.entry("CWE-73", "CWE-22"),
                Map.entry("CWE-77", "CWE-78"), Map.entry("CWE-88", "CWE-78"),
                Map.entry("CWE-564", "CWE-89"), Map.entry("CWE-943", "CWE-89"),
                Map.entry("CWE-326", "CWE-327"), Map.entry("CWE-780", "CWE-327"),
                Map.entry("CWE-916", "CWE-328"), Map.entry("CWE-760", "CWE-328"),
                Map.entry("CWE-321", "CWE-798"), Map.entry("CWE-259", "CWE-798"),
                Map.entry("CWE-502", "CWE-502"), Map.entry("CWE-79", "CWE-79"),
                Map.entry("cwe-023", "CWE-22"), Map.entry(" CWE_916 ", "CWE-328"), Map.entry("89", "CWE-89"),
                Map.entry("not-a-cwe", "not-a-cwe"));
        expected.forEach((in, out) -> assertThat(Fingerprints.cweFamily(in)).as(in).isEqualTo(out));
    }

    @Test
    void everyFamilyMemberMapsToItsRoot() {
        Fingerprints.CWE_FAMILIES.forEach((root, members) ->
                members.forEach(m -> assertThat(Fingerprints.cweFamily(m)).as(m).isEqualTo(root)));
    }

    // ---------------------------------------------------------------- invariances

    @Test
    void lineShiftDoesNotChangeTheId() {
        // The same sink statement reached at a different line (e.g. code inserted above it, re-indented) is the
        // same root cause: lines are never an input.
        String atLine12 = "        " + SQL_SINK;
        String atLine40 = "\n\n\t\t" + SQL_SINK + "   // moved down by an unrelated edit\n";
        String expected = Fingerprints.code("CWE-89", DAO, DAO_SCOPE, SQL_SINK, 0);
        assertThat(Fingerprints.code("CWE-89", DAO, DAO_SCOPE, atLine12, 0)).isEqualTo(expected);
        assertThat(Fingerprints.code("CWE-89", DAO, DAO_SCOPE, atLine40, 0)).isEqualTo(expected);
    }

    @Test
    void whitespaceAndCommentsOutsideLiteralsDoNotChangeTheId() {
        String messy = "stmt.executeQuery( /* tainted */\"SELECT * FROM users WHERE name = '\"\t+  name   // TODO\n"
                + "+ \"'\")";
        String tidy = "stmt.executeQuery( \"SELECT * FROM users WHERE name = '\" + name + \"'\")";
        assertThat(Fingerprints.normalizeStatement(messy)).isEqualTo(tidy);
        assertThat(Fingerprints.code("CWE-89", DAO, DAO_SCOPE, messy, 0))
                .isEqualTo(Fingerprints.code("CWE-89", DAO, DAO_SCOPE, tidy, 0));
    }

    @Test
    void normalizeStatementKeepsCommentMarkersAndWhitespaceInsideLiterals() {
        assertThat(Fingerprints.normalizeStatement("  a(\"x  // y /* z */\" ,\n\t'/' , '\"')  "))
                .isEqualTo("a(\"x  // y /* z */\" , '/' , '\"')");
        assertThat(Fingerprints.normalizeStatement("q(\"\"\"\n  b  /* c */\n\"\"\") // tail"))
                .isEqualTo("q(\"\"\"\n  b  /* c */\n\"\"\")");
        assertThat(Fingerprints.normalizeStatement("a/*x*/b")).isEqualTo("a b");
        assertThat(Fingerprints.normalizeStatement("a /* unterminated")).isEqualTo("a");
        assertThat(Fingerprints.normalizeStatement(" \t\r\n ")).isEmpty();
        assertThat(Fingerprints.normalizeStatement("x = 1 / 2 * 3")).isEqualTo("x = 1 / 2 * 3");
    }

    @Test
    void stringLiteralContentChangesAlterTheId() {
        String base = Fingerprints.code("CWE-89", DAO, DAO_SCOPE, SQL_SINK, 0);
        String extraSpaceInLiteral = SQL_SINK.replace("FROM users", "FROM  users");
        String otherTable = SQL_SINK.replace("users", "accounts");
        String commentLookalikeInLiteral = SQL_SINK.replace("FROM users", "FROM users /* x */");
        assertThat(Fingerprints.code("CWE-89", DAO, DAO_SCOPE, extraSpaceInLiteral, 0)).isNotEqualTo(base);
        assertThat(Fingerprints.code("CWE-89", DAO, DAO_SCOPE, otherTable, 0)).isNotEqualTo(base);
        assertThat(Fingerprints.code("CWE-89", DAO, DAO_SCOPE, commentLookalikeInLiteral, 0)).isNotEqualTo(base);
    }

    @Test
    void occurrenceIndexDistinguishesDuplicateSinksInOneScope() {
        Set<String> ids = new HashSet<>();
        for (int occurrence = 0; occurrence < 5; occurrence++) {
            ids.add(Fingerprints.code("CWE-89", DAO, DAO_SCOPE, SQL_SINK, occurrence));
        }
        assertThat(ids).hasSize(5);
        assertThatThrownBy(() -> Fingerprints.code("CWE-89", DAO, DAO_SCOPE, SQL_SINK, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void familyVariantsCollapseToTheSameId() {
        String sink = "new File(baseDir, fileName)";
        String file = "src/main/java/com/acme/FileController.java";
        String scope = "com.acme.FileController#download(java.lang.String)";
        String root = Fingerprints.code("CWE-22", file, scope, sink, 0);
        assertThat(root).startsWith("code:CWE-22:");
        for (String variant : List.of("CWE-23", "CWE-36", "CWE-73", "cwe-023")) {
            assertThat(Fingerprints.code(variant, file, scope, sink, 0)).as(variant).isEqualTo(root);
        }
        assertThat(Fingerprints.code("CWE-79", file, scope, sink, 0)).isNotEqualTo(root);
    }

    @Test
    void distinctFileOrScopeGiveDistinctIdsAndSeparatorsAreNormalized() {
        String base = Fingerprints.code("CWE-89", DAO, DAO_SCOPE, SQL_SINK, 0);
        assertThat(Fingerprints.code("CWE-89", "src/main/java/com/acme/OtherDao.java", DAO_SCOPE, SQL_SINK, 0))
                .isNotEqualTo(base);
        assertThat(Fingerprints.code("CWE-89", DAO, "com.acme.UserDao#find(long)", SQL_SINK, 0))
                .isNotEqualTo(base);
        assertThat(Fingerprints.code("CWE-89", DAO.replace('/', '\\'), DAO_SCOPE, SQL_SINK, 0)).isEqualTo(base);
        assertThat(Fingerprints.code("CWE-89", "./" + DAO, DAO_SCOPE, SQL_SINK, 0)).isEqualTo(base);
    }

    @Test
    void invalidInputsAreRejected() {
        assertThatThrownBy(() -> Fingerprints.code("not-a-cwe", DAO, DAO_SCOPE, SQL_SINK, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Fingerprints.dependency("pkg:maven/a/b@1", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Fingerprints.code(null, DAO, DAO_SCOPE, SQL_SINK, 0))
                .isInstanceOf(NullPointerException.class);
    }

    // ---------------------------------------------------------------- property-style

    @Test
    void everyOutputMatchesPatternForRandomInputs() {
        Random random = new Random(0x5EC9_19E5L);
        List<String> cwes = new ArrayList<>(List.of("CWE-79", "CWE-611", "CWE-918", "89", "cwe-0022"));
        Fingerprints.CWE_FAMILIES.values().forEach(cwes::addAll);
        for (int i = 0; i < 1_000; i++) {
            String cwe = cwes.get(random.nextInt(cwes.size()));
            String code = Fingerprints.code(cwe, randomText(random), randomText(random), randomText(random),
                    random.nextInt(10));
            assertThat(code).as("code #%d", i).matches(ID_PATTERN).matches("code:CWE-\\d+:[0-9a-f]{24}");

            List<String> ids = new ArrayList<>();
            int count = 1 + random.nextInt(3);
            for (int k = 0; k < count; k++) {
                ids.add(randomText(random));
            }
            String dep = Fingerprints.dependency(randomText(random), ids);
            assertThat(dep).as("dep #%d", i).matches(ID_PATTERN).startsWith("dep:");
        }
    }

    @Test
    void outputsAreStableAcrossRepeatedCalls() {
        String first = Fingerprints.code("CWE-89", DAO, DAO_SCOPE, SQL_SINK, 0);
        for (int i = 0; i < 100; i++) {
            assertThat(Fingerprints.code("CWE-89", DAO, DAO_SCOPE, SQL_SINK, 0)).isEqualTo(first);
        }
    }

    /** Random text drawn from ASCII, Java punctuation, control chars, whitespace, non-ASCII and surrogate pairs. */
    private static String randomText(Random random) {
        String alphabet = "abcXYZ019 ._:/@+-%\"'\\/*\n\t\r{}()[];,#?=&\u0000\u001f\u00e9\u4e2d";
        int length = random.nextInt(40);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            if (random.nextInt(20) == 0) {
                sb.appendCodePoint(0x1F600 + random.nextInt(50));
            } else {
                sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
        }
        return sb.toString();
    }
}
