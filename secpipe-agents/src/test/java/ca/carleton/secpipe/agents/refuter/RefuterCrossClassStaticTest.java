package ca.carleton.secpipe.agents.refuter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.spi.SourceIndex;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** WO-G.12: {@code Other.NAME} is trusted only when the scanned {@code Other} declares it as a literal constant. */
class RefuterCrossClassStaticTest {

    private static String snippet(String expr) {
        return """
                package demo;

                public class Snippet {
                    void run(java.sql.Connection conn) throws Exception {
                        String sql = "select * from t where k = " + %s;
                        conn.createStatement().executeQuery(sql); // SINK
                    }
                }
                """.formatted(expr);
    }

    private static SourceIndex index(Map<String, String> sources) {
        TreeMap<String, List<String>> files = new TreeMap<>();
        sources.forEach((path, text) -> files.put(path, List.of(text.split("\n", -1))));
        return new SourceIndex() {
            @Override
            public List<String> files() {
                return List.copyOf(files.keySet());
            }

            @Override
            public boolean exists(String path) {
                return files.containsKey(path);
            }

            @Override
            public Optional<List<String>> lines(String path) {
                return Optional.ofNullable(files.get(path));
            }

            @Override
            public Optional<String> line(String path, int oneBasedLine) {
                return lines(path).filter(l -> oneBasedLine >= 1 && oneBasedLine <= l.size())
                        .map(l -> l.get(oneBasedLine - 1));
            }
        };
    }

    private static Optional<Refutation> refute(String expr, String otherBody) {
        String src = snippet(expr);
        CompilationUnit cu = RefuterFixtures.parse(src);
        SourceIndex idx = index(Map.of(RefuterFixtures.FILE, src,
                "src/main/java/demo/Other.java", "package demo;\npublic class Other {\n" + otherBody + "\n}\n"));
        Candidate c = RefuterFixtures.snippetCandidate(src, "CWE-89");
        return new Refuter().check(c, cu, ScannedTypes.of(idx));
    }

    @Test
    void staticFinalLiteralOfAScannedClassIsRefuted() {
        Optional<Refutation> r = refute("Other.NAME", "    static final String NAME = \"fixed\";");
        assertTrue(r.isPresent());
        assertEquals(Refutation.CONSTANT_VALUE, r.get().rule());
    }

    @Test
    void staticFinalConcatenationOfLiteralsIsRefuted() {
        assertTrue(refute("Other.NAME", "    public static final String NAME = \"a\" + \"b\" + 3;").isPresent());
    }

    @Test
    void nonFinalStaticOfAScannedClassIsNotRefuted() {
        assertTrue(refute("Other.NAME", "    public static String NAME = \"fixed\";").isEmpty());
    }

    @Test
    void staticFinalInitialisedFromAMethodCallIsNotRefuted() {
        String body = "    static final String NAME = System.getProperty(\"user.input\");";
        assertTrue(refute("Other.NAME", body).isEmpty());
    }

    @Test
    void staticFinalWithoutAnInitialiserOrNotDeclaredIsNotRefuted() {
        assertTrue(refute("Other.NAME", "    static final String NAME;\n    static { NAME = System.getenv(\"X\"); }")
                .isEmpty());
        assertTrue(refute("Other.NAME", "    static final String OTHER_NAME = \"x\";").isEmpty());
    }

    @Test
    void jdkConstantKeepsTheOldAssumption() {
        // Integer is not in the scanned sources, so the long-standing assumption still applies
        assertTrue(refute("Integer.MAX_VALUE", "    static String NAME = \"x\";").isPresent());
    }

    @Test
    void withoutAScannedIndexTheOldAssumptionStillHolds() {
        String src = snippet("Other.NAME");
        Candidate c = RefuterFixtures.snippetCandidate(src, "CWE-89");
        assertTrue(new Refuter().check(c, RefuterFixtures.parse(src)).isPresent());
    }
}
