package ca.carleton.secpipe.agents.disprover;

import static ca.carleton.secpipe.agents.disprover.DisproverFixtures.SOURCES;
import static ca.carleton.secpipe.agents.disprover.DisproverFixtures.fake;
import static ca.carleton.secpipe.agents.disprover.DisproverFixtures.fp;
import static ca.carleton.secpipe.agents.disprover.DisproverFixtures.hunterCandidate;
import static ca.carleton.secpipe.agents.disprover.DisproverFixtures.toolCandidate;
import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.agents.prompt.DefaultPromptRenderer;
import ca.carleton.secpipe.agents.runner.AgentRunner;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.VerifierRef.CapabilityTier;
import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.llm.FakeLlmClient;
import ca.carleton.secpipe.validators.CapPolicy;
import ca.carleton.secpipe.validators.FindingsValidator;
import ca.carleton.secpipe.validators.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class DisproverNodeTest {

    private static final class Log implements RunContext.RunLog {
        final List<String> lines = new ArrayList<>();

        @Override
        public void info(String msg) {
            lines.add("info " + msg);
        }

        @Override
        public void warn(String msg) {
            lines.add("warn " + msg);
        }

        @Override
        public void debug(String msg) {
            lines.add("debug " + msg);
        }
    }

    private static PipelineState state(List<Candidate> candidates, Map<String, Finding> records) {
        return new PipelineState("run-1", null, Optional.empty(), Optional.empty(), List.of(), List.of(), candidates,
                records, Map.of(), List.of(), Map.of(), 0, 0, false, Map.of(), Optional.empty(), List.of(),
                Optional.empty());
    }

    private static StateDelta run(LlmClient llm, Candidate... candidates) {
        return run(llm, Map.of(), candidates);
    }

    private static StateDelta run(LlmClient llm, Map<String, Finding> records, Candidate... candidates) {
        AgentRunner runner = new AgentRunner(llm, new DefaultPromptRenderer(), AgentRunner.DiscardListener.NONE);
        return DisproverNode.disprove(state(List.of(candidates), records), llm, runner, SOURCES, Optional.empty(),
                new Log());
    }

    private static Finding only(StateDelta d) {
        assertThat(d.putRecords()).hasSize(1);
        return d.putRecords().get(0);
    }

    private static void assertValid(StateDelta d) {
        ValidationResult v = FindingsValidator.validate(d.putRecords(), Optional.empty(), CapPolicy.NONE);
        assertThat(v.errors()).isEmpty();
    }

    @Test
    void groundedRefutationFromStrongModelRejectsTheCandidate() {
        FakeLlmClient llm = fake();
        StateDelta d = run(llm, toolCandidate(1, "CASE_REFUTED"));

        Finding f = only(d);
        assertThat(f).isInstanceOf(Finding.Rejected.class);
        Finding.Rejected r = (Finding.Rejected) f;
        assertThat(r.fingerprint()).isEqualTo(fp(1));
        assertThat(r.reason()).contains("overwritten");
        Finding.VerifierRef ref = r.secpipe().verification().orElseThrow().disprover().orElseThrow();
        assertThat(ref.kind()).isEqualTo(Finding.VerifierRef.Kind.LLM);
        assertThat(ref.tier()).contains(CapabilityTier.STRONG);
        assertThat(ref.agentId().orElseThrow()).matches("disprover-[0-9a-f]{8}");
        assertThat(r.secpipe().verification().orElseThrow().guardrailApplied()).isFalse();
        assertThat(d.putSurvivorDrafts()).isEmpty();
        assertThat(llm.calls()).hasSize(1);
        assertValid(d);
    }

    @Test
    void ungroundedControlIsRerunWithFreshAgentsThenDroppedToNeedsValidation() {
        FakeLlmClient llm = fake();
        StateDelta d = run(llm, toolCandidate(2, "CASE_UNGROUNDED"));

        assertThat(llm.calls()).hasSize(AgentRunner.MAX_ATTEMPTS);
        Finding f = only(d);
        assertThat(f).isInstanceOf(Finding.NeedsValidation.class);
        assertThat(((Finding.NeedsValidation) f).blockers()).singleElement().asString()
                .contains("disprover output invalid");
        assertThat(d.putSurvivorDrafts()).isEmpty();
        assertValid(d);
    }

    @Test
    void survivingCandidateBecomesASurvivorDraftWithoutARecord() {
        StateDelta d = run(fake(), toolCandidate(3, "CASE_SURVIVES"));

        assertThat(d.putRecords()).isEmpty();
        assertThat(d.putSurvivorDrafts()).containsOnlyKeys(fp(3));
        assertThat(d.putSurvivorDrafts().get(fp(3)).verdict()).isEqualTo(Finding.Verdict.CONFIRMED);
        assertThat(d.putSurvivorDrafts().get(fp(3)).rootCause()).isPresent();
    }

    @Test
    void malformedReplyIsDiscardedAndAFreshAgentIsUsed() {
        FakeLlmClient llm = fake();
        StateDelta d = run(llm, toolCandidate(4, "CASE_MALFORMED"));

        assertThat(llm.calls()).hasSize(2);
        assertThat(d.putSurvivorDrafts()).containsOnlyKeys(fp(4));
    }

    @Test
    void smallTierModelAloneNeverRejectsAToolFlaggedCandidate() {
        LlmClient small = new DisproverFixtures.TierOverride(fake(), CapabilityTier.SMALL);
        StateDelta d = run(small, toolCandidate(7, "CASE_SMALL"));

        Finding f = only(d);
        assertThat(f.verdict()).isEqualTo(Finding.Verdict.NEEDS_VALIDATION);
        assertThat(f.verdict()).isNotEqualTo(Finding.Verdict.REJECTED);
        Finding.NeedsValidation nv = (Finding.NeedsValidation) f;
        assertThat(nv.blockers()).containsExactly(DisproverNode.GUARDRAIL_BLOCKER);
        Finding.Verification v = nv.secpipe().verification().orElseThrow();
        assertThat(v.guardrailApplied()).isTrue();
        assertThat(v.disprover().orElseThrow().tier()).contains(CapabilityTier.SMALL);
        assertValid(d);
    }

    @Test
    void smallTierRefutationOfAHunterOnlyCandidateIsStillRejected() {
        LlmClient small = new DisproverFixtures.TierOverride(fake(), CapabilityTier.SMALL);
        StateDelta d = run(small, hunterCandidate(8, "CASE_HUNTER_SMALL"));

        assertThat(only(d)).isInstanceOf(Finding.Rejected.class);
        assertValid(d);
    }

    @Test
    void refutationWithoutAnEffectiveControlIsNotARejection() {
        StateDelta d = run(fake(), toolCandidate(6, "CASE_NO_CONTROL"));

        Finding f = only(d);
        assertThat(f).isInstanceOf(Finding.NeedsValidation.class);
        assertThat(((Finding.NeedsValidation) f).blockers()).containsExactly(DisproverNode.NO_CONTROL_BLOCKER);
        assertValid(d);
    }

    @Test
    void unavailableProviderLeavesNeedsValidationWithTheReason() {
        StateDelta d = run(fake(), toolCandidate(5, "CASE_OUTAGE"));

        Finding f = only(d);
        assertThat(f).isInstanceOf(Finding.NeedsValidation.class);
        assertThat(((Finding.NeedsValidation) f).blockers()).singleElement().asString()
                .startsWith("disprover unavailable:").contains("fake outage");
        assertValid(d);
    }

    @Test
    void aiModeNoneSkipsTheNode() {
        FakeLlmClient inner = fake();
        StateDelta d = run(new DisproverFixtures.Off(inner), toolCandidate(1, "CASE_REFUTED"));

        assertThat(d).isEqualTo(StateDelta.empty());
        assertThat(inner.calls()).isEmpty();
    }

    @Test
    void oneFreshAgentPerCandidateWithOnlyItsOwnClaim() {
        FakeLlmClient llm = fake();
        StateDelta d = run(llm, toolCandidate(3, "CASE_SURVIVES"), toolCandidate(1, "CASE_REFUTED"));

        assertThat(llm.calls()).hasSize(2);
        // fingerprint order: fp(1) before fp(3)
        assertThat(llm.calls().get(0).request().userPrompt()).contains(fp(1)).doesNotContain(fp(3));
        assertThat(llm.calls().get(1).request().userPrompt()).contains(fp(3)).doesNotContain(fp(1));
        assertThat(d.putRecords()).extracting(Finding::fingerprint).containsExactly(fp(1));
        assertThat(d.putSurvivorDrafts()).containsOnlyKeys(fp(3));
    }

    @Test
    void candidatesAlreadyRejectedOrConfirmedAreNotSentToTheDisprover() {
        FakeLlmClient llm = fake();
        Finding prior = new Finding.Rejected(fp(1), "t", "d", "r", toolCandidate(1, "x").trace(),
                toolCandidate(1, "x").evidence(), "refuted by the deterministic refuter",
                new Finding.SecPipeExt(Finding.Category.CODE, Finding.Origin.TOOL, List.of(), List.of("CWE-89"),
                        List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(),
                        List.of(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(),
                        Optional.empty()));
        StateDelta d = run(llm, Map.of(fp(1), prior), toolCandidate(1, "CASE_REFUTED"));

        assertThat(d).isEqualTo(StateDelta.empty());
        assertThat(llm.calls()).isEmpty();
    }

    @Test
    void nodeIsRegisteredUnderItsGraphId() {
        DisproverNode node = new DisproverNode();
        assertThat(node.id()).isEqualTo("disprove");
        assertThat(node.requiresLlm()).isTrue();
        assertThat(node.usesSandbox()).isFalse();
        assertThat(PipelineGraph.NODES.get("disprove")).isEqualTo(DisproverNode.class.getName());
    }
}
