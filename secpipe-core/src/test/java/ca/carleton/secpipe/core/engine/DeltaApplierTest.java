package ca.carleton.secpipe.core.engine;

import static ca.carleton.secpipe.core.engine.EngineFixtures.candidate;
import static ca.carleton.secpipe.core.engine.EngineFixtures.delta;
import static ca.carleton.secpipe.core.engine.EngineFixtures.draft;
import static ca.carleton.secpipe.core.engine.EngineFixtures.emptyState;
import static ca.carleton.secpipe.core.engine.EngineFixtures.environment;
import static ca.carleton.secpipe.core.engine.EngineFixtures.profile;
import static ca.carleton.secpipe.core.engine.EngineFixtures.rejected;
import static ca.carleton.secpipe.core.engine.EngineFixtures.toolRun;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.validators.ValidationError;
import ca.carleton.secpipe.validators.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Scalar, candidate, record and timing merge rules of {@link DeltaApplier}, plus delta rejection. */
class DeltaApplierTest {

    private final DeltaApplier applier = new DeltaApplier(EngineValidators.acceptAll());

    @Test
    void emptyDeltaKeepsTheStateValues() throws Exception {
        PipelineState start = DeltaApplier.freeze(emptyState());

        PipelineState next = applier.apply(start, StateDelta.empty());

        assertThat(next).isEqualTo(start);
    }

    @Test
    void presentScalarsReplaceAndAbsentScalarsAreKept() throws Exception {
        PipelineState start = applier.apply(DeltaApplier.freeze(emptyState()),
                delta().hunterWave(2).criticPasses(1).diffScope(List.of("a.java")).build());

        PipelineState next = applier.apply(start, delta().environment(environment()).profile(profile())
                .criticSaysStop(true).hunterWave(3).build());

        assertThat(next.environment()).contains(environment());
        assertThat(next.profile().map(p -> p.language())).contains("java");
        assertThat(next.hunterWave()).isEqualTo(3);
        assertThat(next.criticPasses()).isEqualTo(1);
        assertThat(next.criticSaysStop()).isTrue();
        assertThat(next.diffScope()).containsExactly("a.java");
        assertThat(next.runId()).isEqualTo("run-1");
    }

    @Test
    void notesAndToolRunsAppendAndTimingsAccumulateSortedByStage() throws Exception {
        PipelineState s = DeltaApplier.freeze(emptyState());
        s = applier.apply(s, delta().notes("first").toolRuns(toolRun("semgrep")).timing("hunt", 10)
                .timing("detect", 5).build());
        s = applier.apply(s, delta().notes("second").toolRuns(toolRun("pmd")).timing("hunt", 7)
                .timing("critic", 3).build());

        assertThat(s.notes()).containsExactly("first", "second");
        assertThat(s.toolRuns()).extracting(r -> r.id()).containsExactly("semgrep", "pmd");
        assertThat(s.stageTimingsMs()).containsExactly(Map.entry("critic", 3L), Map.entry("detect", 5L),
                Map.entry("hunt", 17L));
    }

    @Test
    void firstIncompleteReasonWinsAndLaterOnesBecomeNotes() throws Exception {
        PipelineState s = DeltaApplier.freeze(emptyState());
        s = applier.apply(s, delta().incomplete("node hunt failed: boom").build());
        s = applier.apply(s, delta().incomplete("node critic failed: bang").build());

        assertThat(s.incompleteReason()).contains("node hunt failed: boom");
        assertThat(s.notes()).containsExactly("additional incomplete reason: node critic failed: bang");
    }

    @Test
    void replaceCandidatesThenAddCandidatesInOrder() throws Exception {
        PipelineState s = applier.apply(DeltaApplier.freeze(emptyState()),
                delta().addCandidates(candidate("old-1"), candidate("old-2")).build());

        s = applier.apply(s, delta().replaceCandidates(new ArrayList<>(List.of(candidate("normalized"))))
                .addCandidates(candidate("extra")).build());

        assertThat(s.candidates()).extracting(c -> c.title()).containsExactly("normalized", "extra");
    }

    @Test
    void putRecordsInsertOrReplaceByFingerprintAndAreSorted() throws Exception {
        PipelineState s = applier.apply(DeltaApplier.freeze(emptyState()),
                delta().putRecords(rejected("fp-b", "first"), rejected("fp-a", "only")).build());

        s = applier.apply(s, delta().putRecords(rejected("fp-b", "second")).build());

        assertThat(s.records().keySet()).containsExactly("fp-a", "fp-b");
        assertThat(((Finding.Rejected) s.records().get("fp-b")).reason()).isEqualTo("second");
    }

