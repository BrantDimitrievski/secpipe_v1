package ca.carleton.secpipe.core.engine;

import static ca.carleton.secpipe.core.engine.EngineFixtures.candidate;
import static ca.carleton.secpipe.core.engine.EngineFixtures.delta;
import static ca.carleton.secpipe.core.engine.EngineFixtures.draft;
import static ca.carleton.secpipe.core.engine.EngineFixtures.emptyState;
import static ca.carleton.secpipe.core.engine.EngineFixtures.environment;
import static ca.carleton.secpipe.core.engine.EngineFixtures.profile;
import static ca.carleton.secpipe.core.engine.EngineFixtures.rejected;
import static ca.carleton.secpipe.core.engine.EngineFixtures.sink;
import static ca.carleton.secpipe.core.engine.EngineFixtures.toolRun;
import static ca.carleton.secpipe.core.engine.EngineFixtures.unit;
import static ca.carleton.secpipe.core.engine.EngineFixtures.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Feature A: nodes see deeply immutable state and can change it only through their returned delta. */
class DeltaApplierImmutabilityTest {

    private final DeltaApplier applier = new DeltaApplier(EngineValidators.acceptAll());

    /** A state with something in every collection, nested ones included. */
    private PipelineState populated() throws DeltaRejectedException {
        PipelineState s = DeltaApplier.freeze(emptyState());
        s = applier.apply(s, delta().environment(environment()).profile(profile()).diffScope(new ArrayList<>(
                List.of("A.java"))).newUnits(unit("u-a", LedgerUnit.Status.IN_PROGRESS)).build());
        return applier.apply(s, delta()
                .updates(update("u-a", LedgerUnit.Status.COVERED, Optional.of("hunter-1"), List.of("A.java"),
                        Optional.empty()))
                .addCandidates(candidate("c")).putRecords(rejected("fp-a", "r")).putDraft("fp-a", draft("fp-a"))
                .toolRuns(toolRun("semgrep")).timing("detect", 1).fixAttempts("fp-a", 1)
                .prior(new PipelineState.PriorRun("run-0", new ArrayList<>(List.of(unit("u-p",
                        LedgerUnit.Status.COVERED))), new ArrayList<>(List.of(rejected("fp-p", "r"))),
                        new HashMap<>(Map.of("A.java", "abc"))))
                .notes("n").build());
    }

    @Test
    void everyCollectionReachableFromTheStateRejectsMutation() throws Exception {
        PipelineState s = populated();
        LedgerUnit unit = s.ledger().get(0);
        PipelineState.PriorRun prior = s.prior().orElseThrow();

        List<Runnable> mutations = List.of(
                () -> s.ledger().add(unit),
                () -> s.candidates().clear(),
                () -> s.records().put("fp-x", rejected("fp-x", "r")),
                () -> s.survivorDrafts().clear(),
                () -> s.toolRuns().clear(),
                () -> s.stageTimingsMs().put("x", 1L),
                () -> s.fixAttemptsByFingerprint().put("x", 1),
                () -> s.notes().add("x"),
                () -> s.diffScope().add("x"),
                () -> unit.attempts().clear(),
                () -> unit.attempts().get(0).reviewedPaths().add("x"),
                () -> unit.reviewedPaths().add("x"),
                () -> unit.startingPaths().add("x"),
                () -> s.candidates().get(0).trace().add(sink()),
                () -> s.candidates().get(0).toolSources().clear(),
                () -> s.records().get("fp-a").trace().clear(),
                () -> s.records().get("fp-a").secpipe().cwe().add("x"),
                () -> s.survivorDrafts().get("fp-a").blockers().clear(),
                () -> s.config().disabledNodes().add("x"),
                () -> s.config().denyPaths().add("x"),
                () -> s.config().modelForRole().clear(),
                () -> s.environment().orElseThrow().toolsOnPath().put("x", Path.of("x")),
                () -> s.profile().orElseThrow().frameworks().add("x"),
                () -> s.profile().orElseThrow().sourceFiles().add("x"),
                () -> prior.ledger().clear(),
                () -> prior.ledger().get(0).settledBy().add("x"),
                () -> prior.findings().get(0).evidence().clear(),
                () -> prior.fileHashes().put("x", "y"));

        for (Runnable m : mutations) {
            assertThatThrownBy(m::run).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void aNodeCannotMutateItsInputAndOnlyItsReturnedDeltaChangesState() throws Exception {
        PipelineState before = populated();
        PipelineNode sneaky = new SneakyNode();

        PipelineNode.Result result = sneaky.apply(before, null);
        PipelineState after = applier.apply(before, result.delta());

        assertThat(before.notes()).containsExactly("n");
        assertThat(before.ledger()).hasSize(1);
        assertThat(after.notes()).containsExactly("n", "blocked: notes", "blocked: ledger", "blocked: records",
                "blocked: nested");
    }

    @Test
    void stateIsIsolatedFromTheCallersCollections() throws Exception {
        PipelineState raw = emptyState();
        PipelineState frozen = DeltaApplier.freeze(raw);
        raw.notes().add("late");
        raw.records().put("fp-late", rejected("fp-late", "r"));

        EngineFixtures.Delta d = delta().addCandidates(candidate("c1")).notes("n1");
        PipelineState applied = applier.apply(frozen, d.build());
        d.liveAddCandidates().add(candidate("c2"));
        d.liveNotes().add("n2");

        assertThat(frozen.notes()).isEmpty();
        assertThat(frozen.records()).isEmpty();
        assertThat(applied.candidates()).extracting(c -> c.title()).containsExactly("c1");
        assertThat(applied.notes()).containsExactly("n1");
    }

    /** Tries to write to every part of the state it is handed and reports each refusal as a note. */
    private static final class SneakyNode implements PipelineNode {
        @Override
        public String id() {
            return "sneaky";
        }

        @Override
        public boolean requiresLlm() {
            return false;
        }

        @Override
        public boolean usesSandbox() {
            return false;
        }

        @Override
        public Result apply(PipelineState state, RunContext ctx) {
            List<String> notes = new ArrayList<>();
            attempt(notes, "notes", () -> state.notes().add("smuggled"));
            attempt(notes, "ledger", () -> state.ledger().remove(0));
            attempt(notes, "records", () -> state.records().clear());
            attempt(notes, "nested", () -> state.ledger().get(0).attempts().clear());
            return Result.next(delta().notes(notes.toArray(String[]::new)).build());
        }

        private static void attempt(List<String> notes, String what, Runnable mutation) {
            try {
                mutation.run();
                notes.add("mutated: " + what);
            } catch (UnsupportedOperationException e) {
                notes.add("blocked: " + what);
            }
        }
    }
}
