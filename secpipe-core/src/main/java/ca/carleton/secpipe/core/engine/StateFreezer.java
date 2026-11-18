package ca.carleton.secpipe.core.engine;

import ca.carleton.secpipe.contracts.agent.AgentOutputs;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.ProjectProfile;
import ca.carleton.secpipe.contracts.spi.EnvironmentInfo;
import ca.carleton.secpipe.core.ledger.LedgerStore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * Deep, explicit copies of contract records with every collection made unmodifiable, so a node holding a
 * {@link PipelineState} cannot change it (feature A). Written out per record type (no reflection) so that a contract
 * change that adds a collection field fails compilation here instead of silently leaking a mutable list.
 *
 * <p>Lists keep their order ({@code List.copyOf}); sets and maps keep their iteration order in a fresh
 * {@code LinkedHashSet}/{@code LinkedHashMap} behind an unmodifiable view. Null collection elements are rejected with
 * {@code NullPointerException}. The state's top-level maps are additionally sorted by key.
 */
final class StateFreezer {

    private StateFreezer() { }

    static PipelineState state(PipelineState s) {
        return new PipelineState(
                s.runId(),
                s.config() == null ? null : config(s.config()),
                s.environment().map(StateFreezer::environment),
                s.profile().map(StateFreezer::profile),
                List.copyOf(s.diffScope()),
                LedgerStore.sorted(list(s.ledger(), StateFreezer::ledgerUnit)),
                list(s.candidates(), StateFreezer::candidate),
                sortedMap(s.records(), StateFreezer::finding),
                sortedMap(s.survivorDrafts(), StateFreezer::disproverResult),
                List.copyOf(s.toolRuns()),
                sortedMap(s.stageTimingsMs(), UnaryOperator.identity()),
                s.hunterWave(),
                s.criticPasses(),
                s.criticSaysStop(),
                sortedMap(s.fixAttemptsByFingerprint(), UnaryOperator.identity()),
                s.prior().map(StateFreezer::priorRun),
                List.copyOf(s.notes()),
                s.incompleteReason());
    }

    static SecPipeConfig config(SecPipeConfig c) {
        return new SecPipeConfig(c.target(), c.outDir(), c.profile(), c.scope(), c.baseRef(), c.headRef(),
                c.llmProvider(), c.defaultModel(), map(c.modelForRole()), map(c.tierOverrides()), c.llmBaseUrl(),
                set(c.tools()), set(c.excludeTools()), c.maxAgentCalls(), c.maxFixAttempts(), c.maxSandboxRuns(),
                c.enableFixes(), c.sandboxEnabled(), c.offline(), c.failOnSeverity(), c.failOnNeedsValidation(),
                c.failOnIncomplete(), set(c.formats()), c.priorRunDir(), c.printPrompts(), List.copyOf(c.denyPaths()),
                c.maxSnippetLines(), c.maxFilesPerCall(), set(c.disabledNodes()), c.includeTests(),
                c.allowBuildExtensions(), c.acceptCodeqlTerms(), c.engine(), c.toolTimeout(), c.verbosity());
    }

    static EnvironmentInfo environment(EnvironmentInfo e) {
        return new EnvironmentInfo(e.os(), e.arch(), e.javaVersion(), e.javaHome(), e.mavenVersion(), e.mavenWrapper(),
                e.dockerAvailable(), e.dockerVersion(), e.githubActions(), map(e.toolsOnPath()));
    }

    static ProjectProfile profile(ProjectProfile p) {
        return new ProjectProfile(p.root(), p.language(), p.buildTool(), set(p.frameworks()),
                List.copyOf(p.sourceFiles()), List.copyOf(p.testSourceFiles()), List.copyOf(p.manifests()),
                p.linesOfCode());
    }

    static PipelineState.PriorRun priorRun(PipelineState.PriorRun p) {
        return new PipelineState.PriorRun(p.runId(), LedgerStore.sorted(list(p.ledger(), StateFreezer::ledgerUnit)),
                list(p.findings(), StateFreezer::finding), map(p.fileHashes()));
    }

    static LedgerUnit ledgerUnit(LedgerUnit u) {
        return LedgerStore.immutableCopy(u);
    }

    static Candidate candidate(Candidate c) {
        return new Candidate(c.fingerprint(), c.category(), c.origin(), List.copyOf(c.toolSources()),
                List.copyOf(c.cwe()), List.copyOf(c.cve()), c.title(), c.message(), c.claimedRootCause(),
                List.copyOf(c.trace()), List.copyOf(c.evidence()), c.sinkStatement(), c.sinkScope(),
                c.dependency().map(StateFreezer::dependency), c.toolSeverityHint(), c.confidenceHint(),
                c.strongestControl(), c.smallestFix(), c.regressionCase(), List.copyOf(c.ledgerUnits()));
    }

