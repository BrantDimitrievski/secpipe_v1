package ca.carleton.secpipe.agents.remediation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.Adequacy;
import ca.carleton.secpipe.contracts.finding.Finding.FixAttempt;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.spi.Budget;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Control;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Network;
import ca.carleton.secpipe.llm.CallBudget;
import ca.carleton.secpipe.llm.FakeLlmClient;
import ca.carleton.secpipe.sandbox.MavenWorkspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link AdequacyCheck} (WO-M.2): changed-line mapping, the adequate / not-adequate decision, the coverage run spec,
 * and the remediation loop's adequacy step with the fake LLM and a fake sandbox that writes JaCoCo reports.
 */
class AdequacyCheckTest {

    static final String LOGIN = RemediationLoopNodeTest.LOGIN;
    static final String FP = RemediationLoopNodeTest.fp("c001");

    @TempDir
    Path tmp;

    static String fixturePatch() {
        return RemediationLoopNodeTest.read(RemediationLoopNodeTest.FIXTURE.resolve("fix/prepared-statement.patch"));
    }

    static JacocoXml.Coverage coverage(String name) {
        return JacocoXml.parse(JacocoXmlTest.resource(name)).orElseThrow();
    }

    // ------------------------------------------------------------------ changed lines

    @Test
    void changedLinesAreNewSideAdditionsInProductionFilesOnly() {
        Map<String, SortedSet<Integer>> changed = AdequacyCheck.changedLines(fixturePatch());
        assertEquals(Map.of(LOGIN, new TreeSet<>(List.of(11, 20, 21, 22))), changed);

        String testAndResource = "--- a/src/test/java/a/ATest.java\n+++ b/src/test/java/a/ATest.java\n"
                + "@@ -1,1 +1,2 @@\n class ATest {\n+    int x;\n"
                + "--- a/src/main/resources/app.properties\n+++ b/src/main/resources/app.properties\n"
                + "@@ -1,1 +1,1 @@\n-a=1\n+a=2\n"
                + "--- a/src/main/java/a/A.java\n+++ b/src/main/java/a/A.java\n"
                + "@@ -3,3 +3,3 @@\n x\n-y\n z\n+w\n";
        assertEquals(Map.of("src/main/java/a/A.java", new TreeSet<>(List.of(5))),
                AdequacyCheck.changedLines(testAndResource));
    }

    // ------------------------------------------------------------------ decision

    @Test
    void everyExecutableChangedLineCoveredIsAdequate() {
        Adequacy a = AdequacyCheck.measure(fixturePatch(), coverage("login-covered.xml"));
        assertEquals(new Adequacy(3, 3, true, Optional.empty()), a, "line 11 (import) is not executable");
    }

    @Test
    void anUncoveredChangedLineIsNotAdequateAndNamed() {
        AdequacyCheck.Measurement m = AdequacyCheck.evaluate(AdequacyCheck.changedLines(fixturePatch()),
                Optional.of(coverage("login-line22-missed.xml")));
        assertEquals(3, m.adequacy().changedLinesTotal());
        assertEquals(2, m.adequacy().changedLinesCovered());
        assertFalse(m.adequacy().adequate());
        assertEquals(Map.of(LOGIN, List.of(22)), m.uncovered());
        assertTrue(m.adequacy().note().orElseThrow().contains(LOGIN + ":22"), m.adequacy().note().toString());
    }

    @Test
    void noCoverageOrNoDataForAChangedFileIsNotAdequate() {
        AdequacyCheck.Measurement none = AdequacyCheck.evaluate(AdequacyCheck.changedLines(fixturePatch()),
                Optional.empty());
        assertFalse(none.adequacy().adequate());
        assertTrue(none.adequacy().note().orElseThrow().startsWith("coverage not measured"));

        AdequacyCheck.Measurement noData = AdequacyCheck.evaluate(
                Map.of("src/main/java/com/shop/app/Other.java", Set.of(4)),
                Optional.of(coverage("login-covered.xml")));
        assertEquals(new Adequacy(0, 0, false,
                Optional.of("no coverage data for src/main/java/com/shop/app/Other.java")), noData.adequacy());

        AdequacyCheck.Measurement importsOnly = AdequacyCheck.evaluate(Map.of(LOGIN, Set.of(11)),
                Optional.of(coverage("login-covered.xml")));
        assertEquals(new Adequacy(0, 0, true, Optional.of("no executable changed lines")), importsOnly.adequacy());
    }

