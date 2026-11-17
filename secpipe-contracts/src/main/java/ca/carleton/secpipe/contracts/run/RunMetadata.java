package ca.carleton.secpipe.contracts.run;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.RunProfile;
import ca.carleton.secpipe.contracts.finding.Finding.VerifierRef.CapabilityTier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** run-metadata.json (schemas/run-metadata.schema.json). Built incrementally by the engine; frozen by finalize. */
public record RunMetadata(
        int schemaVersion,
        String runId,
        String secpipeVersion,
        Instant startedAt,
        Instant finishedAt,
        TerminalState terminalState,
        Optional<String> incompleteReason,
        RunProfile profile,
        Scope scope,
        Target target,
        Ai ai,
        BudgetSnapshot budget,
        List<ToolRun> tools,
        Map<String, Long> stageTimingsMs,
        Sandbox sandbox,
        Optional<PriorRun> priorRun,
        Counts counts,
        Validators validators) {

    public enum TerminalState { COMPLETE, INCOMPLETE }
    public enum ValidatorStatus { PASS, FAIL, NOT_RUN }

    public record Scope(ScopeMode mode, Optional<String> baseRef, Optional<String> headRef, List<String> changedFiles) {
        public enum ScopeMode { DIFF, FULL }
    }
    public record Target(String name, String buildTool, Optional<String> jdk, Optional<String> mavenVersion, long loc,
                         int sourceFiles, List<String> frameworks, Optional<String> gitCommit) { }
    public record Fallback(ProviderMode from, ProviderMode to, String reason) { }
    public record ModelAssignment(AgentRole role, ProviderMode provider, String model, CapabilityTier tier) { }
    public record Usage(AgentRole role, int calls, int retries, int discardedOutputs, long inputTokens, long outputTokens,
                        Optional<Double> estimatedCostUsd) { }
    public record Ai(ProviderMode requestedMode, ProviderMode effectiveMode, List<Fallback> fallbacks,
                     List<ModelAssignment> models, List<Usage> usage, int guardrailDowngrades, Optional<String> promptsHash) { }
    public record BudgetSnapshot(int maxAgentCalls, int used, Map<String, Integer> reserved, boolean exhausted,
                                 int maxSandboxRuns, int sandboxRunsUsed) { }
    public record ToolRun(String id, Optional<String> version, Status status, Optional<String> reason, long durationMs, int candidates) {
        public enum Status { OK, SKIPPED, FAILED, TIMEOUT }
    }
    public record Sandbox(boolean available, Optional<String> image, List<String> enforcedControls,
                          List<String> unenforceableControls, Optional<String> reason) { }
    public record PriorRun(Optional<String> runId, int carriedForward, int revalidated, int suppressedRejections,
                           Optional<String> incompatibleReason) { }
    public record Counts(int confirmed, int needsValidation, int rejected, Map<String, Integer> ledgerUnits,
                         Map<String, Integer> bySeverity) { }
    public record Validators(ValidatorStatus findings, ValidatorStatus ledger) { }
}
