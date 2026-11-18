package ca.carleton.secpipe.core.detect;

import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.core.ledger.LedgerStore;
import ca.carleton.secpipe.validators.ValidationResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DetectNodeSettlementTest {

    private static final String A = "src/main/java/A.java";
    private static final String B = "src/main/java/B.java";
    private static final String C = "src/main/java/C.java";

    @TempDir
    Path work;

    private final List<LedgerUnit> ledger = List.of(
            DetectFixtures.unit("a", "CWE-89", List.of(A), LedgerUnit.Status.PLANNED),
            DetectFixtures.unit("b", "CWE-89", List.of(B), LedgerUnit.Status.PLANNED),
            DetectFixtures.unit("c", "CWE-22", List.of("src/main/java"), LedgerUnit.Status.PLANNED),
            DetectFixtures.unit("d", "CWE-79", List.of(A), LedgerUnit.Status.PLANNED),
            DetectFixtures.unit("e", "CWE-89", List.of(A), LedgerUnit.Status.IN_PROGRESS));

    @BeforeEach
    void setUp() {
        FakeAdapter.reset();
    }

    @AfterEach
    void tearDown() {
        FakeAdapter.reset();
    }

    private StateDelta detect() throws Exception {
        return new DetectNode(ctx -> DetectFixtures.fakes())
                .apply(DetectFixtures.state(DetectFixtures.config(), ledger),
                        DetectFixtures.ctx(work, Optional.empty()))
                .delta();
    }

    private static Map<String, LedgerUnit.Update> byId(StateDelta d) {
        return d.ledgerUpdates().stream().collect(Collectors.toMap(u -> u.coverageId().substring(4, 5),
                Function.identity()));
    }

    @Test
    void claimsSettleUnitsAsCandidateWhereAHitSitsAndCoveredElsewhere() throws Exception {
        Map<String, LedgerUnit.Update> u = byId(detect());

        assertThat(u).containsOnlyKeys("a", "b", "c");
        assertThat(u.get("a").newStatus()).isEqualTo(LedgerUnit.Status.CANDIDATE);
        assertThat(u.get("a").reviewedPaths()).containsExactly(A);
        assertThat(u.get("a").settledBy()).containsExactly("fake-alpha");
        assertThat(u.get("a").localChecks()).containsExactly("fake-alpha:fake-alpha-rule");
        assertThat(u.get("b").newStatus()).isEqualTo(LedgerUnit.Status.COVERED);
        assertThat(u.get("b").reviewedPaths()).containsExactly(B);
        // CWE-23 claim settles a CWE-22 unit (same family); the D.java hit lies outside the claimed files.
        assertThat(u.get("c").newStatus()).isEqualTo(LedgerUnit.Status.COVERED);
        assertThat(u.get("c").reviewedPaths()).containsExactly(C);
        assertThat(u.get("c").settledBy()).containsExactly("fake-beta");
    }

    @Test
    void settlementUpdatesAreValidToolSettlementsAndApply() throws Exception {
        StateDelta d = detect();

        assertThat(d.ledgerUpdates()).extracting(LedgerUnit.Update::coverageId).isSorted();
        for (LedgerUnit.Update upd : d.ledgerUpdates()) {
            assertThat(upd.agentId()).isEmpty();
            assertThat(upd.settledBy()).isNotEmpty().allMatch(s -> s.matches("^[a-z][a-z0-9-]*$"));
            assertThat(upd.reviewedPaths()).isNotEmpty();
            assertThat(upd.localChecks()).isNotEmpty().allMatch(s -> s.matches(".*\\S.*"));
            assertThat(upd.resultFingerprints()).isEmpty();
            assertThat(upd.unresolved()).isEmpty();
            assertThat(upd.archiveReason()).isEmpty();
        }
        List<LedgerUnit> after = new LedgerStore((x, y) -> ValidationResult.valid()).apply(ledger, d.ledgerUpdates());
        Map<String, LedgerUnit.Status> status = after.stream()
                .collect(Collectors.toMap(x -> x.coverageId().substring(4, 5), LedgerUnit::status));
        assertThat(status).containsEntry("a", LedgerUnit.Status.CANDIDATE).containsEntry("b", LedgerUnit.Status.COVERED)
                .containsEntry("c", LedgerUnit.Status.COVERED).containsEntry("d", LedgerUnit.Status.PLANNED)
                .containsEntry("e", LedgerUnit.Status.IN_PROGRESS);
        assertThat(after).allMatch(x -> x.attempts().isEmpty());
    }

    @Test
    void failedAdapterMakesNoCoverageClaims() throws Exception {
        FakeAdapter.BEHAVIOR.put("fake-alpha", FakeAdapter.Behavior.FAIL);

        assertThat(byId(detect())).containsOnlyKeys("c");
    }

    @Test
    void startingPathsMatchFilesExactlyOrAsDirectories() {
        assertThat(LedgerSettlement.underAny(A, List.of(A))).isTrue();
        assertThat(LedgerSettlement.underAny(A, List.of("src/main"))).isTrue();
        assertThat(LedgerSettlement.underAny(A, List.of("src/main/"))).isTrue();
        assertThat(LedgerSettlement.underAny(A, List.of("src/mai"))).isFalse();
        assertThat(LedgerSettlement.underAny(A, List.of(B))).isFalse();
    }
}
