package ca.carleton.secpipe.agents.remediation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.sandbox.MavenWorkspace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * [DOCKER] {@link SuiteBaseline} on fixtures/remediation/sqli-fixable through the real sandbox: the baseline holds the
 * fixture's four tests; the fixture's regression test fails on the vulnerable code and passes after
 * {@code fix/prepared-statement.patch}, and the patched suite keeps every baseline test passing. Work directories are
 * not deleted: the container user (uid 10001) owns parts of them; CI runners are ephemeral.
 */
@Tag("sandbox")
class SuiteBaselineSandboxTest {

    private static final String REGRESSION = "secpipe.generated.LoginServletSqliRegressionTest";
    private static final String REGRESSION_FILE = "secpipe/generated/LoginServletSqliRegressionTest.java";

    @Test
    void sqliFixableBaselineRegressionTestAndPatchedSuite() throws Exception {
        Optional<SandboxRunner> runner = ServiceLoader.load(SandboxRunner.class).findFirst();
        boolean ci = System.getenv("SECPIPE_RUNNER_IMAGE") != null;
        if (ci) {
            assertTrue(runner.isPresent(), "SandboxRunner via ServiceLoader");
            assertTrue(runner.get().probe().available(), String.valueOf(runner.get().probe()));
        }
        assumeTrue(runner.isPresent(), "no SandboxRunner on the class path");
        assumeTrue(runner.get().probe().available(), "Docker sandbox not available");
        SandboxRunner sandbox = runner.get();
        Path work = Files.createTempDirectory("secpipe-m1a-");
        Path fixture = SuiteBaselineTest.FIXTURE;

        MavenWorkspace ws = new MavenWorkspace(sandbox, work.resolve("ws"), MavenWorkspace.Options.defaults());
        MavenWorkspace.Prepared prepared = assertInstanceOf(MavenWorkspace.Prepared.class, ws.prepare(fixture));
        Path pristine = prepared.scratchCopy();

        SuiteBaseline.Outcome outcome = SuiteBaseline.capture(sandbox, ws, pristine, work.resolve("baseline"),
                SuiteBaselineTest.rescanner(List.of(SuiteBaselineTest.FP), new ArrayList<>()), work.resolve("work"));
        SuiteBaseline.Captured captured = assertInstanceOf(SuiteBaseline.Captured.class, outcome, outcome.toString());
        JsonNode expected = JsonMapper.builder().build().readTree(
                Files.readString(fixture.resolve("expected-remediation.json")));
        List<String> expectedTests = new ArrayList<>();
        expected.get("baseline_passing_tests").forEach(n -> expectedTests.add(n.asString()));
        assertEquals(expectedTests, captured.baseline().passingTests());

        // vulnerable code: the regression test fails (an assertion, not a skip)
        Path vulnerable = work.resolve("attempt0");
        SuiteBaseline.freshCopy(pristine, vulnerable);
        addRegressionTest(fixture, vulnerable);
        SuiteBaseline.TestRun before = SuiteBaseline.runTests(sandbox, ws, vulnerable, Optional.of(REGRESSION),
                "regression test " + REGRESSION);
        assertEquals(FixClassifier.RegressionResult.FAILED, FixClassifier.regressionResult(before), before.toString());

        // fixed code: the regression test passes and the suite keeps the baseline
        Path fixed = work.resolve("attempt1");
        SuiteBaseline.freshCopy(pristine, fixed);
        addRegressionTest(fixture, fixed);
        UnifiedDiff.apply(fixed, Files.readString(fixture.resolve("fix/prepared-statement.patch")));
        SuiteBaseline.TestRun after = SuiteBaseline.runTests(sandbox, ws, fixed, Optional.of(REGRESSION),
                "regression test " + REGRESSION);
        assertEquals(FixClassifier.RegressionResult.PASSED, FixClassifier.regressionResult(after), after.toString());
        Path suiteCopy = work.resolve("attempt1-suite");
        SuiteBaseline.freshCopy(fixed, suiteCopy);
        SuiteBaseline.TestRun suite = SuiteBaseline.runTests(sandbox, ws, suiteCopy, Optional.empty(),
                SuiteBaseline.PURPOSE_PREFIX + " (attempt 1)");
        SuiteBaseline.Completed done = assertInstanceOf(SuiteBaseline.Completed.class, suite, suite.toString());
        assertTrue(captured.baseline().suitePassed(done), String.valueOf(captured.baseline().brokenTests(done)));
    }

    private static void addRegressionTest(Path fixture, Path copy) throws Exception {
        Path dest = copy.resolve("src/test/java").resolve(REGRESSION_FILE);
        Files.createDirectories(dest.getParent());
        Files.copy(fixture.resolve("regression").resolve(REGRESSION_FILE), dest);
    }
}
