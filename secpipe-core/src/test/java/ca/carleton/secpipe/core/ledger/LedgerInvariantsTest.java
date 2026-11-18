package ca.carleton.secpipe.core.ledger;

import static ca.carleton.secpipe.core.ledger.LedgerFixtures.AGENT;
import static ca.carleton.secpipe.core.ledger.LedgerFixtures.attempt;
import static ca.carleton.secpipe.core.ledger.LedgerFixtures.in;
import static ca.carleton.secpipe.core.ledger.LedgerFixtures.planned;
import static ca.carleton.secpipe.core.ledger.LedgerFixtures.with;
import static ca.carleton.secpipe.core.ledger.LedgerFixtures.withAttempts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit.Attempt.AttemptStatus;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit.Status;
import ca.carleton.secpipe.validators.ValidationError;
import ca.carleton.secpipe.validators.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** {@link LedgerInvariants}: one passing and one failing case per rule L1–L6. */
class LedgerInvariantsTest {

    private static List<String> codes(ValidationResult r) {
        return r.errors().stream().map(ValidationError::code).distinct().toList();
    }

    private static LedgerUnit renamed(LedgerUnit u, String coverageId) {
        return new LedgerUnit(coverageId, u.canonicalRefs(), u.surface(), u.boundary(), u.subsystem(),
                u.attackClass(), u.cwe(), u.startingPaths(), u.ordinaryAttackClassBlock(), u.selectedCompanionBlocks(),
                u.excludedBlocks(), u.priorStatus(), u.attempts(), u.wave(), u.status(), u.agentId(), u.reviewedPaths(),
                u.localChecks(), u.resultFingerprints(), u.unresolved(), u.settledBy());
    }

    @Test
    void l1PassesForDerivedSortedUniqueIds() {
        List<LedgerUnit> ledger = LedgerStore.sorted(List.of(planned("a"), planned("b"), planned("c")));
        assertTrue(LedgerInvariants.check(List.of(), ledger).ok());
    }

    @Test
    void l1FailsForWrongIdDuplicateAndUnsortedLedger() {
        LedgerUnit wrongId = renamed(planned("a"), "cov:00000000000000000000000000000000");
        assertEquals(List.of("L1"), codes(LedgerInvariants.check(List.of(), List.of(wrongId))));

        List<LedgerUnit> sorted = LedgerStore.sorted(List.of(planned("a"), planned("b")));
        assertEquals(List.of("L1"), codes(LedgerInvariants.check(List.of(), sorted.reversed())));
        assertEquals(List.of("L1"), codes(LedgerInvariants.check(List.of(), List.of(planned("a"), planned("a")))));
    }

    @Test
    void l2PassesForEveryStatusInItsValidShape() {
        for (Status s : Status.values()) {
            LedgerUnit u = in(planned("a"), s);
            assertEquals(List.of(), LedgerInvariants.checkL2(u, "/0"), s.toString());
        }
    }

    @Test
    void l2FailsForEachStatusRowViolation() {
        LedgerUnit p = planned("a");
        List<LedgerUnit> bad = List.of(
                with(p, Status.PLANNED, Optional.of(AGENT), List.of(), List.of(), List.of(), List.of(), List.of()),
                with(p, Status.IN_PROGRESS, Optional.empty(), List.of(), List.of(), List.of(), List.of(), List.of()),
                // L2: a covered unit has no result_fingerprints
                with(p, Status.COVERED, Optional.of(AGENT), List.of("A.java"), List.of("c"), List.of("code:x"),
                        List.of(), List.of()),
                with(p, Status.COVERED, Optional.empty(), List.of("A.java"), List.of("c"), List.of(), List.of(),
                        List.of()),
                with(p, Status.CANDIDATE, Optional.of(AGENT), List.of("A.java"), List.of(), List.of("code:x"),
                        List.of(), List.of()),
                with(p, Status.BLOCKED, Optional.of(AGENT), List.of(), List.of(), List.of(), List.of(), List.of()),
                with(p, Status.DEFERRED, Optional.empty(), List.of(), List.of(), List.of(), List.of(), List.of()),
                with(p, Status.NOT_APPLICABLE, Optional.of(AGENT), List.of(), List.of(), List.of(), List.of("r"),
                        List.of()));
        for (LedgerUnit u : bad) {
            List<ValidationError> errors = LedgerInvariants.checkL2(u, "/0");
            assertFalse(errors.isEmpty(), u.status() + " " + u);
            assertTrue(errors.stream().allMatch(e -> e.code().equals("L2")));
        }
        assertEquals("/0/result_fingerprints", LedgerInvariants.checkL2(bad.get(2), "/0").get(0).path());
    }

    @Test
    void l2PerDeltaAcceptsACandidateWithoutFingerprintsBeforeNormalize() {
        LedgerUnit settled = with(planned("a"), Status.CANDIDATE, Optional.empty(), List.of("A.java"),
                List.of("semgrep:sqli"), List.of(), List.of(), List.of("semgrep"));
        assertTrue(LedgerInvariants.check(List.of(planned("a")), List.of(settled)).ok());
    }

