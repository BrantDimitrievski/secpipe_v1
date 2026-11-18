package ca.carleton.secpipe.report;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Everything the report writers render from: the final pipeline state plus the frozen run metadata. Nothing else
 * (no clock, no file system) feeds a report, so equal inputs give equal bytes (CLAUDE.md §8).
 */
public record ReportInputs(PipelineState state, RunMetadata metadata) {

    public ReportInputs {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(metadata, "metadata");
    }

    /** All current records (every verdict), sorted by fingerprint. */
    public List<Finding> findings() {
        return state.records().values().stream()
                .sorted(Comparator.comparing(Finding::fingerprint))
                .toList();
    }

    /** The coverage ledger, sorted by {@code coverage_id}. */
    public List<LedgerUnit> ledger() {
        return state.ledger().stream()
                .sorted(Comparator.comparing(LedgerUnit::coverageId))
                .toList();
    }
}
