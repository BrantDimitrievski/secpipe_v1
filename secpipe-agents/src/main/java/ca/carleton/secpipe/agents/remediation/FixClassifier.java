package ca.carleton.secpipe.agents.remediation;

import ca.carleton.secpipe.contracts.finding.Finding.FixAttempt;
import ca.carleton.secpipe.contracts.finding.Finding.RemediationStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Deterministic classification of one remediation attempt (WO-M.1). The loop runs the steps in order
 * (apply, compile, regression test, suite, rescan) and stops at the first failing step; the observations it made
 * become one frozen {@code fix_attempts[].outcome} and, over all attempts, the final {@code remediation_status}.
 *
 * <p>Precedence (first match wins): no usable sandbox → {@code sandbox_unavailable}; patch not applied →
 * {@code refused}; build failed → {@code build_broken}; regression test not passed (failed, errored, timed out,
 * <b>skipped</b>, not run) → {@code still_present}; a baseline-passing test no longer passes →
 * {@code tests_broken};
 * rescan missing or the original fingerprint still present → {@code still_present}; a new fingerprint →
 * {@code newly_introduced_findings}; otherwise {@code fixed}. {@code fixed} therefore always carries
 * {@code regression_test_passed}, {@code suite_passed} and {@code rescan_clean} = true (validator rule F8).
 */
public final class FixClassifier {

    private FixClassifier() {
    }

    /** The step an attempt failed at (passed to the next attempt's prompt); {@code NONE} for a fixed attempt. */
    public enum Step { SANDBOX, APPLY, COMPILE, REGRESSION_TEST, SUITE, RESCAN, NONE }

    /** Result of the regression-test step. Only {@code PASSED} counts as a pass. */
    public enum RegressionResult { PASSED, FAILED, ERROR, SKIPPED, TIMEOUT, NOT_RUN }

    /** Result of the rescan step: whether the record's fingerprint is still detected, and fingerprints not in the
     *  baseline (sorted, unique). */
    public record Rescan(boolean originalPresent, List<String> newFingerprints) {
        public Rescan {
            newFingerprints = List.copyOf(new TreeSet<>(newFingerprints));
        }

        /** From the rescanned fingerprints, the record's fingerprint and the baseline fingerprints. */
        public static Rescan of(Collection<String> rescanned, String original, SuiteBaseline.Baseline baseline) {
            return new Rescan(rescanned.contains(original), baseline.newFingerprints(rescanned));
        }

        public boolean clean() {
            return !originalPresent && newFingerprints.isEmpty();
        }
    }

    /**
     * What one attempt observed. Empty means the step was not reached. Build with {@link #start()} and the
     * {@code with...} methods in step order.
     */
    public record Observations(boolean sandboxAvailable, Optional<Boolean> patchApplied, Optional<Boolean> compiled,
                               Optional<RegressionResult> regressionTest, Optional<Boolean> suitePassed,
                               Optional<Rescan> rescan) {

        /** A usable sandbox, nothing done yet. */
        public static Observations start() {
            return new Observations(true, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty());
        }

        public static Observations sandboxUnavailable() {
            return new Observations(false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty());
        }

        public Observations withPatchApplied(boolean applied) {
            return new Observations(sandboxAvailable, Optional.of(applied), compiled, regressionTest, suitePassed,
                    rescan);
        }

        public Observations withCompiled(boolean ok) {
            return new Observations(sandboxAvailable, patchApplied, Optional.of(ok), regressionTest, suitePassed,
                    rescan);
        }

        public Observations withRegressionTest(RegressionResult result) {
            return new Observations(sandboxAvailable, patchApplied, compiled, Optional.of(result), suitePassed,
                    rescan);
        }

        public Observations withSuitePassed(boolean passed) {
            return new Observations(sandboxAvailable, patchApplied, compiled, regressionTest, Optional.of(passed),
                    rescan);
        }

        public Observations withRescan(Rescan r) {
            return new Observations(sandboxAvailable, patchApplied, compiled, regressionTest, suitePassed,
                    Optional.of(r));
        }
    }

    /** The classified attempt: outcome, the failing step and the verification flags of {@code fix_attempts[]}. */
    public record Classification(FixAttempt.Outcome outcome, Step failedStep, Optional<Boolean> regressionTestPassed,
                                 Optional<Boolean> suitePassed, Optional<Boolean> rescanClean,
                                 List<String> newFingerprints) {
        public Classification {
            newFingerprints = List.copyOf(newFingerprints);
        }
    }

