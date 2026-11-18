package ca.carleton.secpipe.report;

import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The last graph node ({@code report}). Renders findings.json, coverage-ledger.json, run-metadata.json, the SARIF
 * file and report.md from the final state, validated first. On a validation failure it writes nothing and records an
 * {@code incomplete_reason}. It writes only files under {@code RunContext.outDir()}.
 */
public final class ReportNode implements PipelineNode {

    /** Supplies the run metadata for the report. */
    @FunctionalInterface
    public interface MetadataSource {
        RunMetadata metadata(PipelineState state, RunContext ctx);
    }

    private final ReportRenderer renderer;
    private final MetadataSource metadata;

    /** Used by the engine (FQCN table in PipelineGraph). */
    public ReportNode() {
        this(new DefaultReportRenderer(), (state, ctx) -> RunMetadataAssembler.assemble(state, ctx.clock().instant(),
                ctx.budget(), ctx.sandbox().isPresent()));
    }

    public ReportNode(ReportRenderer renderer, MetadataSource metadata) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
    }

    @Override
    public String id() {
        return "report";
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
        ReportInputs inputs = new ReportInputs(state, metadata.metadata(state, ctx));
        ReportBundle bundle = ReportBundle.render(inputs, renderer, state.config().formats());
        if (!bundle.ok()) {
            String reason = bundle.incompleteReason().orElseThrow();
            ctx.log().warn(reason);
            return new Result(PipelineGraph.NEXT, withIncomplete(reason));
        }
        bundle.write(ctx.outDir());
        ctx.log().info("report: wrote " + String.join(", ", bundle.files().keySet()));
        return Result.next(StateDelta.empty());
    }

    private static StateDelta withIncomplete(String reason) {
        return new StateDelta(Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of(),
                Optional.empty(), List.of(), List.of(), Map.of(), List.of(), Map.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), Map.of(), Optional.empty(), List.of(), Optional.of(reason));
    }
}
