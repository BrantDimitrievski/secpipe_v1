package ca.carleton.secpipe.agents.testgen;

import ca.carleton.secpipe.contracts.agent.AgentOutputs.TestGenResult;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Static gate for generated regression tests (WO-G.6). It never compiles or runs the source (that is WO-G.7, inside
 * {@code SandboxRunner}); it parses it with JavaParser and rejects tests that would use the network, touch files
 * outside the scratch/{@code @TempDir} area, reach into SecPipe, exit the JVM, spawn processes or sleep. A test with
 * any violation is treated as malformed agent output: the caller asks a fresh agent, never edits the test.
 *
 * <p>WO-G.11: setup checks must be JUnit assumptions ({@code Assumptions.assumeTrue}), so that only the vulnerability
 * assertion can fail. Java {@code assert} statements, {@code if (...) fail(...)} / {@code throw new AssertionError} and
 * (with a {@link TestGenResult}) assertions whose literal message does not report {@code expected_failure} are
 * rejected as {@link Rule#SETUP_CHECK}.
 */
public final class GeneratedTestChecker {

    /** Maximum source length in lines (PLAN Part 5 A8). */
    public static final int MAX_LINES = 200;

    /** The rules, in the order they are reported for the same line. */
    public enum Rule {
        /** The source does not parse as Java. */
        PARSE_ERROR,
        /** No {@code package secpipe.generated[...]} declaration. */
        PACKAGE,
        /** {@code package_name} / {@code class_name} do not match the source. */
        DECLARATION_MISMATCH,
        /** No JUnit 5 test method (or a JUnit 4 import). */
        JUNIT5,
        /** More than {@link #MAX_LINES} lines. */
        TOO_LONG,
        /** Network APIs: java.net, javax.net, sockets, HTTP clients, JNDI, RMI. */
        NETWORK,
        /** File access by absolute or escaping literal path, or via user.home / user.dir. */
        FILESYSTEM,
        /** Any reference to SecPipe's own classes ({@code ca.carleton.secpipe}). */
        SECPIPE_REFLECTION,
        /** {@code System.exit}, {@code Runtime.exit}/{@code halt}. */
        SYSTEM_EXIT,
        /** Process creation. */
        PROCESS,
        /** {@code Thread.sleep} / {@code TimeUnit.sleep}. */
        SLEEP,
        /** A setup check written as an assertion instead of a JUnit assumption (WO-G.11). */
        SETUP_CHECK
    }

    /** One problem; {@code line} is 1-based, 0 if unknown. */
    public record Violation(Rule rule, int line, String detail) {
        public Violation {
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(detail, "detail");
        }

        @Override
        public String toString() {
            return rule + " at line " + line + ": " + detail;
        }
    }

    private static final Comparator<Violation> ORDER = Comparator.comparingInt(Violation::line)
            .thenComparing(Violation::rule).thenComparing(Violation::detail);

    private static final Pattern PACKAGE_NAME = Pattern.compile("secpipe\\.generated(\\.[a-z][a-z0-9_]*)*");
    private static final List<String> NETWORK_PREFIXES = List.of("java.net", "javax.net", "jdk.net", "java.rmi",
            "javax.rmi", "javax.naming", "sun.net", "com.sun.net", "okhttp3", "org.apache.http", "org.apache.hc",
            "java.nio.channels.SocketChannel", "java.nio.channels.ServerSocketChannel",
            "java.nio.channels.DatagramChannel", "java.nio.channels.AsynchronousSocketChannel",
            "java.nio.channels.AsynchronousServerSocketChannel");
    private static final String SECPIPE = "ca.carleton.secpipe";
    private static final Set<String> FILE_TYPES = Set.of("File", "FileInputStream", "FileOutputStream", "FileReader",
            "FileWriter", "RandomAccessFile", "java.io.File", "java.io.FileInputStream", "java.io.FileOutputStream",
            "java.io.FileReader", "java.io.FileWriter", "java.io.RandomAccessFile");
    private static final Set<String> PATH_SCOPES = Set.of("Paths", "Path", "Files", "java.nio.file.Paths",
            "java.nio.file.Path", "java.nio.file.Files");
    private static final Set<String> HOST_DIR_PROPERTIES = Set.of("user.home", "user.dir");
    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest",
            "TestFactory", "org.junit.jupiter.api.Test", "org.junit.jupiter.params.ParameterizedTest",
            "org.junit.jupiter.api.RepeatedTest", "org.junit.jupiter.api.TestFactory");
    private static final Pattern ABSOLUTE = Pattern.compile("^([/\\\\~]|[A-Za-z]:[/\\\\]).*");
    private static final Set<String> ASSERTION_SCOPES = Set.of("", "Assertions", "org.junit.jupiter.api.Assertions");
    private static final Set<String> ASSERTION_ERRORS = Set.of("AssertionError", "java.lang.AssertionError",
            "AssertionFailedError", "org.opentest4j.AssertionFailedError");
    private static final int MIN_PARTIAL_MESSAGE = 12;
    private static final String USE_ASSUMPTIONS = "; setup checks must use Assumptions.assumeTrue/assumeFalse";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern ESCAPING = Pattern.compile("(^|.*[/\\\\])\\.\\.([/\\\\].*|$)");

    private GeneratedTestChecker() {
    }

    /** Violations of {@code source}, sorted by line, rule and detail; empty means the test may go to the sandbox. */
    public static List<Violation> check(String source) {
        Objects.requireNonNull(source, "source");
        TreeSet<Violation> out = new TreeSet<>(ORDER);
        Optional<CompilationUnit> unit = parse(source, out);
        unit.ifPresent(cu -> checkUnit(cu, out));
        long lines = source.lines().count();
        if (lines > MAX_LINES) {
            out.add(new Violation(Rule.TOO_LONG, 0, lines + " lines (max " + MAX_LINES + ")"));
        }
        return List.copyOf(out);
    }

    /** {@link #check(String)} plus: the declared package and a top-level class match the result's fields. */
    public static List<Violation> check(TestGenResult result) {
        Objects.requireNonNull(result, "result");
        TreeSet<Violation> out = new TreeSet<>(ORDER);
        out.addAll(check(result.source()));
        Optional<CompilationUnit> unit = parse(result.source(), new TreeSet<>(ORDER));
        unit.ifPresent(cu -> {
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            if (!pkg.equals(result.packageName())) {
                out.add(new Violation(Rule.DECLARATION_MISMATCH, line(cu),
                        "package_name " + result.packageName() + " but source declares '" + pkg + "'"));
            }
            boolean found = cu.getTypes().stream().map(TypeDeclaration::getNameAsString)
                    .anyMatch(result.className()::equals);
            if (!found) {
                out.add(new Violation(Rule.DECLARATION_MISMATCH, line(cu),
                        "class_name " + result.className() + " is not a top-level type of the source"));
            }
            assertionMessages(cu, result.expectedFailure() == null ? "" : result.expectedFailure(), out);
        });
        return List.copyOf(out);
    }

    private static Optional<CompilationUnit> parse(String source, Set<Violation> out) {
        ParserConfiguration config = new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21);
        ParseResult<CompilationUnit> r = new JavaParser(config).parse(source);
        if (!r.isSuccessful() || r.getResult().isEmpty()) {
            String msg = r.getProblems().isEmpty() ? "unparseable" : r.getProblems().get(0).getVerboseMessage();
            int line = r.getProblems().isEmpty() ? 0 : r.getProblems().get(0).getLocation()
                    .flatMap(l -> l.getBegin().getRange()).map(rg -> rg.begin.line).orElse(0);
            out.add(new Violation(Rule.PARSE_ERROR, line, firstLine(msg)));
            return Optional.empty();
        }
        return r.getResult();
    }

    private static void checkUnit(CompilationUnit cu, Set<Violation> out) {
        Optional<PackageDeclaration> pkg = cu.getPackageDeclaration();
        if (pkg.isEmpty() || !PACKAGE_NAME.matcher(pkg.get().getNameAsString()).matches()) {
            out.add(new Violation(Rule.PACKAGE, pkg.map(GeneratedTestChecker::line).orElse(1),
                    "package must be secpipe.generated or a lower-case sub-package, found '"
                            + pkg.map(PackageDeclaration::getNameAsString).orElse("") + "'"));
        }
        checkJunit(cu, out);
        for (ImportDeclaration imp : cu.getImports()) {
            names(imp, imp.getNameAsString(), out);
            if (imp.getNameAsString().equals("org.junit") || imp.getNameAsString().startsWith("org.junit.")
                    && !imp.getNameAsString().startsWith("org.junit.jupiter.")) {
                out.add(new Violation(Rule.JUNIT5, line(imp), "JUnit 4 import " + imp.getNameAsString()));
            }
        }
        for (ClassOrInterfaceType t : cu.findAll(ClassOrInterfaceType.class)) {
            names(t, t.asString(), out);
        }
        for (FieldAccessExpr f : cu.findAll(FieldAccessExpr.class)) {
            names(f, f.toString(), out);
        }
        for (StringLiteralExpr s : cu.findAll(StringLiteralExpr.class)) {
            if (s.getValue().contains(SECPIPE)) {
                out.add(new Violation(Rule.SECPIPE_REFLECTION, line(s), "string names SecPipe: " + s.getValue()));
            }
        }
        for (ObjectCreationExpr n : cu.findAll(ObjectCreationExpr.class)) {
            String type = n.getType().getNameWithScope();
            if (FILE_TYPES.contains(type)) {
                literalPaths(n.getArguments(), "new " + type, out);
            }
            if (type.equals("ProcessBuilder") || type.equals("java.lang.ProcessBuilder")) {
                out.add(new Violation(Rule.PROCESS, line(n), "new " + type));
            }
        }
        for (MethodCallExpr m : cu.findAll(MethodCallExpr.class)) {
            calls(m, out);
        }
        for (AssertStmt a : cu.findAll(AssertStmt.class)) {
            out.add(new Violation(Rule.SETUP_CHECK, line(a), "Java assert statement" + USE_ASSUMPTIONS));
        }
        for (IfStmt i : cu.findAll(IfStmt.class)) {
            boolean fails = i.getThenStmt().findAll(MethodCallExpr.class).stream()
                    .anyMatch(GeneratedTestChecker::isFail)
                    || i.getThenStmt().findAll(ThrowStmt.class).stream().anyMatch(t -> t.getExpression()
                    instanceof ObjectCreationExpr n && ASSERTION_ERRORS.contains(n.getType().getNameWithScope()));
            if (fails) {
                out.add(new Violation(Rule.SETUP_CHECK, line(i), "if (...) fail/throw AssertionError; use an "
                        + "Assertions.assert* call for the vulnerability check" + USE_ASSUMPTIONS));
            }
        }
    }

    private static boolean isFail(MethodCallExpr m) {
        return m.getNameAsString().equals("fail")
                && ASSERTION_SCOPES.contains(m.getScope().map(Expression::toString).orElse(""));
    }

    private static boolean isAssertion(MethodCallExpr m) {
        String name = m.getNameAsString();
        return (name.startsWith("assert") && !name.equals("assertThat") || name.equals("fail"))
                && ASSERTION_SCOPES.contains(m.getScope().map(Expression::toString).orElse(""));
    }

    /**
     * An assertion whose message argument (the last one) contains string literals, none of which reports
     * {@code expected_failure}, can only fail for a reason other than the vulnerability: it is a setup check.
     */
    private static void assertionMessages(CompilationUnit cu, String expectedFailure, Set<Violation> out) {
        String expected = normalize(expectedFailure);
        for (MethodCallExpr m : cu.findAll(MethodCallExpr.class)) {
            if (!isAssertion(m) || m.getArguments().isEmpty()
                    || m.getArguments().size() < 2 && !m.getNameAsString().equals("fail")) {
                continue;
            }
            Expression message = m.getArgument(m.getArguments().size() - 1);
            List<String> literals = new ArrayList<>();
            message.walk(StringLiteralExpr.class, l -> literals.add(normalize(l.getValue())));
            message.walk(TextBlockLiteralExpr.class, l -> literals.add(normalize(l.getValue())));
            if (literals.isEmpty()) {
                continue;
            }
            boolean reports = !expected.isEmpty() && literals.stream().anyMatch(l -> l.contains(expected)
                    || l.length() >= MIN_PARTIAL_MESSAGE && expected.contains(l));
            if (!reports) {
                out.add(new Violation(Rule.SETUP_CHECK, line(m), m.getNameAsString() + " message \""
                        + literals.get(0) + "\" does not report expected_failure" + USE_ASSUMPTIONS));
            }
        }
    }

    private static String normalize(String s) {
        return WHITESPACE.matcher(s).replaceAll(" ").strip();
    }

    private static void checkJunit(CompilationUnit cu, Set<Violation> out) {
        boolean hasTest = cu.findAll(AnnotationExpr.class).stream()
                .anyMatch(a -> TEST_ANNOTATIONS.contains(a.getNameAsString()));
        if (!hasTest) {
            out.add(new Violation(Rule.JUNIT5, 1, "no JUnit 5 @Test method"));
        }
    }

    private static void calls(MethodCallExpr m, Set<Violation> out) {
        String name = m.getNameAsString();
        String scope = m.getScope().map(Expression::toString).orElse("");
        if (name.equals("exit") && (scope.equals("System") || scope.equals("java.lang.System"))
                || (name.equals("exit") || name.equals("halt")) && scope.contains("Runtime")) {
            out.add(new Violation(Rule.SYSTEM_EXIT, line(m), scope + "." + name));
        }
        if (name.equals("exec") && scope.contains("Runtime") || scope.startsWith("ProcessHandle")) {
            out.add(new Violation(Rule.PROCESS, line(m), scope + "." + name));
        }
        if (name.equals("sleep") && (scope.equals("Thread") || scope.equals("java.lang.Thread")
                || scope.startsWith("TimeUnit.") || scope.startsWith("java.util.concurrent.TimeUnit."))) {
            out.add(new Violation(Rule.SLEEP, line(m), scope + "." + name));
        }
        if (PATH_SCOPES.contains(scope) || name.equals("resolve") || name.equals("resolveSibling")) {
            literalPaths(m.getArguments(), scope.isEmpty() ? name : scope + "." + name, out);
        }
        if ((name.equals("getProperty") || name.equals("getenv")) && !m.getArguments().isEmpty()
                && m.getArgument(0) instanceof StringLiteralExpr s && HOST_DIR_PROPERTIES.contains(s.getValue())) {
            out.add(new Violation(Rule.FILESYSTEM, line(m), "reads host directory property " + s.getValue()));
        }
    }

    private static void literalPaths(List<Expression> args, String api, Set<Violation> out) {
        for (Expression a : args) {
            if (a instanceof StringLiteralExpr s) {
                String v = s.getValue();
                if (ABSOLUTE.matcher(v).matches() || ESCAPING.matcher(v).matches()) {
                    out.add(new Violation(Rule.FILESYSTEM, line(s),
                            api + " opens a path outside the test's temp directory: \"" + v + "\""));
                }
            }
        }
    }

    private static void names(Node node, String name, Set<Violation> out) {
        for (String p : NETWORK_PREFIXES) {
            if (name.equals(p) || name.startsWith(p + ".") || name.startsWith(p + "<")) {
                out.add(new Violation(Rule.NETWORK, line(node), "uses " + name));
                break;
            }
        }
        if (name.equals(SECPIPE) || name.startsWith(SECPIPE + ".")) {
            out.add(new Violation(Rule.SECPIPE_REFLECTION, line(node), "references " + name));
        }
    }

    private static int line(Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
