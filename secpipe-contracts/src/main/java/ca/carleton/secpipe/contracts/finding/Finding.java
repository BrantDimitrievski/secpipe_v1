package ca.carleton.secpipe.contracts.finding;

import java.util.List;
import java.util.Optional;

/**
 * One record of findings.json (schemas/findings.schema.json). Exactly three verdicts (ADR-05).
 * Records are immutable; the orchestrator replaces whole records, never mutates them.
 * JSON (de)serialization lives in ca.carleton.secpipe.contracts.json.ContractJson (WO-C.1).
 */
public sealed interface Finding permits Finding.Confirmed, Finding.NeedsValidation, Finding.Rejected {

    Verdict verdict();
    String fingerprint();
    String title();
    String description();
    List<TraceStep> trace();
    List<Evidence> evidence();
    SecPipeExt secpipe();

    enum Verdict { CONFIRMED, NEEDS_VALIDATION, REJECTED }               // JSON: confirmed | needs_validation | rejected
    enum Score5 { INFORMATIONAL, LOW, MEDIUM, HIGH, CRITICAL }           // JSON: lower-case
    enum Level3 { LOW, MEDIUM, HIGH }
    enum TraceKind { ENTRYPOINT, PROPAGATION, SINK }
    enum ConditionKind { AUTHENTICATION_LEVEL, AUTHORIZATION_ROLE, USER_INTERACTION, SYSTEM_CONFIGURATION,
        NETWORK_ROUTING, ENVIRONMENTAL_DEPENDENCY, DATA_STATE, TIMING_DEPENDENCY, THIRD_PARTY_DEPENDENCY }
    enum Category { CODE, DEPENDENCY }
    enum Origin { TOOL, HUNTER, CARRIED_FORWARD }
    enum RemediationStatus { FIXED, STILL_PRESENT, NEWLY_INTRODUCED, NOT_ATTEMPTED }

    record Confirmed(String fingerprint, String title, String description, String rootCause, String intendedBehavior,
                     List<TraceStep> trace, List<Evidence> evidence, List<Condition> conditions, Execution execution,
                     Remediation remediation, Severity severity, Confidence confidence, SecPipeExt secpipe) implements Finding {
        public Verdict verdict() { return Verdict.CONFIRMED; }
    }

    record NeedsValidation(String fingerprint, String title, String description, String claimedRootCause,
                           List<TraceStep> trace, List<Evidence> evidence, List<String> blockers,
                           ValidationPlan validationPlan, SecPipeExt secpipe) implements Finding {
        public Verdict verdict() { return Verdict.NEEDS_VALIDATION; }
    }

    record Rejected(String fingerprint, String title, String description, String claimedRootCause,
                    List<TraceStep> trace, List<Evidence> evidence, String reason, SecPipeExt secpipe) implements Finding {
        public Verdict verdict() { return Verdict.REJECTED; }
    }

    record TraceStep(TraceKind kind, String file, int line, String scope, String description) { }
    record Evidence(String file, int line, String description) { }
    record Condition(ConditionKind kind, String description) { }
    record Execution(String attackerPerspective, List<String> payloads, List<String> instructions, String observedResult) { }
    record CodeChange(String fileName, String fixedCode) { }
    record Remediation(String strategy, List<CodeChange> codeChanges) { }
    record Scored(Score5 score, String reason) { }
    record Severity(Scored likelihood, Scored impact, Score5 overallSeverity) { }
    record Confidence(Level3 score, String reason) { }
    record ValidationPlan(Optional<String> local, Optional<String> deployment) { }

    record ToolSource(String tool, String ruleId, Optional<Score5> toolSeverity, Optional<String> file, Optional<Integer> line) { }
    record DependencyRef(String purl, String group, String artifact, String version, Optional<String> fixedVersion,
                         Optional<String> scope, List<String> dependencyPath, Reachability reachable) {
        public enum Reachability { YES, NO, UNKNOWN }
    }
    record TestEvidence(String id, Phase phase, Optional<String> testFile, Optional<String> testClass, Outcome outcome,
                        int runs, Optional<String> observedResult, Optional<String> blocker, Optional<String> logArtifact) {
        public enum Phase { PRE_FIX, POST_FIX, ADEQUACY }
        public enum Outcome { FAILED_AS_EXPECTED, PASSED, COMPILE_ERROR, ERROR, FLAKY, TIMEOUT, NOT_RUN }
    }
    record Adequacy(int changedLinesTotal, int changedLinesCovered, boolean adequate, Optional<String> note) { }
    record FixAttempt(int attempt, Optional<String> patchArtifact, Optional<String> strategy, Outcome outcome,
                      Optional<Boolean> regressionTestPassed, Optional<Boolean> suitePassed, Optional<Boolean> rescanClean,
                      List<String> newFingerprints, Optional<Adequacy> adequacy) {
        public enum Outcome { FIXED, STILL_PRESENT, NEWLY_INTRODUCED_FINDINGS, BUILD_BROKEN, TESTS_BROKEN, REFUSED, SANDBOX_UNAVAILABLE }
    }
    record VerifierRef(Kind kind, Optional<String> agentId, Optional<String> provider, Optional<String> model,
                       Optional<CapabilityTier> tier, Optional<String> rule) {
        public enum Kind { DETERMINISTIC, LLM }
        public enum CapabilityTier { STRONG, STANDARD, SMALL }
    }
    record Verification(Optional<VerifierRef> disprover, Optional<VerifierRef> recordVerifier,
                        Optional<VerifierRef> secondVerifier, boolean guardrailApplied) { }

    /** The required "secpipe" extension object. Empty lists are serialized as omitted where the schema allows. */
    record SecPipeExt(Category category, Origin origin, List<ToolSource> toolSources, List<String> cwe, List<String> cve,
                      Optional<String> owasp, Optional<DependencyRef> dependency, Optional<Score5> toolSeverityHint,
                      Optional<Level3> confidenceHint, List<TestEvidence> testEvidence, List<FixAttempt> fixAttempts,
                      Optional<RemediationStatus> remediationStatus, Optional<String> recommendedRemediation,
                      Optional<Verification> verification, List<String> ledgerUnits, Optional<String> firstSeenRun,
                      Optional<String> carriedForwardFrom) { }
}