    public static Classification classify(Observations o) {
        Optional<Boolean> regression = o.regressionTest().map(r -> r == RegressionResult.PASSED);
        Optional<Boolean> suite = o.suitePassed();
        Optional<Boolean> clean = o.rescan().map(Rescan::clean);
        List<String> newFps = o.rescan().map(Rescan::newFingerprints).orElse(List.of());
        if (!o.sandboxAvailable()) {
            return new Classification(FixAttempt.Outcome.SANDBOX_UNAVAILABLE, Step.SANDBOX, Optional.empty(),
                    Optional.empty(), Optional.empty(), List.of());
        }
        if (!o.patchApplied().orElse(false)) {
            return new Classification(FixAttempt.Outcome.REFUSED, Step.APPLY, Optional.empty(), Optional.empty(),
                    Optional.empty(), List.of());
        }
        if (!o.compiled().orElse(false)) {
            return new Classification(FixAttempt.Outcome.BUILD_BROKEN, Step.COMPILE, Optional.empty(),
                    Optional.empty(), Optional.empty(), List.of());
        }
        if (!regression.orElse(false)) {
            return new Classification(FixAttempt.Outcome.STILL_PRESENT, Step.REGRESSION_TEST,
                    Optional.of(false), suite, clean, newFps);
        }
        if (!suite.orElse(false)) {
            return new Classification(FixAttempt.Outcome.TESTS_BROKEN, Step.SUITE, regression,
                    Optional.of(false), clean, newFps);
        }
        if (o.rescan().isEmpty() || o.rescan().get().originalPresent()) {
            return new Classification(FixAttempt.Outcome.STILL_PRESENT, Step.RESCAN, regression, suite,
                    o.rescan().isEmpty() ? Optional.empty() : Optional.of(false), newFps);
        }
        if (!newFps.isEmpty()) {
            return new Classification(FixAttempt.Outcome.NEWLY_INTRODUCED_FINDINGS, Step.RESCAN, regression, suite,
                    Optional.of(false), newFps);
        }
        return new Classification(FixAttempt.Outcome.FIXED, Step.NONE, Optional.of(true), Optional.of(true),
                Optional.of(true), List.of());
    }

    /** The {@code fix_attempts[]} entry for attempt number {@code attempt} (1-based). Adequacy is M.2's. */
    public static FixAttempt toFixAttempt(int attempt, Optional<String> patchArtifact, Optional<String> strategy,
                                          Classification c) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt numbers start at 1: " + attempt);
        }
        return new FixAttempt(attempt, patchArtifact, strategy, c.outcome(), c.regressionTestPassed(),
                c.suitePassed(), c.rescanClean(), c.newFingerprints(), Optional.empty());
    }

    /** A planned attempt that could not run because the sandbox is unusable. */
    public static FixAttempt sandboxUnavailable(int attempt) {
        return toFixAttempt(attempt, Optional.empty(), Optional.empty(),
                classify(Observations.sandboxUnavailable()));
    }

    /**
     * Final {@code remediation_status} over the attempts in order. No attempt, or only {@code sandbox_unavailable}
     * ones → {@code not_attempted}. {@code fixed} only when the literal last attempt is {@code fixed} with all three
     * verification flags true (F8). Otherwise the last attempt that ran decides: {@code newly_introduced_findings} →
     * {@code newly_introduced}, anything else → {@code still_present}.
     */
    public static RemediationStatus remediationStatus(List<FixAttempt> attempts) {
        List<FixAttempt> ran = attempts.stream()
                .filter(a -> a.outcome() != FixAttempt.Outcome.SANDBOX_UNAVAILABLE).toList();
        if (ran.isEmpty()) {
            return RemediationStatus.NOT_ATTEMPTED;
        }
        FixAttempt last = attempts.get(attempts.size() - 1);
        if (last.outcome() == FixAttempt.Outcome.FIXED && last.regressionTestPassed().orElse(false)
                && last.suitePassed().orElse(false) && last.rescanClean().orElse(false)) {
            return RemediationStatus.FIXED;
        }
        return ran.get(ran.size() - 1).outcome() == FixAttempt.Outcome.NEWLY_INTRODUCED_FINDINGS
                ? RemediationStatus.NEWLY_INTRODUCED : RemediationStatus.STILL_PRESENT;
    }

    /** Maps a {@link SuiteBaseline.TestRun} of the regression test class to a {@link RegressionResult}. */
    public static RegressionResult regressionResult(SuiteBaseline.TestRun run) {
        return switch (run) {
            case SuiteBaseline.Completed done -> {
                if (done.tests().isEmpty()) {
                    yield RegressionResult.NOT_RUN;
                }
                if (done.tests().containsValue(SuiteBaseline.TestStatus.ERROR)) {
                    yield RegressionResult.ERROR;
                }
                if (done.tests().containsValue(SuiteBaseline.TestStatus.FAILED)) {
                    yield RegressionResult.FAILED;
                }
                if (done.tests().containsValue(SuiteBaseline.TestStatus.SKIPPED)) {
                    yield RegressionResult.SKIPPED;
                }
                yield done.exitCode() == 0 ? RegressionResult.PASSED : RegressionResult.ERROR;
            }
            case SuiteBaseline.BuildFailed bf -> RegressionResult.NOT_RUN;
            case SuiteBaseline.NotRun nr -> nr.reason() == SuiteBaseline.NotRun.Reason.TIMEOUT
                    ? RegressionResult.TIMEOUT : RegressionResult.NOT_RUN;
        };
    }
}
