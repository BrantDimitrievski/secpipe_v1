package ca.carleton.secpipe.contracts.pipeline;

import ca.carleton.secpipe.contracts.agent.AgentOutputs;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.EnvironmentInfo;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a node may ask the engine to change. The engine applies deltas in order, then runs LedgerValidator
 * (after any ledger change) and FindingsValidator (after any record change); a delta that fails validation is
 * rejected and the run becomes `incomplete` with reason "validator rejected delta from node <id>: ...".
 */
public record StateDelta(
        Optional<EnvironmentInfo> environment,
        Optional<ProjectProfile> profile,
        Optional<List<String>> diffScope,
        List<LedgerUnit> newLedgerUnits,
        List<LedgerUnit.Update> ledgerUpdates,
        Optional<List<Candidate>> replaceCandidates,   // normalize replaces the whole list
        List<Candidate> addCandidates,
        List<Finding> putRecords,                      // insert or replace by fingerprint
        Map<String, AgentOutputs.DisproverResult> putSurvivorDrafts,
        List<RunMetadata.ToolRun> toolRuns,
        Map<String, Long> timingsMs,
        Optional<Integer> hunterWave,
        Optional<Integer> criticPasses,
        Optional<Boolean> criticSaysStop,
        Map<String, Integer> fixAttemptsByFingerprint,
        Optional<PipelineState.PriorRun> prior,
        List<String> notes,
        Optional<String> incompleteReason) {

    public static StateDelta empty() {
        return new StateDelta(Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of(), Optional.empty(),
                List.of(), List.of(), Map.of(), List.of(), Map.of(), Optional.empty(), Optional.empty(), Optional.empty(), Map.of(),
                Optional.empty(), List.of(), Optional.empty());
    }
}
