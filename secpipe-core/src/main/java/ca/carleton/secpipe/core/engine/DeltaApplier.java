package ca.carleton.secpipe.core.engine;

import ca.carleton.secpipe.contracts.agent.AgentOutputs;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.core.ledger.LedgerStore;
import ca.carleton.secpipe.validators.ValidationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The only code that writes {@link PipelineState} (feature A). Nodes return a {@link StateDelta}; the engine calls
 * {@link #apply} and hands the next node the returned, deeply immutable state.
 *
 * <p>Application order (WO-K.1): (1) scalar fields, (2) {@code newLedgerUnits}, (3) {@code ledgerUpdates} through
 * {@link LedgerStore}, (4) candidates ({@code replaceCandidates}, then {@code addCandidates}), (5) records and survivor
 * drafts, (6) tool runs and timings. The ledger validator runs after each ledger write and the findings validator after
 * any record change; a failure rejects the whole delta and the input state stays as it was.
 *
 * <p>Merge rules: Optional scalars replace when present. {@code notes} and {@code toolRuns} append. {@code putRecords},
 * {@code putSurvivorDrafts} and {@code fixAttemptsByFingerprint} insert or replace by key. {@code timingsMs} are added
 * to any existing value for the same stage (a node that runs twice, e.g. {@code hunt}, accumulates). The first
 * {@code incompleteReason} wins; a later one is kept as a note "additional incomplete reason: ...". All maps are
 * re-sorted by key and the ledger by coverage id; candidates, notes and tool runs keep insertion order.
 */
public final class DeltaApplier {

    private final EngineValidators validators;
    private final LedgerStore ledgerStore;

    public DeltaApplier(EngineValidators validators) {
        this.validators = Objects.requireNonNull(validators, "validators");
        this.ledgerStore = new LedgerStore(validators.ledgerTransition());
    }

    /**
     * A deeply immutable copy of {@code state}: every collection, including those nested in contract records, is
     * unmodifiable; maps are sorted by key and the ledger by coverage id. The runner freezes the initial state once;
     * every state returned by {@link #apply} is already frozen.
     */
    public static PipelineState freeze(PipelineState state) {
        return StateFreezer.state(Objects.requireNonNull(state, "state"));
    }

    /**
     * Applies {@code delta} to {@code state} and returns the new frozen state.
     *
     * @throws DeltaRejectedException if the delta is malformed (null fields or elements, unknown or duplicate coverage
     *                                ids, records without a fingerprint) or a validator rejects it
     */
    public PipelineState apply(PipelineState state, StateDelta delta) throws DeltaRejectedException {
        Objects.requireNonNull(state, "state");
        if (delta == null) {
            throw new DeltaRejectedException("node returned no delta");
        }
        requireFields(delta);
        try {
            return applyChecked(state, delta);
        } catch (NullPointerException e) {
            throw new DeltaRejectedException("delta contains a null value"
                    + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")"));
        }
    }

    private PipelineState applyChecked(PipelineState s, StateDelta d) throws DeltaRejectedException {
        // (1) scalar fields
        var environment = d.environment().isPresent() ? d.environment().map(StateFreezer::environment)
                : s.environment();
        var profile = d.profile().isPresent() ? d.profile().map(StateFreezer::profile) : s.profile();
        List<String> diffScope = d.diffScope().map(List::copyOf).orElse(s.diffScope());
        int hunterWave = d.hunterWave().orElse(s.hunterWave());
        int criticPasses = d.criticPasses().orElse(s.criticPasses());
        boolean criticSaysStop = d.criticSaysStop().orElse(s.criticSaysStop());
        var prior = d.prior().isPresent() ? d.prior().map(StateFreezer::priorRun) : s.prior();
        List<String> notes = new ArrayList<>(s.notes());
        notes.addAll(d.notes());
        Optional<String> incompleteReason = s.incompleteReason();
        if (d.incompleteReason().isPresent()) {
            if (incompleteReason.isEmpty()) {
                incompleteReason = d.incompleteReason();
            } else {
                notes.add("additional incomplete reason: " + d.incompleteReason().get());
            }
        }

        // (2) + (3) ledger, through the sole ledger writer
        List<LedgerUnit> ledger = s.ledger();
        try {
            if (!d.newLedgerUnits().isEmpty()) {
                ledger = ledgerStore.add(ledger, StateFreezer.list(d.newLedgerUnits(), StateFreezer::ledgerUnit));
            }
            if (!d.ledgerUpdates().isEmpty()) {
                ledger = ledgerStore.apply(ledger, d.ledgerUpdates());
            }
        } catch (LedgerStore.LedgerRejectedException e) {
            throw e.validation().isPresent()
                    ? new DeltaRejectedException(e.getMessage(), e.validation().get())
                    : new DeltaRejectedException(e.getMessage());
        }

        // (4) candidates
        List<Candidate> candidates = d.replaceCandidates().isPresent()
                ? new ArrayList<>(d.replaceCandidates().get())
                : new ArrayList<>(s.candidates());
        candidates.addAll(d.addCandidates());
        List<Candidate> frozenCandidates = d.replaceCandidates().isEmpty() && d.addCandidates().isEmpty()
                ? s.candidates()
                : StateFreezer.list(candidates, StateFreezer::candidate);

        // (5) records and survivor drafts
        Map<String, Finding> records = s.records();
        if (!d.putRecords().isEmpty()) {
            Map<String, Finding> next = new LinkedHashMap<>(records);
            for (Finding f : d.putRecords()) {
                if (f.fingerprint() == null || f.fingerprint().isBlank()) {
                    throw new DeltaRejectedException("record without a fingerprint: " + f.title());
                }
                next.put(f.fingerprint(), f);
            }
            records = StateFreezer.sortedMap(next, StateFreezer::finding);
            ValidationResult result = validators.findings().apply(List.copyOf(records.values()));
            if (result == null) {
                throw new DeltaRejectedException("findings validator returned no result");
            }
            if (!result.ok()) {
                throw new DeltaRejectedException("findings validator: " + LedgerStore.summary(result), result);
            }
        }
        Map<String, AgentOutputs.DisproverResult> drafts = d.putSurvivorDrafts().isEmpty()
                ? s.survivorDrafts()
                : StateFreezer.sortedMap(putAll(s.survivorDrafts(), d.putSurvivorDrafts()),
                        StateFreezer::disproverResult);
        Map<String, Integer> fixAttempts = d.fixAttemptsByFingerprint().isEmpty()
                ? s.fixAttemptsByFingerprint()
                : StateFreezer.sortedMap(putAll(s.fixAttemptsByFingerprint(), d.fixAttemptsByFingerprint()),
                        UnaryOperator.identity());

        // (6) tool runs and timings
        List<RunMetadata.ToolRun> toolRuns = new ArrayList<>(s.toolRuns());
        toolRuns.addAll(d.toolRuns());
        Map<String, Long> timings = new LinkedHashMap<>(s.stageTimingsMs());
        d.timingsMs().forEach((stage, ms) -> timings.merge(Objects.requireNonNull(stage, "timing stage"),
                Objects.requireNonNull(ms, "timing value"), Long::sum));

        return new PipelineState(s.runId(), s.config(), environment, profile, diffScope, ledger, frozenCandidates,
                records, drafts, List.copyOf(toolRuns), StateFreezer.sortedMap(timings, UnaryOperator.identity()),
                hunterWave, criticPasses, criticSaysStop, fixAttempts, prior, List.copyOf(notes), incompleteReason);
    }

    private static <V> Map<String, V> putAll(Map<String, V> base, Map<String, V> add) {
        Map<String, V> out = new LinkedHashMap<>(base);
        out.putAll(add);
        return out;
    }

    private static void requireFields(StateDelta d) throws DeltaRejectedException {
        Object[][] fields = {
            {"environment", d.environment()}, {"profile", d.profile()}, {"diffScope", d.diffScope()},
            {"newLedgerUnits", d.newLedgerUnits()}, {"ledgerUpdates", d.ledgerUpdates()},
            {"replaceCandidates", d.replaceCandidates()}, {"addCandidates", d.addCandidates()},
            {"putRecords", d.putRecords()}, {"putSurvivorDrafts", d.putSurvivorDrafts()},
            {"toolRuns", d.toolRuns()}, {"timingsMs", d.timingsMs()}, {"hunterWave", d.hunterWave()},
            {"criticPasses", d.criticPasses()}, {"criticSaysStop", d.criticSaysStop()},
            {"fixAttemptsByFingerprint", d.fixAttemptsByFingerprint()}, {"prior", d.prior()},
            {"notes", d.notes()}, {"incompleteReason", d.incompleteReason()},
        };
        for (Object[] f : fields) {
            if (f[1] == null) {
                throw new DeltaRejectedException("delta field " + f[0] + " is null");
            }
        }
    }
}
