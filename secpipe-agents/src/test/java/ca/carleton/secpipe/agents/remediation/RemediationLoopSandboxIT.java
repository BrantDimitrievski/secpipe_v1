package ca.carleton.secpipe.agents.remediation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.FixAttempt;
import ca.carleton.secpipe.contracts.json.ContractJson;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.llm.CallBudget;
import ca.carleton.secpipe.validators.CapPolicy;
import ca.carleton.secpipe.validators.FindingsValidator;
import ca.carleton.secpipe.validators.ValidationResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * [DOCKER] {@link RemediationLoopNode} on {@code fixtures/remediation/sqli-fixable} through the real sandbox: the
 * fake remediation agent answers {@code fix/prepared-statement.patch} (fixture {@code remediation-loop/c001}); the
 * fixture's own regression test ({@code regression/secpipe/generated/...}, never skipped) must pass on the patched
 * copy, the four baseline tests must still pass, and the rescan must be clean. The result must match
 * {@code expected-remediation.json} and the patch must be written. The rescanner is a content check (no
 * {@code createStatement} left in {@code LoginServlet.java}) because {@code secpipe-agents} cannot depend on core's
 * {@code DetectRescanner}. With {@code SECPIPE_RUNNER_IMAGE} set (CI's sandbox job) a missing sandbox fails the test
 * instead of skipping it. Work directories are not deleted: the container user owns parts of them.
 */
@Tag("sandbox")
class RemediationLoopSandboxIT {

    @Test
    void sqliFixableIsFixedWithinTheExpectedAttempts() throws Exception {
        Optional<SandboxRunner> runner = ServiceLoader.load(SandboxRunner.class).findFirst();
        if (System.getenv("SECPIPE_RUNNER_IMAGE") != null) {
            assertTrue(runner.isPresent(), "SandboxRunner via ServiceLoader");
            assertTrue(runner.get().probe().available(), String.valueOf(runner.get().probe()));
        }
        assumeTrue(runner.isPresent(), "no SandboxRunner on the class path");
        assumeTrue(runner.get().probe().available(), "Docker sandbox not available");

        Path fixture = RemediationLoopNodeTest.FIXTURE;
        Map<String, byte[]> before = SuiteBaselineTest.snapshot(fixture);
        Path tmp = Files.createTempDirectory("secpipe-m1b-");
        Path out = tmp.resolve("out");
        String fp = RemediationLoopNodeTest.fp("c001");
        List<String> log = new ArrayList<>();
        RemediationLoopNode.Settings settings = new RemediationLoopNode.Settings(SecPipeConfig.FixMode.SUGGEST,
                SecPipeConfig.DEFAULT_MAX_FIX_ATTEMPTS, true, false, fixture, out.resolve(".work"), out);
        CallBudget budget = new CallBudget(50, SecPipeConfig.DEFAULT_MAX_SANDBOX_RUNS);
        RemediationLoopNode.Services services = new RemediationLoopNode.Services(
                RemediationLoopNodeTest.fake().withBudget(budget), runner, contentRescanner(fp), budget,
                new RemediationLoopNodeTest.Log() {
                    @Override
                    public void info(String msg) {
                        log.add(msg);
                        System.err.println("[remediate] " + msg);
                    }

                    @Override
                    public void warn(String msg) {
                        info(msg);
                    }
                }, (r, w) -> Optional.of(RemediationLoopNodeTest.regressionTest()));

        StateDelta d = RemediationLoopNode.evaluate(RemediationLoopNodeTest.state(
                RemediationLoopNodeTest.confirmed(fp)), settings, services);

        JsonNode expected = JsonMapper.builder().build().readTree(
                Files.readString(fixture.resolve("expected-remediation.json"))).get("findings").get(0);
        Finding.Confirmed c = assertInstanceOf(Finding.Confirmed.class, d.putRecords().get(0), log.toString());
        assertEquals(expected.get("remediation_status").asString(),
                c.secpipe().remediationStatus().orElseThrow().name().toLowerCase(Locale.ROOT), log.toString());
        List<FixAttempt> attempts = c.secpipe().fixAttempts();
        assertTrue(attempts.size() <= expected.get("max_attempts").asInt(), attempts.toString());
        FixAttempt last = attempts.get(attempts.size() - 1);
        JsonNode lastExpected = expected.get("last_attempt");
        assertEquals(lastExpected.get("outcome").asString(), last.outcome().name().toLowerCase(Locale.ROOT));
        assertEquals(Optional.of(lastExpected.get("regression_test_passed").asBoolean()), last.regressionTestPassed());
        assertEquals(Optional.of(lastExpected.get("suite_passed").asBoolean()), last.suitePassed());
        assertEquals(Optional.of(lastExpected.get("rescan_clean").asBoolean()), last.rescanClean());
        assertTrue(last.strategy().orElse("").contains(expected.get("fix_strategy").asString()));
        Path patch = out.resolve(last.patchArtifact().orElseThrow());
        assertTrue(Files.isRegularFile(patch), patch.toString());
        assertEquals(Files.readString(fixture.resolve("fix/prepared-statement.patch")), Files.readString(patch));
        System.err.println("[remediate] patch written: " + patch);

        List<String> baselineTests = new ArrayList<>();
        JsonMapper.builder().build().readTree(Files.readString(fixture.resolve("expected-remediation.json")))
                .get("baseline_passing_tests").forEach(n -> baselineTests.add(n.asString()));
        assertEquals(Optional.of(baselineTests), SuiteBaseline.read(out.resolve(".work/remediation")
                .resolve(SuiteBaseline.FILE_NAME)).map(SuiteBaseline.Baseline::passingTests));

        ValidationResult valid = FindingsValidator.validateJson(ContractJson.writeFindings(d.putRecords()),
                Optional.of(fixture), CapPolicy.NONE);
        assertTrue(valid.ok(), valid.toString());
        Map<String, byte[]> after = SuiteBaselineTest.snapshot(fixture);
        assertEquals(before.keySet(), after.keySet());
        before.forEach((k, v) -> assertArrayEquals(v, after.get(k), k));
    }

    /** The original finding is present while {@code LoginServlet.java} still calls {@code createStatement}. */
    private static RunContext.Rescanner contentRescanner(String fp) {
        return (copy, scope) -> {
            String login = Files.readString(copy.resolve(RemediationLoopNodeTest.LOGIN));
            return login.contains("createStatement")
                    ? List.of(RemediationLoopNodeTest.candidate(fp, "CWE-89")) : List.of();
        };
    }
}
