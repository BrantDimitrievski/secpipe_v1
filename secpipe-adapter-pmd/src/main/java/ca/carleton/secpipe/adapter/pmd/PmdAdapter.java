package ca.carleton.secpipe.adapter.pmd;

import ca.carleton.secpipe.contracts.config.RunProfile;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.ids.Fingerprints;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;
import ca.carleton.secpipe.contracts.spi.EnvironmentInfo;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PMDVersion;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * PMD 7 embedded as a library (adapter id {@code pmd}), restricted to the rules in {@code pmd/secpipe-security.xml}.
 * PMD only parses source text; it never compiles or runs target code. Results come from PMD's report API.
 *
 * <p>Ported-from: capstone-mvp-1@2905635:src/main/java/ca/carleton/secpipe/tools/PmdAdapter.java
 */
public class PmdAdapter implements AnalyzerAdapter {

    public static final String ID = "pmd";
    static final String RULESET = "pmd/secpipe-security.xml";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "PMD 7 (security rules)";
    }

    @Override
    public Kind kind() {
        return Kind.SAST;
    }

    @Override
    public Set<String> supportedCwes() {
        return PmdRules.supportedCwes();
    }

    @Override
    public Availability availability(EnvironmentInfo env) {
        return Availability.yes(PMDVersion.VERSION);
    }

    @Override
    public boolean defaultEnabled(RunProfile profile) {
        return true;
    }

    @Override
    public String licenseNote() {
        return "PMD is BSD-style licensed and embedded as a library; no separate install.";
    }

    @Override
    public boolean requiresSandbox() {
        return false;
    }

    @Override
    public Result analyze(Request request) throws AnalyzerException {
        long started = System.nanoTime();
        Path root = request.projectRoot().toAbsolutePath().normalize();
        TreeSet<String> files = new TreeSet<>();
        for (String rel : request.profile().sourceFiles()) {
            if (!rel.endsWith(".java")) continue;
            if (!request.scopeFiles().isEmpty() && !request.scopeFiles().contains(rel)) continue;
            if (root.resolve(rel).normalize().startsWith(root)) files.add(rel);
        }

        List<Candidate> candidates = new ArrayList<>();
        int processingErrors = 0;
        if (!files.isEmpty()) {
            PMDConfiguration config = new PMDConfiguration();
            config.addRuleSet(RULESET);
            config.setIgnoreIncrementalAnalysis(true);
            config.setThreads(1);
            try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
                for (String rel : files) pmd.files().addFile(root.resolve(rel).normalize());
                Report report = pmd.performAnalysisAndCollectReport();
                processingErrors = report.getProcessingErrors().size();
                List<Candidate> found = new ArrayList<>();
                for (RuleViolation v : report.getViolations()) {
                    Optional<PmdRules.Info> info = PmdRules.lookup(v.getRule().getName());
                    if (info.isEmpty()) continue; // unmapped rules are dropped, not emitted
                    Path abs = Path.of(v.getFileId().getAbsolutePath()).toAbsolutePath().normalize();
                    if (!abs.startsWith(root)) continue;
                    String rel = root.relativize(abs).toString().replace('\\', '/');
                    found.add(toCandidate(v, info.get(), rel, abs));
                }
                found.sort(Comparator.comparing((Candidate c) -> c.toolSources().get(0).file().orElse(""))
                        .thenComparingInt(c -> c.toolSources().get(0).line().orElse(0))
                        .thenComparing(c -> c.toolSources().get(0).ruleId()));
                candidates.addAll(found);
            } catch (RuntimeException e) {
                throw new AnalyzerException("PMD failed: " + e.getMessage(), e);
            }
        }

        List<CoverageClaim> coverage = new ArrayList<>();
        if (!files.isEmpty()) {
            PmdRules.rulesByCwe().forEach((cwe, rules) -> coverage.add(new CoverageClaim(cwe, List.copyOf(files), rules)));
        }
        long ms = (System.nanoTime() - started) / 1_000_000L;
        RunMetadata.ToolRun run = new RunMetadata.ToolRun(ID, Optional.of(PMDVersion.VERSION),
                RunMetadata.ToolRun.Status.OK,
                processingErrors == 0 ? Optional.empty() : Optional.of(processingErrors + " file(s) could not be analyzed"),
                ms, candidates.size());
        return new Result(List.copyOf(candidates), List.copyOf(coverage), run);
    }

    private static Candidate toCandidate(RuleViolation v, PmdRules.Info info, String rel, Path abs) {
        int line = Math.max(v.getBeginLine(), 1);
        String source = sourceLine(abs, line);
        String statement = Fingerprints.normalizeStatement(source.isEmpty() ? info.title() : source);
        String scope = scope(v, abs);
        String ruleId = "PMD:" + v.getRule().getName();
        String message = v.getDescription() == null || v.getDescription().isBlank() ? info.title() : v.getDescription();
        return new Candidate(
                Optional.empty(),
                Finding.Category.CODE,
                Finding.Origin.TOOL,
                List.of(new Finding.ToolSource(ID, ruleId, Optional.of(info.severity()), Optional.of(rel), Optional.of(line))),
                List.of(info.cwe()),
                List.of(),
                info.title(),
                message,
                info.title(),
                List.of(new Finding.TraceStep(Finding.TraceKind.SINK, rel, line, scope, statement)),
                List.of(new Finding.Evidence(rel, line, statement)),
                Optional.of(statement),
                Optional.of(scope),
                Optional.empty(),
                Optional.of(info.severity()),
                Optional.of(info.confidence()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    /** Enclosing method as {@code Class#method}, else {@code Class#<unknown>}. */
    private static String scope(RuleViolation v, Path abs) {
        String cls = v.getClassName();
        if (cls == null || cls.isBlank()) {
            String name = abs.getFileName().toString();
            cls = name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
        }
        String method = v.getMethodName();
        return cls + "#" + (method == null || method.isBlank() ? "<unknown>" : method);
    }

    private static String sourceLine(Path file, int oneBasedLine) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            return oneBasedLine <= lines.size() ? lines.get(oneBasedLine - 1).trim() : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
