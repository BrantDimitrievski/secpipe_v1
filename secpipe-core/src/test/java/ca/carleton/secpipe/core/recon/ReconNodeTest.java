package ca.carleton.secpipe.core.recon;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.json.ReconFactsCodec;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.recon.Location;
import ca.carleton.secpipe.contracts.recon.ReconFacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReconNodeTest {

    private static final String APP = "src/main/java/com/shop/app/";

    @TempDir
    Path tmp;

    private static Set<String> classes(List<Location> locations) {
        Set<String> out = new TreeSet<>();
        for (Location l : locations) {
            String f = l.file();
            out.add(f.substring(f.lastIndexOf('/') + 1, f.length() - ".java".length()));
        }
        return out;
    }

    @Test
    void findsServletEntrySurfacesInShopSamples() {
        ReconFacts facts = ReconNode.run(ReconFixtures.shopSamples(), false).facts();

        assertThat(classes(facts.entrySurfaces())).containsExactly("ArchiveJob", "AttachmentDownload", "AvatarServe",
                "CartRestore", "DiskUsage", "HostCheck", "InvoiceLookup", "LogViewer", "OrderSearch", "PrefsRestore",
                "ReportSort", "ScriptRunner", "TagFilter", "ThemeLoader");
        assertThat(facts.entrySurfaces()).contains(Location.of(APP + "OrderSearch.java", 17,
                "com.shop.app.OrderSearch#doGet(HttpServletRequest,HttpServletResponse)"));
    }

    @Test
    void findsExpectedSinksPerFamilyInShopSamples() {
        ReconFacts facts = ReconNode.run(ReconFixtures.shopSamples(), false).facts();

        assertThat(facts.sinksByFamily().keySet()).containsExactly("CWE-22", "CWE-327", "CWE-328", "CWE-78", "CWE-89");
        assertThat(classes(facts.sinks("CWE-89"))).containsExactly("InvoiceLookup", "OrderSearch", "ReportSort",
                "TagFilter");
        assertThat(classes(facts.sinks("CWE-78"))).containsExactly("ArchiveJob", "DiskUsage", "HostCheck",
                "ScriptRunner");
        assertThat(classes(facts.sinks("CWE-22"))).containsExactly("AttachmentDownload", "AvatarServe",
                "FileChecksum", "LogViewer", "ThemeLoader");
        assertThat(classes(facts.sinks("CWE-327"))).containsExactly("BackupEncryptor", "ConfigEncryptor");
        assertThat(classes(facts.sinks("CWE-328"))).containsExactly("FileChecksum", "PasswordHasher");
        assertThat(facts.sinks("CWE-89")).contains(Location.of(APP + "OrderSearch.java", 21,
                "com.shop.app.OrderSearch#doGet(HttpServletRequest,HttpServletResponse)"));
        assertThat(facts.sinks("CWE-78")).contains(Location.of(APP + "ScriptRunner.java", 20,
                "com.shop.app.ScriptRunner#runJob(String)"));
        // A sink in a field initializer has no enclosing method, so no scope.
        assertThat(facts.sinks("CWE-22")).contains(Location.of(APP + "AvatarServe.java", 15));
    }

    @Test
    void applyWritesReconFactsByteIdenticalAcrossRuns() throws Exception {
        Path shop = ReconFixtures.shopSamples();
        Path work1 = tmp.resolve("w1");
        Path work2 = tmp.resolve("w2");
        new ReconNode().apply(ReconFixtures.state(shop, List.of()), ReconFixtures.ctx(work1));
        new ReconNode().apply(ReconFixtures.state(shop, List.of()), ReconFixtures.ctx(work2));

        byte[] first = Files.readAllBytes(work1.resolve(ReconFactsCodec.FILE_NAME));
        byte[] second = Files.readAllBytes(work2.resolve(ReconFactsCodec.FILE_NAME));
        assertThat(first).isNotEmpty().isEqualTo(second);
        assertThat(ReconFactsCodec.read(work1.resolve(ReconFactsCodec.FILE_NAME)))
                .isEqualTo(ReconNode.run(shop, false).facts());
    }

    @Test
    void applySeedsPlannedUnitsAndSkipsUnitsAlreadyInTheLedger() throws Exception {
        Path shop = ReconFixtures.shopSamples();
        PipelineNode.Result first = new ReconNode().apply(ReconFixtures.state(shop, List.of()),
                ReconFixtures.ctx(tmp.resolve("a")));
        List<LedgerUnit> seeded = first.delta().newLedgerUnits();

        assertThat(first.label()).isEqualTo(PipelineGraph.NEXT);
        assertThat(seeded).isNotEmpty().allMatch(u -> u.status() == LedgerUnit.Status.PLANNED);

        PipelineNode.Result again = new ReconNode().apply(ReconFixtures.state(shop, seeded.subList(0, 10)),
                ReconFixtures.ctx(tmp.resolve("b")));
        assertThat(again.delta().newLedgerUnits()).hasSize(seeded.size() - 10)
                .doesNotContainAnyElementsOf(seeded.subList(0, 10));
    }

    @Test
    void unparseableFileBecomesANoteNotACrash() throws Exception {
        ReconFixtures.write(tmp, "src/main/java/a/Broken.java", "package a; class Broken { void x( { }");
        ReconFixtures.write(tmp, "src/main/java/a/Ok.java", """
                package a;
                import java.sql.Statement;
                class Ok {
                    void q(Statement st, String s) throws Exception {
                        st.executeQuery(s);
                    }
                }
                """);

        PipelineNode.Result result = new ReconNode().apply(ReconFixtures.state(tmp, List.of()),
                ReconFixtures.ctx(tmp.resolve("work")));

        assertThat(result.delta().notes())
                .anyMatch(n -> n.startsWith("recon: could not parse src/main/java/a/Broken.java"));
        ReconFacts facts = ReconFactsCodec.read(tmp.resolve("work").resolve(ReconFactsCodec.FILE_NAME));
        assertThat(facts.sinks("CWE-89")).containsExactly(Location.of("src/main/java/a/Ok.java", 5,
                "a.Ok#q(Statement,String)"));
    }

    @Test
    void detectsSpringAndJaxRsEntrySurfaces() throws Exception {
        ReconFixtures.write(tmp, "src/main/java/w/Api.java", """
                package w;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;
                @RestController
                @RequestMapping("/api")
                class Api {
                    @GetMapping("/users")
                    String users(String q) { return q; }
                    String helper() { return ""; }
                }
                """);
        ReconFixtures.write(tmp, "src/main/java/w/Res.java", """
                package w;
                import javax.ws.rs.GET;
                import javax.ws.rs.Path;
                @Path("/res")
                class Res {
                    @GET
                    @Path("{id}")
                    String get(String id) { return id; }
                }
                """);

        ReconNode.Outcome outcome = ReconNode.run(tmp, false);

        assertThat(outcome.facts().entrySurfaces()).containsExactly(
                Location.of("src/main/java/w/Api.java", 9, "w.Api#users(String)"),
                Location.of("src/main/java/w/Res.java", 8, "w.Res#get(String)"));
        assertThat(outcome.units()).extracting(LedgerUnit::surface).contains("GET /api/users", "GET /res/{id}");
    }

    @Test
    void staticOnlySinksNeedTheClassAsReceiverAndTestSourcesAreSkipped() throws Exception {
        ReconFixtures.write(tmp, "src/main/java/s/Out.java", """
                package s;
                import java.io.OutputStream;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import static java.nio.file.Files.readString;
                class Out {
                    void w(OutputStream out, Path p, byte[] b) throws Exception {
                        out.write(b);
                        Files.write(p, b);
                        readString(p);
                    }
                }
                """);
        ReconFixtures.write(tmp, "src/test/java/s/OutTest.java", """
                package s;
                class OutTest { void t() throws Exception { Runtime.getRuntime().exec("x"); } }
                """);

        ReconFacts facts = ReconNode.run(tmp, false).facts();

        assertThat(facts.sinks("CWE-22")).extracting(Location::line).containsExactly(9, 10);
        assertThat(facts.sinks("CWE-78")).isEmpty();
        assertThat(ReconNode.run(tmp, true).facts().sinks("CWE-78")).extracting(Location::file)
                .containsExactly("src/test/java/s/OutTest.java");
    }

    @Test
    void nodeIsDeterministicAndNeedsNoLlmOrSandbox() {
        ReconNode node = new ReconNode();
        assertThat(node.id()).isEqualTo("recon");
        assertThat(node.requiresLlm()).isFalse();
        assertThat(node.usesSandbox()).isFalse();
        assertThat(PipelineGraph.NODES.get("recon")).isEqualTo(ReconNode.class.getName());
        assertThat(ReconNode.run(tmp.resolve("missing"), false).facts().entrySurfaces()).isEmpty();
        assertThat(ReconNode.run(tmp.resolve("missing"), false).notes()).anyMatch(n -> n.contains("not a directory"));
        assertThat(ReconNode.run(ReconFixtures.shopSamples(), false).notes()).isEmpty();
    }
}
