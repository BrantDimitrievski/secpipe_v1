package ca.carleton.secpipe.core.detect;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.core.knowledge.KnowledgeBase;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The matching rule of tool settlement, shared by detect (which settles a unit to {@code CANDIDATE}) and normalize
 * (which then gives that unit the fingerprints of the candidates that settled it): a candidate settles a unit when
 * one of its CWEs is in the unit's CWE family and one of its trace steps sits in a settled file that lies under the
 * unit's starting paths. Deterministic; no LLM calls.
 */
public final class SettlementMatch {

    private SettlementMatch() { }

    /** True if {@code c} has a CWE in {@code family} and a trace step in one of {@code files}. */
    public static boolean sitsIn(KnowledgeBase kb, Candidate c, String family, Collection<String> files) {
        Objects.requireNonNull(kb, "kb");
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

    /**
     * True if {@code c} settled the tool-settled {@code unit}: the unit has a CWE, and {@code c} sits in one of the
     * unit's reviewed paths (the settled files) that lies under its starting paths, with a CWE of the unit's family.
     */
    public static boolean settles(KnowledgeBase kb, LedgerUnit unit, Candidate c) {
        if (unit.cwe().isEmpty()) {
            return false;
        }
        List<String> files = unit.reviewedPaths().stream().filter(f -> underAny(f, unit.startingPaths())).toList();
        return !files.isEmpty() && sitsIn(kb, c, kb.family(unit.cwe().get()), files);
    }

    /** True if {@code file} equals a starting path or lies under one treated as a directory. */
    public static boolean underAny(String file, List<String> startingPaths) {
        for (String p : startingPaths) {
            String dir = p.endsWith("/") ? p : p + "/";
            if (file.equals(p) || file.startsWith(dir)) {
                return true;
            }
        }
        return false;
    }
}
