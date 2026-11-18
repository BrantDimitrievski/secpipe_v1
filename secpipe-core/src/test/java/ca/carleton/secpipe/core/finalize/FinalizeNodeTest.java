package ca.carleton.secpipe.core.finalize;

import static ca.carleton.secpipe.core.scoring.ScoringFixtures.confirmed;
import static ca.carleton.secpipe.core.scoring.ScoringFixtures.rejected;
import static ca.carleton.secpipe.core.scoring.ScoringFixtures.state;
import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.Score5;
import ca.carleton.secpipe.contracts.json.RunMetadataCodec;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.core.engine.DefaultRunContext;
import ca.carleton.secpipe.core.scoring.ScoringFixtures;
import ca.carleton.secpipe.validators.SchemaRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FinalizeNodeTest {

    private static final Instant NOW = Instant.parse("2026-11-30T12:00:00Z");

    @TempDir
    Path tmp;

    private RunContext ctx() {
        return DefaultRunContext.builder().workDir(tmp).outDir(tmp.resolve("out"))
                .clock(Clock.fixed(NOW, ZoneOffset.UTC)).build();
    }

    private PipelineNode.Result run(PipelineState s) {
        return new FinalizeNode().apply(s, ctx());
    }

    private RunMetadata written() throws Exception {
        Path file = tmp.resolve("out").resolve(FinalizeNode.RUN_METADATA);
        assertThat(file).exists();
        String json = Files.readString(file);
        assertThat(SchemaRegistry.shared().validate("run-metadata.schema.json", json).errors()).isEmpty();
        return RunMetadataCodec.read(json);
    }

    @Test
    void validRunIsCompleteAndFreezesMetadata() throws Exception {
        List<Finding> records = List.of(
                confirmed("fp-b", "CWE-89", Score5.HIGH, Score5.CRITICAL, Score5.CRITICAL), rejected("fp-a"));
        PipelineNode.Result r = run(state(records, List.of(), Optional.empty()));
        assertThat(r.label()).isEqualTo(PipelineGraph.NEXT);
        assertThat(r.delta().incompleteReason()).isEmpty();
        assertThat(r.delta().putRecords()).isEmpty();
        RunMetadata m = written();
        assertThat(m.terminalState()).isEqualTo(RunMetadata.TerminalState.COMPLETE);
        assertThat(m.incompleteReason()).isEmpty();
        assertThat(m.finishedAt()).isEqualTo(NOW);
        assertThat(m.validators()).isEqualTo(new RunMetadata.Validators(RunMetadata.ValidatorStatus.PASS,
                RunMetadata.ValidatorStatus.PASS));
        assertThat(m.counts().confirmed()).isEqualTo(1);
        assertThat(m.counts().rejected()).isEqualTo(1);
        assertThat(m.counts().bySeverity()).containsEntry("critical", 1);
    }

    @Test
    void findingsValidatorFailureSetsIncompleteReasonAndStillWritesMetadata() throws Exception {
        // CWE-327 is anchored at medium; a confirmed high record violates F4 with the scoring cap.
        List<Finding> records = List.of(confirmed("fp-c", "CWE-327", Score5.HIGH, Score5.HIGH, Score5.HIGH));
        PipelineNode.Result r = run(state(records, List.of(), Optional.empty()));
        assertThat(r.delta().incompleteReason()).hasValueSatisfying(reason -> assertThat(reason)
                .startsWith("findings validator failed: ").contains("[F4]").contains(ScoringFixtures.fp("fp-c")));
        RunMetadata m = written();
        assertThat(m.terminalState()).isEqualTo(RunMetadata.TerminalState.INCOMPLETE);
        assertThat(m.incompleteReason()).isEqualTo(r.delta().incompleteReason());
        assertThat(m.validators().findings()).isEqualTo(RunMetadata.ValidatorStatus.FAIL);
        assertThat(m.validators().ledger()).isEqualTo(RunMetadata.ValidatorStatus.PASS);
    }

    @Test
    void earlierIncompleteReasonWinsAndFinalizeCausesFollow() throws Exception {
        List<Finding> records = List.of(confirmed("fp-c", "CWE-327", Score5.HIGH, Score5.HIGH, Score5.HIGH));
        PipelineNode.Result r = run(state(records, List.of(), Optional.of("node hunt failed")));
        RunMetadata m = written();
        assertThat(m.incompleteReason()).hasValueSatisfying(reason -> assertThat(reason)
                .startsWith("node hunt failed; findings validator failed: "));
        assertThat(r.delta().incompleteReason()).hasValueSatisfying(reason -> assertThat(reason)
                .startsWith("findings validator failed: "));
    }

    @Test
    void priorReasonAloneStillYieldsIncompleteMetadata() throws Exception {
        PipelineNode.Result r = run(state(List.of(), List.of(), Optional.of("node detect threw")));
        assertThat(r.delta().incompleteReason()).isEmpty();
        RunMetadata m = written();
        assertThat(m.terminalState()).isEqualTo(RunMetadata.TerminalState.INCOMPLETE);
        assertThat(m.incompleteReason()).contains("node detect threw");
    }

    private static LedgerUnit unit(String id, LedgerUnit.Status status, List<String> fingerprints) {
        return new LedgerUnit(id, new LedgerUnit.CanonicalRefs("http", "auth", "core", "injection", Optional.empty()),
                "http", "auth", "core", "injection", Optional.of("CWE-89"), List.of("src/main/java/A.java"),
                "injection", List.of(), List.of(), LedgerUnit.PriorStatus.NEW, List.of(), 1, status,
                Optional.empty(), List.of(), List.of(), fingerprints, List.of(), List.of());
    }

    @Test
    void ledgerProblemsBecomeIncompleteCausesInOrder() throws Exception {
        List<LedgerUnit> ledger = List.of(
                unit("cov-1", LedgerUnit.Status.PLANNED, List.of()),
                unit("cov-2", LedgerUnit.Status.CANDIDATE, List.of()),
                unit("cov-3", LedgerUnit.Status.CANDIDATE, List.of("fp-unknown")));
        PipelineNode.Result r = run(state(List.of(), ledger, Optional.empty()));
        assertThat(r.delta().incompleteReason()).hasValueSatisfying(reason -> assertThat(reason)
                .startsWith("unassigned ledger units: 1; ledger validator failed: ")
                .contains("[L2]").contains("cov-2").contains("[L5]").contains("fp-unknown"));
        RunMetadata m = written();
        assertThat(m.validators().ledger()).isEqualTo(RunMetadata.ValidatorStatus.FAIL);
        assertThat(m.counts().ledgerUnits()).containsEntry("planned", 1).containsEntry("candidate", 2);
    }

    @Test
    void metadataIsByteStableForTheSameInput() throws Exception {
        List<Finding> records = List.of(rejected("fp-b"), rejected("fp-a"));
        run(state(records, List.of(), Optional.empty()));
        String first = Files.readString(tmp.resolve("out").resolve(FinalizeNode.RUN_METADATA));
        run(state(records.reversed(), List.of(), Optional.empty()));
        assertThat(Files.readString(tmp.resolve("out").resolve(FinalizeNode.RUN_METADATA))).isEqualTo(first);
        assertThat(new FinalizeNode().requiresLlm()).isFalse();
        assertThat(PipelineGraph.NODES).containsEntry("finalize", FinalizeNode.class.getName());
    }
}
