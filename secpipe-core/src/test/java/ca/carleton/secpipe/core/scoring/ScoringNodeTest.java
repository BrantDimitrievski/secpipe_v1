package ca.carleton.secpipe.core.scoring;

import static ca.carleton.secpipe.core.scoring.ScoringFixtures.confirmed;
import static ca.carleton.secpipe.core.scoring.ScoringFixtures.needsValidation;
import static ca.carleton.secpipe.core.scoring.ScoringFixtures.rejected;
import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.Score5;
import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.core.engine.DefaultRunContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScoringNodeTest {

    @TempDir
    Path tmp;

    @Test
    void putsOnlyRescoredRecordsSortedByFingerprint() {
        List<Finding> records = List.of(
                confirmed("fp-z", "CWE-327", Score5.HIGH, Score5.HIGH, Score5.HIGH),
                confirmed("fp-b", "CWE-89", Score5.HIGH, Score5.CRITICAL, Score5.CRITICAL),
                needsValidation("fp-a", "CWE-22", Optional.of(Score5.HIGH)),
                rejected("fp-m"));
        ScoringNode node = new ScoringNode();
        PipelineNode.Result r = node.apply(ScoringFixtures.state(records, List.of(), Optional.empty()),
                DefaultRunContext.builder().workDir(tmp).outDir(tmp.resolve("out")).build());
        assertThat(r.label()).isEqualTo(PipelineGraph.NEXT);
        assertThat(r.delta().putRecords()).extracting(Finding::fingerprint)
                .containsExactly(ScoringFixtures.fp("fp-a"), ScoringFixtures.fp("fp-z"));
        assertThat(((Finding.Confirmed) r.delta().putRecords().get(1)).severity().overallSeverity())
                .isEqualTo(Score5.MEDIUM);
        assertThat(node.requiresLlm()).isFalse();
        assertThat(PipelineGraph.NODES).containsEntry(node.id(), ScoringNode.class.getName());
    }
}
