package ca.carleton.secpipe.agents.refuter;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Test helpers: parse snippets, build candidates whose sink is the line marked {@code // SINK}. */
final class RefuterFixtures {

    static final String FILE = "src/main/java/demo/Snippet.java";
    static final Path SHOP = Path.of("..", "fixtures", "mvp-samples", "shop-samples");

    private RefuterFixtures() {
    }

    static CompilationUnit parse(String source) {
        ParseResult<CompilationUnit> r = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)).parse(source);
        if (!r.isSuccessful() || r.getResult().isEmpty()) {
            throw new IllegalArgumentException("snippet does not parse: " + r.getProblems());
        }
        return r.getResult().get();
    }

    static int lineOf(String source, String marker) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException("marker not found: " + marker);
    }

    static Candidate candidate(String file, String cwe, int sinkLine, String fingerprintSuffix) {
        List<Finding.TraceStep> trace = List.of(
                new Finding.TraceStep(Finding.TraceKind.ENTRYPOINT, file, 1, "Snippet#doPost(HttpServletRequest)",
                        "getParameter"),
                new Finding.TraceStep(Finding.TraceKind.SINK, file, sinkLine, "Snippet#doPost(HttpServletRequest)",
                        "sink call"));
        return new Candidate(Optional.of("code:" + cwe + ":" + fingerprintSuffix), Finding.Category.CODE,
                Finding.Origin.TOOL,
                List.of(new Finding.ToolSource("builtin", "SP-JAVA", Optional.of(Finding.Score5.HIGH),
                        Optional.of(file), Optional.of(sinkLine))),
                List.of(cwe), List.of(), "Injection claim", "Tool flagged a tainted sink.",
                "Request parameter reaches the sink.", trace,
                List.of(new Finding.Evidence(file, sinkLine, "sink")), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(Finding.Score5.HIGH), Optional.of(Finding.Level3.MEDIUM),
                Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }

    /** Candidate for the {@code // SINK} line of an in-memory snippet. */
    static Candidate snippetCandidate(String source, String cwe) {
        return candidate(FILE, cwe, lineOf(source, "// SINK"), "000000000000000000000001");
    }

    /** Runs one rule against the {@code // SINK} line of a snippet (all sink calls on that line must agree). */
    static Optional<Refutation> runRule(RefuterRule rule, String source, String cwe) {
        CompilationUnit cu = parse(source);
        List<SinkSite> sites = SinkLocator.locate(cu, FILE, lineOf(source, "// SINK"), cwe);
        if (sites.isEmpty()) {
            throw new IllegalArgumentException("no sink on the marked line");
        }
        List<Optional<Refutation>> all = sites.stream().map(rule::check).toList();
        return all.stream().allMatch(Optional::isPresent) ? all.getFirst() : Optional.empty();
    }

    /** Wraps a method body in a servlet-shaped class. */
    static String servlet(String body) {
        return "package demo;\n"
                + "public class Snippet extends javax.servlet.http.HttpServlet {\n"
                + "    private static final String TABLE = \"users\";\n"
                + "    protected void doPost(javax.servlet.http.HttpServletRequest request,\n"
                + "            javax.servlet.http.HttpServletResponse response) throws Exception {\n"
                + "        String param = request.getParameter(\"p\");\n"
                + body
                + "    }\n"
                + "}\n";
    }

    static String resource(String name) {
        try (InputStream in = RefuterFixtures.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String shopSource(String className) {
        try {
            return Files.readString(SHOP.resolve("src/main/java/com/shop/app/" + className + ".java"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
