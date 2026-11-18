package ca.carleton.secpipe.agents.evidence;

import ca.carleton.secpipe.contracts.agent.AgentOutputs.TestGenResult;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Control;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Promotion;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Result;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Spec;
import ca.carleton.secpipe.sandbox.DockerSandboxRunner;
import ca.carleton.secpipe.sandbox.MavenWorkspace;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Runs one generated regression test inside {@link SandboxRunner} and classifies the result. The test is written into
 * a fresh copy of the pristine workspace; the copy is compiled offline with Maven ({@code test-compile}, all 13
 * controls, {@code --network none}) and the test class is run with the JUnit console launcher of the runner image, so
 * the target POM is never edited. Only the JUnit XML report is declared for promotion.
 *
 * <p>Only a failing assertion ({@code <failure>}) whose message contains testgen's {@code expected_failure} (after
 * whitespace normalisation, WO-G.11) confirms. Any other failure is {@link Kind#UNEXPECTED_FAILURE}; a failed JUnit
 * assumption ({@code <skipped>}) is {@link Kind#SKIPPED}. Compile errors, other exceptions ({@code <error>}), timeouts
 * and resource limits never confirm.
 */
final class RegressionTestRun {

    /** Exit code of the wrapper script when the Maven build (compile) failed before any test ran. */
    static final int BUILD_FAILED_EXIT = 90;
    /** JUnit legacy XML report, relative to the scratch copy. */
    static final String REPORT = "target/secpipe-junit/TEST-junit-jupiter.xml";
    static final long REPORT_MAX_BYTES = 1024 * 1024;
    static final String JUNIT_CONSOLE = "/opt/junit/junit-platform-console-standalone.jar";
    static final String PURPOSE_PREFIX = "regression test ";

    private static final Pattern FQCN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final Set<String> SKIP_DIRS = Set.of(".git", "target");
    private static final Pattern THROWABLE_PREFIX = Pattern.compile("^[A-Za-z_$][\\w$.]*(Exception|Error):\\s*");
    private static final String ASSUMPTION_PREFIX = "Assumption failed:";

    /** How a run ended. */
    enum Kind { FAILED_AS_EXPECTED, UNEXPECTED_FAILURE, SKIPPED, PASSED, COMPILE_ERROR, ERROR, TIMEOUT, RESOURCE_LIMIT,
        REFUSED }

    /** The classified result; {@code report} is the promoted JUnit XML on the host when there was one. */
    record Observation(Kind kind, String detail, Optional<Path> report, Set<Control> missing) {
        Observation {
            missing = Set.copyOf(missing);
        }

        static Observation of(Kind kind, String detail) {
            return new Observation(kind, detail, Optional.empty(), Set.of());
        }
    }

    private RegressionTestRun() {
    }

    static String fqcn(TestGenResult test) {
        String name = test.packageName() + "." + test.className();
        if (!FQCN.matcher(name).matches()) {
            throw new IllegalArgumentException("not a Java class name: " + name);
        }
        return name;
    }

    static String testFile(TestGenResult test) {
        return "src/test/java/" + test.packageName().replace('.', '/') + "/" + test.className() + ".java";
    }

    /** The shell command run in the sandbox: offline test-compile and classpath, then the JUnit console. */
    static List<String> command(String fqcn) {
        String mvn = String.join(" ", MavenWorkspace.offlineCommand("test-compile", "dependency:build-classpath",
                "-Dmdep.outputFile=target/secpipe-cp.txt", "-Dmdep.includeScope=test"));
        String script = mvn + " || exit " + BUILD_FAILED_EXIT + "\n"
                + "exec java -jar " + JUNIT_CONSOLE + " execute --disable-banner --details=none"
                + " --class-path \"target/test-classes:target/classes:$(cat target/secpipe-cp.txt)\""
                + " --select-class \"$1\" --reports-dir target/secpipe-junit\n";
        return List.of("bash", "-c", script, "secpipe-regression-test", fqcn);
    }

    /** Copies {@code pristine} to {@code scratch}, writes the test, runs it in the sandbox and classifies the run. */
    static Observation run(SandboxRunner runner, MavenWorkspace ws, Path pristine, Path scratch, TestGenResult test)
            throws InterruptedException {
        String fqcn = fqcn(test);
        try {
            copyTree(pristine, scratch);
            Path file = scratch.resolve(testFile(test));
            Files.createDirectories(file.getParent());
            Files.writeString(file, test.source(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot prepare regression-test scratch copy " + scratch, e);
        }
        Spec base = ws.offlineSpec(command(fqcn), PURPOSE_PREFIX + fqcn);
        Spec spec = new Spec(base.image(), base.command(), scratch, base.mounts(), base.env(), base.limits(),
                base.network(), Set.copyOf(EnumSet.allOf(Control.class)),
                List.of(new Promotion(REPORT, REPORT_MAX_BYTES)), base.purpose());
        Result result;
        try {
            result = runner.run(spec);
        } catch (SandboxRunner.SandboxRefusedException e) {
            return new Observation(Kind.REFUSED, e.getMessage(), Optional.empty(), e.missing());
        }
        Path report = scratch.resolve(REPORT);
        Optional<Path> promoted = result.promoted().stream().filter(p -> p.normalize().equals(report.normalize()))
                .findFirst();
        return classify(result, promoted, test.expectedFailure());
    }

    /**
     * Classifies a sandbox run. {@code expectedFailure} is testgen's {@code expected_failure}: a {@code <failure>}
     * confirms only when its message contains it (whitespace-normalised string containment, no LLM).
     */
    static Observation classify(Result result, Optional<Path> report, String expectedFailure) {
        if (result.timedOut()) {
            return Observation.of(Kind.TIMEOUT, "wall-clock limit reached after " + result.elapsed().toSeconds()
                    + " s");
        }
        if (DockerSandboxRunner.isResourceLimitExit(result)) {
            return Observation.of(Kind.RESOURCE_LIMIT, "killed at a resource limit (exit 137)");
        }
        String output = result.stdoutTail() + "\n" + result.stderrTail();
        if (result.exitCode() == BUILD_FAILED_EXIT) {
            Kind kind = output.contains("COMPILATION ERROR") || output.contains("Compilation failure")
                    ? Kind.COMPILE_ERROR : Kind.ERROR;
            return Observation.of(kind, (kind == Kind.COMPILE_ERROR ? "compile error" : "build failed")
                    + firstError(output).map(e -> ": " + e).orElse(""));
        }
        if (report.isEmpty() || !Files.isRegularFile(report.get(), LinkOption.NOFOLLOW_LINKS)) {
            return Observation.of(Kind.ERROR, "no JUnit report (exit " + result.exitCode() + ")"
                    + firstError(output).map(e -> ": " + e).orElse(""));
        }
        Optional<Report> parsed = parse(report.get());
        if (parsed.isEmpty()) {
            return Observation.of(Kind.ERROR, "unreadable JUnit report (exit " + result.exitCode() + ")");
        }
        Report r = parsed.get();
        Observation o;
        if (r.tests() == 0) {
            o = Observation.of(Kind.ERROR, "no test ran (exit " + result.exitCode() + ")");
        } else if (!r.errors().isEmpty()) {
            o = Observation.of(Kind.ERROR, "test errored: " + r.errors().get(0).summary());
        } else if (!r.failures().isEmpty()) {
            Optional<Issue> unexpected = r.failures().stream().filter(f -> !matches(f.message(), expectedFailure))
                    .findFirst();
            o = unexpected.map(f -> Observation.of(Kind.UNEXPECTED_FAILURE, f.firstLine()))
                    .orElseGet(() -> Observation.of(Kind.FAILED_AS_EXPECTED, r.failures().get(0).summary()));
        } else if (!r.skipped().isEmpty()) {
            o = Observation.of(Kind.SKIPPED, r.skipped().get(0).firstLine());
        } else if (result.exitCode() == 0) {
            o = Observation.of(Kind.PASSED, r.tests() + " test(s) passed");
        } else {
            o = Observation.of(Kind.ERROR, "exit " + result.exitCode() + " without a failing assertion");
        }
        return new Observation(o.kind(), o.detail(), report, Set.of());
    }

    /** Same as {@link FailureMatch#matches(String, String)}. */
    static boolean matches(String message, String expectedFailure) {
        return FailureMatch.matches(message, expectedFailure);
    }

    /**
     * One {@code <failure>}, {@code <error>} or {@code <skipped>}: {@code summary} is "class#test: type: first line",
     * {@code message} the full message (or the element text when there is no message attribute) and
     * {@code firstLine} its first line without a leading exception class or "Assumption failed:" prefix.
     */
    record Issue(String summary, String message, String firstLine) { }

    /** Test count and the failures (assertions), errors (other exceptions) and skips (failed assumptions). */
    record Report(int tests, List<Issue> failures, List<Issue> errors, List<Issue> skipped) {
        Report {
            failures = List.copyOf(failures);
            errors = List.copyOf(errors);
            skipped = List.copyOf(skipped);
        }
    }

    static Optional<Report> parse(Path xml) {
        try (InputStream in = Files.newInputStream(xml, LinkOption.NOFOLLOW_LINKS)) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document doc = f.newDocumentBuilder().parse(in);
            NodeList cases = doc.getElementsByTagName("testcase");
            List<Issue> failures = new ArrayList<>();
            List<Issue> errors = new ArrayList<>();
            List<Issue> skipped = new ArrayList<>();
            for (int i = 0; i < cases.getLength(); i++) {
                Element tc = (Element) cases.item(i);
                String name = tc.getAttribute("classname") + "#" + tc.getAttribute("name");
                summarize(tc, "failure", name).ifPresent(failures::add);
                summarize(tc, "error", name).ifPresent(errors::add);
                summarize(tc, "skipped", name).ifPresent(skipped::add);
            }
            return Optional.of(new Report(cases.getLength(), failures, errors, skipped));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<Issue> summarize(Element testcase, String tag, String name) {
        NodeList nodes = testcase.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return Optional.empty();
        }
        Element e = (Element) nodes.item(0);
        String type = e.getAttribute("type");
        String full = e.hasAttribute("message") ? e.getAttribute("message") : e.getTextContent();
        String message = full.lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
        String text = name + ": " + (type.isEmpty() ? tag : type) + (message.isEmpty() ? "" : ": " + message);
        String first = THROWABLE_PREFIX.matcher(message).replaceFirst("");
        if (first.startsWith(ASSUMPTION_PREFIX)) {
            first = first.substring(ASSUMPTION_PREFIX.length()).strip();
        }
        if (first.isEmpty()) {
            first = type.isEmpty() ? tag : type;
        }
        return Optional.of(new Issue(cap(text, 500), full, cap(first, 300)));
    }

    private static String cap(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static Optional<String> firstError(String output) {
        return output.lines().filter(l -> l.contains("[ERROR]")).map(String::strip).findFirst()
                .map(l -> l.length() > 300 ? l.substring(0, 300) + "..." : l);
    }

    static String names(Set<Control> controls) {
        return controls.stream().map(Enum::name).collect(Collectors.toCollection(TreeSet::new)).stream()
                .collect(Collectors.joining(", "));
    }

    /** Copies regular files and directories; skips symlinks, {@code .git/} and {@code target/}. */
    static void copyTree(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(from) && (attrs.isSymbolicLink() || SKIP_DIRS.contains(dir.getFileName().toString()))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING,
                            LinkOption.NOFOLLOW_LINKS);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