    // ------------------------------------------------------------------ coverage run

    @Test
    void coverageRunIsOneOfflineSandboxRunThatPromotesOnlyTheReports() throws Exception {
        CoverageSandbox sandbox = new CoverageSandbox();
        MavenWorkspace ws = new MavenWorkspace(sandbox, tmp.resolve("ws"), MavenWorkspace.Options.defaults());
        Path copy = tmp.resolve("copy");
        SuiteBaseline.freshCopy(RemediationLoopNodeTest.FIXTURE, copy);

        AdequacyCheck.CoverageRun run = AdequacyCheck.run(sandbox, ws, copy,
                List.of(RemediationLoopNodeTest.REGRESSION_CLASS), "regression test x (fix attempt 1)");

        assertEquals(1, sandbox.specs.size());
        SandboxRunner.Spec spec = sandbox.specs.get(0);
        assertEquals(Network.NONE, spec.network());
        assertEquals(EnumSet.allOf(Control.class), spec.required());
        assertEquals(copy, spec.scratchDir());
        List<String> promoted = spec.promote().stream().map(SandboxRunner.Promotion::relativePath).toList();
        assertTrue(promoted.contains(AdequacyCheck.XML), promoted.toString());
        assertTrue(promoted.containsAll(SuiteBaseline.REPORTS), promoted.toString());
        assertFalse(promoted.contains(AdequacyCheck.EXEC));
        String script = spec.command().get(2);
        assertTrue(script.contains("-javaagent:" + AdequacyCheck.AGENT + "=destfile=" + AdequacyCheck.EXEC
                + ",inclnolocationclasses=true"), script);
        assertTrue(script.contains("java -jar " + AdequacyCheck.CLI + " report " + AdequacyCheck.EXEC), script);
        assertTrue(script.startsWith("mvn -o "), script);
        assertTrue(script.endsWith("exit $rc\n"), "the launcher's exit code decides, not JaCoCo's");
        assertEquals(RemediationLoopNodeTest.REGRESSION_CLASS, spec.command().get(4));
        SuiteBaseline.Completed done = assertInstanceOf(SuiteBaseline.Completed.class, run.tests());
        assertEquals(List.of(RemediationLoopNodeTest.REGRESSION_CLASS + "#t()"), done.passing());
        assertTrue(run.coverage().isPresent());

        List<String> two = AdequacyCheck.command(List.of("a.B", "c.D"));
        assertTrue(two.get(2).contains("--select-class \"$1\" --select-class \"$2\""), two.get(2));
        assertEquals(List.of("a.B", "c.D"), two.subList(4, 6));
    }

    // ------------------------------------------------------------------ the loop's adequacy step

    @Test
    void coveredFixRecordsAdequateWithoutAnExtraTestgenCall() throws Exception {
        CoverageSandbox sandbox = new CoverageSandbox();
        sandbox.firstReport = "login-covered.xml";
        FakeLlmClient llm = llm(new CallBudget(200, 25));
        StateDelta d = runLoop(sandbox, llm, new CallBudget(200, 25));

        FixAttempt a = fixed(d);
        assertEquals(Optional.of(new Adequacy(3, 3, true, Optional.empty())), a.adequacy());
        assertEquals(0, sandbox.runs("regression test " + RemediationLoopNodeTest.REGRESSION_CLASS + " with"));
        assertTrue(llm.calls().stream().noneMatch(c -> c.request().promptTemplateId().equals("testgen.v1")));
    }

