package ca.carleton.secpipe.core.ledger;

import static ca.carleton.secpipe.core.ledger.LedgerFixtures.AGENT;
import static ca.carleton.secpipe.core.ledger.LedgerFixtures.AGENT_2;
import static ca.carleton.secpipe.core.ledger.LedgerFixtures.in;
import static ca.carleton.secpipe.core.ledger.LedgerFixtures.planned;
import static ca.carleton.secpipe.core.ledger.LedgerFixtures.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit.Attempt.AttemptStatus;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit.Status;
import ca.carleton.secpipe.core.ledger.LedgerStore.LedgerRejectedException;
import ca.carleton.secpipe.validators.ValidationError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@link LedgerStore} with {@link LedgerInvariants#check} injected: every legal and illegal L3 transition. */
class LedgerStoreTest {

    private final LedgerStore store = new LedgerStore(LedgerInvariants::check);

    private LedgerUnit applyOne(LedgerUnit start, LedgerUnit.Update update) throws LedgerRejectedException {
        List<LedgerUnit> out = store.apply(List.of(start), List.of(update));
        assertEquals(1, out.size());
        return out.get(0);
    }

    private static List<String> codes(LedgerRejectedException e) {
        return e.validation().orElseThrow().errors().stream().map(ValidationError::code).toList();
    }

    @Test
    void plannedToInProgressAssignsWithoutAttempt() throws Exception {
        LedgerUnit u = applyOne(planned("a"), LedgerTransitions.assign(planned("a").coverageId(), AGENT));
        assertEquals(Status.IN_PROGRESS, u.status());
        assertEquals(Optional.of(AGENT), u.agentId());
        assertTrue(u.attempts().isEmpty());
    }

    @Test
    void plannedToCoveredOrCandidateOnlyWithSettledBy() throws Exception {
        String id = planned("a").coverageId();
        LedgerUnit covered = applyOne(planned("a"), LedgerTransitions.toolSettled(id, Status.COVERED,
                List.of("semgrep"), List.of("src/main/java/A.java"), List.of("parameterized"), List.of()));
        assertEquals(Status.COVERED, covered.status());
        assertTrue(covered.attempts().isEmpty());

        LedgerUnit candidate = applyOne(planned("a"), LedgerTransitions.toolSettled(id, Status.CANDIDATE,
                List.of("semgrep"), List.of("src/main/java/A.java"), List.of("tainted"), List.of("code:CWE-89:x")));
        assertEquals(Status.CANDIDATE, candidate.status());

        LedgerRejectedException e = assertThrows(LedgerRejectedException.class, () -> applyOne(planned("a"),
                LedgerTransitions.covered(id, AGENT, List.of("src/main/java/A.java"), List.of("c"))));
        assertEquals(List.of("L3"), codes(e));
        assertTrue(e.getMessage().contains("requires settled_by"), e.getMessage());
    }

    @Test
    void plannedToClosedStatusesNeedAReason() throws Exception {
        for (Status s : List.of(Status.DEFERRED, Status.OUT_OF_SCOPE, Status.NOT_APPLICABLE)) {
            LedgerUnit u = applyOne(planned("a"), LedgerTransitions.close(planned("a").coverageId(), s, "generated"));
            assertEquals(s, u.status());
            assertEquals(List.of("generated"), u.unresolved());
            assertTrue(u.agentId().isEmpty());
        }
    }

    @Test
    void inProgressSettlesToCoveredCandidateOrBlockedAndAppendsAttempts() throws Exception {
        LedgerUnit start = in(planned("a"), Status.IN_PROGRESS);
        String id = start.coverageId();

        LedgerUnit covered = applyOne(start, LedgerTransitions.covered(id, AGENT, List.of("src/main/java/A.java"),
                List.of("prepared statement")));
        assertEquals(Status.COVERED, covered.status());
        assertEquals(AttemptStatus.COVERED, covered.attempts().get(0).status());

        LedgerUnit candidate = applyOne(start, LedgerTransitions.candidate(id, AGENT, List.of("src/main/java/A.java"),
                List.of("no sanitizer"), List.of("code:CWE-89:x"), List.of()));
        assertEquals(Status.CANDIDATE, candidate.status());
        assertEquals(List.of("code:CWE-89:x"), candidate.attempts().get(0).resultFingerprints());

        LedgerUnit blocked = applyOne(start, LedgerTransitions.blocked(id, AGENT, List.of("docker missing")));
        assertEquals(Status.BLOCKED, blocked.status());
        assertEquals(AttemptStatus.BLOCKED, blocked.attempts().get(0).status());
    }

    @Test
    void inProgressReassignmentArchivesAnAttemptAndClearsTheUnit() throws Exception {
        LedgerUnit start = with(planned("a"), Status.IN_PROGRESS, Optional.of(AGENT), List.of("src/main/java/A.java"),
                List.of("partial"), List.of(), List.of("timed out"), List.of());

        LedgerUnit u = applyOne(start, LedgerTransitions.reassign(start.coverageId(), AGENT, "agent timed out"));

        assertEquals(Status.PLANNED, u.status());
        assertTrue(u.agentId().isEmpty());
        assertTrue(u.reviewedPaths().isEmpty() && u.localChecks().isEmpty() && u.unresolved().isEmpty());
        LedgerUnit.Attempt a = u.attempts().get(0);
        assertEquals(AttemptStatus.BLOCKED, a.status());
        assertEquals(List.of("src/main/java/A.java"), a.reviewedPaths());
        assertEquals(Optional.of("agent timed out"), a.archivedReason());
    }

    @Test
    void reassignmentWithoutArchiveReasonIsRejected() {
        LedgerUnit start = in(planned("a"), Status.IN_PROGRESS);
        LedgerUnit.Update noReason = new LedgerUnit.Update(start.coverageId(), Status.PLANNED, Optional.of(AGENT),
                List.of(), List.of(), List.of(), List.of(), List.of(), Optional.empty());

        LedgerRejectedException e = assertThrows(LedgerRejectedException.class, () -> applyOne(start, noReason));
        assertEquals(List.of("L3"), codes(e));
        assertTrue(e.getMessage().contains("requires an archived attempt"), e.getMessage());
    }

    @Test
    void blockedGoesBackToInProgressOrToDeferred() throws Exception {
        LedgerUnit start = in(planned("a"), Status.BLOCKED);

        LedgerUnit again = applyOne(start, LedgerTransitions.assign(start.coverageId(), AGENT_2));
        assertEquals(Status.IN_PROGRESS, again.status());
        assertEquals(Optional.of(AGENT_2), again.agentId());

        LedgerUnit deferred = applyOne(start,
                LedgerTransitions.close(start.coverageId(), Status.DEFERRED, "no docker"));
        assertEquals(Status.DEFERRED, deferred.status());
        assertTrue(deferred.agentId().isEmpty());
    }

    @Test
    void coveredCriticReassignmentArchivesAndClearsListsAndSettledBy() throws Exception {
        LedgerUnit start = with(planned("a"), Status.COVERED, Optional.of(AGENT), List.of("src/main/java/A.java"),
                List.of("ok"), List.of(), List.of(), List.of("semgrep"));

        LedgerUnit u = applyOne(start, LedgerTransitions.reassign(start.coverageId(), AGENT, "critic: shallow"));

        assertEquals(Status.PLANNED, u.status());
        assertTrue(u.settledBy().isEmpty() && u.reviewedPaths().isEmpty() && u.localChecks().isEmpty());
        assertEquals(AttemptStatus.COVERED, u.attempts().get(0).status());
        assertEquals(Optional.of("critic: shallow"), u.attempts().get(0).archivedReason());
    }

    @Test
    void candidateStaysCandidateOnlyWhenFingerprintsGrow() throws Exception {
        LedgerUnit start = in(planned("a"), Status.CANDIDATE);
        String id = start.coverageId();

        LedgerUnit grown = applyOne(start, LedgerTransitions.candidate(id, AGENT, List.of(), List.of(),
                List.of("code:CWE-89:fp1", "code:CWE-89:fp2"), List.of()));
        assertEquals(2, grown.resultFingerprints().size());

        LedgerRejectedException e = assertThrows(LedgerRejectedException.class, () -> applyOne(start,
                LedgerTransitions.candidate(id, AGENT, List.of(), List.of(), List.of("code:CWE-89:fp1"), List.of())));
        assertEquals(List.of("L3"), codes(e));
    }

    @Test
    void everyUnlistedTransitionIsRejectedNamingL3() {
        int rejected = 0;
        for (Status from : Status.values()) {
            for (Status to : Status.values()) {
                if (LedgerTransitions.isListed(from, to)) {
                    continue;
                }
                LedgerUnit start = in(planned("a"), from);
                LedgerUnit.Update upd = new LedgerUnit.Update(start.coverageId(), to, Optional.of(AGENT_2),
                        List.of("src/main/java/B.java"), List.of("x"), List.of(), List.of("why"), List.of(),
                        Optional.of("reason"));
                LedgerRejectedException e = assertThrows(LedgerRejectedException.class, () -> applyOne(start, upd),
                        from + " -> " + to);
                assertEquals(List.of("L3"), codes(e), from + " -> " + to);
                assertTrue(e.getMessage().contains("illegal ledger transition"), e.getMessage());
                rejected++;
            }
        }
        assertEquals(64 - 14, rejected);
    }

    @Test
    void emptyListsKeepValuesExceptWhereL2RequiresClearing() throws Exception {
        LedgerUnit start = with(planned("a"), Status.IN_PROGRESS, Optional.of(AGENT), List.of("src/main/java/A.java"),
                List.of("checked"), List.of(), List.of("open question"), List.of());

        LedgerUnit u = applyOne(start, new LedgerUnit.Update(start.coverageId(), Status.COVERED, Optional.of(AGENT),
                List.of(), List.of(), List.of(), List.of(), List.of(), Optional.empty()));

        assertEquals(List.of("src/main/java/A.java"), u.reviewedPaths());
        assertEquals(List.of("checked"), u.localChecks());
        assertTrue(u.unresolved().isEmpty(), "covered must clear unresolved (L2)");
    }

    @Test
    void forbiddenNonEmptyListIsKeptSoTheInvariantsRejectIt() {
        LedgerUnit start = in(planned("a"), Status.IN_PROGRESS);
        LedgerUnit.Update bad = new LedgerUnit.Update(start.coverageId(), Status.COVERED, Optional.of(AGENT),
                List.of("src/main/java/A.java"), List.of("c"), List.of("code:CWE-89:x"), List.of(), List.of(),
                Optional.empty());

        LedgerRejectedException e = assertThrows(LedgerRejectedException.class, () -> applyOne(start, bad));
        assertEquals(List.of("L2"), codes(e));
        assertTrue(e.getMessage().contains("result_fingerprints"), e.getMessage());
    }

    @Test
    void ledgerRoundTripsThroughCoverageLedgerJsonSortedAndByteStable(@TempDir Path dir) throws Exception {
        List<LedgerUnit> ledger = new ArrayList<>(List.of(in(planned("z"), Status.CANDIDATE),
                in(planned("b"), Status.COVERED), planned("m")));
        Path file = dir.resolve("coverage-ledger.json");

        LedgerStore.write(file, ledger);
        List<LedgerUnit> back = LedgerStore.read(file);

        assertEquals(LedgerStore.sorted(ledger), back);
        assertEquals(LedgerStore.toJson(List.copyOf(ledger).reversed()),
                Files.readString(file, StandardCharsets.UTF_8));
        List<String> ids = back.stream().map(LedgerUnit::coverageId).toList();
        assertEquals(ids.stream().sorted().toList(), ids);
    }

    @Test
    void schemaInvalidLedgerIsNotWritten(@TempDir Path dir) {
        LedgerUnit badAgent = with(planned("a"), Status.IN_PROGRESS, Optional.of("Not An Agent"), List.of(), List.of(),
                List.of(), List.of(), List.of());
        Path file = dir.resolve("coverage-ledger.json");

        LedgerRejectedException e = assertThrows(LedgerRejectedException.class,
                () -> LedgerStore.write(file, List.of(badAgent)));
        assertTrue(e.getMessage().contains("ledger.schema.json"), e.getMessage());
        assertFalse(Files.exists(file));
    }
}