    static Finding finding(Finding f) {
        return switch (f) {
            case Finding.Confirmed c -> new Finding.Confirmed(c.fingerprint(), c.title(), c.description(),
                    c.rootCause(), c.intendedBehavior(), List.copyOf(c.trace()), List.copyOf(c.evidence()),
                    List.copyOf(c.conditions()), c.execution() == null ? null : execution(c.execution()),
                    c.remediation() == null ? null : remediation(c.remediation()), c.severity(), c.confidence(),
                    ext(c.secpipe()));
            case Finding.NeedsValidation n -> new Finding.NeedsValidation(n.fingerprint(), n.title(), n.description(),
                    n.claimedRootCause(), List.copyOf(n.trace()), List.copyOf(n.evidence()), List.copyOf(n.blockers()),
                    n.validationPlan(), ext(n.secpipe()));
            case Finding.Rejected r -> new Finding.Rejected(r.fingerprint(), r.title(), r.description(),
                    r.claimedRootCause(), List.copyOf(r.trace()), List.copyOf(r.evidence()), r.reason(),
                    ext(r.secpipe()));
        };
    }

    static AgentOutputs.DisproverResult disproverResult(AgentOutputs.DisproverResult d) {
        return new AgentOutputs.DisproverResult(d.fingerprint(), d.verdict(), d.entrypointValid(), d.sinkValid(),
                List.copyOf(d.controlsExamined()), d.refutationPattern(), d.rootCause(), d.intendedBehavior(),
                List.copyOf(d.trace()), List.copyOf(d.evidence()), List.copyOf(d.conditions()), d.proposedSeverity(),
                List.copyOf(d.blockers()), d.validationPlan(), d.reason());
    }

    private static Finding.Execution execution(Finding.Execution e) {
        return new Finding.Execution(e.attackerPerspective(), List.copyOf(e.payloads()), List.copyOf(e.instructions()),
                e.observedResult());
    }

    private static Finding.Remediation remediation(Finding.Remediation r) {
        return new Finding.Remediation(r.strategy(), List.copyOf(r.codeChanges()));
    }

    private static Finding.DependencyRef dependency(Finding.DependencyRef d) {
        return new Finding.DependencyRef(d.purl(), d.group(), d.artifact(), d.version(), d.fixedVersion(), d.scope(),
                List.copyOf(d.dependencyPath()), d.reachable());
    }

    private static Finding.FixAttempt fixAttempt(Finding.FixAttempt a) {
        return new Finding.FixAttempt(a.attempt(), a.patchArtifact(), a.strategy(), a.outcome(),
                a.regressionTestPassed(), a.suitePassed(), a.rescanClean(), List.copyOf(a.newFingerprints()),
                a.adequacy());
    }

    private static Finding.SecPipeExt ext(Finding.SecPipeExt x) {
        if (x == null) {
            return null;
        }
        return new Finding.SecPipeExt(x.category(), x.origin(), List.copyOf(x.toolSources()), List.copyOf(x.cwe()),
                List.copyOf(x.cve()), x.owasp(), x.dependency().map(StateFreezer::dependency), x.toolSeverityHint(),
                x.confidenceHint(), List.copyOf(x.testEvidence()), list(x.fixAttempts(), StateFreezer::fixAttempt),
                x.remediationStatus(), x.recommendedRemediation(), x.verification(), List.copyOf(x.ledgerUnits()),
                x.firstSeenRun(), x.carriedForwardFrom());
    }

    static <T> List<T> list(List<T> in, UnaryOperator<T> freeze) {
        return in.stream().map(freeze).toList();
    }

    private static <T> Set<T> set(Set<T> in) {
        for (T t : in) {
            Objects.requireNonNull(t, "set element");
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(in));
    }

    private static <K, V> Map<K, V> map(Map<K, V> in) {
        Map<K, V> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(Objects.requireNonNull(k, "map key"),
                Objects.requireNonNull(v, "map value")));
        return Collections.unmodifiableMap(out);
    }

    /** A copy sorted by key, values frozen, as an unmodifiable view over a fresh {@code LinkedHashMap}. */
    static <V> Map<String, V> sortedMap(Map<String, V> in, UnaryOperator<V> freeze) {
        Map<String, V> sorted = new TreeMap<>();
        in.forEach((k, v) -> sorted.put(Objects.requireNonNull(k, "map key"),
                freeze.apply(Objects.requireNonNull(v, "map value"))));
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
}
