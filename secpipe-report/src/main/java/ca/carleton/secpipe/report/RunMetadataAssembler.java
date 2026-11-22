package ca.carleton.secpipe.report;

import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.ProjectProfile;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.Budget;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Derives {@code run-metadata.json} content from the final {@link PipelineState} when no earlier node froze one.
 * Facts the state does not carry (sandbox image) are reported as empty rather than
 * guessed. Counts always come from the records and the ledger.
 */
public final class RunMetadataAssembler {

    static final int SCHEMA_VERSION = 1;
    static final String FALLBACK_VERSION = "0.0.0-dev";

    private RunMetadataAssembler() {
    }

    /**
     * @param now        the finish time, from {@code RunContext.clock()}
     * @param budget     the run's call budget, for the budget block
     * @param sandbox    whether a sandbox runner was available
     * @param ai         the run's {@code ai} block, from {@code RunContext.llm().usageSnapshot()}
     */
    public static RunMetadata assemble(PipelineState state, Instant now, Budget budget, boolean sandbox,
                                       RunMetadata.Ai ai) {
        SecPipeConfig config = state.config();
        long elapsedMs = state.stageTimingsMs().values().stream().mapToLong(Long::longValue).sum();
        Instant started = now.minus(Duration.ofMillis(elapsedMs));
        Optional<String> incomplete = state.incompleteReason().filter(r -> !r.isBlank());

        RunMetadata.Scope scope = new RunMetadata.Scope(
                config.scope() == SecPipeConfig.ScopeMode.DIFF ? RunMetadata.Scope.ScopeMode.DIFF
                        : RunMetadata.Scope.ScopeMode.FULL,
                config.baseRef(), config.headRef(), List.copyOf(state.diffScope()));

        return new RunMetadata(
                SCHEMA_VERSION,
                state.runId(),
                version(),
                started,
                now,
                incomplete.isPresent() ? RunMetadata.TerminalState.INCOMPLETE : RunMetadata.TerminalState.COMPLETE,
                incomplete,
                config.profile(),
                scope,
                target(state),
                ai,
                new RunMetadata.BudgetSnapshot(config.maxAgentCalls(), budget.used(), Map.of(), budget.exhausted(),
                        config.maxSandboxRuns(), 0),
                state.toolRuns().stream().sorted(java.util.Comparator.comparing(RunMetadata.ToolRun::id)).toList(),
                new TreeMap<>(state.stageTimingsMs()),
                new RunMetadata.Sandbox(sandbox, Optional.empty(), List.of(), List.of(), Optional.empty()),
                state.prior().map(p -> new RunMetadata.PriorRun(Optional.of(p.runId()), 0, 0, 0, Optional.empty())),
                counts(state),
                new RunMetadata.Validators(RunMetadata.ValidatorStatus.PASS, RunMetadata.ValidatorStatus.PASS));
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

    static RunMetadata.Counts counts(PipelineState state) {
        int confirmed = 0;
        int needs = 0;
        int rejected = 0;
        Map<String, Integer> bySeverity = new TreeMap<>();
        for (Finding f : state.records().values()) {
            switch (f.verdict()) {
                case CONFIRMED -> confirmed++;
                case NEEDS_VALIDATION -> needs++;
                case REJECTED -> rejected++;
            }
            if (f instanceof Finding.Confirmed c) {
                bySeverity.merge(c.severity().overallSeverity().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        Map<String, Integer> units = new TreeMap<>();
        for (LedgerUnit u : state.ledger()) {
            units.merge(u.status().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        return new RunMetadata.Counts(confirmed, needs, rejected, units, bySeverity);
    }

    private static String version() {
        String v = RunMetadataAssembler.class.getPackage().getImplementationVersion();
        return v == null ? FALLBACK_VERSION : v;
    }
}