    @Test
    void survivorDraftsAndFixAttemptsPutByKeyAndSort() throws Exception {
        PipelineState s = applier.apply(DeltaApplier.freeze(emptyState()),
                delta().putDraft("fp-z", draft("fp-z")).putDraft("fp-a", draft("fp-a"))
                        .fixAttempts("fp-z", 1).build());
        s = applier.apply(s, delta().fixAttempts("fp-z", 2).fixAttempts("fp-c", 1).build());

        assertThat(s.survivorDrafts().keySet()).containsExactly("fp-a", "fp-z");
        assertThat(s.fixAttemptsByFingerprint()).containsExactly(Map.entry("fp-c", 1), Map.entry("fp-z", 2));
    }

    @Test
    void findingsValidatorSeesAllRecordsSortedAndItsFailureRejectsTheWholeDelta() throws Exception {
        AtomicReference<List<String>> seen = new AtomicReference<>();
        EngineValidators validators = new EngineValidators(records -> {
            seen.set(records.stream().map(Finding::fingerprint).toList());
            return records.size() < 3 ? ValidationResult.valid()
                    : ValidationResult.of(List.of(ValidationError.of("F3", "/2", "too many records")));
        }, (before, after) -> ValidationResult.valid());
        DeltaApplier strict = new DeltaApplier(validators);
        PipelineState start = strict.apply(DeltaApplier.freeze(emptyState()),
                delta().putRecords(rejected("fp-b", "r"), rejected("fp-a", "r")).build());
        assertThat(seen.get()).containsExactly("fp-a", "fp-b");

        StateDelta bad = delta().putRecords(rejected("fp-c", "r")).hunterWave(9).notes("lost").build();

        assertThatThrownBy(() -> strict.apply(start, bad))
                .isInstanceOf(DeltaRejectedException.class)
                .hasMessageContaining("findings validator: /2: too many records [F3]")
                .satisfies(e -> {
                    DeltaRejectedException r = (DeltaRejectedException) e;
                    assertThat(r.validation()).isPresent();
                    assertThat(r.incompleteReason("score"))
                            .startsWith("validator rejected delta from node score: findings validator:");
                });
        assertThat(start.records().keySet()).containsExactly("fp-a", "fp-b");
        assertThat(start.hunterWave()).isZero();
        assertThat(start.notes()).isEmpty();
    }

    @Test
    void findingsValidatorRunsOnlyWhenRecordsChange() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DeltaApplier counting = new DeltaApplier(new EngineValidators(records -> {
            calls.incrementAndGet();
            return ValidationResult.valid();
        }, (before, after) -> ValidationResult.valid()));

        PipelineState s = counting.apply(DeltaApplier.freeze(emptyState()), delta().notes("n").build());
        assertThat(calls).hasValue(0);
        counting.apply(s, delta().putRecords(rejected("fp-a", "r")).build());
        assertThat(calls).hasValue(1);
    }

    @Test
    void malformedDeltasAreRejectedAsEngineProblems() {
        PipelineState start = DeltaApplier.freeze(emptyState());
        StateDelta nullField = new StateDelta(Optional.empty(), Optional.empty(), Optional.empty(), List.of(), null,
                Optional.empty(), List.of(), List.of(), Map.of(), List.of(), Map.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), Map.of(), Optional.empty(), List.of(), Optional.empty());
        List<String> scopeWithNull = new ArrayList<>();
        scopeWithNull.add(null);

        assertThatThrownBy(() -> applier.apply(start, null)).isInstanceOf(DeltaRejectedException.class)
                .hasMessage("node returned no delta");
        assertThatThrownBy(() -> applier.apply(start, nullField)).isInstanceOf(DeltaRejectedException.class)
                .hasMessage("delta field ledgerUpdates is null");
        assertThatThrownBy(() -> applier.apply(start, delta().diffScope(scopeWithNull).build()))
                .isInstanceOf(DeltaRejectedException.class).hasMessageStartingWith("delta contains a null value");
        assertThatThrownBy(() -> applier.apply(start, delta().putRecords(rejected(" ", "r")).build()))
                .isInstanceOf(DeltaRejectedException.class).hasMessageStartingWith("record without a fingerprint")
                .satisfies(e -> assertThat(((DeltaRejectedException) e).incompleteReason("refute"))
                        .startsWith("engine rejected delta from node refute: "));
    }

    @Test
    void engineValidatorsRequireBothFunctions() {
        assertThatThrownBy(() -> new EngineValidators(null, (b, a) -> ValidationResult.valid()))
                .isInstanceOf(NullPointerException.class).hasMessage("findings");
        assertThatThrownBy(() -> new EngineValidators(r -> ValidationResult.valid(), null))
                .isInstanceOf(NullPointerException.class).hasMessage("ledgerTransition");
    }
}
