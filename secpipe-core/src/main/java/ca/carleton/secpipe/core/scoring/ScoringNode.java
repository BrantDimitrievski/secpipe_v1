package ca.carleton.secpipe.core.scoring;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Graph node {@code score}: applies {@link ScoringPolicy} to every record and puts back the records whose severity
 * (or severity hint) changed, sorted by fingerprint. Deterministic; no LLM.
 */
public final class ScoringNode implements PipelineNode {

    private final ScoringPolicy policy;

    /** Used by the engine (FQCN table in PipelineGraph). */
    public ScoringNode() {
        this(new ScoringPolicy());
    }

    public ScoringNode(ScoringPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public String id() {
        return "score";
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
        List<Finding> sorted = state.records().values().stream()
                .sorted(Comparator.comparing(Finding::fingerprint)).toList();
        List<Finding> scored = sorted.stream().map(policy::apply).toList();
        List<Finding> changed = IntStream.range(0, sorted.size())
                .filter(i -> !scored.get(i).equals(sorted.get(i))).mapToObj(scored::get).toList();
        ctx.log().info("score: " + SeveritySummary.of(scored) + "; rescored " + changed.size());
        return Result.next(new StateDelta(Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of(),
                Optional.empty(), List.of(), changed, Map.of(), List.of(), Map.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), Map.of(), Optional.empty(), List.of(), Optional.empty()));
    }
}
