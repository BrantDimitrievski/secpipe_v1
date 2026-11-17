package ca.carleton.secpipe.contracts.pipeline;

import ca.carleton.secpipe.contracts.agent.AgentOutputs;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.EnvironmentInfo;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable snapshot of run state handed to nodes (feature A: nodes read, only the engine writes by applying
 * StateDelta). Also the LangGraph4j state schema if WO-K.2 is built (one channel per field).
 */
public record PipelineState(
        String runId,
        SecPipeConfig config,
        Optional<EnvironmentInfo> environment,
        Optional<ProjectProfile> profile,
        List<String> diffScope,                         // empty when scope=FULL
        List<LedgerUnit> ledger,
        List<Candidate> candidates,                     // normalized & fingerprinted after `normalize`
        Map<String, Finding> records,                   // fingerprint -> current record
        Map<String, AgentOutputs.DisproverResult> survivorDrafts, // fingerprint -> disprover result for candidates that
                                                        // survived refutation; consumed by test_evidence (WO-G.7)
        List<RunMetadata.ToolRun> toolRuns,
        Map<String, Long> stageTimingsMs,
        int hunterWave,
        int criticPasses,
        boolean criticSaysStop,
        Map<String, Integer> fixAttemptsByFingerprint,
        Optional<PriorRun> prior,
        List<String> notes,                             // human-readable run notes surfaced in the report
        Optional<String> incompleteReason) {

    /** Previous run inputs for incremental mode (feature M), loaded by setup from --prior-run. */
    public record PriorRun(String runId, List<LedgerUnit> ledger, List<Finding> findings, Map<String, String> fileHashes) { }
}
