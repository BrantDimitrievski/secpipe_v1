package ca.carleton.secpipe.agents.disprover;

import ca.carleton.secpipe.contracts.finding.Finding;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Exactly what one disprover sees (PLAN Part 5 §A7): the candidate's claim without tool names, confidence or any
 * other verifier's output; source slices around every cited line and the enclosing method of each trace step; the
 * control catalogue for the CWE family; and two recon facts about the trace ends.
 *
 * @param controlCatalogue  trusted SecPipe text (from {@code disprover/controls/}), never repository text
 * @param firstStepIsEntry  recon lists the first trace step as an entry surface (empty: no recon facts)
 * @param lastStepIsSink    recon lists the last trace step as a known sink (empty: no recon facts)
 * @param truncated         the slices were cut to stay within {@link DisproverContextBuilder#MAX_CONTEXT_CHARS}
 */
public record DisproverInput(String fingerprint, String title, List<String> cwe, String claimedRootCause,
                             List<Finding.TraceStep> trace, List<Finding.Evidence> evidence, List<SourceSlice> slices,
                             String controlCatalogue, Optional<Boolean> firstStepIsEntry,
                             Optional<Boolean> lastStepIsSink, boolean truncated) {

    public DisproverInput {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(title, "title");
        cwe = List.copyOf(cwe);
        Objects.requireNonNull(claimedRootCause, "claimedRootCause");
        trace = List.copyOf(trace);
        evidence = List.copyOf(evidence);
        slices = List.copyOf(slices);
        Objects.requireNonNull(controlCatalogue, "controlCatalogue");
        Objects.requireNonNull(firstStepIsEntry, "firstStepIsEntry");
        Objects.requireNonNull(lastStepIsSink, "lastStepIsSink");
    }
}
