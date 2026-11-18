package ca.carleton.secpipe.core.engine;

import ca.carleton.secpipe.contracts.agent.AgentOutputs;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.RunProfile;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.ProjectProfile;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.EnvironmentInfo;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Test data for the engine tests. Collections are deliberately mutable ({@code ArrayList}, {@code HashMap}) so tests
 * can prove the engine copies and freezes them.
 */
final class EngineFixtures {

    private EngineFixtures() { }

    static SecPipeConfig config() {
        return config(Set.of());
    }

    static SecPipeConfig config(Set<String> disabledNodes) {
        return new SecPipeConfig(Path.of("target-project"), Path.of("secpipe-out"), RunProfile.STANDARD,
                SecPipeConfig.ScopeMode.FULL, Optional.empty(), Optional.empty(), ProviderMode.NONE, Optional.empty(),
                new HashMap<>(), new HashMap<>(), Optional.empty(), new HashSet<>(Set.of("semgrep")), new HashSet<>(),
                SecPipeConfig.DEFAULT_MAX_AGENT_CALLS_STANDARD, SecPipeConfig.DEFAULT_MAX_FIX_ATTEMPTS,
                SecPipeConfig.DEFAULT_MAX_SANDBOX_RUNS, SecPipeConfig.FixMode.SUGGEST, true, false,
                Optional.of(Finding.Score5.HIGH), false, false, new HashSet<>(Set.of(SecPipeConfig.OutputFormat.JSON)),
                Optional.empty(), false, new ArrayList<>(), SecPipeConfig.DEFAULT_MAX_SNIPPET_LINES,
                SecPipeConfig.DEFAULT_MAX_FILES_PER_CALL, new HashSet<>(disabledNodes), false, false, false,
                SecPipeConfig.GraphEngine.PLAIN, Duration.ofSeconds(120), SecPipeConfig.Verbosity.NORMAL);
    }

