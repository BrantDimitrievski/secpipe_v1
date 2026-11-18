package ca.carleton.secpipe.adapter.builtin;

import ca.carleton.secpipe.contracts.config.RunProfile;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;
import ca.carleton.secpipe.contracts.spi.EnvironmentInfo;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * SecPipe's own JavaParser taint rules (adapter id {@code builtin}), ported from the MVP. They need no external
 * tool and never execute target code. Covers CWE-22, 78, 89, 327 and 328.
 *
 * <p>Ported-from: capstone-mvp-1@2905635:src/main/java/ca/carleton/secpipe/tools/builtin/BuiltinRulesAdapter.java
 */
public class BuiltinRulesAdapter implements AnalyzerAdapter {

    public static final String ID = RuleContext.TOOL_ID;

    private static final Map<String, List<String>> RULES_BY_CWE = Map.of(
            "CWE-22", List.of("SP-JAVA-PATH"),
            "CWE-78", List.of("SP-JAVA-CMDI"),
            "CWE-89", List.of("SP-JAVA-SQLI"),
            "CWE-327", List.of("SP-JAVA-CRYPTO"),
            "CWE-328", List.of("SP-JAVA-HASH"));

    private final List<JavaRule> rules = List.of(
            new SqlInjectionRule(),
            new PathTraversalRule(),
            new CommandInjectionRule(),
            new WeakCryptoRule());

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "SecPipe built-in rules (JavaParser)";
    }

    @Override
    public Kind kind() {
        return Kind.SAST;
    }

    @Override
    public Set<String> supportedCwes() {
        return Set.copyOf(RULES_BY_CWE.keySet());
    }

    @Override
    public Availability availability(EnvironmentInfo env) {
        return Availability.yes("1");
    }

    @Override
    public boolean defaultEnabled(RunProfile profile) {
        return true;
    }

    @Override
    public String licenseNote() {
        return "Part of SecPipe; no external tool or licence needed.";
    }

    @Override
    public boolean requiresSandbox() {
        return false;
    }

    @Override
    public Result analyze(Request request) throws AnalyzerException {
        long started = System.nanoTime();
        Path root = request.projectRoot().toAbsolutePath().normalize();
        ParserConfiguration config = new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        JavaParser parser = new JavaParser(config);

        List<String> files = new ArrayList<>(request.profile().sourceFiles());
        if (!request.scopeFiles().isEmpty()) files.retainAll(request.scopeFiles());
        files = new ArrayList<>(new TreeSet<>(files));

        List<Hit> hits = new ArrayList<>();
        List<String> analyzed = new ArrayList<>();
        int unreadable = 0;
        for (String rel : files) {
            Path path = root.resolve(rel).normalize();
            if (!path.startsWith(root) || !rel.endsWith(".java")) continue;
            try {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                ParseResult<CompilationUnit> parsed = parser.parse(String.join("\n", lines));
                if (parsed.getResult().isEmpty()) {
                    unreadable++;
                    continue;
                }
                hits.addAll(analyzeFile(rel, parsed.getResult().get(), lines));
                analyzed.add(rel);
            } catch (IOException | RuntimeException e) {
                unreadable++;
            }
        }

        hits.sort(Comparator.comparing(Hit::file).thenComparingInt(Hit::line).thenComparing(Hit::ruleId));
        List<Candidate> candidates = hits.stream().map(BuiltinRulesAdapter::toCandidate).toList();

        List<CoverageClaim> coverage = new ArrayList<>();
        Map<String, List<String>> sorted = new TreeMap<>(RULES_BY_CWE);
        if (!analyzed.isEmpty()) {
            sorted.forEach((cwe, ruleIds) -> coverage.add(new CoverageClaim(cwe, List.copyOf(analyzed), ruleIds)));
        }
        long ms = (System.nanoTime() - started) / 1_000_000L;
        RunMetadata.ToolRun run = new RunMetadata.ToolRun(ID, Optional.of("1"), RunMetadata.ToolRun.Status.OK,
                unreadable == 0 ? Optional.empty() : Optional.of(unreadable + " file(s) could not be parsed"),
                ms, candidates.size());
        return new Result(candidates, List.copyOf(coverage), run);
    }

    /** Runs every rule over one parsed file; a rule that throws is skipped for that file. */
    public List<Hit> analyzeFile(String relativeFile, CompilationUnit cu, List<String> lines) {
        RuleContext rc = new RuleContext(relativeFile, cu, lines, RequestSurface.fileHandlesRequests(cu));
        for (JavaRule rule : rules) {
            try {
                rule.check(rc);
            } catch (RuntimeException ex) {
                // A rule bug must not abort the scan; the file is simply not covered by that rule.
            }
        }
        return rc.hits();
    }

    static Candidate toCandidate(Hit h) {
        String rootCause = h.message().contains(". ") ? h.message().substring(0, h.message().indexOf(". ") + 1) : h.message();
        return new Candidate(
                Optional.empty(),
                Finding.Category.CODE,
                Finding.Origin.TOOL,
                List.of(new Finding.ToolSource(ID, h.ruleId(), Optional.of(h.severity()), Optional.of(h.file()),
                        Optional.of(h.line()))),
                List.of(h.cwe()),
                List.of(),
                h.title(),
                h.message(),
                rootCause,
                h.trace(),
                List.of(new Finding.Evidence(h.file(), h.line(), h.snippet().isEmpty() ? h.title() : h.snippet())),
                Optional.of(h.sinkStatement()),
                Optional.of(h.sinkScope()),
                Optional.empty(),
                Optional.of(h.severity()),
                Optional.of(h.confidence()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }
}
