package ca.carleton.secpipe.contracts.finding;

import java.util.List;
import java.util.Optional;

/**
 * A pre-verdict claim produced by an analyzer adapter or a hunter. Candidates become Findings only through
 * the normalize → refute → disprove → test_evidence nodes. fingerprint is computed by
 * ca.carleton.secpipe.contracts.ids.Fingerprints (ADR-06) during normalization; adapters leave it empty.
 */
public record Candidate(
        Optional<String> fingerprint,
        Finding.Category category,
        Finding.Origin origin,
        List<Finding.ToolSource> toolSources,
        List<String> cwe,
        List<String> cve,
        String title,
        String message,
        String claimedRootCause,
        List<Finding.TraceStep> trace,          // last step SHOULD be kind=SINK; adapters with only a location emit one SINK step
        List<Finding.Evidence> evidence,
        Optional<String> sinkStatement,         // normalized source text of the sink call (fingerprint input)
        Optional<String> sinkScope,             // Class#method(ParamTypes) of the sink (fingerprint input)
        Optional<Finding.DependencyRef> dependency,
        Optional<Finding.Score5> toolSeverityHint,
        Optional<Finding.Level3> confidenceHint,
        Optional<String> strongestControl,      // hunters only
        Optional<String> smallestFix,           // hunters only
        Optional<String> regressionCase,        // hunters only
        List<String> ledgerUnits) {
}
