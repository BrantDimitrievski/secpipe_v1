package ca.carleton.secpipe.adapter.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.config.RunProfile;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.pipeline.ProjectProfile;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the ported builtin rules over the MVP shop-samples corpus (see its README for ground truth). */
class BuiltinAdapterTest {

    /** Vulnerable samples of the five families, by file (ground truth from the corpus README). */
    private static final Map<String, String> TRUE_POSITIVES = Map.of(
            "OrderSearch", "CWE-89", "ReportSort", "CWE-89",
            "HostCheck", "CWE-78", "ScriptRunner", "CWE-78",
            "AttachmentDownload", "CWE-22", "LogViewer", "CWE-22",
            "PasswordHasher", "CWE-328", "ConfigEncryptor", "CWE-327");
    /** Known false positives of the MVP's no-AI run (README): TPR 100 %, FPR 20 %. */
    private static final Set<String> KNOWN_FALSE_POSITIVES = Set.of("TagFilter", "ArchiveJob");
    private static final Set<String> SAFE_AND_CLEAN = Set.of(
            "InvoiceLookup", "AvatarServe", "ThemeLoader", "FileChecksum", "BackupEncryptor", "DiskUsage");

    private static Path shop;
    private static AnalyzerAdapter.Result shopResult;

    @BeforeAll
    static void scanShopSamples() throws Exception {
        shop = findShopSamples();
        shopResult = new BuiltinRulesAdapter().analyze(request(shop, Set.of()));
    }

