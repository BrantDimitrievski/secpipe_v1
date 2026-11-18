package ca.carleton.secpipe.agents.disprover;

import ca.carleton.secpipe.agents.prompt.DefaultPromptRenderer;
import ca.carleton.secpipe.agents.runner.AgentOutcome;
import ca.carleton.secpipe.agents.runner.AgentRunner;
import ca.carleton.secpipe.agents.runner.GroundingContext;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.ControlExamined;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.DisproverResult;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.VerifierRef;
import ca.carleton.secpipe.contracts.finding.Finding.VerifierRef.CapabilityTier;
import ca.carleton.secpipe.contracts.json.ReconFactsCodec;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.recon.ReconFacts;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.contracts.spi.SourceIndex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pipeline node {@code disprove} (WO-G.4, PLAN Part 5 §A7). Runs after the deterministic refuter.
 *
 * <p>For every normalized code candidate that has no {@code rejected} or {@code confirmed} record yet, in fingerprint
 * order, it runs one fresh {@link DisproverAgent} through {@link AgentRunner} (no shared conversation state) and maps
 * the outcome:
 * <ul>
 *   <li>{@code Ok} + {@code rejected} with at least one effective, grounded control: a {@code rejected} record,
 *       unless the routed tier is {@code small} and the candidate is tool-flagged, in which case the guardrail
 *       makes it {@code needs_validation} (a small model is never the sole reason a tool finding is rejected);</li>
 *   <li>{@code Ok} + {@code rejected} citing no effective control: {@code needs_validation};</li>
 *   <li>{@code Ok} + {@code confirmed}/{@code needs_validation} (survives): the result goes to
 *       {@code StateDelta.putSurvivorDrafts} for test generation (WO-G.7); no record is written here;</li>
 *   <li>{@code Dropped} (invalid or ungrounded output on every fresh attempt) or {@code Unavailable}:
 *       {@code needs_validation} with the reason as blocker.</li>
 * </ul>
 * With AI off ({@code mode()==NONE}) the node changes nothing.
 */
public final class DisproverNode implements PipelineNode {

    public static final String ID = "disprove";

    static final String GUARDRAIL_BLOCKER = "a small-tier disprover refuted this tool-flagged candidate; a small model "
            + "alone never rejects a tool finding, so it needs a strong/standard or deterministic second verifier";
    static final String NO_CONTROL_BLOCKER = "the disprover refuted this candidate without citing an effective control";

    public DisproverNode() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean requiresLlm() {
        return true;
    }

    @Override
    public boolean usesSandbox() {
        return false;
    }

    @Override
    public Result apply(PipelineState state, RunContext ctx) {
        if (ctx.llm().mode() == ProviderMode.NONE) {
            return Result.next(StateDelta.empty());
        }
        Path factsFile = ctx.workDir().resolve(ReconFactsCodec.FILE_NAME);
        Optional<ReconFacts> facts = Files.isRegularFile(factsFile)
                ? Optional.of(ReconFactsCodec.read(factsFile)) : Optional.empty();
        AgentRunner runner = new AgentRunner(ctx.llm(), new DefaultPromptRenderer(),
                (role, agentId, attempt, reason) -> ctx.log().warn("discarded " + agentId + " output (attempt "
                        + attempt + "): " + reason));
        return Result.next(disprove(state, ctx.llm(), runner, ProjectSourceIndex.of(state), facts, ctx.log()));
    }

    /** The node's work with its collaborators passed in (used by {@link #apply} and by tests). */
    static StateDelta disprove(PipelineState state, LlmClient llm, AgentRunner runner, SourceIndex sources,
                               Optional<ReconFacts> facts, RunContext.RunLog log) {
        if (llm.mode() == ProviderMode.NONE) {
            return StateDelta.empty();
        }
        DisproverContextBuilder builder = new DisproverContextBuilder(sources, facts);
        List<Candidate> todo = new ArrayList<>();
        for (Candidate c : state.candidates()) {
            if (c.fingerprint().isEmpty() || c.category() != Finding.Category.CODE || c.trace().isEmpty()) {
                continue;
            }
            Finding prior = state.records().get(c.fingerprint().get());
            if (prior != null && prior.verdict() != Finding.Verdict.NEEDS_VALIDATION) {
                continue;
            }
            todo.add(c);
        }
        todo.sort(Comparator.comparing(c -> c.fingerprint().get()));

        List<Finding> records = new ArrayList<>();
        Map<String, DisproverResult> drafts = new TreeMap<>();
        for (Candidate c : todo) {
            String fp = c.fingerprint().get();
            DisproverInput input = builder.build(c);
            AgentOutcome<DisproverResult> outcome = runner.run(new DisproverAgent(), input,
                    GroundingContext.of(sources, Set.of(fp)));
            switch (outcome) {
                case AgentOutcome.Ok<DisproverResult> ok -> {
                    DisproverResult r = ok.value();
                    if (r.verdict() != Finding.Verdict.REJECTED) {
                        drafts.put(fp, r);
                        log.info("disprover: " + fp + " survives (" + r.verdict().name().toLowerCase(Locale.ROOT) + ")");
                    } else {
                        records.add(refuted(c, r, ok));
                    }
                }
                case AgentOutcome.Dropped<DisproverResult> d -> {
                    log.warn("disprover: " + fp + " dropped: " + d.reason());
                    records.add(needsValidation(c, "disprover output invalid after " + d.attempts()
                            + " fresh attempts", Optional.empty(), false));
                }
                case AgentOutcome.Unavailable<DisproverResult> u -> {
                    log.warn("disprover: " + fp + " not verified: " + u.reason());
                    records.add(needsValidation(c, "disprover unavailable: " + u.reason(), Optional.empty(), false));
                }
            }
        }
        if (records.isEmpty() && drafts.isEmpty()) {
            return StateDelta.empty();
        }
        StateDelta e = StateDelta.empty();
        return new StateDelta(e.environment(), e.profile(), e.diffScope(), e.newLedgerUnits(), e.ledgerUpdates(),
                e.replaceCandidates(), e.addCandidates(), List.copyOf(records), Map.copyOf(drafts), e.toolRuns(),
                e.timingsMs(), e.hunterWave(), e.criticPasses(), e.criticSaysStop(), e.fixAttemptsByFingerprint(),
                e.prior(), e.notes(), e.incompleteReason());
    }

