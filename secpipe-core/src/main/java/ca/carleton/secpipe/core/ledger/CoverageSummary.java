package ca.carleton.secpipe.core.ledger;

import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit.Status;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Unit counts per status for reports and the L6 terminal check.
 *
 * @param total    number of units
 * @param byStatus count per status; every status is present (zero when unused), in enum order, unmodifiable
 */
public record CoverageSummary(int total, Map<Status, Integer> byStatus) {

    public CoverageSummary {
        byStatus = Collections.unmodifiableMap(new EnumMap<>(byStatus));
    }

    /** Counts the ledger. */
    public static CoverageSummary of(List<LedgerUnit> ledger) {
        EnumMap<Status, Integer> counts = new EnumMap<>(Status.class);
        for (Status s : Status.values()) {
            counts.put(s, 0);
        }
        for (LedgerUnit u : ledger) {
            counts.merge(u.status(), 1, Integer::sum);
        }
        return new CoverageSummary(ledger.size(), counts);
    }

    /** The count for one status. */
    public int count(Status status) {
        return byStatus.getOrDefault(status, 0);
    }

    /** Units still planned or in_progress (L6). */
    public int unassigned() {
        return count(Status.PLANNED) + count(Status.IN_PROGRESS);
    }

    /** Whether the run may end {@code complete} as far as the ledger is concerned (L6). */
    public boolean complete() {
        return unassigned() == 0;
    }
}