    private static Path findShopSamples() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("fixtures/mvp-samples/shop-samples");
            if (Files.isDirectory(candidate)) return candidate;
            dir = dir.getParent();
        }
        throw new IllegalStateException("fixtures/mvp-samples/shop-samples not found above the working directory");
    }

    private static AnalyzerAdapter.Request request(Path root, Set<String> scope) throws IOException {
        List<String> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(p -> p.toString().endsWith(".java"))
                    .map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
        ProjectProfile profile = new ProjectProfile(root, "java", ProjectProfile.BuildTool.NONE, Set.of(), files,
                List.of(), List.of(), 0);
        return new AnalyzerAdapter.Request(root, profile, scope, root, Duration.ofMinutes(1), true, Optional.empty());
    }

    private static String simpleFile(Candidate c) {
        String file = c.toolSources().get(0).file().orElseThrow();
        return file.substring(file.lastIndexOf('/') + 1).replace(".java", "");
    }

    private static Map<String, Set<String>> cwesByFile(AnalyzerAdapter.Result r) {
        return r.candidates().stream().collect(Collectors.groupingBy(BuiltinAdapterTest::simpleFile,
                Collectors.flatMapping(c -> c.cwe().stream(), Collectors.toCollection(TreeSet::new))));
    }

    @Test
    void reportsEveryShopSamplesTruePositive() {
        Map<String, Set<String>> hits = cwesByFile(shopResult);
        TRUE_POSITIVES.forEach((file, cwe) -> assertThat(hits.get(file)).as("hits in %s", file).contains(cwe));
    }

    @Test
    void reproducesTheMvpNoAiResultWithOnlyTheKnownFalsePositives() {
        Set<String> flagged = cwesByFile(shopResult).keySet();
        Set<String> unexpected = new HashSet<>(flagged);
        unexpected.removeAll(TRUE_POSITIVES.keySet());
        assertThat(unexpected).isEqualTo(KNOWN_FALSE_POSITIVES);
        assertThat(flagged).doesNotContainAnyElementsOf(SAFE_AND_CLEAN);
    }

    @Test
    void doesNotReportFamiliesOutsideTheFiveSupportedOnes() {
        assertThat(shopResult.candidates()).flatExtracting(Candidate::cwe)
                .containsOnly("CWE-22", "CWE-78", "CWE-89", "CWE-327", "CWE-328");
        assertThat(cwesByFile(shopResult).keySet()).doesNotContain("MailClient", "CartRestore");
    }

    @Test
    void candidatesAreTracedToolOriginAndUnfingerprinted() {
        assertThat(shopResult.candidates()).isNotEmpty().allSatisfy(c -> {
            assertThat(c.fingerprint()).isEmpty();
            assertThat(c.category()).isEqualTo(Finding.Category.CODE);
            assertThat(c.origin()).isEqualTo(Finding.Origin.TOOL);
            assertThat(c.toolSources()).singleElement().satisfies(s -> {
                assertThat(s.tool()).isEqualTo("builtin");
                assertThat(s.ruleId()).startsWith("SP-JAVA-");
                assertThat(s.line()).isPresent();
            });
            assertThat(c.trace()).isNotEmpty();
            assertThat(c.trace().get(c.trace().size() - 1).kind()).isEqualTo(Finding.TraceKind.SINK);
            assertThat(c.sinkStatement()).isPresent();
            assertThat(c.sinkScope().orElseThrow()).matches("[\\w.]+#[\\w<>]+\\(.*\\)");
        });
    }

    @Test
    void taintedCandidatesCarryASourceToSinkTrace() {
        Candidate sqli = shopResult.candidates().stream().filter(c -> simpleFile(c).equals("OrderSearch")).findFirst().orElseThrow();
        assertThat(sqli.trace()).hasSize(2);
        assertThat(sqli.trace().get(0).kind()).isEqualTo(Finding.TraceKind.ENTRYPOINT);
        assertThat(sqli.trace().get(0).line()).isLessThanOrEqualTo(sqli.trace().get(1).line());
        assertThat(sqli.trace().get(0).description()).contains("getParameter");
        Candidate hash = shopResult.candidates().stream().filter(c -> simpleFile(c).equals("PasswordHasher")).findFirst().orElseThrow();
        assertThat(hash.trace()).singleElement().satisfies(s -> assertThat(s.kind()).isEqualTo(Finding.TraceKind.SINK));
    }

    @Test
    void claimsCoverageForTheFiveFamilies() {
        assertThat(shopResult.coverage()).extracting(AnalyzerAdapter.CoverageClaim::cwe)
                .containsExactly("CWE-22", "CWE-327", "CWE-328", "CWE-78", "CWE-89");
        assertThat(shopResult.coverage()).allSatisfy(claim -> {
            assertThat(claim.files()).contains("src/main/java/com/shop/app/OrderSearch.java");
            assertThat(claim.ruleIds()).isNotEmpty();
        });
        assertThat(new BuiltinRulesAdapter().supportedCwes())
                .containsExactlyInAnyOrder("CWE-22", "CWE-78", "CWE-89", "CWE-327", "CWE-328");
    }

    @Test
    void reportsRunMetadata() {
        assertThat(shopResult.run().id()).isEqualTo("builtin");
        assertThat(shopResult.run().status()).isEqualTo(RunMetadata.ToolRun.Status.OK);
        assertThat(shopResult.run().candidates()).isEqualTo(shopResult.candidates().size());
    }

    @Test
    void outputIsDeterministic() throws Exception {
        AnalyzerAdapter.Result again = new BuiltinRulesAdapter().analyze(request(shop, Set.of()));
        assertThat(again.candidates()).isEqualTo(shopResult.candidates());
        assertThat(again.coverage()).isEqualTo(shopResult.coverage());
    }

    @Test
    void scopeFilesLimitTheScan() throws Exception {
        String only = "src/main/java/com/shop/app/HostCheck.java";
        AnalyzerAdapter.Result r = new BuiltinRulesAdapter().analyze(request(shop, Set.of(only)));
        assertThat(r.candidates()).isNotEmpty().allSatisfy(c -> assertThat(simpleFile(c)).isEqualTo("HostCheck"));
        assertThat(r.coverage()).allSatisfy(claim -> assertThat(claim.files()).containsExactly(only));
    }

    @Test
    void isDiscoverableThroughServiceLoader() {
        List<String> ids = ServiceLoader.load(AnalyzerAdapter.class).stream().map(p -> p.get().id()).toList();
        assertThat(ids).contains("builtin");
        AnalyzerAdapter adapter = new BuiltinRulesAdapter();
        assertThat(adapter.kind()).isEqualTo(AnalyzerAdapter.Kind.SAST);
        assertThat(adapter.requiresSandbox()).isFalse();
        assertThat(adapter.defaultEnabled(RunProfile.QUICK)).isTrue();
    }

    @Test
    void sinkScopeAndStatementAreNormalized(@TempDir Path dir) throws Exception {
        write(dir, "A.java", """
                import java.sql.*;
                class A {
                    void find(Statement st, String name) throws Exception {
                        st.executeQuery(  "SELECT * FROM t WHERE n = '" + name
                                + "'" );
                    }
                }
                """);
        Candidate c = scan(dir).candidates().get(0);
        assertThat(c.sinkScope()).contains("A#find(Statement,String)");
        assertThat(c.sinkStatement().orElseThrow()).doesNotContain("\n").contains("st.executeQuery( \"SELECT");
        assertThat(c.cwe()).containsExactly("CWE-89");
        assertThat(c.trace().get(0).description()).contains("method parameter 'name'");
    }

    @Test
    void parameterizedQueryAndConstantsAreNotFlagged(@TempDir Path dir) throws Exception {
        write(dir, "B.java", """
                import java.sql.*;
                import java.io.File;
                class B {
                    void ok(Connection c, String name) throws Exception {
                        PreparedStatement ps = c.prepareStatement("SELECT * FROM t WHERE n = ?");
                        ps.setString(1, name);
                        Runtime.getRuntime().exec("ls");
                        new File("/tmp/fixed");
                    }
                }
                """);
        assertThat(scan(dir).candidates()).isEmpty();
    }

    @Test
    void flagsWeakCryptoWithoutTaintAndHonoursCanonicalPathChecks(@TempDir Path dir) throws Exception {
        write(dir, "C.java", """
                import java.io.File;
                import java.security.MessageDigest;
                import javax.crypto.Cipher;
                import javax.servlet.http.HttpServletRequest;
                class C {
                    void h() throws Exception {
                        MessageDigest.getInstance("MD5");
                        Cipher.getInstance("DES");
                    }
                    File safe(HttpServletRequest req) throws Exception {
                        File f = new File("/base", req.getParameter("n"));
                        if (!f.getCanonicalPath().startsWith("/base")) throw new SecurityException();
                        return f;
                    }
                }
                """);
        assertThat(scan(dir).candidates()).extracting(c -> c.cwe().get(0)).containsExactlyInAnyOrder("CWE-328", "CWE-327");
    }

    private static AnalyzerAdapter.Result scan(Path dir) throws Exception {
        return new BuiltinRulesAdapter().analyze(request(dir, Set.of()));
    }

    private static void write(Path dir, String name, String source) throws IOException {
        Files.writeString(dir.resolve(name), source);
    }
}
