package ca.carleton.secpipe.report;

import ca.carleton.secpipe.contracts.json.RunMetadataCodec;
import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.report.patch.PatchSource;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final boolean keepFrozen;

    /**
     * Used by the engine (FQCN table in PipelineGraph). Reads the {@code run-metadata.json} that finalize froze into
     * the output directory and leaves it untouched on success; assembles from the state only when it is missing.
     */
    public ReportNode() {
        this(new DefaultReportRenderer(), ReportNode::frozenOrAssembled, true);
    }

    public ReportNode(ReportRenderer renderer, MetadataSource metadata) {
        this(renderer, metadata, false);
    }

    private ReportNode(ReportRenderer renderer, MetadataSource metadata, boolean keepFrozen) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.keepFrozen = keepFrozen;
    }

    private static RunMetadata frozenOrAssembled(PipelineState state, RunContext ctx) {
        Path file = ctx.outDir().resolve(ReportBundle.METADATA);
        if (Files.isRegularFile(file)) {
            return RunMetadataCodec.read(file);
        }
        return RunMetadataAssembler.assemble(state, ctx.clock().instant(), ctx.budget(), ctx.sandbox().isPresent(),
                ctx.llm().usageSnapshot());
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
        boolean frozen = keepFrozen && Files.isRegularFile(ctx.outDir().resolve(ReportBundle.METADATA));
        RunMetadata meta = metadata.metadata(state, ctx);
        ReportInputs inputs = new ReportInputs(state, meta);
        ReportBundle bundle = ReportBundle.render(inputs, renderer, state.config().formats(),
                PatchSource.fromDir(ctx.outDir()));
        if (!bundle.ok()) {
            String reason = bundle.incompleteReason().orElseThrow();
            ctx.log().warn(reason);
            if (frozen) {
                RunMetadataCodec.write(ctx.outDir().resolve(ReportBundle.METADATA), amended(meta, reason));
            }
            return new Result(PipelineGraph.NEXT, withIncomplete(reason));
        }
        if (frozen) {
            // finalize's file is the record: keep it byte for byte
            Map<String, String> files = new java.util.TreeMap<>(bundle.files());
            files.remove(ReportBundle.METADATA);
            new ReportBundle(files, Optional.empty()).write(ctx.outDir());
        } else {
            bundle.write(ctx.outDir());
        }
        ctx.log().info("report: wrote " + String.join(", ", bundle.files().keySet()));
        return Result.next(StateDelta.empty());
    }

    /** Only the terminal state and its reason change; validator statuses and everything else stay as finalize set. */
    static RunMetadata amended(RunMetadata m, String reason) {
        String joined = m.incompleteReason().filter(r -> !r.isBlank()).map(r -> r + "; " + reason).orElse(reason);
        return new RunMetadata(m.schemaVersion(), m.runId(), m.secpipeVersion(), m.startedAt(), m.finishedAt(),
                RunMetadata.TerminalState.INCOMPLETE, Optional.of(joined), m.profile(), m.scope(), m.target(),
                m.ai(), m.budget(), m.tools(), m.stageTimingsMs(), m.sandbox(), m.priorRun(), m.counts(),
                m.validators());
    }

    private static StateDelta withIncomplete(String reason) {
        return new StateDelta(Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of(),
                Optional.empty(), List.of(), List.of(), Map.of(), List.of(), Map.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), Map.of(), Optional.empty(), List.of(), Optional.of(reason));
    }
}