    /** Whether a tool reported this candidate (alone or merged with a hunter). */
    static boolean toolFlagged(Candidate c) {
        return c.origin() == Finding.Origin.TOOL || !c.toolSources().isEmpty();
    }

    private static Finding refuted(Candidate c, DisproverResult r, AgentOutcome.Ok<DisproverResult> ok) {
        VerifierRef ref = new VerifierRef(VerifierRef.Kind.LLM, Optional.of(ok.agentId()),
                Optional.of(ok.model().provider().cliName()), Optional.of(ok.model().modelId()),
                Optional.ofNullable(ok.model().tier()), Optional.empty());
        boolean effectiveControl = r.controlsExamined().stream().anyMatch(ControlExamined::effective);
        if (!effectiveControl) {
            return needsValidation(c, NO_CONTROL_BLOCKER, Optional.of(ref), false);
        }
        if (ok.model().tier() == CapabilityTier.SMALL && toolFlagged(c)) {
            return needsValidation(c, GUARDRAIL_BLOCKER, Optional.of(ref), true);
        }
        String reason = r.reason().filter(s -> !s.isBlank()).orElse("refuted by the disprover");
        return new Finding.Rejected(c.fingerprint().get(), title(c), description(c), rootCause(c), c.trace(),
                evidence(c), reason, ext(c, Optional.of(ref), false, true));
    }

    private static Finding needsValidation(Candidate c, String blocker, Optional<VerifierRef> ref, boolean guardrail) {
        Finding.ValidationPlan plan = new Finding.ValidationPlan(Optional.of("Review the cited trace by hand, or "
                + "re-run SecPipe with a strong or standard disprover model."), Optional.empty());
        return new Finding.NeedsValidation(c.fingerprint().get(), title(c), description(c), rootCause(c), c.trace(),
                evidence(c), List.of(blocker), plan, ext(c, ref, guardrail, false));
    }

    private static Finding.SecPipeExt ext(Candidate c, Optional<VerifierRef> ref, boolean guardrail,
                                          boolean rejected) {
        Optional<Finding.Verification> verification = ref.map(r ->
                new Finding.Verification(Optional.of(r), Optional.empty(), Optional.empty(), guardrail));
        // F5: tool_severity_hint is allowed only on needs_validation records.
        Optional<Finding.Score5> hint = rejected ? Optional.empty() : c.toolSeverityHint();
        return new Finding.SecPipeExt(c.category(), c.origin(), c.toolSources(), c.cwe(), c.cve(), Optional.empty(),
                c.dependency(), hint, c.confidenceHint(), List.of(), List.of(), Optional.empty(),
                Optional.empty(), verification, c.ledgerUnits(), Optional.empty(), Optional.empty());
    }

    private static String title(Candidate c) {
        return nonBlank(c.title(), "candidate " + c.fingerprint().get());
    }

    private static String description(Candidate c) {
        return nonBlank(c.message(), title(c));
    }

    private static String rootCause(Candidate c) {
        return nonBlank(c.claimedRootCause(), description(c));
    }

    private static List<Finding.Evidence> evidence(Candidate c) {
        if (!c.evidence().isEmpty()) {
            return c.evidence();
        }
        Finding.TraceStep sink = c.trace().get(c.trace().size() - 1);
        return List.of(new Finding.Evidence(sink.file(), sink.line(), nonBlank(sink.description(), "sink")));
    }

    private static String nonBlank(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
