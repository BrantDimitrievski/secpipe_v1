package ca.carleton.secpipe.core.finalize;

import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.ProjectProfile;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.Budget;
import ca.carleton.secpipe.core.scoring.SeveritySummary;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Builds the frozen {@link RunMetadata} at finalize from the final state. Facts the state does not carry (per-model
 * usage, fallbacks, sandbox image) are empty rather than guessed. {@code terminal_state} is {@code complete} exactly
 * when {@code incompleteReason} is empty (schema {@code allOf} rule). Deterministic: maps sorted, tools sorted by id.
 */
public final class RunMetadataBuilder {

    static final int SCHEMA_VERSION = 1;
    static final String FALLBACK_VERSION = "0.0.0-dev";

    private RunMetadataBuilder() {
    }

    public static RunMetadata build(PipelineState state, Instant finishedAt, Budget budget, boolean sandbox,
                                    Optional<String> incompleteReason, RunMetadata.Validators validators) {
        SecPipeConfig config = state.config();
        long elapsedMs = state.stageTimingsMs().values().stream().mapToLong(Long::longValue).sum();
        Optional<String> reason = incompleteReason.filter(r -> !r.isBlank());
        RunMetadata.Scope scope = new RunMetadata.Scope(
                config.scope() == SecPipeConfig.ScopeMode.DIFF ? RunMetadata.Scope.ScopeMode.DIFF
                        : RunMetadata.Scope.ScopeMode.FULL,
                config.baseRef(), config.headRef(), List.copyOf(state.diffScope()));
        return new RunMetadata(
                SCHEMA_VERSION,
                state.runId(),
                version(),
                finishedAt.minus(Duration.ofMillis(elapsedMs)),
                finishedAt,
                reason.isPresent() ? RunMetadata.TerminalState.INCOMPLETE : RunMetadata.TerminalState.COMPLETE,
                reason,
                config.profile(),
                scope,
                target(state),
                new RunMetadata.Ai(config.llmProvider(), config.llmProvider(), List.of(), List.of(), List.of(), 0,
                        Optional.empty()),
                new RunMetadata.BudgetSnapshot(config.maxAgentCalls(), budget.used(), Map.of(), budget.exhausted(),
                        config.maxSandboxRuns(), 0),
                state.toolRuns().stream().sorted(Comparator.comparing(RunMetadata.ToolRun::id)).toList(),
                new TreeMap<>(state.stageTimingsMs()),
                new RunMetadata.Sandbox(sandbox, Optional.empty(), List.of(), List.of(), Optional.empty()),
                state.prior().map(p -> new RunMetadata.PriorRun(Optional.of(p.runId()), 0, 0, 0, Optional.empty())),
                counts(state),
                validators);
    }

    static RunMetadata.Counts counts(PipelineState state) {
        SeveritySummary summary = SeveritySummary.of(state.records().values());
        Map<String, Integer> units = new TreeMap<>();
        for (LedgerUnit u : state.ledger()) {
            units.merge(u.status().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        return new RunMetadata.Counts(summary.confirmed(), summary.needsValidation(), summary.rejected(), units,
                summary.bySeverityNames());
    }

    private static RunMetadata.Target target(PipelineState state) {
        Path root = state.config().target();
        String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        Optional<ProjectProfile> p = state.profile();
        return new RunMetadata.Target(
                name,
                p.map(x -> x.buildTool().name().toLowerCase(Locale.ROOT)).orElse("none"),
                state.environment().map(e -> e.javaVersion()),
                state.environment().flatMap(e -> e.mavenVersion()),
                p.map(ProjectProfile::linesOfCode).orElse(0L),
                p.map(x -> x.sourceFiles().size()).orElse(0),
                p.map(x -> x.frameworks().stream().sorted().toList()).orElse(List.of()),
                Optional.empty());
    }

    private static String version() {
        String v = RunMetadataBuilder.class.getPackage().getImplementationVersion();
        return v == null ? FALLBACK_VERSION : v;
    }
}
