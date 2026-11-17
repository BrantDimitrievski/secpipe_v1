package ca.carleton.secpipe.core.engine;

import static ca.carleton.secpipe.core.engine.EngineFixtures.delta;
import static ca.carleton.secpipe.core.engine.EngineFixtures.emptyState;
import static ca.carleton.secpipe.core.engine.EngineFixtures.unit;
import static ca.carleton.secpipe.core.engine.EngineFixtures.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit.Attempt.AttemptStatus;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit.Status;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.validators.ValidationError;
import ca.carleton.secpipe.validators.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Ledger writes through {@link DeltaApplier} and the minimal {@code LedgerStore}. */
class DeltaApplierLedgerTest {

    private final DeltaApplier applier = new DeltaApplier(EngineValidators.acceptAll());

    private PipelineState withUnits(LedgerUnit... units) throws DeltaRejectedException {
        return applier.apply(DeltaApplier.freeze(emptyState()), delta().newUnits(units).build());
    }

    @Test
    void newUnitsAreAddedSortedByCoverageIdAndValidatedWithBeforeAndAfter() throws Exception {
        List<List<String>> calls = new ArrayList<>();
        DeltaApplier recording = new DeltaApplier(new EngineValidators(r -> ValidationResult.valid(),
                (before, after) -> {
                    calls.add(before.stream().map(LedgerUnit::coverageId).toList());
                    calls.add(after.stream().map(LedgerUnit::coverageId).toList());
                    return ValidationResult.valid();
                }));

        PipelineState s = recording.apply(DeltaApplier.freeze(emptyState()),
                delta().newUnits(unit("u-c", Status.PLANNED), unit("u-a", Status.PLANNED)).build());
        s = recording.apply(s, delta().newUnits(unit("u-b", Status.PLANNED)).build());

        assertThat(s.ledger()).extracting(LedgerUnit::coverageId).containsExactly("u-a", "u-b", "u-c");
        assertThat(calls).containsExactly(List.of(), List.of("u-a", "u-c"), List.of("u-a", "u-c"),
                List.of("u-a", "u-b", "u-c"));
    }

    @Test
    void duplicateOrUnknownCoverageIdsAreRejected() throws Exception {
        PipelineState s = withUnits(unit("u-a", Status.PLANNED));

        assertThatThrownBy(() -> applier.apply(s, delta().newUnits(unit("u-a", Status.PLANNED)).build()))
                .isInstanceOf(DeltaRejectedException.class).hasMessage("duplicate coverage_id u-a");
        assertThatThrownBy(() -> applier.apply(s,
                delta().updates(update("u-x", Status.COVERED, Optional.empty(), List.of(), Optional.empty())).build()))
                .isInstanceOf(DeltaRejectedException.class).hasMessage("ledger update for unknown coverage_id u-x");
    }

    @Test
    void assignmentSetsAgentWithoutArchivingAnAttempt() throws Exception {
        PipelineState s = withUnits(unit("u-a", Status.PLANNED));

        s = applier.apply(s, delta().updates(update("u-a", Status.IN_PROGRESS, Optional.of("hunter-1"), List.of(),
                Optional.empty())).build());

        LedgerUnit u = s.ledger().get(0);
        assertThat(u.status()).isEqualTo(Status.IN_PROGRESS);
        assertThat(u.agentId()).contains("hunter-1");
        assertThat(u.attempts()).isEmpty();
    }

    @Test
    void settlementMapsFieldsAndAppendsAnAttempt() throws Exception {
        PipelineState s = withUnits(unit("u-a", Status.IN_PROGRESS));
        LedgerUnit.Update settle = new LedgerUnit.Update("u-a", Status.CANDIDATE, Optional.of("hunter-1"),
                List.of("src/main/java/A.java"), List.of("checked sanitizer"), List.of("fp-1"), List.of(),
                List.of(), Optional.empty());

        s = applier.apply(s, delta().updates(settle).build());

        LedgerUnit u = s.ledger().get(0);
        assertThat(u.status()).isEqualTo(Status.CANDIDATE);
        assertThat(u.reviewedPaths()).containsExactly("src/main/java/A.java");
        assertThat(u.localChecks()).containsExactly("checked sanitizer");
        assertThat(u.resultFingerprints()).containsExactly("fp-1");
        assertThat(u.attempts()).singleElement().satisfies(a -> {
            assertThat(a.agentId()).isEqualTo("hunter-1");
            assertThat(a.status()).isEqualTo(AttemptStatus.CANDIDATE);
            assertThat(a.wave()).isEqualTo(1);
            assertThat(a.resultFingerprints()).containsExactly("fp-1");
        });
    }

