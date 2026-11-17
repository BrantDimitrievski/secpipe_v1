package ca.carleton.secpipe.contracts.config;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.finding.Finding;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fully resolved run configuration. Precedence (WO-C.4): CLI flag > environment > secpipe.yml > defaults.
 * The API key is NOT a field: it is read from the environment by the provider factory at call time
 * (SECPIPE_API_KEY, or ANTHROPIC_API_KEY / OPENAI_API_KEY / COPILOT_GITHUB_TOKEN / GITHUB_TOKEN) and never stored (ADR-10).
 */
public record SecPipeConfig(
        Path target,
        Path outDir,
        RunProfile profile,
        ScopeMode scope,
        Optional<String> baseRef,
        Optional<String> headRef,
        ProviderMode llmProvider,
        Optional<String> defaultModel,
        Map<AgentRole, String> modelForRole,
        Map<String, Finding.VerifierRef.CapabilityTier> tierOverrides,   // model id -> tier
        Optional<String> llmBaseUrl,
        Set<String> tools,                 // empty = defaults for profile
        Set<String> excludeTools,
        int maxAgentCalls,
        int maxFixAttempts,
        int maxSandboxRuns,
        FixMode enableFixes,
        boolean sandboxEnabled,
        boolean offline,
        Optional<Finding.Score5> failOnSeverity,  // empty = never fail on severity
        boolean failOnNeedsValidation,
        boolean failOnIncomplete,
        Set<OutputFormat> formats,
        Optional<Path> priorRunDir,
        boolean printPrompts,
        List<String> denyPaths,            // globs, in addition to built-in denylist
        int maxSnippetLines,
        int maxFilesPerCall,
        Set<String> disabledNodes,         // ablation / eval only (e.g. "disprove")
        boolean includeTests,
        boolean allowBuildExtensions,
        boolean acceptCodeqlTerms,
        GraphEngine engine,
        Duration toolTimeout,
        Verbosity verbosity) {

    public enum ScopeMode { DIFF, FULL }
    public enum FixMode { FALSE, SUGGEST, PR }        // CLI/Action: false | suggest | pr  (true == suggest)
    public enum OutputFormat { JSON, SARIF, HTML, MARKDOWN, METRICS }
    public enum GraphEngine { PLAIN, LANGGRAPH4J }
    public enum Verbosity { QUIET, NORMAL, VERBOSE }

    /** Defaults used when nothing else is set (documented in the CLI contract, Part 4). */
    public static final int DEFAULT_MAX_AGENT_CALLS_QUICK = 40;
    public static final int DEFAULT_MAX_AGENT_CALLS_STANDARD = 200;
    public static final int DEFAULT_MAX_AGENT_CALLS_DEEP = 800;
    public static final int DEFAULT_MAX_FIX_ATTEMPTS = 3;
    public static final int DEFAULT_MAX_SANDBOX_RUNS = 25;
    public static final int DEFAULT_MAX_SNIPPET_LINES = 80;
    public static final int DEFAULT_MAX_FILES_PER_CALL = 6;
}
