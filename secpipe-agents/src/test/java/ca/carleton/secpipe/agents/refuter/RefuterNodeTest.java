package ca.carleton.secpipe.agents.refuter;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.ProjectProfile;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;
import ca.carleton.secpipe.contracts.spi.Budget;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.validators.CapPolicy;
import ca.carleton.secpipe.validators.FindingsValidator;
import ca.carleton.secpipe.validators.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefuterNodeTest {

    private static final String APP = "src/main/java/com/shop/app/";

    @TempDir
    Path root;

    @Test
    void isRegisteredDeterministicAndNeedsNoLlmOrSandbox() {
        DeterministicRefuterNode node = new DeterministicRefuterNode();
        assertEquals("refute", node.id());
        assertFalse(node.requiresLlm());
        assertFalse(node.usesSandbox());
        assertEquals(DeterministicRefuterNode.class.getName(), PipelineGraph.NODES.get("refute"));
    }

    @Test
    void rejectsOnlyRefutedCandidatesAndNeverConfirmsOrMarksNeedsValidation() throws Exception {
        for (String cls : List.of("TagFilter", "OrderSearch", "ArchiveJob", "HostCheck", "LogViewer")) {
            copy(cls);
        }
        List<Candidate> candidates = new ArrayList<>(List.of(
                at("TagFilter", "CWE-89", "executeQuery(", "aaaaaaaaaaaaaaaaaaaaaaa1"),
                at("OrderSearch", "CWE-89", "executeQuery(", "aaaaaaaaaaaaaaaaaaaaaaa2"),
                at("ArchiveJob", "CWE-78", "new ProcessBuilder(", "aaaaaaaaaaaaaaaaaaaaaaa3"),
                at("HostCheck", "CWE-78", "exec(", "aaaaaaaaaaaaaaaaaaaaaaa4"),
                at("LogViewer", "CWE-22", "new FileReader(", "aaaaaaaaaaaaaaaaaaaaaaa5"),
                at("Missing", "CWE-89", null, "aaaaaaaaaaaaaaaaaaaaaaa6")));
        PipelineNode.Result result = new DeterministicRefuterNode().apply(state(candidates), new Ctx());

        assertEquals(PipelineGraph.NEXT, result.label());
        List<Finding> records = result.delta().putRecords();
        assertEquals(2, records.size());
        for (Finding f : records) {
            assertEquals(Finding.Verdict.REJECTED, f.verdict());
            assertFalse(f instanceof Finding.Confirmed || f instanceof Finding.NeedsValidation);
            Finding.VerifierRef v = f.secpipe().verification().orElseThrow().disprover().orElseThrow();
            assertEquals(Finding.VerifierRef.Kind.DETERMINISTIC, v.kind());
            assertTrue(v.rule().orElseThrow().startsWith("refuter."));
            assertEquals(Optional.empty(), f.secpipe().toolSeverityHint());
        }
        // sorted by fingerprint: CWE-78 (ArchiveJob) before CWE-89 (TagFilter)
        assertEquals("code:CWE-78:aaaaaaaaaaaaaaaaaaaaaaa3", records.get(0).fingerprint());
        Finding.Rejected tag = assertInstanceOf(Finding.Rejected.class, records.get(1));
        assertEquals("code:CWE-89:aaaaaaaaaaaaaaaaaaaaaaa1", tag.fingerprint());
        assertEquals(Optional.of(Refutation.VALUE_OVERWRITTEN),
                tag.secpipe().verification().orElseThrow().disprover().orElseThrow().rule());
        assertEquals(APP + "TagFilter.java", tag.evidence().getFirst().file());
        assertEquals(lineOf("TagFilter", "tag = \"featured\""), tag.evidence().getFirst().line());
        assertTrue(tag.reason().contains("overwritten"));
        assertTrue(result.delta().ledgerUpdates().isEmpty());
        assertTrue(result.delta().putSurvivorDrafts().isEmpty());

        ValidationResult validation = FindingsValidator.validate(records, Optional.of(root), CapPolicy.NONE);
        assertTrue(validation.ok(), validation.toString());
    }

    @Test
    void skipsCandidatesThatAlreadyHaveARecordOrNoFingerprint() throws Exception {
        copy("TagFilter");
        Candidate tag = at("TagFilter", "CWE-89", "executeQuery(", "bbbbbbbbbbbbbbbbbbbbbbb1");
        Finding existing = DeterministicRefuterNode.toRecord(tag, new Refutation(Refutation.CONSTANT_VALUE,
                ca.carleton.secpipe.contracts.recon.Location.of(APP + "TagFilter.java", 1), "already decided"));
        PipelineState withRecord = state(List.of(tag), Map.of(tag.fingerprint().orElseThrow(), existing));
        assertTrue(new DeterministicRefuterNode().apply(withRecord, new Ctx()).delta().putRecords().isEmpty());

        Candidate unfingerprinted = new Candidate(Optional.empty(), tag.category(), tag.origin(), tag.toolSources(),
                tag.cwe(), tag.cve(), tag.title(), tag.message(), tag.claimedRootCause(), tag.trace(), tag.evidence(),
                tag.sinkStatement(), tag.sinkScope(), tag.dependency(), tag.toolSeverityHint(), tag.confidenceHint(),
                tag.strongestControl(), tag.smallestFix(), tag.regressionCase(), tag.ledgerUnits());
        assertTrue(new DeterministicRefuterNode().apply(state(List.of(unfingerprinted)), new Ctx())
                .delta().putRecords().isEmpty());
    }

    @Test
    void outputIsDeterministic() throws Exception {
        copy("TagFilter");
        copy("ArchiveJob");
        List<Candidate> forward = List.of(at("TagFilter", "CWE-89", "executeQuery(", "ccccccccccccccccccccccc2"),
                at("ArchiveJob", "CWE-78", "new ProcessBuilder(", "ccccccccccccccccccccccc1"));
        List<Candidate> backward = List.of(forward.get(1), forward.get(0));
        List<Finding> a = new DeterministicRefuterNode().apply(state(forward), new Ctx()).delta().putRecords();
        List<Finding> b = new DeterministicRefuterNode().apply(state(backward), new Ctx()).delta().putRecords();
        assertEquals(a, b);
        assertEquals("code:CWE-78:ccccccccccccccccccccccc1", a.getFirst().fingerprint());
    }

    private void copy(String cls) throws IOException {
        Path target = root.resolve(APP + cls + ".java");
        Files.createDirectories(target.getParent());
        Files.writeString(target, RefuterFixtures.shopSource(cls));
    }

    private static int lineOf(String cls, String text) {
        return RefuterFixtures.lineOf(RefuterFixtures.shopSource(cls), text);
    }

    private static Candidate at(String cls, String cwe, String sinkText, String suffix) {
        int line = sinkText == null ? 3 : lineOf(cls, sinkText);
        return RefuterFixtures.candidate(APP + cls + ".java", cwe, line, suffix);
    }

    private PipelineState state(List<Candidate> candidates) {
        return state(candidates, Map.of());
    }

    private PipelineState state(List<Candidate> candidates, Map<String, Finding> records) {
        ProjectProfile profile = new ProjectProfile(root, "java", ProjectProfile.BuildTool.MAVEN, Set.of(), List.of(),
                List.of(), List.of(), 0);
        return new PipelineState("run-1", null, Optional.empty(), Optional.of(profile), List.of(), List.of(),
                candidates, records, Map.of(), List.of(), Map.of(), 0, 0, false, Map.of(), Optional.empty(),
                List.of(), Optional.empty());
    }

    /** Only log() is used; every other service fails the test if touched (no LLM, no sandbox). */
    private static final class Ctx implements RunContext {
        @Override
        public Path workDir() {
            throw new AssertionError("workDir");
        }

        @Override
        public Path outDir() {
            throw new AssertionError("outDir");
        }

        @Override
        public LlmClient llm() {
            throw new AssertionError("the refuter must not call an LLM");
        }

        @Override
        public Optional<SandboxRunner> sandbox() {
            throw new AssertionError("the refuter must not use the sandbox");
        }

        @Override
        public Budget budget() {
            throw new AssertionError("budget");
        }

        @Override
        public List<AnalyzerAdapter> adapters() {
            throw new AssertionError("adapters");
        }

        @Override
        public Rescanner rescanner() {
            throw new AssertionError("rescanner");
        }

        @Override
        public Clock clock() {
            throw new AssertionError("clock");
        }

        @Override
        public RunLog log() {
            return new RunLog() {
                @Override
                public void info(String msg) {
                }

                @Override
                public void warn(String msg) {
                }

                @Override
                public void debug(String msg) {
                }
            };
        }
    }
}