    @Test
    void l2PerDeltaStillRequiresReviewedPathsAndLocalChecksForCandidate() {
        LedgerUnit noPaths = with(planned("a"), Status.CANDIDATE, Optional.empty(), List.of(), List.of("c"),
                List.of(), List.of(), List.of("semgrep"));
        LedgerUnit noChecks = with(planned("a"), Status.CANDIDATE, Optional.empty(), List.of("A.java"), List.of(),
                List.of(), List.of(), List.of("semgrep"));
        ValidationResult r1 = LedgerInvariants.check(List.of(planned("a")), List.of(noPaths));
        ValidationResult r2 = LedgerInvariants.check(List.of(planned("a")), List.of(noChecks));
        assertEquals(List.of("L2"), codes(r1));
        assertEquals("/0/reviewed_paths", r1.errors().get(0).path());
        assertEquals(List.of("L2"), codes(r2));
        assertEquals("/0/local_checks", r2.errors().get(0).path());
    }

    @Test
    void l2FinalPassesWhenEveryCandidateHasAFingerprint() {
        List<LedgerUnit> ledger = List.of(in(planned("a"), Status.CANDIDATE), in(planned("b"), Status.COVERED));
        assertTrue(LedgerInvariants.checkFinal(ledger).ok());
    }

    @Test
    void l2FinalFailsForACandidateWithoutFingerprints() {
        LedgerUnit bare = with(planned("a"), Status.CANDIDATE, Optional.empty(), List.of("A.java"), List.of("c"),
                List.of(), List.of(), List.of("semgrep"));
        ValidationResult r = LedgerInvariants.checkFinal(List.of(bare));
        assertEquals(List.of("L2"), codes(r));
        assertEquals("/0/result_fingerprints", r.errors().get(0).path());
        assertEquals(Optional.empty(), LedgerInvariants.incompleteReason(List.of(bare)));
    }

    @Test
    void l3PassesForAnAllowedTransition() {
        LedgerUnit before = in(planned("a"), Status.PLANNED);
        LedgerUnit after = in(planned("a"), Status.IN_PROGRESS);
        assertTrue(LedgerInvariants.check(List.of(before), List.of(after)).ok());
    }

    @Test
    void l3IllegalTransitionReturnsFailingResultNamingTheRule() {
        LedgerUnit before = in(planned("a"), Status.CANDIDATE);
        LedgerUnit after = in(planned("a"), Status.COVERED);

        ValidationResult r = LedgerInvariants.check(List.of(before), List.of(after));

        assertFalse(r.ok());
        assertEquals(List.of("L3"), codes(r));
        assertTrue(r.errors().get(0).message().contains("candidate -> covered"), r.errors().get(0).message());
    }

    @Test
    void l3RejectsRemovedUnitsAndNewUnitsThatAreNotPlannedOrToolSettled() {
        assertEquals(List.of("L3"), codes(LedgerInvariants.check(List.of(planned("a")), List.of())));
        assertEquals(List.of("L3"), codes(LedgerInvariants.check(List.of(),
                List.of(in(planned("a"), Status.IN_PROGRESS)))));
        LedgerUnit settled = with(planned("a"), Status.COVERED, Optional.empty(), List.of("A.java"), List.of("c"),
                List.of(), List.of(), List.of("semgrep"));
        assertTrue(LedgerInvariants.check(List.of(), List.of(settled)).ok());
    }

    @Test
    void l4PassesWhenAttemptsOnlyGrow() {
        LedgerUnit before = withAttempts(in(planned("a"), Status.BLOCKED),
                List.of(attempt(AGENT, AttemptStatus.BLOCKED)));
        LedgerUnit after = withAttempts(in(planned("a"), Status.IN_PROGRESS),
                List.of(attempt(AGENT, AttemptStatus.BLOCKED), attempt(AGENT, AttemptStatus.BLOCKED)));
        assertTrue(LedgerInvariants.check(List.of(before), List.of(after)).ok());
    }

    @Test
    void l4FailsWhenAnAttemptIsRewrittenOrDropped() {
        LedgerUnit before = withAttempts(in(planned("a"), Status.BLOCKED),
                List.of(attempt(AGENT, AttemptStatus.BLOCKED)));
        LedgerUnit after = withAttempts(in(planned("a"), Status.IN_PROGRESS), List.of());
        assertEquals(List.of("L4"), codes(LedgerInvariants.check(List.of(before), List.of(after))));
    }

    @Test
    void l5PassesWhenEveryFingerprintIsKnownAndFailsOtherwise() {
        List<LedgerUnit> ledger = List.of(in(planned("a"), Status.CANDIDATE));
        assertTrue(LedgerInvariants.checkFingerprints(ledger, Set.of("code:CWE-89:fp1")).ok());

        ValidationResult r = LedgerInvariants.checkFingerprints(ledger, Set.of("code:CWE-89:other"));
        assertEquals(List.of("L5"), codes(r));
        assertEquals("/0/result_fingerprints/0", r.errors().get(0).path());
    }

    @Test
    void l6PassesWithNoUnassignedUnits() {
        List<LedgerUnit> ledger = List.of(in(planned("a"), Status.COVERED), in(planned("b"), Status.DEFERRED));
        assertTrue(LedgerInvariants.checkTerminal(ledger).ok());
        assertEquals(Optional.empty(), LedgerInvariants.incompleteReason(ledger));
    }

    @Test
    void l6FailsWithPlannedOrInProgressUnits() {
        List<LedgerUnit> ledger = List.of(planned("a"), in(planned("b"), Status.IN_PROGRESS),
                in(planned("c"), Status.COVERED));
        assertEquals(List.of("L6"), codes(LedgerInvariants.checkTerminal(ledger)));
        assertEquals(Optional.of("unassigned ledger units: 2"), LedgerInvariants.incompleteReason(ledger));
    }
}
