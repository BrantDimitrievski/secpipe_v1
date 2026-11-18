package ca.carleton.secpipe.agents.remediation;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Control;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Promotion;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Result;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Spec;
import ca.carleton.secpipe.sandbox.DockerSandboxRunner;
import ca.carleton.secpipe.sandbox.MavenWorkspace;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The once-per-run baseline of the remediation loop (WO-M.1): the project's test suite is run once on an unpatched
 * fresh copy of the pristine workspace, offline in the sandbox (all 13 controls, {@code --network none}, through
 * {@link MavenWorkspace#offlineSpec}), and the ids of the tests that pass are recorded together with the detect
 * fingerprint set of the same unpatched copy ({@link RunContext.Rescanner}). The baseline lives in the work
 * directory ({@value #FILE_NAME}), never in run metadata.
 *
 * <p>Tests run the same way as the regression test of WO-G.7: {@code mvn -o test-compile dependency:build-classpath}
 * then the JUnit console launcher of the runner image (surefire's JUnit Platform provider is resolved lazily and is
 * not in the prefetched cache). The console writes JUnit legacy XML reports under {@value #REPORT_DIR}; only those
 * declared files are promoted (ArtifactPromoter, inside the runner). A test id is {@code classname#name} of a
 * {@code <testcase>}. A test passes only when its testcase has no {@code failure}, {@code error}, {@code skipped}
 * (a failed assumption is skipped, never a pass) or flaky child. The suite of a patched copy passes
 * ({@code suite_passed}) when every baseline-passing test still passes.
 */
public final class SuiteBaseline {

    /** File name of the stored baseline inside the work directory. */
    public static final String FILE_NAME = "suite-baseline.json";
    /** Format marker of the stored baseline. */
    public static final String FORMAT = "secpipe-suite-baseline/1";
    /** Exit code of the wrapper script when the offline Maven build (compile, test-compile) failed. */
    public static final int BUILD_FAILED_EXIT = 90;
    /** Directory of the JUnit XML reports, relative to the scratch copy. */
    public static final String REPORT_DIR = "target/secpipe-suite";
    /** Declared report files (one per JUnit engine that can run); missing ones are simply not promoted. */
    public static final List<String> REPORTS = List.of(REPORT_DIR + "/TEST-junit-jupiter.xml",
            REPORT_DIR + "/TEST-junit-vintage.xml", REPORT_DIR + "/TEST-junit-platform-suite.xml");
    public static final long REPORT_MAX_BYTES = 8L * 1024 * 1024;
    /** Purpose of the baseline suite run; {@link #runTests} purposes start with {@value #PURPOSE_PREFIX}. */
    public static final String PURPOSE_PREFIX = "project tests";
    public static final String BASELINE_PURPOSE = PURPOSE_PREFIX + " (baseline)";
    static final String JUNIT_CONSOLE = "/opt/junit/junit-platform-console-standalone.jar";
    /** Surefire's default includes ({@code Test*}, {@code *Test}, {@code *Tests}, {@code *TestCase}). */
    static final String INCLUDE_CLASSNAME = "^(.*[.$])?(Test[^.$]*|[^.$]*Tests?|[^.$]*TestCase)$";

    private static final Pattern FQCN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_$]*)*");
    private static final Set<String> SKIP_DIRS = Set.of(".git", "target");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SuiteBaseline() {
    }

    // ---------------------------------------------------------------- data

    /** Status of one test id; when an id occurs more than once the worst status wins. */
    public enum TestStatus { PASSED, SKIPPED, FAILED, ERROR }

    /** The baseline: passing test ids and detect fingerprints of the unpatched copy, both sorted and unique. */
    public record Baseline(List<String> passingTests, List<String> fingerprints) {
        public Baseline {
            passingTests = List.copyOf(new TreeSet<>(passingTests));
            fingerprints = List.copyOf(new TreeSet<>(fingerprints));
        }

        /** {@code suite_passed}: every baseline-passing test passed in {@code after}. */
        public boolean suitePassed(Completed after) {
            return brokenTests(after).isEmpty();
        }

        /** Baseline-passing tests that did not pass in {@code after} (missing, skipped, failed, errored), sorted. */
        public List<String> brokenTests(Completed after) {
            return passingTests.stream().filter(id -> after.tests().get(id) != TestStatus.PASSED).toList();
        }

        /** Fingerprints in {@code rescanned} that the unpatched copy did not have, sorted. */
        public List<String> newFingerprints(Collection<String> rescanned) {
            return new TreeSet<>(rescanned).stream().filter(fp -> !fingerprints.contains(fp)).toList();
        }
    }

    /** The outcome of one sandboxed test run. */
    public sealed interface TestRun permits Completed, BuildFailed, NotRun { }

    /** The tests ran and a report was promoted; {@code tests} maps test id to status (sorted by id). */
    public record Completed(Map<String, TestStatus> tests, int exitCode, List<Path> reports) implements TestRun {
        public Completed {
            tests = Map.copyOf(tests);
            reports = List.copyOf(reports);
        }

        public Map<String, TestStatus> sorted() {
            return new TreeMap<>(tests);
        }

        public List<String> passing() {
            return withStatus(TestStatus.PASSED);
        }

        public List<String> withStatus(TestStatus status) {
            return new TreeMap<>(tests).entrySet().stream().filter(e -> e.getValue() == status)
                    .map(Map.Entry::getKey).toList();
        }
    }

    /** The offline Maven build failed before any test ran; {@code compileError} when javac reported errors. */
    public record BuildFailed(boolean compileError, String detail, String logTail) implements TestRun { }

    /**
     * No usable result: refused (missing controls), timed out, killed at a resource limit, or no readable report.
     * Never a test failure.
     */
    public record NotRun(Reason reason, String detail, Set<Control> missing) implements TestRun {
        public enum Reason { REFUSED, TIMEOUT, RESOURCE_LIMIT, NO_REPORT }

        public NotRun {
            missing = Set.copyOf(missing);
        }
    }

    /** Outcome of {@link #capture}. */
    public sealed interface Outcome permits Captured, Unusable { }

    /** The baseline was taken and written to {@code file}; {@code suite} is the raw baseline run. */
    public record Captured(Baseline baseline, Path file, Completed suite) implements Outcome { }

    /** No baseline: remediation cannot be verified in this run; {@code reason} is a blocker text. */
    public record Unusable(String reason, Set<Control> missing) implements Outcome {
        public Unusable {
            missing = Set.copyOf(missing);
        }
    }

    // ---------------------------------------------------------------- capture

    /**
     * Copies {@code pristine} to the fresh directory {@code scratch} (no attempt sees another's edits), rescans the
     * unpatched copy, runs the whole suite in the sandbox and writes the baseline to
     * {@code workDir/}{@value #FILE_NAME}. Counts as one sandbox run.
     *
     * @param pristine the prepared scratch copy from {@link MavenWorkspace#prepare} (never modified)
     */
    public static Outcome capture(SandboxRunner runner, MavenWorkspace ws, Path pristine, Path scratch,
                                  RunContext.Rescanner rescanner, Path workDir) throws InterruptedException {
        freshCopy(pristine, scratch);
        List<Candidate> scanned;
        try {
            scanned = rescanner.rescan(scratch, Set.of());
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return new Unusable("baseline rescan failed: " + e.getMessage(), Set.of());
        }
        List<String> fingerprints = new ArrayList<>();
        for (Candidate c : scanned) {
            c.fingerprint().ifPresent(fingerprints::add);
        }
        TestRun run = runTests(runner, ws, scratch, Optional.empty(), BASELINE_PURPOSE);
        return switch (run) {
            case Completed done -> {
                Baseline baseline = new Baseline(done.passing(), fingerprints);
                Path file = workDir.resolve(FILE_NAME);
                write(baseline, file);
                yield new Captured(baseline, file, done);
            }
            case BuildFailed bf -> new Unusable("baseline test suite did not build: " + bf.detail(), Set.of());
            case NotRun nr -> new Unusable("baseline test suite not run: " + nr.detail(), nr.missing());
        };
    }

    // ---------------------------------------------------------------- running tests

    /**
     * Runs tests in the existing scratch copy {@code copy} in the sandbox: the whole suite when {@code selectClass} is
     * empty, otherwise only that class (e.g. the regression test, which need not match the suite's name pattern).
     * The copy is compiled offline first ({@code test-compile}); a build failure is {@link BuildFailed}.
     */
    public static TestRun runTests(SandboxRunner runner, MavenWorkspace ws, Path copy, Optional<String> selectClass,
                                   String purpose) throws InterruptedException {
        selectClass.ifPresent(SuiteBaseline::requireFqcn);
        Spec base = ws.offlineSpec(command(selectClass), purpose);
        List<Promotion> promote = REPORTS.stream().map(r -> new Promotion(r, REPORT_MAX_BYTES)).toList();
        Spec spec = new Spec(base.image(), base.command(), copy, base.mounts(), base.env(), base.limits(),
                base.network(), Set.copyOf(EnumSet.allOf(Control.class)), promote, purpose);
        Result result;
        try {
            result = runner.run(spec);
        } catch (SandboxRunner.SandboxRefusedException e) {
            return new NotRun(NotRun.Reason.REFUSED, "sandbox refused: " + e.getMessage(), e.missing());
        }
        return classify(result, copy);
    }

    /** The shell command: offline test-compile and test classpath, then the JUnit console launcher. */
    static List<String> command(Optional<String> selectClass) {
        String mvn = String.join(" ", MavenWorkspace.offlineCommand("test-compile", "dependency:build-classpath",
                "-Dmdep.outputFile=target/secpipe-cp.txt", "-Dmdep.includeScope=test"));
        String select = selectClass.isPresent() ? " --select-class \"$1\""
                : " --scan-class-path=target/test-classes --include-classname '" + INCLUDE_CLASSNAME + "'";
        String script = mvn + " || exit " + BUILD_FAILED_EXIT + "\n"
                + "mkdir -p target/test-classes " + REPORT_DIR + "\n"
                + "exec java -jar " + JUNIT_CONSOLE + " execute --disable-banner --details=none"
                + " --class-path \"target/test-classes:target/classes:$(cat target/secpipe-cp.txt)\""
                + select + " --reports-dir " + REPORT_DIR + "\n";
        List<String> cmd = new ArrayList<>(List.of("bash", "-c", script, "secpipe-project-tests"));
        selectClass.ifPresent(cmd::add);
        return List.copyOf(cmd);
    }

    static TestRun classify(Result result, Path copy) {
        if (result.timedOut()) {
            return new NotRun(NotRun.Reason.TIMEOUT, "wall-clock limit reached after " + result.elapsed().toSeconds()
                    + " s", Set.of());
        }
        if (DockerSandboxRunner.isResourceLimitExit(result)) {
            return new NotRun(NotRun.Reason.RESOURCE_LIMIT, "killed at a resource limit (exit 137)", Set.of());
        }
        String output = result.stdoutTail() + "\n" + result.stderrTail();
        if (result.exitCode() == BUILD_FAILED_EXIT) {
            boolean compile = output.contains("COMPILATION ERROR") || output.contains("Compilation failure");
            return new BuildFailed(compile, (compile ? "compile error" : "build failed")
                    + firstError(output).map(e -> ": " + e).orElse(""), tail(output));
        }
        List<Path> reports = new ArrayList<>();
        for (String r : REPORTS) {
            Path expected = copy.resolve(r).normalize();
            if (result.promoted().stream().anyMatch(p -> p.normalize().equals(expected))
                    && Files.isRegularFile(expected, LinkOption.NOFOLLOW_LINKS)) {
                reports.add(expected);
            }
        }
        if (reports.isEmpty()) {
            return new NotRun(NotRun.Reason.NO_REPORT, "no JUnit report (exit " + result.exitCode() + ")"
                    + firstError(output).map(e -> ": " + e).orElse(""), Set.of());
        }
        Map<String, TestStatus> tests = new TreeMap<>();
        for (Path report : reports) {
            Optional<Map<String, TestStatus>> parsed = parse(report);
            if (parsed.isEmpty()) {
                return new NotRun(NotRun.Reason.NO_REPORT, "unreadable JUnit report " + copy.relativize(report),
                        Set.of());
            }
            parsed.get().forEach((id, st) -> tests.merge(id, st, SuiteBaseline::worse));
        }
        return new Completed(tests, result.exitCode(), reports);
    }

    /** Test id to status for one JUnit legacy XML report; empty when the file is not a readable report. */
    static Optional<Map<String, TestStatus>> parse(Path xml) {
        try (InputStream in = Files.newInputStream(xml, LinkOption.NOFOLLOW_LINKS)) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document doc = f.newDocumentBuilder().parse(in);
            String root = doc.getDocumentElement().getTagName();
            if (!root.equals("testsuite") && !root.equals("testsuites")) {
                return Optional.empty();
            }
            NodeList cases = doc.getElementsByTagName("testcase");
            Map<String, TestStatus> out = new TreeMap<>();
            for (int i = 0; i < cases.getLength(); i++) {
                Element tc = (Element) cases.item(i);
                out.merge(tc.getAttribute("classname") + "#" + tc.getAttribute("name"), status(tc),
                        SuiteBaseline::worse);
            }
            return Optional.of(out);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static TestStatus status(Element testcase) {
        TestStatus st = TestStatus.PASSED;
        NodeList children = testcase.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            TestStatus child = switch (((Element) n).getTagName()) {
                case "error", "rerunError", "flakyError" -> TestStatus.ERROR;
                case "failure", "rerunFailure", "flakyFailure" -> TestStatus.FAILED;
                case "skipped" -> TestStatus.SKIPPED;
                default -> TestStatus.PASSED;
            };
            st = worse(st, child);
        }
        return st;
    }

    private static TestStatus worse(TestStatus a, TestStatus b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    // ---------------------------------------------------------------- storage

    /** Writes the baseline as stable, sorted JSON (byte-identical for the same baseline). */
    public static void write(Baseline baseline, Path file) {
        ObjectNode root = JSON.createObjectNode();
        root.put("format", FORMAT);
        ArrayNode tests = root.putArray("passing_tests");
        baseline.passingTests().forEach(tests::add);
        ArrayNode fps = root.putArray("fingerprints");
        baseline.fingerprints().forEach(fps::add);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write suite baseline " + file, e);
        }
    }

    /** Reads a baseline written by {@link #write}; empty if missing or not exactly that format. */
    public static Optional<Baseline> read(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            JsonNode root = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isObject() || root.size() != 3 || !FORMAT.equals(root.path("format").asString(""))) {
                return Optional.empty();
            }
            Optional<List<String>> tests = strings(root.get("passing_tests"));
            Optional<List<String>> fps = strings(root.get("fingerprints"));
            if (tests.isEmpty() || fps.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Baseline(tests.get(), fps.get()));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<List<String>> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Optional.empty();
        }
        List<String> out = new ArrayList<>();
        Iterator<JsonNode> it = node.iterator();
        while (it.hasNext()) {
            JsonNode n = it.next();
            if (!n.isString()) {
                return Optional.empty();
            }
            out.add(n.asString());
        }
        return Optional.of(out);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Copies {@code pristine} into {@code scratch} with plain NIO: regular files and directories only, no symlinks,
     * no {@code .git/} or {@code target/}. {@code scratch} must not exist yet or be empty, so every copy is fresh.
     */
    public static void freshCopy(Path pristine, Path scratch) {
        try {
            if (Files.exists(scratch, LinkOption.NOFOLLOW_LINKS)) {
                try (var entries = Files.list(scratch)) {
                    if (entries.findAny().isPresent()) {
                        throw new IllegalArgumentException("scratch directory is not empty: " + scratch);
                    }
                }
            }
            Files.createDirectories(scratch);
            Files.walkFileTree(pristine, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(pristine)
                            && (attrs.isSymbolicLink() || SKIP_DIRS.contains(dir.getFileName().toString()))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(scratch.resolve(pristine.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isRegularFile() && !attrs.isSymbolicLink()) {
                        Files.copy(file, scratch.resolve(pristine.relativize(file).toString()),
                                LinkOption.NOFOLLOW_LINKS);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot copy " + pristine + " to " + scratch, e);
        }
    }

    private static void requireFqcn(String name) {
        if (!FQCN.matcher(name).matches()) {
            throw new IllegalArgumentException("not a Java class name: " + name);
        }
    }

    private static Optional<String> firstError(String output) {
        return output.lines().filter(l -> l.contains("[ERROR]")).map(String::strip).findFirst()
                .map(l -> l.length() > 300 ? l.substring(0, 300) + "..." : l);
    }

    /** The last 40 lines (at most 4000 chars) of the build output, for the next attempt's prompt. */
    static String tail(String output) {
        List<String> lines = output.strip().lines().toList();
        String t = String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
        return t.length() > 4000 ? t.substring(t.length() - 4000) : t;
    }
}
