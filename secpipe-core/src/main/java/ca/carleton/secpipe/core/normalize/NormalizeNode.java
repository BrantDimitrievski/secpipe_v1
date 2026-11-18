package ca.carleton.secpipe.core.normalize;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.core.detect.SettlementMatch;
import ca.carleton.secpipe.core.knowledge.KnowledgeBase;
import ca.carleton.secpipe.core.ledger.LedgerTransitions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Graph nodes {@code normalize} and {@code normalize_llm}: replaces the candidate list with fingerprinted,
 * cross-tool-merged candidates sorted by fingerprint, and gives every tool-settled {@code candidate} ledger unit the
 * fingerprints of the normalized candidates that settled it (same matching rule as detect, {@link SettlementMatch}).
 * Ledger fingerprint sets only grow: a unit is updated only when the union adds a fingerprint. Deterministic; never
 * calls an LLM.
 */
public final class NormalizeNode implements PipelineNode {

    private final CandidateMerger merger;
    private final KnowledgeBase knowledge;

    public NormalizeNode() {
        this(KnowledgeBase.load());
    }

    NormalizeNode(KnowledgeBase knowledge) {
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
        this.merger = new CandidateMerger(knowledge);
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
        List<LedgerUnit.Update> updates = ledgerFingerprints(state.ledger(), outcome.candidates());
        StateDelta e = StateDelta.empty();
        StateDelta delta = new StateDelta(e.environment(), e.profile(), e.diffScope(), e.newLedgerUnits(),
                updates, Optional.of(outcome.candidates()), e.addCandidates(), e.putRecords(),
                e.putSurvivorDrafts(), e.toolRuns(), e.timingsMs(), e.hunterWave(), e.criticPasses(),
                e.criticSaysStop(), e.fixAttemptsByFingerprint(), e.prior(), outcome.notes(), e.incompleteReason());
        return Result.next(delta);
    }

    /**
     * {@code toolSettled} candidate -> candidate updates for tool-settled candidate units (settled_by present, no
     * agent) whose fingerprint set grows by the fingerprints of the matching normalized candidates. The update carries
     * the sorted union (existing plus new), since a non-empty list replaces the unit's list. Sorted by coverage id.
     */
    List<LedgerUnit.Update> ledgerFingerprints(List<LedgerUnit> ledger, List<Candidate> normalized) {
        List<LedgerUnit.Update> out = new ArrayList<>();
        for (LedgerUnit unit : ledger) {
            if (unit.status() != LedgerUnit.Status.CANDIDATE || unit.settledBy().isEmpty()
                    || unit.agentId().isPresent()) {
                continue;
            }
            Set<String> fingerprints = new TreeSet<>(unit.resultFingerprints());
            int before = fingerprints.size();
            for (Candidate c : normalized) {
                if (c.fingerprint().isPresent() && SettlementMatch.settles(knowledge, unit, c)) {
                    fingerprints.add(c.fingerprint().get());
                }
            }
            if (fingerprints.size() > before) {
                out.add(LedgerTransitions.toolSettled(unit.coverageId(), LedgerUnit.Status.CANDIDATE,
                        unit.settledBy(), unit.reviewedPaths(), unit.localChecks(), List.copyOf(fingerprints)));
            }
        }
        out.sort(Comparator.comparing(LedgerUnit.Update::coverageId));
        return List.copyOf(out);
    }
}
