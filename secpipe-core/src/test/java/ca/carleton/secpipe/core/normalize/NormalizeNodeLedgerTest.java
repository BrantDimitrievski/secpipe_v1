package ca.carleton.secpipe.core.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.ids.CoverageIds;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit.Status;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.core.ledger.LedgerInvariants;
import ca.carleton.secpipe.core.ledger.LedgerStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NormalizeNodeLedgerTest {

    private static final String FILE = "src/main/java/app/UserDao.java";
    private static final String OTHER = "src/main/java/app/Other.java";

    private final NormalizeNode node = new NormalizeNode();
    private final LedgerStore store = new LedgerStore(LedgerInvariants::check);

    private static PipelineState state(List<LedgerUnit> ledger, List<Candidate> candidates) {
        return new PipelineState("run-1", null, Optional.empty(), Optional.empty(), List.of(), ledger, candidates,
                Map.of(), Map.of(), List.of(), Map.of(), 0, 0, false, Map.of(), Optional.empty(), List.of(),
                Optional.empty());
    }

    /** A unit as detect leaves it after tool settlement: candidate, settled_by, no agent, no fingerprints. */
    private static LedgerUnit toolSettled(String subsystem, String cwe, String reviewed, List<String> fingerprints) {
        LedgerUnit.CanonicalRefs refs = new LedgerUnit.CanonicalRefs("http-endpoint", "user-input", subsystem,
                "sql-injection", Optional.empty());
        return new LedgerUnit(CoverageIds.of(refs), refs, "http-endpoint", "user-input", subsystem, "sql-injection",
                Optional.of(cwe), List.of("src/main/java/app"), "INJECTION.md#sql", List.of(), List.of(),
                LedgerUnit.PriorStatus.NEW, List.of(), 1, Status.CANDIDATE, Optional.empty(), List.of(reviewed),
                List.of("builtin:SQLI-001"), fingerprints, List.of(), List.of("builtin"));
    }

    private static List<LedgerUnit> sorted(LedgerUnit... units) {
        List<LedgerUnit> out = new ArrayList<>(List.of(units));
        out.sort((a, b) -> a.coverageId().compareTo(b.coverageId()));
        return out;
    }

    private static LedgerUnit byId(List<LedgerUnit> ledger, String id) {
        return ledger.stream().filter(u -> u.coverageId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void toolSettledCandidateUnitGetsSortedFingerprintsAndPassesCheckFinal() throws Exception {
        LedgerUnit unit = toolSettled("users", "CWE-89", FILE, List.of());
        List<Candidate> input = List.of(CandidateMergerTest.code("builtin", "SQLI-001", 90),
                CandidateMergerTest.code("builtin", "SQLI-001", 42));
        List<LedgerUnit> ledger = List.of(unit);
        assertThat(LedgerInvariants.checkFinal(ledger).ok()).isFalse();

        var delta = node.apply(state(ledger, input), null).delta();

        List<String> expected = delta.replaceCandidates().orElseThrow().stream()
                .map(c -> c.fingerprint().orElseThrow()).sorted().toList();
        assertThat(expected).hasSize(2);
        assertThat(delta.ledgerUpdates()).singleElement().satisfies(u -> {
            assertThat(u.coverageId()).isEqualTo(unit.coverageId());
            assertThat(u.newStatus()).isEqualTo(Status.CANDIDATE);
            assertThat(u.agentId()).isEmpty();
            assertThat(u.settledBy()).containsExactly("builtin");
            assertThat(u.resultFingerprints()).isEqualTo(expected);
        });
        List<LedgerUnit> after = store.apply(ledger, delta.ledgerUpdates());
        assertThat(after.get(0).resultFingerprints()).isEqualTo(expected);
        assertThat(after.get(0).attempts()).isEmpty();
        assertThat(LedgerInvariants.checkFinal(after).ok()).isTrue();
        assertThat(LedgerInvariants.checkFingerprints(after, Set.copyOf(expected)).ok()).isTrue();
    }

    @Test
    void reEntryOnlyGrowsTheSet() throws Exception {
        LedgerUnit unit = toolSettled("users", "CWE-89", FILE, List.of());
        Candidate first = CandidateMergerTest.code("builtin", "SQLI-001", 42);
        List<LedgerUnit> ledger = store.apply(List.of(unit),
                node.apply(state(List.of(unit), List.of(first)), null).delta().ledgerUpdates());
        List<String> before = ledger.get(0).resultFingerprints();
        assertThat(before).hasSize(1);

        // Same candidates again (normalize_llm with nothing new): no update, nothing removed.
        List<Candidate> normalized = node.apply(state(ledger, List.of(first)), null).delta().replaceCandidates()
                .orElseThrow();
        assertThat(node.apply(state(ledger, normalized), null).delta().ledgerUpdates()).isEmpty();

        // An existing fingerprint no longer present among candidates is kept; a new matching one is added.
        LedgerUnit withStale = byId(store.apply(ledger, List.of(new LedgerUnit.Update(unit.coverageId(),
                Status.CANDIDATE, Optional.empty(), List.of(), List.of(), List.of("code:CWE-89:aaaa", before.get(0)),
                List.of(), List.of(), Optional.empty()))), unit.coverageId());
        Candidate agentFound = CandidateMergerTest.code("hunter", "llm", "CWE-89", "app.UserDao#other(String)",
                "stmt.execute(q)", 77, List.of(CandidateMergerTest.step(Finding.TraceKind.SINK, FILE, 77)));
        var updates = node.apply(state(List.of(withStale), List.of(first, agentFound)), null).delta()
                .ledgerUpdates();

        assertThat(updates).singleElement().satisfies(u -> {
            assertThat(u.resultFingerprints()).containsAll(withStale.resultFingerprints()).hasSize(3).isSorted();
        });
        List<LedgerUnit> grown = store.apply(List.of(withStale), updates);
        assertThat(grown.get(0).resultFingerprints()).containsAll(withStale.resultFingerprints()).hasSize(3);
    }

    @Test
    void unitsWithoutMatchingCandidateAreLeftAlone() throws Exception {
        LedgerUnit otherFile = toolSettled("a-other-file", "CWE-89", OTHER, List.of());
        LedgerUnit otherFamily = toolSettled("b-other-family", "CWE-22", FILE, List.of());
        LedgerUnit agentUnit = new LedgerUnit(otherFile.coverageId(), otherFile.canonicalRefs(), otherFile.surface(),
                otherFile.boundary(), "c", otherFile.attackClass(), otherFile.cwe(), otherFile.startingPaths(),
                otherFile.ordinaryAttackClassBlock(), List.of(), List.of(), otherFile.priorStatus(), List.of(), 1,
                Status.IN_PROGRESS, Optional.of("hunter-0a1b2c3d"), List.of(), List.of(), List.of(), List.of(),
                List.of());
        LedgerUnit matching = toolSettled("d-match", "CWE-89", FILE, List.of());
        List<Candidate> input = List.of(CandidateMergerTest.code("builtin", "SQLI-001", 42));

        var updates = node.apply(state(sorted(otherFile, otherFamily, matching), input), null).delta()
                .ledgerUpdates();

        assertThat(updates).extracting(LedgerUnit.Update::coverageId).containsExactly(matching.coverageId());
        assertThat(node.apply(state(List.of(agentUnit), input), null).delta().ledgerUpdates()).isEmpty();
    }

    @Test
    void ledgerUpdatesAreIndependentOfInputOrder() {
        List<LedgerUnit> ledger = sorted(toolSettled("x", "CWE-89", FILE, List.of()),
                toolSettled("y", "CWE-89", FILE, List.of()));
        List<Candidate> input = new ArrayList<>(List.of(CandidateMergerTest.code("builtin", "SQLI-001", 42),
                CandidateMergerTest.code("semgrep", "java.sqli", 42), CandidateMergerTest.code("builtin", "S", 90)));
        var first = node.apply(state(ledger, input), null).delta().ledgerUpdates();
        Collections.reverse(input);
        var second = node.apply(state(ledger, input), null).delta().ledgerUpdates();

        assertThat(second).isEqualTo(first).hasSize(2);
        assertThat(first).extracting(LedgerUnit.Update::coverageId).isSorted();
    }
}