    /** A fresh, unfrozen state with mutable collections. */
    static PipelineState emptyState() {
        return new PipelineState("run-1", config(), Optional.empty(), Optional.empty(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new HashMap<>(), new HashMap<>(), new ArrayList<>(),
                new HashMap<>(), 0, 0, false, new HashMap<>(), Optional.empty(), new ArrayList<>(), Optional.empty());
    }

    static EnvironmentInfo environment() {
        return new EnvironmentInfo("linux", "amd64", "21", Optional.empty(), Optional.of("3.9.9"), true, false,
                Optional.empty(), false, new HashMap<>(Map.of("semgrep", Path.of("/usr/bin/semgrep"))));
    }

    static ProjectProfile profile() {
        return new ProjectProfile(Path.of("target-project"), "java", ProjectProfile.BuildTool.MAVEN_WRAPPER,
                new HashSet<>(Set.of("spring")), new ArrayList<>(List.of("src/main/java/A.java")), new ArrayList<>(),
                new ArrayList<>(List.of("pom.xml")), 120);
    }

    static LedgerUnit unit(String coverageId, LedgerUnit.Status status) {
        return new LedgerUnit(coverageId,
                new LedgerUnit.CanonicalRefs("http", "auth", "core", "injection", Optional.empty()),
                "http", "auth", "core", "injection", Optional.of("CWE-89"),
                new ArrayList<>(List.of("src/main/java/A.java")), "injection", new ArrayList<>(), new ArrayList<>(),
                LedgerUnit.PriorStatus.NEW, new ArrayList<>(), 1, status, Optional.empty(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    static LedgerUnit.Update update(String coverageId, LedgerUnit.Status newStatus, Optional<String> agentId,
                                    List<String> reviewedPaths, Optional<String> archiveReason) {
        return new LedgerUnit.Update(coverageId, newStatus, agentId, new ArrayList<>(reviewedPaths),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), archiveReason);
    }

    static Finding.TraceStep sink() {
        return new Finding.TraceStep(Finding.TraceKind.SINK, "src/main/java/A.java", 10, "A#run()", "sink");
    }

    static Candidate candidate(String title) {
        return new Candidate(Optional.empty(), Finding.Category.CODE, Finding.Origin.TOOL,
                new ArrayList<>(List.of(new Finding.ToolSource("semgrep", "rule", Optional.empty(), Optional.empty(),
                        Optional.empty()))),
                new ArrayList<>(List.of("CWE-89")), new ArrayList<>(), title, "message", "root cause",
                new ArrayList<>(List.of(sink())), new ArrayList<>(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), new ArrayList<>());
    }

    static Finding.SecPipeExt ext() {
        return new Finding.SecPipeExt(Finding.Category.CODE, Finding.Origin.TOOL, new ArrayList<>(),
                new ArrayList<>(List.of("CWE-89")), new ArrayList<>(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), new ArrayList<>(), new ArrayList<>(), Optional.empty(),
                Optional.empty(), Optional.empty(), new ArrayList<>(), Optional.empty(), Optional.empty());
    }

    static Finding rejected(String fingerprint, String reason) {
        return new Finding.Rejected(fingerprint, "title " + fingerprint, "description", "claimed root cause",
                new ArrayList<>(List.of(sink())), new ArrayList<>(), reason, ext());
    }

    static AgentOutputs.DisproverResult draft(String fingerprint) {
        return new AgentOutputs.DisproverResult(fingerprint, Finding.Verdict.NEEDS_VALIDATION, true, true,
                new ArrayList<>(), Optional.empty(), Optional.empty(), Optional.empty(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), Optional.empty(),
                new ArrayList<>(List.of("awaiting regression test evidence")), Optional.empty(), Optional.empty());
    }

    static RunMetadata.ToolRun toolRun(String id) {
        return new RunMetadata.ToolRun(id, Optional.of("1.0"), RunMetadata.ToolRun.Status.OK, Optional.empty(), 5, 1);
    }

    static Delta delta() {
        return new Delta();
    }

    /** A mutable builder over the 18-field {@link StateDelta}; unset fields are empty. */
    static final class Delta {
        private Optional<EnvironmentInfo> environment = Optional.empty();
        private Optional<ProjectProfile> profile = Optional.empty();
        private Optional<List<String>> diffScope = Optional.empty();
        private List<LedgerUnit> newLedgerUnits = new ArrayList<>();
        private List<LedgerUnit.Update> ledgerUpdates = new ArrayList<>();
        private Optional<List<Candidate>> replaceCandidates = Optional.empty();
        private List<Candidate> addCandidates = new ArrayList<>();
        private List<Finding> putRecords = new ArrayList<>();
        private Map<String, AgentOutputs.DisproverResult> putSurvivorDrafts = new LinkedHashMap<>();
        private List<RunMetadata.ToolRun> toolRuns = new ArrayList<>();
        private Map<String, Long> timingsMs = new LinkedHashMap<>();
        private Optional<Integer> hunterWave = Optional.empty();
        private Optional<Integer> criticPasses = Optional.empty();
        private Optional<Boolean> criticSaysStop = Optional.empty();
        private Map<String, Integer> fixAttempts = new LinkedHashMap<>();
        private Optional<PipelineState.PriorRun> prior = Optional.empty();
        private List<String> notes = new ArrayList<>();
        private Optional<String> incompleteReason = Optional.empty();

        Delta environment(EnvironmentInfo e) { environment = Optional.of(e); return this; }
        Delta profile(ProjectProfile p) { profile = Optional.of(p); return this; }
        Delta diffScope(List<String> s) { diffScope = Optional.of(s); return this; }
        Delta newUnits(LedgerUnit... u) { newLedgerUnits.addAll(List.of(u)); return this; }
        Delta updates(LedgerUnit.Update... u) { ledgerUpdates.addAll(List.of(u)); return this; }
        Delta replaceCandidates(List<Candidate> c) { replaceCandidates = Optional.of(c); return this; }
        Delta addCandidates(Candidate... c) { addCandidates.addAll(List.of(c)); return this; }
        Delta putRecords(Finding... f) { putRecords.addAll(List.of(f)); return this; }
        Delta putDraft(String fp, AgentOutputs.DisproverResult d) { putSurvivorDrafts.put(fp, d); return this; }
        Delta toolRuns(RunMetadata.ToolRun... t) { toolRuns.addAll(List.of(t)); return this; }
        Delta timing(String stage, long ms) { timingsMs.put(stage, ms); return this; }
        Delta hunterWave(int w) { hunterWave = Optional.of(w); return this; }
        Delta criticPasses(int p) { criticPasses = Optional.of(p); return this; }
        Delta criticSaysStop(boolean s) { criticSaysStop = Optional.of(s); return this; }
        Delta fixAttempts(String fp, int n) { fixAttempts.put(fp, n); return this; }
        Delta prior(PipelineState.PriorRun p) { prior = Optional.of(p); return this; }
        Delta notes(String... n) { notes.addAll(List.of(n)); return this; }
        Delta incomplete(String r) { incompleteReason = Optional.of(r); return this; }

        /** The live (mutable) list passed into the built delta, to test defensive copying. */
        List<Candidate> liveAddCandidates() { return addCandidates; }
        List<String> liveNotes() { return notes; }

        StateDelta build() {
            return new StateDelta(environment, profile, diffScope, newLedgerUnits, ledgerUpdates, replaceCandidates,
                    addCandidates, putRecords, putSurvivorDrafts, toolRuns, timingsMs, hunterWave, criticPasses,
                    criticSaysStop, fixAttempts, prior, notes, incompleteReason);
        }
    }
}
