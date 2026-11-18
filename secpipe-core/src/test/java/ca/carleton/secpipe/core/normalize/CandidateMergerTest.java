package ca.carleton.secpipe.core.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.ids.Fingerprints;
import ca.carleton.secpipe.contracts.json.FindingCodec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CandidateMergerTest {

    private static final String FILE = "src/main/java/app/UserDao.java";
    private static final String SCOPE = "app.UserDao#find(String)";
    private static final String SINK = "stmt.executeQuery(sql)";

    private final CandidateMerger merger = new CandidateMerger();

    static Finding.TraceStep step(Finding.TraceKind kind, String file, int line) {
        return new Finding.TraceStep(kind, file, line, SCOPE, kind.name().toLowerCase());
    }

    static Candidate code(String tool, String rule, String cwe, String scope, String sink, int line,
                          List<Finding.TraceStep> trace) {
        return new Candidate(Optional.empty(), Finding.Category.CODE, Finding.Origin.TOOL,
                List.of(new Finding.ToolSource(tool, rule, Optional.empty(), Optional.of(FILE), Optional.of(line))),
                List.of(cwe), List.of(), "SQL injection", "msg from " + tool, "concatenated SQL", trace, List.of(),
                Optional.of(sink), Optional.of(scope), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), List.of("unit-" + tool));
    }

    static Candidate code(String tool, String rule, int line) {
        return code(tool, rule, "CWE-89", SCOPE, SINK, line, List.of(step(Finding.TraceKind.SINK, FILE, line)));
    }

    static Candidate dependency(String tool, String rule, List<String> cves) {
        Finding.DependencyRef dep = new Finding.DependencyRef("pkg:maven/org.x/lib@1.0", "org.x", "lib", "1.0",
                Optional.empty(), Optional.empty(), List.of(), Finding.DependencyRef.Reachability.UNKNOWN);
        return new Candidate(Optional.empty(), Finding.Category.DEPENDENCY, Finding.Origin.TOOL,
                List.of(new Finding.ToolSource(tool, rule, Optional.empty(), Optional.empty(), Optional.empty())),
                List.of(), cves, "vulnerable lib", "msg", "old version", List.of(), List.of(), Optional.empty(),
                Optional.empty(), Optional.of(dep), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of());
    }

    @Test
    void sameFingerprintFromTwoToolsMergesIntoOne() {
        List<Finding.TraceStep> longTrace = List.of(step(Finding.TraceKind.ENTRYPOINT, FILE, 10),
                step(Finding.TraceKind.PROPAGATION, FILE, 20), step(Finding.TraceKind.SINK, FILE, 42));
        Candidate semgrep = code("semgrep", "java.sqli", "CWE-89", SCOPE, SINK, 42, longTrace);
        Candidate builtin = code("builtin", "SQLI-001", 42);

        List<Candidate> out = merger.merge(List.of(builtin, semgrep));

        assertThat(out).hasSize(1);
        Candidate merged = out.get(0);
        assertThat(merged.toolSources()).extracting(Finding.ToolSource::tool).containsExactly("builtin", "semgrep");
        assertThat(merged.toolSources()).extracting(Finding.ToolSource::ruleId)
                .containsExactly("SQLI-001", "java.sqli");
        assertThat(merged.trace()).isEqualTo(longTrace);
        assertThat(merged.ledgerUnits()).containsExactly("unit-builtin", "unit-semgrep");
        assertThat(merged.fingerprint()).contains(Fingerprints.code("CWE-89", FILE, SCOPE, SINK, 0));
    }

    @Test
    void differentSinksStayTwo() {
        Candidate a = code("builtin", "SQLI-001", 42);
        Candidate b = code("builtin", "SQLI-001", "CWE-89", SCOPE, "stmt.execute(other)", 50,
                List.of(step(Finding.TraceKind.SINK, FILE, 50)));

        List<Candidate> out = merger.merge(List.of(a, b));

        assertThat(out).hasSize(2);
        assertThat(out).extracting(c -> c.fingerprint().orElseThrow()).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void sameSinkTextOnDifferentLinesGetsDistinctOccurrences() {
        List<Candidate> out = merger.merge(List.of(code("builtin", "R", 60), code("builtin", "R", 42)));

        assertThat(out).extracting(c -> c.fingerprint().orElseThrow()).containsExactlyInAnyOrder(
                Fingerprints.code("CWE-89", FILE, SCOPE, SINK, 0), Fingerprints.code("CWE-89", FILE, SCOPE, SINK, 1));
    }

    @Test
    void cweVariantMapsToFamilyAndScopeSpacingIsCanonical() {
        Candidate variant = code("semgrep", "x", "CWE-564: Hibernate Injection", "app.UserDao # find( String )",
                SINK, 42, List.of(step(Finding.TraceKind.SINK, FILE, 42)));
        Candidate root = code("builtin", "y", 42);

        List<Candidate> out = merger.merge(List.of(variant, root));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).fingerprint().orElseThrow()).startsWith("code:CWE-89:");
        assertThat(Scopes.canonical(" Foo # bar( String , java.util.Map<K, V> [] ) "))
                .isEqualTo("Foo#bar(String,java.util.Map<K,V>[])");
        assertThat(Scopes.canonical("Foo#bar(final  String)")).isEqualTo("Foo#bar(final String)");
    }

    @Test
    void shuffledInputProducesByteIdenticalOutput() {
        List<Candidate> input = new ArrayList<>(List.of(
                code("semgrep", "java.sqli", 42), code("builtin", "SQLI-001", 42), code("pmd", "P", 42),
                code("builtin", "SQLI-001", 77),
                code("builtin", "PT-1", "CWE-23", "app.Files#read(String)", "new File(p)", 12,
                        List.of(step(Finding.TraceKind.SINK, FILE, 12))),
                dependency("osv", "GHSA-aaaa-bbbb-cccc", List.of("CVE-2024-0002", "CVE-2024-0001")),
                dependency("dependency-check", "CVE-2024-0001", List.of("CVE-2024-0001"))));
        String expected = render(merger.merge(input));

        Random random = new Random(8);
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(input, random);
            assertThat(render(merger.merge(input))).isEqualTo(expected);
        }
        assertThat(merger.merge(input)).hasSize(4);
    }

    @Test
    void dependencyCandidatesMergeByPurlAndPreferredVulnId() {
        List<Candidate> out = merger.merge(List.of(
                dependency("osv", "GHSA-aaaa-bbbb-cccc", List.of("CVE-2024-0001")),
                dependency("dependency-check", "CVE-2024-0001", List.of("CVE-2024-0001"))));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).fingerprint()).contains("dep:pkg:maven/org.x/lib@1.0:CVE-2024-0001");
        assertThat(out.get(0).toolSources()).extracting(Finding.ToolSource::tool)
                .containsExactly("dependency-check", "osv");
    }

    @Test
    void candidateWithoutCweIsDroppedWithNote() {
        Candidate noCwe = code("builtin", "R", "not-a-cwe", SCOPE, SINK, 42,
                List.of(step(Finding.TraceKind.SINK, FILE, 42)));

        CandidateMerger.Outcome outcome = merger.normalize(List.of(noCwe, code("builtin", "R", 42)));

        assertThat(outcome.candidates()).hasSize(1);
        assertThat(outcome.notes()).singleElement().asString().contains("without CWE");
    }

    private static String render(List<Candidate> candidates) {
        StringBuilder out = new StringBuilder();
        for (Candidate c : candidates) {
            out.append(FindingCodec.toTree(c).toString()).append('\n');
        }
        return out.toString();
    }
}
