package ca.carleton.secpipe.core.normalize;

import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;

import java.util.Optional;

/**
 * Graph nodes {@code normalize} and {@code normalize_llm}: replaces the candidate list with fingerprinted,
 * cross-tool-merged candidates sorted by fingerprint. Deterministic; never calls an LLM.
 */
public final class NormalizeNode implements PipelineNode {

    private final CandidateMerger merger;

    public NormalizeNode() {
        this(new CandidateMerger());
    }

    NormalizeNode(CandidateMerger merger) {
        this.merger = merger;
    }

    @Override
    public String id() {
        return "normalize";
    }

    @Override
    public boolean requiresLlm() {
        return false;
    }

    @Override
    public boolean usesSandbox() {
        return false;
    }

    @Override
    public Result apply(PipelineState state, RunContext ctx) {
        CandidateMerger.Outcome outcome = merger.normalize(state.candidates());
        StateDelta e = StateDelta.empty();
        StateDelta delta = new StateDelta(e.environment(), e.profile(), e.diffScope(), e.newLedgerUnits(),
                e.ledgerUpdates(), Optional.of(outcome.candidates()), e.addCandidates(), e.putRecords(),
                e.putSurvivorDrafts(), e.toolRuns(), e.timingsMs(), e.hunterWave(), e.criticPasses(),
                e.criticSaysStop(), e.fixAttemptsByFingerprint(), e.prior(), outcome.notes(), e.incompleteReason());
        return Result.next(delta);
    }
}
