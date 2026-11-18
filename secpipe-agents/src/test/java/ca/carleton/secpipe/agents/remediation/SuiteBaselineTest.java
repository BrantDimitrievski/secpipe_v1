package ca.carleton.secpipe.agents.remediation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Control;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Network;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Promotion;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Result;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Spec;
import ca.carleton.secpipe.sandbox.MavenWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/** {@link SuiteBaseline} with a scripted sandbox (no Docker) on a private copy of the sqli-fixable fixture. */
class SuiteBaselineTest {

    static final Path FIXTURE = Path.of("..", "fixtures", "remediation", "sqli-fixable").toAbsolutePath().normalize();
    static final String FP = "code:CWE-89:00000000000000000000beef";
    static final String FP_TAG = "code:CWE-89:0000000000000000000000a1";

    static final String SUITE_REPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="JUnit Jupiter" tests="5" skipped="1" failures="1" errors="1">
              <testcase name="isMappedToLogin()" classname="com.shop.app.LoginServletTest"/>
              <testcase name="answersServerErrorWhenNoDatabaseIsConfigured()" classname="com.shop.app.LoginServletTest">
                <system-out>ok</system-out>
              </testcase>
              <testcase name="isMappedToProductTags()" classname="com.shop.app.TagFilterTest">
                <skipped message="Assumption failed: no database"/>
              </testcase>
              <testcase name="answersServerErrorWhenNoDatabaseIsConfigured()" classname="com.shop.app.TagFilterTest">
                <failure message="expected 500" type="org.opentest4j.AssertionFailedError">stack</failure>
              </testcase>
              <testcase name="flaky()" classname="com.shop.app.TagFilterTest">
                <error message="boom" type="java.lang.IllegalStateException">stack</error>
              </testcase>
            </testsuite>
            """;

    /** Scripted sandbox: each run takes the next step; records every spec. */
    static final class FakeSandbox implements SandboxRunner {
        interface Step {
            Result run(Spec spec) throws SandboxRefusedException;
        }

        final List<Spec> specs = new ArrayList<>();
        private final List<Step> steps = new ArrayList<>();

        FakeSandbox then(Step step) {
            steps.add(step);
            return this;
        }

        @Override
        public Capabilities probe() {
            return new Capabilities(true, EnumSet.allOf(Control.class), Optional.empty(), Optional.of("fake"));
        }

        @Override
        public Result run(Spec spec) throws SandboxRefusedException {
            specs.add(spec);
            if (steps.isEmpty()) {
                throw new AssertionError("unexpected sandbox run " + spec.purpose());
            }
            return steps.remove(0).run(spec);
        }

        static Step reports(int exit, Map<String, String> files) {
            return spec -> {
                List<Path> promoted = new ArrayList<>();
                for (Map.Entry<String, String> e : new TreeMap<>(files).entrySet()) {
                    assertTrue(spec.promote().stream().anyMatch(p -> p.relativePath().equals(e.getKey())),
                            "undeclared promotion " + e.getKey());
                    Path p = spec.scratchDir().resolve(e.getKey());
                    try {
                        Files.createDirectories(p.getParent());
                        Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                    promoted.add(p);
                }
                return new Result(exit, false, "", "", promoted, Duration.ofSeconds(3), EnumSet.allOf(Control.class));
            };
        }

        static Step report(int exit, String xml) {
            return reports(exit, Map.of(SuiteBaseline.REPORTS.get(0), xml));
        }

        static Step exit(int code, String stdout) {
            return spec -> new Result(code, false, stdout, "", List.of(), Duration.ofSeconds(1), Set.of());
        }
    }

    static RunContext.Rescanner rescanner(List<String> fps, List<Path> seen) {
        return (copy, scope) -> {
            seen.add(copy);
            assertEquals(Set.of(), scope);
            List<Candidate> out = new ArrayList<>();
            for (String fp : fps) {
                out.add(candidate(Optional.of(fp)));
            }
            out.add(candidate(Optional.empty()));
            return out;
        };
    }

    static Candidate candidate(Optional<String> fp) {
        return new Candidate(fp, Finding.Category.CODE, Finding.Origin.TOOL, List.of(), List.of("CWE-89"), List.of(),
                "SQL injection", "m", "c", List.of(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }

    static Path pristine(Path dir) {
        Path p = dir.resolve("pristine");
        SuiteBaseline.freshCopy(FIXTURE, p);
        return p;
    }

    static MavenWorkspace workspace(SandboxRunner runner, Path dir) {
        return new MavenWorkspace(runner, dir.resolve("ws"), MavenWorkspace.Options.defaults());
    }

    static Map<String, byte[]> snapshot(Path root) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                out.put(root.relativize(p).toString(), Files.readAllBytes(p));
            }
        }
        return out;
    }

    @Test
    void captureRecordsOnlyPassingTestsAndTheUnpatchedFingerprints(@TempDir Path dir) throws Exception {
        Path pristine = pristine(dir);
        Map<String, byte[]> before = snapshot(pristine);
        FakeSandbox sandbox = new FakeSandbox().then(FakeSandbox.report(1, SUITE_REPORT));
        List<Path> rescanned = new ArrayList<>();
        Path scratch = dir.resolve("baseline-copy");

        SuiteBaseline.Outcome outcome = SuiteBaseline.capture(sandbox, workspace(sandbox, dir), pristine, scratch,
                rescanner(List.of(FP_TAG, FP, FP), rescanned), dir.resolve("work"));

        SuiteBaseline.Captured captured = assertInstanceOf(SuiteBaseline.Captured.class, outcome);
        assertEquals(List.of("com.shop.app.LoginServletTest#answersServerErrorWhenNoDatabaseIsConfigured()",
                "com.shop.app.LoginServletTest#isMappedToLogin()"), captured.baseline().passingTests());
        assertEquals(List.of(FP_TAG, FP), captured.baseline().fingerprints());
        assertEquals(dir.resolve("work").resolve(SuiteBaseline.FILE_NAME), captured.file());
        assertEquals(Optional.of(captured.baseline()), SuiteBaseline.read(captured.file()));
        assertEquals(List.of(scratch), rescanned);
        // the pristine copy is untouched; the run happened in the fresh copy
        assertEquals(before.keySet(), snapshot(pristine).keySet());
        before.forEach((k, v) -> assertArrayEquals(v, bytes(pristine.resolve(k)), k));
        assertTrue(Files.isRegularFile(scratch.resolve("src/test/java/com/shop/app/LoginServletTest.java")));
    }

    private static byte[] bytes(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void suiteRunIsOfflineWithAllControlsAndPromotesOnlyTheDeclaredReports(@TempDir Path dir) throws Exception {
        Path pristine = pristine(dir);
        FakeSandbox sandbox = new FakeSandbox().then(FakeSandbox.report(0, SUITE_REPORT));
        Path scratch = dir.resolve("copy");
        SuiteBaseline.capture(sandbox, workspace(sandbox, dir), pristine, scratch,
                rescanner(List.of(), new ArrayList<>()), dir.resolve("work"));

        assertEquals(1, sandbox.specs.size());
        Spec spec = sandbox.specs.get(0);
        assertEquals(Network.NONE, spec.network());
        assertEquals(EnumSet.allOf(Control.class), EnumSet.copyOf(spec.required()));
        assertEquals(scratch, spec.scratchDir());
        assertEquals(SuiteBaseline.BASELINE_PURPOSE, spec.purpose());
        assertTrue(spec.mounts().stream().allMatch(SandboxRunner.Mount::readOnly), "cache must be read-only");
        assertEquals(SuiteBaseline.REPORTS, spec.promote().stream().map(Promotion::relativePath).toList());
        assertTrue(spec.env().isEmpty(), "no extra env (no secrets)");
        String script = spec.command().get(2);
        assertTrue(script.contains("mvn -o "), script);
        assertTrue(script.contains("test-compile"), script);
        assertTrue(script.contains("--scan-class-path=target/test-classes"), script);
        assertTrue(script.contains("--reports-dir " + SuiteBaseline.REPORT_DIR), script);
        assertFalse(script.contains("--select-class"), script);
    }

    @Test
    void selectedClassRunUsesTheSameOfflinePathAndRejectsOddNames(@TempDir Path dir) throws Exception {
        FakeSandbox sandbox = new FakeSandbox().then(FakeSandbox.report(0, """
                <testsuite tests="1"><testcase classname="secpipe.generated.T" name="t()"/></testsuite>
                """));
        Path copy = pristine(dir);
        SuiteBaseline.TestRun run = SuiteBaseline.runTests(sandbox, workspace(sandbox, dir), copy,
                Optional.of("secpipe.generated.T"), "regression test secpipe.generated.T");
        SuiteBaseline.Completed done = assertInstanceOf(SuiteBaseline.Completed.class, run);
        assertEquals(List.of("secpipe.generated.T#t()"), done.passing());
        List<String> cmd = sandbox.specs.get(0).command();
        assertEquals("secpipe.generated.T", cmd.get(cmd.size() - 1));
        assertTrue(cmd.get(2).contains("--select-class \"$1\""));
        assertThrows(IllegalArgumentException.class, () -> SuiteBaseline.runTests(sandbox, workspace(sandbox, dir),
                copy, Optional.of("a.b; rm -rf /"), "x"));
    }

    @Test
    void reportsOfSeveralEnginesMergeAndTheWorstStatusWins(@TempDir Path dir) throws Exception {
        String jupiter = """
                <testsuite><testcase classname="A" name="x()"/><testcase classname="A" name="y()"/></testsuite>
                """;
        String vintage = """
                <testsuites><testsuite>
                  <testcase classname="A" name="x()"><failure message="no"/></testcase>
                  <testcase classname="B" name="z"/>
                </testsuite></testsuites>
                """;
        FakeSandbox sandbox = new FakeSandbox().then(FakeSandbox.reports(1,
                Map.of(SuiteBaseline.REPORTS.get(0), jupiter, SuiteBaseline.REPORTS.get(1), vintage)));
        SuiteBaseline.Completed done = assertInstanceOf(SuiteBaseline.Completed.class, SuiteBaseline.runTests(sandbox,
                workspace(sandbox, dir), pristine(dir), Optional.empty(), "suite"));
        assertEquals(List.of("A#y()", "B#z"), done.passing());
        assertEquals(List.of("A#x()"), done.withStatus(SuiteBaseline.TestStatus.FAILED));
        assertEquals(2, done.reports().size());
    }

    @Test
    void buildFailureTimeoutRefusalAndMissingReportGiveNoBaseline(@TempDir Path dir) throws Exception {
        FakeSandbox sandbox = new FakeSandbox()
                .then(FakeSandbox.exit(SuiteBaseline.BUILD_FAILED_EXIT,
                        "[ERROR] COMPILATION ERROR :\n[ERROR] /scratch/src/main/java/A.java:[3,1] cannot find symbol"))
                .then(spec -> new Result(-1, true, "", "", List.of(), Duration.ofMinutes(5), Set.of()))
                .then(spec -> {
                    throw new SandboxRunner.SandboxRefusedException("no", Set.of(Control.NETWORK_NONE));
                })
                .then(FakeSandbox.exit(0, ""))
                .then(FakeSandbox.exit(137, ""))
                .then(FakeSandbox.report(0, "<html>not a report</html>"));
        Path pristine = pristine(dir);
        MavenWorkspace ws = workspace(sandbox, dir);
        List<SuiteBaseline.Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            outcomes.add(SuiteBaseline.capture(sandbox, ws, pristine, dir.resolve("copy" + i),
                    rescanner(List.of(FP), new ArrayList<>()), dir.resolve("work")));
        }
        SuiteBaseline.Unusable build = assertInstanceOf(SuiteBaseline.Unusable.class, outcomes.get(0));
        assertTrue(build.reason().contains("compile error"), build.reason());
        assertTrue(assertInstanceOf(SuiteBaseline.Unusable.class, outcomes.get(1)).reason().contains("wall-clock"));
        SuiteBaseline.Unusable refused = assertInstanceOf(SuiteBaseline.Unusable.class, outcomes.get(2));
        assertEquals(Set.of(Control.NETWORK_NONE), refused.missing());
        assertTrue(assertInstanceOf(SuiteBaseline.Unusable.class, outcomes.get(3)).reason()
                .contains("no JUnit report"));
        assertTrue(assertInstanceOf(SuiteBaseline.Unusable.class, outcomes.get(4)).reason().contains("resource limit"));
        assertTrue(assertInstanceOf(SuiteBaseline.Unusable.class, outcomes.get(5)).reason().contains("unreadable"));
        assertFalse(Files.exists(dir.resolve("work").resolve(SuiteBaseline.FILE_NAME)));
    }

    @Test
    void failingRescanGivesNoBaselineAndRunsNothing(@TempDir Path dir) throws Exception {
        FakeSandbox sandbox = new FakeSandbox();
        SuiteBaseline.Outcome o = SuiteBaseline.capture(sandbox, workspace(sandbox, dir), pristine(dir),
                dir.resolve("copy"), (copy, scope) -> {
                    throw new IllegalStateException("parser crashed");
                }, dir.resolve("work"));
        assertTrue(assertInstanceOf(SuiteBaseline.Unusable.class, o).reason().contains("parser crashed"));
        assertTrue(sandbox.specs.isEmpty());
    }

    @Test
    void suitePassedNeedsEveryBaselineTestToPassAgain() {
        SuiteBaseline.Baseline baseline = new SuiteBaseline.Baseline(List.of("B#b()", "A#a()", "A#a()"), List.of());
        assertEquals(List.of("A#a()", "B#b()"), baseline.passingTests());
        Path r = Path.of("r.xml");
        var pass = SuiteBaseline.TestStatus.PASSED;
        SuiteBaseline.Completed superset = new SuiteBaseline.Completed(Map.of("A#a()", pass, "B#b()", pass,
                "secpipe.generated.T#t()", SuiteBaseline.TestStatus.FAILED), 1, List.of(r));
        assertTrue(baseline.suitePassed(superset));
        SuiteBaseline.Completed skipped = new SuiteBaseline.Completed(Map.of("A#a()", pass,
                "B#b()", SuiteBaseline.TestStatus.SKIPPED), 0, List.of(r));
        assertFalse(baseline.suitePassed(skipped));
        assertEquals(List.of("B#b()"), baseline.brokenTests(skipped));
        SuiteBaseline.Completed missing = new SuiteBaseline.Completed(Map.of("B#b()", pass), 0, List.of(r));
        assertEquals(List.of("A#a()"), baseline.brokenTests(missing));
        assertTrue(new SuiteBaseline.Baseline(List.of(), List.of()).suitePassed(missing));
    }

    @Test
    void newFingerprintsAreThoseNotInTheBaseline() {
        SuiteBaseline.Baseline baseline = new SuiteBaseline.Baseline(List.of(), List.of(FP, FP_TAG));
        assertEquals(List.of("code:CWE-78:1"), baseline.newFingerprints(List.of(FP_TAG, "code:CWE-78:1",
                "code:CWE-78:1")));
        assertEquals(List.of(), baseline.newFingerprints(List.of()));
    }

    @Test
    void storedBaselineIsByteStableAndStrictlyRead(@TempDir Path dir) throws Exception {
        SuiteBaseline.Baseline b = new SuiteBaseline.Baseline(List.of("Z#z()", "A#a()"), List.of(FP_TAG, FP));
        Path f1 = dir.resolve("one").resolve(SuiteBaseline.FILE_NAME);
        Path f2 = dir.resolve("two").resolve(SuiteBaseline.FILE_NAME);
        SuiteBaseline.write(b, f1);
        SuiteBaseline.write(new SuiteBaseline.Baseline(List.of("A#a()", "Z#z()"), List.of(FP, FP_TAG)), f2);
        assertArrayEquals(Files.readAllBytes(f1), Files.readAllBytes(f2));
        assertEquals(Optional.of(b), SuiteBaseline.read(f1));
        assertTrue(Files.readString(f1).contains(SuiteBaseline.FORMAT));

        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, "{\"format\":\"" + SuiteBaseline.FORMAT
                + "\",\"passing_tests\":[],\"fingerprints\":[],\"extra\":1}");
        assertEquals(Optional.empty(), SuiteBaseline.read(bad));
        Files.writeString(bad, "{\"format\":\"" + SuiteBaseline.FORMAT
                + "\",\"passing_tests\":[1],\"fingerprints\":[]}");
        assertEquals(Optional.empty(), SuiteBaseline.read(bad));
        Files.writeString(bad, "not json");
        assertEquals(Optional.empty(), SuiteBaseline.read(bad));
        assertEquals(Optional.empty(), SuiteBaseline.read(dir.resolve("missing.json")));
    }

    @Test
    void freshCopySkipsBuildOutputAndRefusesANonEmptyTarget(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src");
        Files.createDirectories(src.resolve("target/classes"));
        Files.createDirectories(src.resolve(".git"));
        Files.writeString(src.resolve("pom.xml"), "<project/>");
        Files.writeString(src.resolve("target/classes/A.class"), "x");
        Files.writeString(src.resolve(".git/HEAD"), "ref");
        Path copy = dir.resolve("copy");
        SuiteBaseline.freshCopy(src, copy);
        assertEquals(Set.of("pom.xml"), snapshot(copy).keySet());
        assertThrows(IllegalArgumentException.class, () -> SuiteBaseline.freshCopy(src, copy));
    }
}
