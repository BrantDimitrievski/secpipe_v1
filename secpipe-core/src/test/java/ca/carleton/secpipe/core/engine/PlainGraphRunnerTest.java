package ca.carleton.secpipe.core.engine;

import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.validators.ValidationError;
import ca.carleton.secpipe.validators.ValidationResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlainGraphRunnerTest {

    /** Each stub's elapsed time: the ticking clock advances 7 ms per read and the runner reads it twice. */
    private static final long TICK = 7L;

    private static final List<String> NON_LLM = List.of("setup", "recon", "detect", "normalize", "normalize_llm",
            "refute", "test_evidence", "score", "finalize", "report");

    private PipelineState run(Map<String, Class<?>> overrides, ProviderMode mode) {
        return run(StubNodes.loader(overrides), EngineValidators.acceptAll(), EngineFixtures.emptyState(), mode);
    }

    private PipelineState run(NodeLoader loader, EngineValidators validators, PipelineState initial,
                              ProviderMode mode) {
        RunContext ctx = StubNodes.context(mode, Path.of("unused-work-dir"));
        return new PlainGraphRunner(validators, loader, false).run(initial, ctx);
    }

    private static Map<String, Long> eachTick(List<String> ids) {
        Map<String, Long> m = new LinkedHashMap<>();
        ids.stream().sorted().forEach(id -> m.put(id, TICK));
        return m;
    }

    @Test
    void linearRunWithoutLlmExecutesEveryNonLlmNodeOnceAndIsComplete() {
        PipelineState end = run(Map.of(), ProviderMode.NONE);

        assertThat(end.incompleteReason()).isEmpty();
        assertThat(end.stageTimingsMs()).isEqualTo(eachTick(NON_LLM));
        assertThat(end.hunterWave()).isEqualTo(0);
    }

    @Test
    void llmNodesAreSkippedWithANoteWhenModeIsNone() {
        PipelineState end = run(Map.of(), ProviderMode.NONE);

        assertThat(end.notes()).containsExactly(
                "node recon_summary skipped: llm-provider=none",
                "node hunt skipped: llm-provider=none",
                "node disprove skipped: llm-provider=none",
                "node remediate skipped: llm-provider=none",
                "node verify_records skipped: llm-provider=none");
        assertThat(end.stageTimingsMs()).doesNotContainKey("hunt");
        assertThat(end.criticPasses()).isEqualTo(0);
    }

    @Test
    void loopTransitionRunsHuntAndCriticUntilTheCriticSaysDone() {
        PipelineState end = run(Map.of(), ProviderMode.FAKE);

        assertThat(end.incompleteReason()).isEmpty();
        assertThat(end.hunterWave()).isEqualTo(2);
        assertThat(end.criticPasses()).isEqualTo(2);
        assertThat(end.notes()).isEmpty();
    }

    @Test
    void timingsAreRecordedForEveryExecutedNodeAndAccumulateAcrossLoops() {
        PipelineState end = run(Map.of(), ProviderMode.FAKE);

        Map<String, Long> expected = eachTick(List.copyOf(PipelineGraph.NODES.keySet()));
        expected.put("hunt", 2 * TICK);
        expected.put("critic", 2 * TICK);
        assertThat(end.stageTimingsMs()).isEqualTo(expected);
    }

    @Test
    void missingNodeFollowsItsSkippedEdgeWithANote() {
        Map<String, String> table = StubNodes.table(Map.of());
        table.put("refute", "ca.carleton.secpipe.core.engine.DoesNotExistNode");
        PipelineState end = run(new NodeLoader(table, getClass().getClassLoader()), EngineValidators.acceptAll(),
                EngineFixtures.emptyState(), ProviderMode.FAKE);

        assertThat(end.notes()).containsExactly(
                "node refute not installed (ca.carleton.secpipe.core.engine.DoesNotExistNode)");
        assertThat(end.stageTimingsMs()).doesNotContainKey("refute");
        assertThat(end.stageTimingsMs()).containsKey("disprove");
        assertThat(end.incompleteReason()).isEmpty();
    }

    @Test
    void missingRequiredNodeMakesTheRunIncompleteButFinalizeAndReportStillRun() {
        Map<String, String> table = StubNodes.table(Map.of());
        table.remove("detect");
        PipelineState end = run(new NodeLoader(table, getClass().getClassLoader()), EngineValidators.acceptAll(),
                EngineFixtures.emptyState(), ProviderMode.FAKE);

        assertThat(end.incompleteReason()).contains(
                "required node detect did not run: node detect not installed: no class registered for it");
        assertThat(end.stageTimingsMs()).containsKey("normalize");
        assertThat(end.stageTimingsMs()).containsKey("finalize");
        assertThat(end.stageTimingsMs()).containsKey("report");
    }

    @Test
    void disabledNodeIsTreatedAsMissingWithTheDisableNote() {
        PipelineState initial = withConfig(EngineFixtures.config(Set.of("score")));
        PipelineState end = run(StubNodes.loader(Map.of()), EngineValidators.acceptAll(), initial, ProviderMode.FAKE);

        assertThat(end.notes()).containsExactly("node score disabled by --disable-node");
        assertThat(end.stageTimingsMs()).doesNotContainKey("score");
        assertThat(end.stageTimingsMs()).containsKey("remediate");
    }

    @Test
    void nodeExceptionBecomesIncompleteReasonAndTheRunContinuesToFinalizeAndReport() {
        PipelineState end = run(Map.of("refute", StubNodes.Throwing.class), ProviderMode.FAKE);

        assertThat(end.incompleteReason()).contains("node refute failed: IllegalStateException: boom");
        assertThat(end.stageTimingsMs()).containsEntry("refute", TICK);
        assertThat(end.stageTimingsMs()).containsKey("disprove");
        assertThat(end.stageTimingsMs()).containsKey("finalize");
        assertThat(end.stageTimingsMs()).containsKey("report");
    }

    @Test
    void deltaFailingTheValidatorIsRejectedAndTheRunIsIncompleteWithTheValidatorMessage() {
        EngineValidators rejectRecords = new EngineValidators(
                records -> records.isEmpty() ? ValidationResult.valid()
                        : ValidationResult.of(List.of(ValidationError.of("F7", "/0", "record fp-1 is not allowed"))),
                (before, after) -> ValidationResult.valid());
        PipelineState end = run(StubNodes.loader(Map.of("score", StubNodes.PutsRecord.class)), rejectRecords,
                EngineFixtures.emptyState(), ProviderMode.FAKE);

        assertThat(end.incompleteReason()).isPresent();
        assertThat(end.incompleteReason().get()).startsWith("validator rejected delta from node score:");
        assertThat(end.incompleteReason().get()).contains("record fp-1 is not allowed");
        assertThat(end.records()).isEmpty();
        assertThat(end.notes()).doesNotContain("record written");
        assertThat(end.stageTimingsMs()).containsKey("report");
    }

    @Test
    void loopGuardStopsAfter200StepsThenRunsFinalizeAndReport() {
        PipelineState end = run(Map.of("critic", StubNodes.CriticForever.class), ProviderMode.FAKE);

        assertThat(end.incompleteReason()).isPresent();
        assertThat(end.incompleteReason().get()).startsWith("loop guard: more than 200 node steps");
        // setup, recon, recon_summary, detect, normalize, then hunt/critic alternate for the remaining 195 steps
        assertThat(end.hunterWave() + end.criticPasses()).isEqualTo(PlainGraphRunner.MAX_NODE_STEPS - 5);
        assertThat(end.stageTimingsMs()).doesNotContainKey("normalize_llm");
        assertThat(end.stageTimingsMs()).containsEntry("finalize", TICK);
        assertThat(end.stageTimingsMs()).containsEntry("report", TICK);
    }

    @Test
    void undeclaredLabelMakesTheRunIncompleteButKeepsTheDeltaAndFollowsSkipped() {
        PipelineState end = run(Map.of("recon", StubNodes.Undeclared.class), ProviderMode.FAKE);

        assertThat(end.incompleteReason()).contains("node recon returned undeclared transition 'bogus'");
        assertThat(end.notes()).containsExactly("undeclared ran");
        assertThat(end.stageTimingsMs()).containsKey("recon_summary");
    }

    @Test
    void nullResultMakesTheRunIncomplete() {
        PipelineState end = run(Map.of("test_evidence", StubNodes.ReturnsNull.class), ProviderMode.FAKE);

        assertThat(end.incompleteReason()).contains("node test_evidence returned no result");
        assertThat(end.stageTimingsMs()).containsKey("score");
    }

    @Test
    void laterProblemsAreKeptAsNotesAfterTheFirstIncompleteReason() {
        PipelineState end = run(Map.of("recon", StubNodes.Throwing.class, "refute", StubNodes.ReturnsNull.class),
                ProviderMode.FAKE);

        assertThat(end.incompleteReason()).contains("node recon failed: IllegalStateException: boom");
        assertThat(end.notes()).containsExactly("additional incomplete reason: node refute returned no result");
    }

    @Test
    void nodeCannotMutateStateExceptThroughItsDelta() {
        PipelineState end = run(Map.of("score", StubNodes.Mutating.class), ProviderMode.FAKE);

        assertThat(end.incompleteReason()).contains("node score failed: UnsupportedOperationException");
        assertThat(end.notes()).doesNotContain("smuggled");
        assertThatThrownBy(() -> end.notes().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> end.stageTimingsMs().put("x", 1L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void checkpointIsWrittenToTheWorkDirAndMatchesTheFinalState() throws IOException {
        Path work = Files.createTempDirectory("secpipe-k1-");
        try {
            RunContext ctx = StubNodes.context(ProviderMode.FAKE, work);
            PipelineState end = new PlainGraphRunner(EngineValidators.acceptAll(), StubNodes.loader(Map.of()), true)
                    .run(EngineFixtures.emptyState(), ctx);

            Path file = work.resolve(EngineCheckpoint.FILE_NAME);
            assertThat(Files.readString(file)).isEqualTo(EngineCheckpoint.toJson(end));
            assertThat(Files.exists(work.resolve(EngineCheckpoint.FILE_NAME + ".tmp"))).isFalse();
        } finally {
            deleteTree(work);
        }
    }

    @Test
    void unwritableCheckpointDirIsLoggedOnceAndDoesNotStopTheRun() throws IOException {
        Path work = Files.createTempDirectory("secpipe-k1-");
        try {
            Path blocker = work.resolve("not-a-dir");
            Files.writeString(blocker, "x");
            DefaultRunLog.Collecting log = DefaultRunLog.collecting();
            RunContext ctx = DefaultRunContext.builder().workDir(blocker).outDir(work).log(log)
                    .clock(new StubNodes.TickingClock(TICK)).build();
            PipelineState end = new PlainGraphRunner(EngineValidators.acceptAll(), StubNodes.loader(Map.of()), true)
                    .run(EngineFixtures.emptyState(), ctx);

            assertThat(end.stageTimingsMs()).containsKey("report");
            assertThat(log.lines().stream().filter(l -> l.startsWith("[warn] could not write state.json")).count())
                    .isEqualTo(1L);
        } finally {
            deleteTree(work);
        }
    }

    private static PipelineState withConfig(SecPipeConfig config) {
        PipelineState s = EngineFixtures.emptyState();
        return new PipelineState(s.runId(), config, s.environment(), s.profile(), s.diffScope(), s.ledger(),
                s.candidates(), s.records(), s.survivorDrafts(), s.toolRuns(), s.stageTimingsMs(), s.hunterWave(),
                s.criticPasses(), s.criticSaysStop(), s.fixAttemptsByFingerprint(), s.prior(), s.notes(),
                s.incompleteReason());
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
