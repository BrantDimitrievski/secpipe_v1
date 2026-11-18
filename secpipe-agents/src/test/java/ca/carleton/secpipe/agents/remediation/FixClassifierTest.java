package ca.carleton.secpipe.agents.remediation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.carleton.secpipe.agents.remediation.FixClassifier.Classification;
import ca.carleton.secpipe.agents.remediation.FixClassifier.Observations;
import ca.carleton.secpipe.agents.remediation.FixClassifier.RegressionResult;
import ca.carleton.secpipe.agents.remediation.FixClassifier.Rescan;
import ca.carleton.secpipe.agents.remediation.FixClassifier.Step;
import ca.carleton.secpipe.contracts.finding.Finding.FixAttempt;
import ca.carleton.secpipe.contracts.finding.Finding.FixAttempt.Outcome;
import ca.carleton.secpipe.contracts.finding.Finding.RemediationStatus;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Control;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class FixClassifierTest {

    private static final String FP = "code:CWE-89:00000000000000000000beef";
    private static final String NEW_FP = "code:CWE-78:000000000000000000000c0d";

    private static Observations built() {
        return Observations.start().withPatchApplied(true).withCompiled(true);
    }

    private static Observations allPass() {
        return built().withRegressionTest(RegressionResult.PASSED).withSuitePassed(true)
                .withRescan(new Rescan(false, List.of()));
    }

    @Test
    void allStepsPassingIsFixedWithEveryF8FlagTrue() {
        Classification c = FixClassifier.classify(allPass());
        assertEquals(Outcome.FIXED, c.outcome());
        assertEquals(Step.NONE, c.failedStep());
        FixAttempt a = FixClassifier.toFixAttempt(1, Optional.of("patches/beef-attempt1.patch"),
                Optional.of("PreparedStatement"), c);
        assertEquals(Optional.of(true), a.regressionTestPassed());
        assertEquals(Optional.of(true), a.suitePassed());
        assertEquals(Optional.of(true), a.rescanClean());
        assertEquals(List.of(), a.newFingerprints());
        assertEquals(Optional.empty(), a.adequacy());
        assertEquals(RemediationStatus.FIXED, FixClassifier.remediationStatus(List.of(a)));
    }

    @Test
    void patchNotAppliedIsRefused() {
        Classification c = FixClassifier.classify(Observations.start().withPatchApplied(false));
        assertEquals(Outcome.REFUSED, c.outcome());
        assertEquals(Step.APPLY, c.failedStep());
        assertEquals(Optional.empty(), c.regressionTestPassed());
        assertEquals(Outcome.REFUSED, FixClassifier.classify(Observations.start()).outcome());
    }

    @Test
    void compileFailureIsBuildBroken() {
        Classification c = FixClassifier.classify(Observations.start().withPatchApplied(true).withCompiled(false));
        assertEquals(Outcome.BUILD_BROKEN, c.outcome());
        assertEquals(Step.COMPILE, c.failedStep());
    }

    @Test
    void failingRegressionTestIsStillPresent() {
        Classification c = FixClassifier.classify(built().withRegressionTest(RegressionResult.FAILED));
        assertEquals(Outcome.STILL_PRESENT, c.outcome());
        assertEquals(Step.REGRESSION_TEST, c.failedStep());
        assertEquals(Optional.of(false), c.regressionTestPassed());
    }

    @Test
    void skippedRegressionTestIsNeverAPassEvenWhenEverythingElsePasses() {
        Observations o = built().withRegressionTest(RegressionResult.SKIPPED).withSuitePassed(true)
                .withRescan(new Rescan(false, List.of()));
        Classification c = FixClassifier.classify(o);
        assertEquals(Outcome.STILL_PRESENT, c.outcome());
        assertEquals(Optional.of(false), c.regressionTestPassed());
        for (RegressionResult r : List.of(RegressionResult.ERROR, RegressionResult.NOT_RUN)) {
            assertEquals(Outcome.STILL_PRESENT, FixClassifier.classify(built().withRegressionTest(r)
                    .withSuitePassed(true).withRescan(new Rescan(false, List.of()))).outcome(), r.name());
        }
    }

    @Test
    void brokenBaselineTestIsTestsBroken() {
        Classification c = FixClassifier.classify(built().withRegressionTest(RegressionResult.PASSED)
                .withSuitePassed(false));
        assertEquals(Outcome.TESTS_BROKEN, c.outcome());
        assertEquals(Step.SUITE, c.failedStep());
        assertEquals(Optional.of(true), c.regressionTestPassed());
        assertEquals(Optional.of(false), c.suitePassed());
    }

    @Test
    void originalFingerprintStillDetectedIsStillPresent() {
        Classification c = FixClassifier.classify(built().withRegressionTest(RegressionResult.PASSED)
                .withSuitePassed(true).withRescan(new Rescan(true, List.of(NEW_FP))));
        assertEquals(Outcome.STILL_PRESENT, c.outcome());
        assertEquals(Step.RESCAN, c.failedStep());
        assertEquals(Optional.of(false), c.rescanClean());
        assertEquals(List.of(NEW_FP), c.newFingerprints());
    }

    @Test
    void missingRescanCannotBeFixed() {
        Classification c = FixClassifier.classify(built().withRegressionTest(RegressionResult.PASSED)
                .withSuitePassed(true));
        assertEquals(Outcome.STILL_PRESENT, c.outcome());
        assertEquals(Optional.empty(), c.rescanClean());
    }

    @Test
    void newFingerprintIsNewlyIntroducedFindings() {
        SuiteBaseline.Baseline baseline = new SuiteBaseline.Baseline(List.of(), List.of(FP, "code:CWE-89:aa"));
        Rescan r = Rescan.of(List.of(NEW_FP, "code:CWE-89:aa"), FP, baseline);
        assertEquals(new Rescan(false, List.of(NEW_FP)), r);
        Classification c = FixClassifier.classify(built().withRegressionTest(RegressionResult.PASSED)
                .withSuitePassed(true).withRescan(r));
        assertEquals(Outcome.NEWLY_INTRODUCED_FINDINGS, c.outcome());
        assertEquals(List.of(NEW_FP), c.newFingerprints());
        assertEquals(Optional.of(false), c.rescanClean());
        FixAttempt a = FixClassifier.toFixAttempt(1, Optional.empty(), Optional.empty(), c);
        assertEquals(RemediationStatus.NEWLY_INTRODUCED, FixClassifier.remediationStatus(List.of(a)));
    }

    @Test
    void noSandboxIsSandboxUnavailableAndNotAttempted() {
        FixAttempt a = FixClassifier.sandboxUnavailable(1);
        assertEquals(Outcome.SANDBOX_UNAVAILABLE, a.outcome());
        assertEquals(RemediationStatus.NOT_ATTEMPTED, FixClassifier.remediationStatus(List.of(a)));
        assertEquals(RemediationStatus.NOT_ATTEMPTED, FixClassifier.remediationStatus(List.of()));
        assertEquals(Outcome.SANDBOX_UNAVAILABLE,
                FixClassifier.classify(Observations.sandboxUnavailable().withPatchApplied(true)).outcome());
    }

    @Test
    void finalStatusFollowsTheLastAttemptAndRespectsF8() {
        FixAttempt still = attempt(1, built().withRegressionTest(RegressionResult.FAILED));
        FixAttempt broken = attempt(2, Observations.start().withPatchApplied(true).withCompiled(false));
        FixAttempt fixed = attempt(2, allPass());
        assertEquals(RemediationStatus.FIXED, FixClassifier.remediationStatus(List.of(still, fixed)));
        assertEquals(RemediationStatus.STILL_PRESENT, FixClassifier.remediationStatus(List.of(still, broken)));
        // a fixed attempt that is not the last one never makes the record fixed (F8)
        assertEquals(RemediationStatus.STILL_PRESENT,
                FixClassifier.remediationStatus(List.of(fixed, attempt(3, Observations.start()))));
        assertEquals(RemediationStatus.STILL_PRESENT,
                FixClassifier.remediationStatus(List.of(fixed, FixClassifier.sandboxUnavailable(3))));
        // outcome fixed but a verification flag missing (hand-built) is not fixed either
        FixAttempt forged = new FixAttempt(1, Optional.empty(), Optional.empty(), Outcome.FIXED, Optional.of(true),
                Optional.empty(), Optional.of(true), List.of(), Optional.empty());
        assertEquals(RemediationStatus.STILL_PRESENT, FixClassifier.remediationStatus(List.of(forged)));
    }

    @Test
    void attemptNumbersStartAtOne() {
        assertThrows(IllegalArgumentException.class,
                () -> FixClassifier.toFixAttempt(0, Optional.empty(), Optional.empty(),
                        FixClassifier.classify(allPass())));
    }

    @Test
    void regressionResultFromATestRunTreatsSkipsAndInfrastructureAsNotPassing() {
        Path report = Path.of("r.xml");
        assertEquals(RegressionResult.PASSED, FixClassifier.regressionResult(new SuiteBaseline.Completed(
                Map.of("T#a()", SuiteBaseline.TestStatus.PASSED), 0, List.of(report))));
        assertEquals(RegressionResult.SKIPPED, FixClassifier.regressionResult(new SuiteBaseline.Completed(
                Map.of("T#a()", SuiteBaseline.TestStatus.SKIPPED), 0, List.of(report))));
        assertEquals(RegressionResult.FAILED, FixClassifier.regressionResult(new SuiteBaseline.Completed(
                Map.of("T#a()", SuiteBaseline.TestStatus.FAILED, "T#b()", SuiteBaseline.TestStatus.SKIPPED), 1,
                List.of(report))));
        assertEquals(RegressionResult.ERROR, FixClassifier.regressionResult(new SuiteBaseline.Completed(
                Map.of("T#a()", SuiteBaseline.TestStatus.ERROR), 1, List.of(report))));
        assertEquals(RegressionResult.NOT_RUN, FixClassifier.regressionResult(new SuiteBaseline.Completed(
                Map.of(), 0, List.of(report))));
        for (SuiteBaseline.NotRun.Reason reason : SuiteBaseline.NotRun.Reason.values()) {
            assertEquals(RegressionResult.SANDBOX_UNAVAILABLE, FixClassifier.regressionResult(
                    new SuiteBaseline.NotRun(reason, "r", Set.of(Control.NETWORK_NONE))), reason.name());
        }
        assertEquals(RegressionResult.NOT_RUN, FixClassifier.regressionResult(new SuiteBaseline.BuildFailed(
                true, "compile error", "")));
    }

    @Test
    void sandboxRefusedOrDiedDuringTheRegressionTestIsSandboxUnavailable() {
        Classification c = FixClassifier.classify(built().withRegressionTest(RegressionResult.SANDBOX_UNAVAILABLE));
        assertEquals(Outcome.SANDBOX_UNAVAILABLE, c.outcome());
        assertEquals(Step.SANDBOX, c.failedStep());
        assertEquals(Optional.empty(), c.regressionTestPassed());
        FixAttempt a = FixClassifier.toFixAttempt(1, Optional.of("patches/p.patch"), Optional.of("s"), c);
        assertEquals(RemediationStatus.NOT_ATTEMPTED, FixClassifier.remediationStatus(List.of(a)));
    }

    @Test
    void sandboxLostDuringTheSuiteIsSandboxUnavailableNotTestsBroken() {
        Classification c = FixClassifier.classify(built().withRegressionTest(RegressionResult.PASSED)
                .withSandboxLost());
        assertEquals(Outcome.SANDBOX_UNAVAILABLE, c.outcome());
        assertEquals(Step.SANDBOX, c.failedStep());
    }

    @Test
    void postFixSkipIsStillPresentWithTheFixUnverifiedStrategy() {
        Classification c = FixClassifier.classify(built().withRegressionTest(RegressionResult.SKIPPED));
        FixAttempt a = FixClassifier.fixUnverified(2, Optional.of("patches/p.patch"), Optional.of("bind it"), c);
        assertEquals(Outcome.STILL_PRESENT, a.outcome());
        assertEquals(Optional.of(FixClassifier.FIX_UNVERIFIED + "; proposed: bind it"), a.strategy());
        assertEquals(Optional.of(false), a.regressionTestPassed());
        assertEquals(true, FixClassifier.isFixUnverified(a));
        assertEquals(RemediationStatus.STILL_PRESENT, FixClassifier.remediationStatus(List.of(a)));
        FixAttempt failed = attempt(1, built().withRegressionTest(RegressionResult.FAILED));
        assertEquals(false, FixClassifier.isFixUnverified(failed));
        assertThrows(IllegalArgumentException.class, () -> FixClassifier.fixUnverified(1, Optional.empty(),
                Optional.empty(), FixClassifier.classify(allPass())));
    }

    private static FixAttempt attempt(int n, Observations o) {
        return FixClassifier.toFixAttempt(n, Optional.empty(), Optional.empty(), FixClassifier.classify(o));
    }
}
