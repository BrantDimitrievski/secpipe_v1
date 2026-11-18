package ca.carleton.secpipe.agents.evidence;

import static ca.carleton.secpipe.agents.evidence.EvidenceFixtures.FP;
import static ca.carleton.secpipe.agents.evidence.EvidenceFixtures.FP_MALFORMED;
import static ca.carleton.secpipe.agents.evidence.EvidenceFixtures.awaiting;
import static ca.carleton.secpipe.agents.evidence.EvidenceFixtures.candidate;
import static ca.carleton.secpipe.agents.evidence.EvidenceFixtures.draft;
import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.TestEvidence;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Control;
import ca.carleton.secpipe.llm.FakeLlmClient;
import ca.carleton.secpipe.sandbox.MavenWorkspace;
import ca.carleton.secpipe.validators.CapPolicy;
import ca.carleton.secpipe.validators.FindingsValidator;
import ca.carleton.secpipe.validators.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class TestEvidenceNodeTest {

    @TempDir
    Path tmp;

    private final EvidenceFixtures.Log log = new EvidenceFixtures.Log();

    private StateDelta run(LlmClient llm, Optional<SandboxRunner> sandbox, String fp) throws Exception {
        return run(llm, sandbox, List.of(candidate(fp)), Map.of(fp, awaiting(fp)), Map.of(fp, draft(fp)));
    }

    private StateDelta run(LlmClient llm, Optional<SandboxRunner> sandbox, List<Candidate> candidates,
                           Map<String, Finding> records,
                           Map<String, ca.carleton.secpipe.contracts.agent.AgentOutputs.DisproverResult> drafts)
            throws Exception {
        Path target = EvidenceFixtures.copyOfSqliMin(tmp);
        StateDelta delta = TestEvidenceNode.evaluate(EvidenceFixtures.state(candidates, records, drafts),
                EvidenceFixtures.settings(llm.mode(), target, tmp), llm, sandbox, log);
        ValidationResult v = FindingsValidator.validate(delta.putRecords(), Optional.of(target), CapPolicy.NONE);
        assertThat(v.ok()).as("%s", v).isTrue();
        return delta;
    }

    private static Finding only(StateDelta delta) {
        assertThat(delta.putRecords()).hasSize(1);
        return delta.putRecords().get(0);
    }

    private static Finding.NeedsValidation nv(StateDelta delta) {
        assertThat(only(delta)).isInstanceOf(Finding.NeedsValidation.class);
        return (Finding.NeedsValidation) only(delta);
    }

    @Test
    void failingAssertionConfirmsAndReplacesTheAwaitingRecord() throws Exception {
        FakeSandboxRunner sandbox = new FakeSandboxRunner().then(FakeSandboxRunner.report(1,
                FakeSandboxRunner.FAILED_REPORT));

        StateDelta delta = run(EvidenceFixtures.fake(), Optional.of(sandbox), FP);

        assertThat(only(delta)).isInstanceOf(Finding.Confirmed.class);
        Finding.Confirmed c = (Finding.Confirmed) only(delta);
        assertThat(c.fingerprint()).isEqualTo(FP);
        TestEvidence te = c.secpipe().testEvidence().get(0);
        assertThat(te.id()).isEqualTo(TestEvidenceId.of(FP, TestEvidence.Phase.PRE_FIX, 1));
        assertThat(te.outcome()).isEqualTo(TestEvidence.Outcome.FAILED_AS_EXPECTED);
        assertThat(c.execution().observedResult()).startsWith(te.id()).contains("AssertionFailedError");
        assertThat(c.secpipe().toolSeverityHint()).isEmpty();
        assertThat(c.secpipe().verification()).isEqualTo(awaiting(FP).secpipe().verification());
        assertThat(te.logArtifact()).contains("evidence/" + te.id() + ".xml");
        assertThat(tmp.resolve("out").resolve(te.logArtifact().get())).isRegularFile();
        // the test ran offline with every control required and only the JUnit report declared for promotion
        SandboxRunner.Spec spec = sandbox.testRuns().get(0);
        assertThat(spec.network()).isEqualTo(SandboxRunner.Network.NONE);
        assertThat(spec.required()).isEqualTo(EnumSet.allOf(Control.class));
        assertThat(spec.promote()).extracting(SandboxRunner.Promotion::relativePath)
                .containsExactly(RegressionTestRun.REPORT);
        assertThat(spec.scratchDir().resolve(te.testFile().get())).isRegularFile();
    }

    @Test
    void passingTestLeavesNeedsValidation() throws Exception {
        FakeSandboxRunner sandbox = new FakeSandboxRunner().then(FakeSandboxRunner.report(0,
                FakeSandboxRunner.PASSED_REPORT));

        Finding.NeedsValidation n = nv(run(EvidenceFixtures.fake(), Optional.of(sandbox), FP));

        assertThat(n.blockers()).containsExactly(TestEvidenceNode.TEST_PASSED);
        assertThat(n.secpipe().testEvidence()).extracting(TestEvidence::outcome)
                .containsExactly(TestEvidence.Outcome.PASSED);
    }

    @Test
    void compileErrorRegeneratesOnceThenConfirms() throws Exception {
        FakeSandboxRunner sandbox = new FakeSandboxRunner().then(FakeSandboxRunner.compileError())
                .then(FakeSandboxRunner.report(1, FakeSandboxRunner.FAILED_REPORT));
        FakeLlmClient llm = EvidenceFixtures.fake();

        StateDelta delta = run(llm, Optional.of(sandbox), FP);

        assertThat(only(delta)).isInstanceOf(Finding.Confirmed.class);
        assertThat(llm.calls()).hasSize(2);
        assertThat(only(delta).secpipe().testEvidence()).extracting(TestEvidence::outcome)
                .containsExactly(TestEvidence.Outcome.COMPILE_ERROR, TestEvidence.Outcome.FAILED_AS_EXPECTED);
        assertThat(((Finding.Confirmed) only(delta)).execution().observedResult())
                .startsWith(TestEvidenceId.of(FP, TestEvidence.Phase.PRE_FIX, 2));
    }

    @Test
    void secondCompileErrorLeavesNeedsValidation() throws Exception {
        FakeSandboxRunner sandbox = new FakeSandboxRunner().then(FakeSandboxRunner.compileError())
                .then(FakeSandboxRunner.compileError());
        FakeLlmClient llm = EvidenceFixtures.fake();

        Finding.NeedsValidation n = nv(run(llm, Optional.of(sandbox), FP));

        assertThat(n.blockers()).containsExactly(TestEvidenceNode.COMPILE_TWICE);
        assertThat(llm.calls()).hasSize(2);
        assertThat(sandbox.testRuns()).hasSize(2);
    }

    @Test
    void unenforceableControlRefusesBeforeAnyRun() throws Exception {
        EnumSet<Control> some = EnumSet.allOf(Control.class);
        some.remove(Control.NETWORK_NONE);
        FakeSandboxRunner sandbox = new FakeSandboxRunner().enforcing(some);
        FakeLlmClient llm = EvidenceFixtures.fake();

        Finding.NeedsValidation n = nv(run(llm, Optional.of(sandbox), FP));

        assertThat(n.blockers()).containsExactly(TestEvidenceNode.REFUSED + "NETWORK_NONE");
        assertThat(sandbox.specs).isEmpty();
        assertThat(llm.calls()).isEmpty();
    }

    @Test
    void runnerRefusalNamesTheMissingControl() throws Exception {
        FakeSandboxRunner sandbox = new FakeSandboxRunner().then(FakeSandboxRunner.refused(Control.PID_LIMIT));

        Finding.NeedsValidation n = nv(run(EvidenceFixtures.fake(), Optional.of(sandbox), FP));

        assertThat(n.blockers()).containsExactly(TestEvidenceNode.REFUSED + "PID_LIMIT");
    }

    @Test
    void prefetchIncompleteIsABlockerNotATestFailure() throws Exception {
        FakeSandboxRunner sandbox = new FakeSandboxRunner().prefetchFails();
        FakeLlmClient llm = EvidenceFixtures.fake();

        Finding.NeedsValidation n = nv(run(llm, Optional.of(sandbox), FP));

        assertThat(n.blockers()).hasSize(1);
        assertThat(n.blockers().get(0)).startsWith(MavenWorkspace.BLOCKER + ": ");
        assertThat(n.secpipe().testEvidence()).extracting(TestEvidence::outcome)
                .containsExactly(TestEvidence.Outcome.NOT_RUN);
        assertThat(sandbox.testRuns()).isEmpty();
        assertThat(llm.calls()).isEmpty();
    }

    @Test
    void noSandboxMeansSandboxUnavailable() throws Exception {
        FakeLlmClient llm = EvidenceFixtures.fake();

        Finding.NeedsValidation n = nv(run(llm, Optional.empty(), FP));

        assertThat(n.blockers()).containsExactly(TestEvidenceNode.SANDBOX_UNAVAILABLE);
        assertThat(n.secpipe().toolSeverityHint()).contains(Finding.Score5.HIGH);
        assertThat(llm.calls()).isEmpty();
    }

    @Test
    void timeoutIsNeverATestFailure() throws Exception {
        FakeSandboxRunner sandbox = new FakeSandboxRunner().then(FakeSandboxRunner.timeout());

        Finding.NeedsValidation n = nv(run(EvidenceFixtures.fake(), Optional.of(sandbox), FP));

        assertThat(n.blockers()).containsExactly(TestEvidenceNode.TIMEOUT);
        assertThat(n.secpipe().testEvidence()).extracting(TestEvidence::outcome)
                .containsExactly(TestEvidence.Outcome.TIMEOUT);
    }

    @Test
    void resourceLimitIsNeverATestFailure() throws Exception {
        FakeSandboxRunner sandbox = new FakeSandboxRunner().then(FakeSandboxRunner.oomKilled());

        Finding.NeedsValidation n = nv(run(EvidenceFixtures.fake(), Optional.of(sandbox), FP));

        assertThat(n.blockers()).containsExactly(TestEvidenceNode.RESOURCE_LIMIT);
    }

    @Test
    void erroringTestDoesNotConfirm() throws Exception {
        FakeSandboxRunner sandbox = new FakeSandboxRunner().then(FakeSandboxRunner.report(1,
                FakeSandboxRunner.ERROR_REPORT));

        Finding.NeedsValidation n = nv(run(EvidenceFixtures.fake(), Optional.of(sandbox), FP));

        assertThat(n.blockers().get(0)).startsWith("regression test errored without a failing assertion")
                .contains("NullPointerException");
    }

    @Test
    void failureWithAnotherMessageLeavesNeedsValidationWithTheExactBlocker() throws Exception {
        FakeSandboxRunner sandbox = new FakeSandboxRunner().then(FakeSandboxRunner.report(1,
                FakeSandboxRunner.UNEXPECTED_FAILURE_REPORT));

        Finding.NeedsValidation n = nv(run(EvidenceFixtures.fake(), Optional.of(sandbox), FP));

        assertThat(n.blockers()).containsExactly(
                "unexpected failure: the login query did not run ==> expected: <1> but was: <0>");
        TestEvidence te = n.secpipe().testEvidence().get(0);
        assertThat(te.outcome()).isEqualTo(TestEvidence.Outcome.ERROR);
        assertThat(te.observedResult()).isEmpty();
        assertThat(sandbox.testRuns()).hasSize(1);
    }

    @Test
    void failedSetupAssumptionLeavesNeedsValidation() throws Exception {
        FakeSandboxRunner sandbox = new FakeSandboxRunner().then(FakeSandboxRunner.report(0,
                FakeSandboxRunner.SKIPPED_REPORT));

        Finding.NeedsValidation n = nv(run(EvidenceFixtures.fake(), Optional.of(sandbox), FP));

        assertThat(n.blockers()).containsExactly("setup assumption failed: the recording Db stub did not compile");
        assertThat(n.secpipe().testEvidence()).extracting(TestEvidence::outcome)
                .containsExactly(TestEvidence.Outcome.NOT_RUN);
    }

    @Test
    void malformedGeneratorOutputLeavesNeedsValidation() throws Exception {
        FakeSandboxRunner sandbox = new FakeSandboxRunner();

        Finding.NeedsValidation n = nv(run(EvidenceFixtures.fake(), Optional.of(sandbox), FP_MALFORMED));

        assertThat(n.blockers().get(0)).startsWith("could not generate an executable regression test");
        assertThat(sandbox.testRuns()).isEmpty();
    }

    @Test
    void unrefutedCandidateWithoutRecordOrDraftIsReportedWithTheProviderMode() throws Exception {
        String other = "code:CWE-89:0000000000000000000000b2";
        Candidate decided = candidate("code:CWE-89:0000000000000000000000c3");
        Finding rejected = new Finding.Rejected(decided.fingerprint().get(), "t", "d", "rc", decided.trace(),
                decided.evidence(), "constant overwrite", new Finding.SecPipeExt(Finding.Category.CODE,
                Finding.Origin.HUNTER, List.of(), List.of("CWE-89"), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty()));
        LlmClient off = new EvidenceFixtures.Off(EvidenceFixtures.fake());

        StateDelta delta = run(off, Optional.empty(), List.of(candidate(other), decided),
                Map.of(decided.fingerprint().get(), rejected), Map.of());

        Finding.NeedsValidation n = nv(delta);
        assertThat(n.fingerprint()).isEqualTo(other);
        assertThat(n.blockers()).containsExactly("not verified: llm-provider=none");
        assertThat(n.secpipe().toolSeverityHint()).contains(Finding.Score5.HIGH);
        assertThat(n.secpipe().testEvidence()).isEmpty();
    }

    @Test
    void confirmedOrRejectedSurvivorsAreLeftAlone() throws Exception {
        FakeSandboxRunner sandbox = new FakeSandboxRunner();
        Candidate c = candidate(FP);
        Finding rejected = new Finding.Rejected(FP, "t", "d", "rc", c.trace(), c.evidence(), "refuted",
                new Finding.SecPipeExt(Finding.Category.CODE, Finding.Origin.HUNTER, List.of(), List.of("CWE-89"),
                        List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(),
                        List.of(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(),
                        Optional.empty()));

        StateDelta delta = run(EvidenceFixtures.fake(), Optional.of(sandbox), List.of(c), Map.of(FP, rejected),
                Map.of(FP, draft(FP)));

        assertThat(delta.putRecords()).isEmpty();
        assertThat(sandbox.specs).isEmpty();
    }

    @Test
    void nodeNeverRequiresAnLlm() {
        TestEvidenceNode node = new TestEvidenceNode();
        assertThat(node.id()).isEqualTo("test_evidence");
        assertThat(node.requiresLlm()).isFalse();
        assertThat(node.usesSandbox()).isTrue();
    }

    @Test
    void scratchCopyIsPerAttemptAndTheTargetIsUntouched() throws Exception {
        FakeSandboxRunner sandbox = new FakeSandboxRunner().then(FakeSandboxRunner.compileError())
                .then(FakeSandboxRunner.report(1, FakeSandboxRunner.FAILED_REPORT));

        run(EvidenceFixtures.fake(), Optional.of(sandbox), FP);

        List<SandboxRunner.Spec> runs = sandbox.testRuns();
        assertThat(runs.get(0).scratchDir()).isNotEqualTo(runs.get(1).scratchDir());
        try (var walk = Files.walk(tmp.resolve("target-copy"))) {
            assertThat(walk.filter(p -> p.toString().contains("secpipe" + java.io.File.separator + "generated")))
                    .isEmpty();
        }
    }
}