    @Test
    void uncoveredLinesGetOneFollowUpTestAndBecomeAdequate() throws Exception {
        CoverageSandbox sandbox = new CoverageSandbox();
        sandbox.followUpReport = "login-covered.xml";
        CallBudget budget = new CallBudget(200, 25);
        FakeLlmClient llm = llm(budget);
        StateDelta d = runLoop(sandbox, llm, budget);

        FixAttempt a = fixed(d);
        Adequacy q = a.adequacy().orElseThrow();
        assertTrue(q.adequate(), q.toString());
        assertEquals(3, q.changedLinesCovered());
        assertTrue(q.note().orElseThrow().contains("LoginServletAdequacySecurityTest"), q.toString());
        assertEquals(1, llm.calls().stream().filter(c -> c.request().promptTemplateId().equals("testgen.v1"))
                .count(), "exactly one extra testgen call, through the budgeted client");
        assertEquals(1, sandbox.runs("regression test " + RemediationLoopNodeTest.REGRESSION_CLASS + " with"));
        SandboxRunner.Spec follow = sandbox.specs.get(sandbox.specs.size() - 1);
        assertEquals(Network.NONE, follow.network());
        assertTrue(Files.isRegularFile(follow.scratchDir().resolve(
                "src/test/java/secpipe/generated/LoginServletAdequacySecurityTest.java")));
        assertTrue(RemediationLoopNodeTest.read(follow.scratchDir().resolve(LOGIN)).contains("prepareStatement("),
                "the follow-up runs on a patched copy");
        assertEquals(List.of(RemediationLoopNodeTest.REGRESSION_CLASS, "secpipe.generated.LoginServletAdequacySecurityTest"),
                follow.command().subList(4, 6));
        // baseline + regression + suite + follow-up, each one acquired from the sandbox-run budget
        assertEquals(4, sandbox.specs.stream().filter(s -> !s.purpose().startsWith("maven ")).count());
    }

    @Test
    void stillUncoveredAfterTheFollowUpIsALimitationNotAFailure() throws Exception {
        CoverageSandbox sandbox = new CoverageSandbox();
        CallBudget budget = new CallBudget(200, 25);
        StateDelta d = runLoop(sandbox, llm(budget), budget);

        Finding.Confirmed c = RemediationLoopNodeTest.only(d, FP);
        assertEquals(Optional.of(Finding.RemediationStatus.FIXED), c.secpipe().remediationStatus());
        FixAttempt a = fixed(d);
        Adequacy q = a.adequacy().orElseThrow();
        assertFalse(q.adequate());
        assertEquals(2, q.changedLinesCovered());
        assertTrue(q.note().orElseThrow().contains("still not adequate after one follow-up test"), q.toString());
        assertTrue(d.notes().stream().anyMatch(n -> n.startsWith("adequacy limited for " + FP)),
                d.notes().toString());
    }

    @Test
    void followUpRespectsTheSandboxRunBudget() throws Exception {
        CoverageSandbox sandbox = new CoverageSandbox();
        sandbox.followUpReport = "login-covered.xml";
        CallBudget budget = new CallBudget(200, 3);
        StateDelta d = runLoop(sandbox, llm(budget), budget);

        FixAttempt a = fixed(d);
        Adequacy q = a.adequacy().orElseThrow();
        assertFalse(q.adequate());
        assertTrue(q.note().orElseThrow().contains("sandbox run budget exhausted"), q.toString());
        assertEquals(0, sandbox.runs("regression test " + RemediationLoopNodeTest.REGRESSION_CLASS + " with"));
    }

    @Test
    void aFollowUpTestThatFailsOnThePatchedCodeKeepsTheFirstMeasurement() throws Exception {
        CoverageSandbox sandbox = new CoverageSandbox();
        sandbox.followUpReport = "login-covered.xml";
        sandbox.followUpFails = true;
        CallBudget budget = new CallBudget(200, 25);
        StateDelta d = runLoop(sandbox, llm(budget), budget);

        FixAttempt a = fixed(d);
        Adequacy q = a.adequacy().orElseThrow();
        assertFalse(q.adequate());
        assertEquals(2, q.changedLinesCovered());
        assertTrue(q.note().orElseThrow().contains("did not pass on the patched code"), q.toString());
    }

    // ------------------------------------------------------------------ harness

    static FakeLlmClient llm(Budget budget) {
        return FakeLlmClient.fromDir(JacocoXmlTest.resource("llm")).withBudget(budget);
    }

