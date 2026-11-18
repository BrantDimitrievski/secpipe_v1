package ca.carleton.secpipe.core.detect;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;
import ca.carleton.secpipe.core.knowledge.KnowledgeBase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tool settlement of the coverage ledger. For every {@code PLANNED} unit with a CWE, the coverage claims whose CWE is
 * in the same family and whose files intersect the unit's starting paths settle it: {@code CANDIDATE} if a candidate
 * from this detect pass has a trace step in one of those files with a CWE of that family, else {@code COVERED}.
 * Updates carry {@code settled_by} (tool ids), {@code reviewed_paths} (the intersecting files) and
 * {@code local_checks} (the rule ids), and no fingerprints: normalize assigns those. Output is sorted by coverage id.
 */
final class LedgerSettlement {

    /** A coverage claim and the adapter that made it. */
    record ToolClaim(String toolId, AnalyzerAdapter.CoverageClaim claim) { }

    private final KnowledgeBase kb;

    LedgerSettlement(KnowledgeBase kb) {
        this.kb = kb;
    }

    List<LedgerUnit.Update> settle(List<LedgerUnit> ledger, List<ToolClaim> claims, List<Candidate> candidates) {
        List<LedgerUnit.Update> out = new ArrayList<>();
        for (LedgerUnit unit : ledger) {
            if (unit.status() != LedgerUnit.Status.PLANNED || unit.cwe().isEmpty()) {
                continue;
            }
            settleOne(unit, claims, candidates).ifPresent(out::add);
        }
        out.sort(Comparator.comparing(LedgerUnit.Update::coverageId));
        return List.copyOf(out);
    }

    private Optional<LedgerUnit.Update> settleOne(LedgerUnit unit, List<ToolClaim> claims,
                                                  List<Candidate> candidates) {
        String family = kb.family(unit.cwe().get());
        Set<String> reviewed = new TreeSet<>();
        Set<String> tools = new TreeSet<>();
        Set<String> checks = new TreeSet<>();
        for (ToolClaim tc : claims) {
            AnalyzerAdapter.CoverageClaim c = tc.claim();
            if (c == null || c.cwe() == null || !family.equals(kb.family(c.cwe())) || c.files() == null) {
                continue;
            }
            List<String> hits = c.files().stream().filter(f -> underAny(f, unit.startingPaths())).toList();
            if (hits.isEmpty()) {
                continue;
            }
            reviewed.addAll(hits);
            tools.add(tc.toolId());
            if (c.ruleIds() == null || c.ruleIds().isEmpty()) {
                checks.add(tc.toolId() + ": rules for " + c.cwe());
            } else {
                c.ruleIds().forEach(r -> checks.add(tc.toolId() + ":" + r));
            }
        }
        if (reviewed.isEmpty()) {
            return Optional.empty();
        }
        boolean hit = candidates.stream().anyMatch(c -> sitsIn(c, family, reviewed));
        return Optional.of(new LedgerUnit.Update(unit.coverageId(),
                hit ? LedgerUnit.Status.CANDIDATE : LedgerUnit.Status.COVERED, Optional.empty(),
                List.copyOf(reviewed), List.copyOf(checks), List.of(), List.of(), List.copyOf(tools),
                Optional.empty()));
    }

    private boolean sitsIn(Candidate c, String family, Set<String> files) {
        boolean sameFamily = c.cwe().stream().anyMatch(cwe -> family.equals(kb.family(cwe)));
        if (!sameFamily) {
            return false;
        }
        for (Finding.TraceStep step : c.trace()) {
            if (step.file() != null && files.contains(step.file())) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code file} equals a starting path or lies under one treated as a directory. */
    static boolean underAny(String file, List<String> startingPaths) {
        for (String p : startingPaths) {
            String dir = p.endsWith("/") ? p : p + "/";
            if (file.equals(p) || file.startsWith(dir)) {
                return true;
            }
        }
        return false;
    }
}
