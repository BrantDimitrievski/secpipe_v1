package ca.carleton.secpipe.agents.remediation;

import ca.carleton.secpipe.agents.remediation.RemediationAgent.RegressionTest;
import ca.carleton.secpipe.agents.testgen.TestGenInput;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.DisproverResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.Step;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.Adequacy;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Control;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Promotion;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Result;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Spec;
import ca.carleton.secpipe.contracts.spi.SourceIndex;
import ca.carleton.secpipe.sandbox.MavenWorkspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Test adequacy of a candidate fix (WO-M.2, FR-15): which of the patch's changed production lines the regression test
 * executes, measured with the JaCoCo agent of the runner image.
 *
 * <p><b>Changed lines</b> are the added or modified lines on the new side of the diff, in production files only
 * ({@value #PRODUCTION_ROOT}{@code **.java} at the root or below any module directory, never after
 * {@code src/test/}). Only <i>executable</i> changed lines count (lines the JaCoCo report lists); imports, braces
 * and comments have no bytecode. Every executable changed line covered → adequate.
 *
 * <p><b>Measuring</b>: {@link #run} replaces the plain regression-test run of the remediation loop (step 2 of
 * {@link RemediationLoopNode}): the same offline compile and JUnit console launch, in the sandbox through
 * {@link MavenWorkspace#offlineSpec} (all 13 controls, {@code --network none}), with
 * {@code -javaagent:}{@value #AGENT} added, then {@code jacococli.jar report --xml} inside the same container. The
 * agent's exit code never changes the test outcome (the script exits with the launcher's code), and an image
 * without JaCoCo simply yields no report ("coverage not measured"). Only the declared files (the JUnit reports and
 * {@value #XML}) are promoted, by the runner's ArtifactPromoter. Classes defined by a test's own class loader
 * without a code source are included ({@code inclnolocationclasses=true}), because regression tests often reload
 * the class under test child-first.
 *
 * <p>The decision is pure: {@link #measure(String, JacocoXml.Coverage)} / {@link #evaluate}. Not adequate is a
 * limitation recorded in {@code fix_attempts[].adequacy}; it never fails an attempt.
 */
public final class AdequacyCheck {

    /** Production source root; only changed lines below it count. */
    public static final String PRODUCTION_ROOT = "src/main/java/";
    /** Where the runner image installs the JaCoCo agent (WO-M.2 Dockerfile). */
    public static final String AGENT = "/opt/jacoco/jacocoagent.jar";
    /** Where the runner image installs the JaCoCo command-line interface. */
    public static final String CLI = "/opt/jacoco/jacococli.jar";
    /** Execution data file, relative to the scratch copy (never promoted). */
    public static final String EXEC = "target/secpipe-jacoco/jacoco.exec";
    /** The XML report, relative to the scratch copy (promoted). */
    public static final String XML = "target/secpipe-jacoco/jacoco.xml";
    public static final long XML_MAX_BYTES = 8L * 1024 * 1024;

    private static final Pattern FQCN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_$]*)*");
    private static final Pattern JAVA_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final Pattern INCLUDE = Pattern.compile("[A-Za-z0-9_.$]+(\\.\\*|\\$\\*)?");
    private static final int SNIPPET_CONTEXT = 5;
    private static final int OUTPUT_TAIL = 600;

    private AdequacyCheck() {
    }

    /** The adequacy plus the uncovered executable changed lines (repo path to sorted line numbers). */
    public record Measurement(Adequacy adequacy, Map<String, List<Integer>> uncovered) {
        public Measurement {
            Map<String, List<Integer>> copy = new TreeMap<>();
            uncovered.forEach((k, v) -> copy.put(k, List.copyOf(new TreeSet<>(v))));
            uncovered = Map.copyOf(copy);
        }

        /** {@code adequacy} with {@code note} (replacing any note). */
        public Measurement withNote(String note) {
            return new Measurement(new Adequacy(adequacy.changedLinesTotal(), adequacy.changedLinesCovered(),
                    adequacy.adequate(), Optional.of(note)), uncovered);
        }

        /** The uncovered lines as {@code path:L1,L2; path2:L3}. */
        public String uncoveredText() {
            return uncovered.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue().stream()
                    .map(String::valueOf).collect(Collectors.joining(","))).collect(Collectors.joining("; "));
        }
    }

    /** A regression run with coverage: the test outcome and, when the report was promoted and readable, coverage. */
    public record CoverageRun(SuiteBaseline.TestRun tests, Optional<JacocoXml.Coverage> coverage) { }

    // ---------------------------------------------------------------- changed lines

    /**
     * The package-relative source path of a production file (WO-M.4): the part after the first {@code src/main/java/}
     * segment sequence, which may be at the root or below any module directory ({@code core/src/main/java/a/B.java}
     * gives {@code a/B.java}). Empty when the path is not a {@code .java} file, has no such sequence, or has
     * {@code src/test/} before it.
     */
    static Optional<String> productionSource(String path) {
        if (!path.endsWith(".java")) {
            return Optional.empty();
        }
        int at = path.startsWith(PRODUCTION_ROOT) ? 0 : path.indexOf("/" + PRODUCTION_ROOT) + 1;
        if (at <= 0 && !path.startsWith(PRODUCTION_ROOT)) {
            return Optional.empty();
        }
        String before = "/" + path.substring(0, at);
        if (before.contains("/src/test/")) {
            return Optional.empty();
        }
        String rel = path.substring(at + PRODUCTION_ROOT.length());
        return rel.isEmpty() || rel.equals(".java") ? Optional.empty() : Optional.of(rel);
    }

    /** Added/modified new-side line numbers per production {@code .java} file of {@code patch} (sorted). */
    public static Map<String, SortedSet<Integer>> changedLines(String patch) {
        Map<String, SortedSet<Integer>> out = new TreeMap<>();
        for (UnifiedDiff.FilePatch fp : UnifiedDiff.parse(patch)) {
            String path = fp.newPath();
            if (fp.isDeletion() || productionSource(path).isEmpty()) {
                continue;
            }
            for (UnifiedDiff.Hunk h : fp.hunks()) {
                int line = h.newStart();
                for (UnifiedDiff.Line l : h.lines()) {
                    if (l.op() == '+') {
                        out.computeIfAbsent(path, k -> new TreeSet<>()).add(line);
                        line++;
                    } else if (l.op() == ' ') {
                        line++;
                    }
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- decision

    /** Adequacy of {@code patch} under {@code coverage} (the interface WO-R.4 and WO-E.6 consume). */
    public static Adequacy measure(String patch, JacocoXml.Coverage coverage) {
        return evaluate(changedLines(patch), Optional.of(coverage)).adequacy();
    }

    /**
     * Decides adequacy. No coverage → not adequate ("coverage not measured"). Changed files without coverage data
     * make the result not adequate and are named in the note. No executable changed line at all → adequate.
     */
    public static Measurement evaluate(Map<String, ? extends Set<Integer>> changed,
                                       Optional<JacocoXml.Coverage> coverage) {
        if (coverage.isEmpty()) {
            return new Measurement(new Adequacy(0, 0, false,
                    Optional.of("coverage not measured: no readable JaCoCo report from the regression run")),
                    Map.of());
        }
        JacocoXml.Coverage cov = coverage.get();
        int total = 0;
        int covered = 0;
        Map<String, List<Integer>> uncovered = new TreeMap<>();
        List<String> noData = new ArrayList<>();
        for (Map.Entry<String, ? extends Set<Integer>> e : new TreeMap<>(changed).entrySet()) {
            Optional<String> src = productionSource(e.getKey());
            String source = src.orElse(e.getKey());
            if (src.isEmpty() || !cov.hasFile(source)) {
                noData.add(e.getKey());
                continue;
            }
            for (int line : new TreeSet<>(e.getValue())) {
                Optional<Boolean> c = cov.covered(source, line);
                if (c.isEmpty()) {
                    continue;
                }
                total++;
                if (c.get()) {
                    covered++;
                } else {
                    uncovered.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(line);
                }
            }
        }
        boolean adequate = covered == total && noData.isEmpty();
        List<String> notes = new ArrayList<>();
        if (!uncovered.isEmpty()) {
            notes.add("changed lines not executed by the regression test: " + new Measurement(
                    new Adequacy(0, 0, false, Optional.empty()), uncovered).uncoveredText());
        }
        if (!noData.isEmpty()) {
            notes.add("no coverage data for " + String.join(", ", noData));
        }
        if (notes.isEmpty() && total == 0) {
            notes.add("no executable changed lines");
        }
        return new Measurement(new Adequacy(total, covered, adequate,
                notes.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", notes))), uncovered);
    }

    // ---------------------------------------------------------------- sandbox run

    /**
     * Compiles {@code copy} offline and runs the test classes {@code classes} with the JaCoCo agent, then writes the
     * XML report, all in one sandbox run (counts as one against {@code --max-sandbox-runs}; the caller acquires it).
     */
    public static CoverageRun run(SandboxRunner runner, MavenWorkspace ws, Path copy, List<String> classes,
                                  List<String> includes, String purpose) throws InterruptedException {
        if (classes.isEmpty()) {
            throw new IllegalArgumentException("at least one test class");
        }
        classes.forEach(AdequacyCheck::requireFqcn);
        Spec base = ws.offlineSpec(command(classes, includes), purpose);
        List<Promotion> promote = new ArrayList<>();
        SuiteBaseline.REPORTS.forEach(r -> promote.add(new Promotion(r, SuiteBaseline.REPORT_MAX_BYTES)));
        promote.add(new Promotion(XML, XML_MAX_BYTES));
        Spec spec = new Spec(base.image(), base.command(), copy, base.mounts(), base.env(), base.limits(),
                base.network(), Set.copyOf(EnumSet.allOf(Control.class)), List.copyOf(promote), purpose);
        Result result;
        try {
            result = runner.run(spec);
        } catch (SandboxRunner.SandboxRefusedException e) {
            return new CoverageRun(new SuiteBaseline.NotRun(SuiteBaseline.NotRun.Reason.REFUSED,
                    "sandbox refused: " + e.getMessage(), e.missing()), Optional.empty());
        }
        SuiteBaseline.TestRun tests = SuiteBaseline.classify(result, copy);
        if (tests instanceof SuiteBaseline.NotRun nr && nr.reason() == SuiteBaseline.NotRun.Reason.NO_REPORT) {
            tests = new SuiteBaseline.NotRun(nr.reason(), nr.detail() + "; output: " + tail(result.stderrTail() + "\n"
                    + result.stdoutTail()), nr.missing());
        }
        Path xml = copy.resolve(XML).normalize();
        boolean promoted = result.promoted().stream().anyMatch(p -> p.normalize().equals(xml))
                && Files.isRegularFile(xml, LinkOption.NOFOLLOW_LINKS);
        return new CoverageRun(tests, promoted ? JacocoXml.parse(xml) : Optional.empty());
    }

    /**
     * JaCoCo {@code includes} patterns for the changed production files: {@code <package>.*} per package (a class in
     * the default package: {@code <Class>} and {@code <Class>$*}). Only those classes are instrumented, never the
     * test launcher or libraries. Paths that are not plain Java names are skipped (no coverage data for them).
     */
    public static List<String> includes(Map<String, ? extends Set<Integer>> changed) {
        TreeSet<String> out = new TreeSet<>();
        for (String path : changed.keySet()) {
            Optional<String> src = productionSource(path);
            if (src.isEmpty()) {
                continue;
            }
            String rel = src.get().substring(0, src.get().length() - ".java".length());
            int slash = rel.lastIndexOf('/');
            String pkg = slash < 0 ? "" : rel.substring(0, slash).replace('/', '.');
            String cls = rel.substring(slash + 1);
            if (!JAVA_NAME.matcher(cls).matches() || (!pkg.isEmpty() && !JAVA_NAME.matcher(pkg).matches())) {
                continue;
            }
            if (pkg.isEmpty()) {
                out.add(cls);
                out.add(cls + "$*");
            } else {
                out.add(pkg + ".*");
            }
        }
        return List.copyOf(out);
    }

    /**
     * The shell command: offline test-compile, JUnit console (under the JaCoCo agent when {@code includes} is not
     * empty), then the XML report.
     */
    static List<String> command(List<String> classes, List<String> includes) {
        for (String inc : includes) {
            if (!INCLUDE.matcher(inc).matches()) {
                throw new IllegalArgumentException("not a JaCoCo include pattern: " + inc);
            }
        }
        String mvn = String.join(" ", MavenWorkspace.offlineCommand("test-compile", "dependency:build-classpath",
                "-Dmdep.outputFile=target/secpipe-cp.txt", "-Dmdep.includeScope=test"));
        StringBuilder select = new StringBuilder();
        for (int i = 1; i <= classes.size(); i++) {
            select.append(" --select-class \"$").append(i).append('"');
        }
        String script = mvn + " || exit " + SuiteBaseline.BUILD_FAILED_EXIT + "\n"
                + "mkdir -p target/test-classes " + SuiteBaseline.REPORT_DIR + " target/secpipe-jacoco\n"
                + "agent=\"\"\n"
                + (includes.isEmpty() ? "" : "if [ -f " + AGENT + " ]; then agent=\"-javaagent:" + AGENT
                + "=destfile=" + EXEC + ",inclnolocationclasses=true,includes="
                + String.join(":", includes).replace("$", "\\$")
                + "\"; fi\n")
                + "java $agent -jar " + SuiteBaseline.JUNIT_CONSOLE + " execute --disable-banner --details=none"
                + " --class-path \"target/test-classes:target/classes:$(cat target/secpipe-cp.txt)\""
                + select + " --reports-dir " + SuiteBaseline.REPORT_DIR + "\n"
                + "rc=$?\n"
                + "if [ -f " + EXEC + " ] && [ -f " + CLI + " ]; then java -jar " + CLI + " report " + EXEC
                + " --classfiles target/classes --xml " + XML + " >/dev/null 2>&1"
                + " || echo 'secpipe: jacoco report failed' >&2; fi\n"
                + "exit $rc\n";
        List<String> cmd = new ArrayList<>(List.of("bash", "-c", script, "secpipe-regression-coverage"));
        cmd.addAll(classes);
        return List.copyOf(cmd);
    }

    /**
     * A ustar stream with one regular file, for {@link ca.carleton.secpipe.sandbox.ArtifactPromoter} (WO-M.4: the
     * follow-up test lives in memory, not in a container, so it is promoted from a one-entry tar). The name must fit
     * the 100-byte ustar field.
     */
    static byte[] singleFileTar(String name, byte[] data) {
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        if (nameBytes.length > 100) {
            throw new IllegalArgumentException("path longer than 100 bytes: " + name);
        }
        byte[] h = new byte[512];
        System.arraycopy(nameBytes, 0, h, 0, nameBytes.length);
        putOctal(h, 100, 8, 0644);
        putOctal(h, 108, 8, 0);
        putOctal(h, 116, 8, 0);
        putOctal(h, 124, 12, data.length);
        putOctal(h, 136, 12, 0);
        Arrays.fill(h, 148, 156, (byte) ' ');
        h[156] = '0';
        byte[] magic = "ustar\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, h, 257, magic.length);
        h[263] = '0';
        h[264] = '0';
        int sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        String chk = String.format("%06o", sum);
        System.arraycopy(chk.getBytes(StandardCharsets.US_ASCII), 0, h, 148, 6);
        h[154] = 0;
        h[155] = ' ';
        int padded = (data.length + 511) / 512 * 512;
        byte[] out = new byte[512 + padded + 1024];
        System.arraycopy(h, 0, out, 0, 512);
        System.arraycopy(data, 0, out, 512, data.length);
        return out;
    }

    private static void putOctal(byte[] h, int off, int len, long v) {
        String s = String.format("%0" + (len - 1) + "o", v);
        System.arraycopy(s.getBytes(StandardCharsets.US_ASCII), 0, h, off, len - 1);
        h[off + len - 1] = 0;
    }

    private static String tail(String s) {
        String t = s.strip();
        return t.length() > OUTPUT_TAIL ? t.substring(t.length() - OUTPUT_TAIL) : t;
    }

    private static void requireFqcn(String name) {
        if (!FQCN.matcher(name).matches()) {
            throw new IllegalArgumentException("not a Java class name: " + name);
        }
    }

    // ---------------------------------------------------------------- follow-up testgen

    /**
     * Input for the one extra testgen call: the confirmed record (as a provisional record), an instruction naming the
     * uncovered changed lines, and source slices of the <b>patched</b> copy around them plus the existing regression
     * test (raw text; {@code TestGenAgent} wraps each slice with {@code untrusted(...)}).
     */
    public static TestGenInput followUpInput(Finding.Confirmed record, Measurement m, RegressionTest existing,
                                             SourceIndex patched) {
        String instruction = "Adequacy follow-up: a candidate fix is already applied to the code shown. The existing "
                + "regression test " + RemediationLoopNode.fqcn(existing.path()) + " passes on it but does not "
                + "execute these changed lines: " + m.uncoveredText() + ". Write one additional JUnit 5 test class "
                + "with a different class name that executes those lines and passes on the patched code.";
        List<Step> trace = record.trace().stream()
                .map(s -> new Step(s.kind(), s.file(), s.line(), s.scope(), s.description(), Optional.empty()))
                .toList();
        DisproverResult draft = new DisproverResult(record.fingerprint(), Finding.Verdict.CONFIRMED, true, true,
                List.of(), Optional.empty(), Optional.of(record.rootCause()),
                Optional.of(record.intendedBehavior() + " " + instruction), trace, List.of(), List.of(),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty());
        List<TestGenInput.Snippet> snippets = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : m.uncovered().entrySet()) {
            patched.lines(e.getKey()).filter(l -> !l.isEmpty()).ifPresent(lines -> {
                int from = Math.max(1, e.getValue().get(0) - SNIPPET_CONTEXT);
                int to = Math.min(lines.size(), e.getValue().get(e.getValue().size() - 1) + SNIPPET_CONTEXT);
                if (from <= to) {
                    snippets.add(new TestGenInput.Snippet(e.getKey(), from, to,
                            String.join("\n", lines.subList(from - 1, to))));
                }
            });
        }
        List<String> testLines = existing.source().lines().toList();
        if (!testLines.isEmpty()) {
            snippets.add(new TestGenInput.Snippet(existing.path(), 1, testLines.size(), existing.source()));
        }
        snippets.sort((a, b) -> a.path().equals(b.path()) ? Integer.compare(a.fromLine(), b.fromLine())
                : a.path().compareTo(b.path()));
        return new TestGenInput(record.secpipe().cwe().isEmpty() ? "CWE-unknown" : record.secpipe().cwe().get(0),
                draft, List.of(), List.of(), snippets);
    }
}
