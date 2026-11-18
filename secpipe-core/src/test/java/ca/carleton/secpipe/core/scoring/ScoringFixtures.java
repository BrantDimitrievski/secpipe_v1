package ca.carleton.secpipe.core.scoring;

import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.RunProfile;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.Score5;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Records and states for the scoring and finalize tests. */
public final class ScoringFixtures {

    private ScoringFixtures() {
    }

    /** A valid code fingerprint for a short test name; same-length names keep their sort order. */
    public static String fp(String name) {
        String hex = HexFormat.of().formatHex(name.getBytes(StandardCharsets.UTF_8));
        return "code:CWE-89:" + "0".repeat(24 - hex.length()) + hex;
    }

    public static SecPipeConfig config(Path target) {
        return new SecPipeConfig(target, Path.of("secpipe-out"), RunProfile.STANDARD,
                SecPipeConfig.ScopeMode.FULL, Optional.empty(), Optional.empty(), ProviderMode.NONE, Optional.empty(),
                Map.of(), Map.of(), Optional.empty(), Set.of(), Set.of(),
                SecPipeConfig.DEFAULT_MAX_AGENT_CALLS_STANDARD, SecPipeConfig.DEFAULT_MAX_FIX_ATTEMPTS,
                SecPipeConfig.DEFAULT_MAX_SANDBOX_RUNS, SecPipeConfig.FixMode.SUGGEST, true, false,
                Optional.of(Score5.HIGH), false, false, Set.of(SecPipeConfig.OutputFormat.JSON),
                Optional.empty(), false, List.of(), SecPipeConfig.DEFAULT_MAX_SNIPPET_LINES,
                SecPipeConfig.DEFAULT_MAX_FILES_PER_CALL, Set.of(), false, false, false,
                SecPipeConfig.GraphEngine.PLAIN, Duration.ofSeconds(120), SecPipeConfig.Verbosity.NORMAL);
    }

    public static PipelineState state(List<Finding> records, List<LedgerUnit> ledger, Optional<String> incomplete) {
        Map<String, Finding> byFp = new LinkedHashMap<>();
        records.forEach(f -> byFp.put(f.fingerprint(), f));
        return new PipelineState("run-20261130T120000Z-abc123", config(Path.of("no-such-target-dir")),
                Optional.empty(), Optional.empty(),
                List.of(), ledger, List.of(), byFp, Map.of(), List.of(), Map.of("detect", 40L), 0, 0, false,
                Map.of(), Optional.empty(), List.of(), incomplete);
    }

    public static Finding.SecPipeExt ext(Finding.Category category, List<String> cwe, Optional<Score5> hint,
                                         List<Finding.TestEvidence> evidence,
                                         Optional<Finding.DependencyRef> dependency) {
        return new Finding.SecPipeExt(category, Finding.Origin.TOOL, List.of(), cwe, List.of(), Optional.empty(),
                dependency, hint, Optional.empty(), evidence, List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty());
    }

    public static Finding.Confirmed confirmed(String fp, String cwe, Score5 likelihood, Score5 impact, Score5 overall) {
        return confirmed(fp, Finding.Category.CODE, cwe, likelihood, impact, overall, Optional.empty());
    }

    public static Finding.Confirmed confirmed(String fp, Finding.Category category, String cwe, Score5 likelihood,
                                              Score5 impact, Score5 overall,
                                              Optional<Finding.DependencyRef> dependency) {
        Finding.TestEvidence te = new Finding.TestEvidence("T1", Finding.TestEvidence.Phase.PRE_FIX,
                Optional.empty(), Optional.empty(), Finding.TestEvidence.Outcome.FAILED_AS_EXPECTED, 1,
                Optional.empty(), Optional.empty(), Optional.empty());
        List<Finding.TraceStep> trace = List.of(
                new Finding.TraceStep(Finding.TraceKind.ENTRYPOINT, "src/main/java/A.java", 3, "A#get()", "entry"),
                new Finding.TraceStep(Finding.TraceKind.SINK, "src/main/java/A.java", 9, "A#get()", "sink"));
        return new Finding.Confirmed(fp(fp), "title " + fp, "description", "root cause", "intended", trace, List.of(),
                List.of(), new Finding.Execution("attacker", List.of("p"), List.of("i"), "T1 failed as expected"),
                new Finding.Remediation("strategy", List.of()),
                new Finding.Severity(new Finding.Scored(likelihood, "l"), new Finding.Scored(impact, "i"), overall),
                new Finding.Confidence(Finding.Level3.HIGH, "c"),
                ext(category, List.of(cwe), Optional.empty(), List.of(te), dependency));
    }

    public static Finding.NeedsValidation needsValidation(String fp, String cwe, Optional<Score5> hint) {
        return new Finding.NeedsValidation(fp(fp), "title " + fp, "description", "claimed",
                List.of(new Finding.TraceStep(Finding.TraceKind.SINK, "src/main/java/A.java", 9, "A#get()", "sink")),
                List.of(), List.of("unverified"), new Finding.ValidationPlan(Optional.empty(), Optional.empty()),
                ext(Finding.Category.CODE, List.of(cwe), hint, List.of(), Optional.empty()));
    }

    public static Finding.Rejected rejected(String fp) {
        Finding.VerifierRef det = new Finding.VerifierRef(Finding.VerifierRef.Kind.DETERMINISTIC, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("rule"));
        Finding.SecPipeExt e = ext(Finding.Category.CODE, List.of("CWE-89"), Optional.empty(), List.of(),
                Optional.empty());
        Finding.SecPipeExt withVerification = new Finding.SecPipeExt(e.category(), e.origin(), e.toolSources(),
                e.cwe(), e.cve(), e.owasp(), e.dependency(), e.toolSeverityHint(), e.confidenceHint(),
                e.testEvidence(), e.fixAttempts(), e.remediationStatus(), e.recommendedRemediation(),
                Optional.of(new Finding.Verification(Optional.of(det), Optional.empty(), Optional.empty(), false)),
                e.ledgerUnits(), e.firstSeenRun(), e.carriedForwardFrom());
        return new Finding.Rejected(fp(fp), "title " + fp, "description", "claimed",
                List.of(new Finding.TraceStep(Finding.TraceKind.SINK, "src/main/java/A.java", 9, "A#get()", "sink")),
                List.of(), "refuted", withVerification);
    }
}
