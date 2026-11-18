package ca.carleton.secpipe.core.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NormalizeNodeTest {

    private static PipelineState state(List<Candidate> candidates) {
        return new PipelineState("run-1", null, Optional.empty(), Optional.empty(), List.of(), List.of(), candidates,
                Map.of(), Map.of(), List.of(), Map.of(), 0, 0, false, Map.of(), Optional.empty(), List.of(),
                Optional.empty());
    }

    @Test
    void replacesCandidatesWithMergedFingerprintedList() throws Exception {
        NormalizeNode node = new NormalizeNode();
        List<Candidate> input = List.of(CandidateMergerTest.code("semgrep", "a", 42),
                CandidateMergerTest.code("builtin", "b", 42), CandidateMergerTest.code("builtin", "b", 90));

        PipelineNode.Result result = node.apply(state(input), null);

        assertThat(result.label()).isEqualTo(PipelineGraph.NEXT);
        List<Candidate> out = result.delta().replaceCandidates().orElseThrow();
        assertThat(out).hasSize(2).allSatisfy(c -> assertThat(c.fingerprint()).isPresent());
        assertThat(out).extracting(c -> c.fingerprint().orElseThrow()).isSorted();
        assertThat(result.delta().addCandidates()).isEmpty();
        assertThat(node.requiresLlm()).isFalse();
        assertThat(node.usesSandbox()).isFalse();
    }

    @Test
    void reEntryKeepsExistingFingerprintsStable() throws Exception {
        NormalizeNode node = new NormalizeNode();
        List<Candidate> first = node.apply(state(List.of(CandidateMergerTest.code("builtin", "b", 42))), null)
                .delta().replaceCandidates().orElseThrow();

        List<Candidate> second = node.apply(state(first), null).delta().replaceCandidates().orElseThrow();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void registeredUnderBothGraphIds() {
        assertThat(PipelineGraph.NODES).containsEntry("normalize", NormalizeNode.class.getName())
                .containsEntry("normalize_llm", NormalizeNode.class.getName());
    }
}