    StateDelta runLoop(CoverageSandbox sandbox, FakeLlmClient llm, Budget budget) throws Exception {
        Path target = tmp.resolve("target");
        SuiteBaseline.freshCopy(RemediationLoopNodeTest.FIXTURE, target);
        RemediationLoopNode.Settings settings = new RemediationLoopNode.Settings(SecPipeConfig.FixMode.SUGGEST, 3,
                true, false, target, tmp.resolve("out/.work"), tmp.resolve("out"));
        RemediationLoopNode.Services services = new RemediationLoopNode.Services(llm, Optional.of(sandbox),
                new RemediationLoopNodeTest.FakeRescanner(FP), budget, new RemediationLoopNodeTest.Log(),
                (r, w) -> Optional.of(RemediationLoopNodeTest.regressionTest()));
        return RemediationLoopNode.evaluate(RemediationLoopNodeTest.state(RemediationLoopNodeTest.confirmed(FP)),
                settings, services);
    }

    static FixAttempt fixed(StateDelta d) {
        Finding.Confirmed c = RemediationLoopNodeTest.only(d, FP);
        List<FixAttempt> attempts = c.secpipe().fixAttempts();
        assertEquals(1, attempts.size(), attempts.toString());
        FixAttempt a = attempts.get(0);
        assertEquals(FixAttempt.Outcome.FIXED, a.outcome(), "adequacy never fails an attempt");
        assertEquals(Optional.of(true), a.regressionTestPassed());
        return a;
    }

    /**
     * Answers prefetch, the suite (baseline tests pass) and regression runs: every selected class passes (unless
     * {@code followUpFails}) and the JaCoCo report is {@code firstReport} for the plain regression run and
     * {@code followUpReport} for the run with a follow-up test.
     */
    static final class CoverageSandbox implements SandboxRunner {
        final List<Spec> specs = new ArrayList<>();
        String firstReport = "login-line22-missed.xml";
        String followUpReport = "login-line22-missed.xml";
        boolean followUpFails;

        @Override
        public Capabilities probe() {
            return new Capabilities(true, EnumSet.allOf(Control.class), Optional.empty(), Optional.of("fake"));
        }

        @Override
        public Result run(Spec spec) {
            specs.add(spec);
            if (spec.purpose().startsWith("maven ")) {
                return result(0, List.of());
            }
            assertEquals(Network.NONE, spec.network(), spec.purpose());
            assertEquals(EnumSet.allOf(Control.class), spec.required(), spec.purpose());
            List<Path> promoted = new ArrayList<>();
            StringBuilder cases = new StringBuilder();
            boolean regression = spec.purpose().startsWith("regression test");
            if (regression) {
                List<String> classes = spec.command().subList(4, spec.command().size());
                for (String cls : classes) {
                    boolean fail = followUpFails && classes.size() > 1 && !cls.equals(classes.get(0));
                    cases.append("<testcase classname=\"").append(cls).append("\" name=\"t()\">")
                            .append(fail ? "<failure message=\"x\"/>" : "").append("</testcase>");
                }
                Path xml = spec.scratchDir().resolve(AdequacyCheck.XML);
                copy(JacocoXmlTest.resource(classes.size() > 1 ? followUpReport : firstReport), xml);
                promoted.add(xml);
            } else {
                for (String id : RemediationLoopNodeTest.BASELINE) {
                    String[] parts = id.split("#");
                    cases.append("<testcase classname=\"").append(parts[0]).append("\" name=\"").append(parts[1])
                            .append("\"></testcase>");
                }
            }
            Path report = spec.scratchDir().resolve(SuiteBaseline.REPORTS.get(0));
            write(report, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuite name=\"JUnit Jupiter\">" + cases
                    + "</testsuite>");
            promoted.add(report);
            return result(cases.indexOf("<failure") >= 0 ? 1 : 0, promoted);
        }

        long runs(String prefix) {
            return specs.stream().filter(s -> s.purpose().startsWith(prefix)).count();
        }

        private static Result result(int exit, List<Path> promoted) {
            return new Result(exit, false, "", "", promoted, Duration.ofSeconds(1), EnumSet.allOf(Control.class));
        }

        private static void copy(Path from, Path to) {
            try {
                Files.createDirectories(to.getParent());
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static void write(Path p, String content) {
            try {
                Files.createDirectories(p.getParent());
                Files.writeString(p, content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
