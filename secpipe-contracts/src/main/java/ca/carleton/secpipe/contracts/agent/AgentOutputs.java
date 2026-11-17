package ca.carleton.secpipe.contracts.agent;

import ca.carleton.secpipe.contracts.finding.Finding;

import java.util.List;
import java.util.Optional;

/** Java mirrors of schemas/agent/*.schema.json. Each agent returns exactly one of these per invocation. */
public final class AgentOutputs {
    private AgentOutputs() { }

    public record Citation(String file, int line, String description, String quote) { }
    public record Step(Finding.TraceKind kind, String file, int line, String scope, String description, Optional<String> quote) { }

    public record HunterCandidate(String title, String cwe, String principal, String acceptedInput, String strongestControl,
                                  String claimedRootCause, List<Step> trace, List<Citation> evidence, String smallestFix,
                                  String regressionCase) { }
    public record HunterResult(String coverageId, Status status, List<String> reviewedPaths, List<String> localChecks,
                               List<HunterCandidate> candidates, List<String> unresolved, List<String> hardeningNotes) {
        public enum Status { COVERED, CANDIDATE, BLOCKED }
    }

    public record MissingUnit(String surfaceRef, String boundaryRef, String subsystemRef, String attackClassBlock,
                              Optional<String> cwe, List<String> startingPaths, String reason) { }
    public record Reassignment(String coverageId, String reason) { }
    public record CriticResult(List<MissingUnit> missingUnits, List<Reassignment> reassignIds, boolean stop, String rationale) { }

    public enum RefutationPattern { VALUE_OVERWRITTEN, CONSTANT_VALUE, DEAD_BRANCH, PARAMETERIZED, SANITIZED_OR_VALIDATED,
        NOT_REACHABLE, FRAMEWORK_CONTROL, WRONG_SINK, OTHER }
    public record ControlExamined(String file, int line, String quote, String description, boolean effective) { }
    public record ProposedSeverity(Finding.Score5 likelihood, Finding.Score5 impact, String likelihoodReason, String impactReason) { }
    public record DisproverResult(String fingerprint, Finding.Verdict verdict, boolean entrypointValid, boolean sinkValid,
                                  List<ControlExamined> controlsExamined, Optional<RefutationPattern> refutationPattern,
                                  Optional<String> rootCause, Optional<String> intendedBehavior, List<Step> trace,
                                  List<Citation> evidence, List<Finding.Condition> conditions,
                                  Optional<ProposedSeverity> proposedSeverity, List<String> blockers,
                                  Optional<Finding.ValidationPlan> validationPlan, Optional<String> reason) { }

    public record RecordVerifierResult(String fingerprint, Outcome outcome, int checkedCitations, boolean material,
                                       List<String> changedFields, Optional<Finding> replacement, String reason) {
        public enum Outcome { VERIFIED, REPLACE }
    }

    public record TestGenResult(String fingerprint, String packageName, String className, String source,
                                List<String> targetSymbols, List<String> payloads, String expectedFailure, String rationale) { }

    public record DependencyChange(String purl, String toVersion) { }
    public record RemediationResult(String fingerprint, String invariant, Citation decisionPoint, String strategy, String patch,
                                    List<String> filesChanged, Optional<DependencyChange> dependencyChange, List<String> riskNotes) { }
}