    @Test
    void toolSettlementWithoutAgentKeepsAttemptsAndEmptyListsKeepValues() throws Exception {
        PipelineState s = withUnits(unit("u-a", Status.PLANNED));
        s = applier.apply(s, delta().updates(update("u-a", Status.IN_PROGRESS, Optional.empty(),
                List.of("src/main/java/A.java"), Optional.empty())).build());

        s = applier.apply(s, delta().updates(new LedgerUnit.Update("u-a", Status.COVERED, Optional.empty(),
                List.of(), List.of(), List.of(), List.of(), List.of("semgrep"), Optional.empty())).build());

        LedgerUnit u = s.ledger().get(0);
        assertThat(u.status()).isEqualTo(Status.COVERED);
        assertThat(u.reviewedPaths()).containsExactly("src/main/java/A.java");
        assertThat(u.settledBy()).containsExactly("semgrep");
        assertThat(u.attempts()).isEmpty();
    }

    @Test
    void reassignmentToPlannedArchivesThePreviousResultAndClearsTheAgent() throws Exception {
        PipelineState s = withUnits(unit("u-a", Status.IN_PROGRESS));
        s = applier.apply(s, delta().updates(update("u-a", Status.COVERED, Optional.of("hunter-1"),
                List.of("A.java"), Optional.empty())).build());

        s = applier.apply(s, delta().updates(update("u-a", Status.PLANNED, Optional.of("hunter-1"), List.of(),
                Optional.of("critic: shallow review"))).build());

        LedgerUnit u = s.ledger().get(0);
        assertThat(u.status()).isEqualTo(Status.PLANNED);
        assertThat(u.agentId()).isEmpty();
        assertThat(u.attempts()).hasSize(2);
        assertThat(u.attempts().get(1).status()).isEqualTo(AttemptStatus.COVERED);
        assertThat(u.attempts().get(1).archivedReason()).contains("critic: shallow review");
    }

    @Test
    void newUnitsAreAppliedBeforeUpdatesInTheSameDelta() throws Exception {
        PipelineState s = applier.apply(DeltaApplier.freeze(emptyState()), delta()
                .newUnits(unit("u-new", Status.PLANNED))
                .updates(update("u-new", Status.IN_PROGRESS, Optional.of("hunter-2"), List.of(), Optional.empty()))
                .build());

        assertThat(s.ledger()).singleElement().extracting(LedgerUnit::status).isEqualTo(Status.IN_PROGRESS);
    }

    @Test
    void ledgerValidatorFailureRejectsTheWholeDeltaWithItsMessage() throws Exception {
        DeltaApplier strict = new DeltaApplier(new EngineValidators(r -> ValidationResult.valid(),
                (before, after) -> after.stream().anyMatch(u -> u.status() == Status.COVERED
                        && u.reviewedPaths().isEmpty())
                        ? ValidationResult.of(List.of(ValidationError.of("L4", "/0/reviewed_paths",
                                "covered unit has no reviewed paths")))
                        : ValidationResult.valid()));
        PipelineState start = strict.apply(DeltaApplier.freeze(emptyState()),
                delta().newUnits(unit("u-a", Status.IN_PROGRESS)).build());

        assertThatThrownBy(() -> strict.apply(start, delta().hunterWave(4).notes("lost")
                .updates(update("u-a", Status.COVERED, Optional.of("hunter-1"), List.of(), Optional.empty()))
                .build()))
                .isInstanceOf(DeltaRejectedException.class)
                .satisfies(e -> assertThat(((DeltaRejectedException) e).incompleteReason("hunt")).isEqualTo(
                        "validator rejected delta from node hunt: ledger validator: "
                                + "/0/reviewed_paths: covered unit has no reviewed paths [L4]"));
        assertThat(start.ledger().get(0).status()).isEqualTo(Status.IN_PROGRESS);
        assertThat(start.hunterWave()).isZero();
        assertThat(start.notes()).isEmpty();
    }
}
