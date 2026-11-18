package ca.carleton.secpipe.core.ledger;

import static ca.carleton.secpipe.core.ledger.LedgerFixtures.in;
import static ca.carleton.secpipe.core.ledger.LedgerFixtures.planned;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.ledger.LedgerUnit.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

class CoverageSummaryTest {

    @Test
    void countsEveryStatusAndUnassignedUnits() {
        CoverageSummary s = CoverageSummary.of(List.of(planned("a"), in(planned("b"), Status.COVERED),
                in(planned("c"), Status.COVERED), in(planned("d"), Status.IN_PROGRESS)));

        assertEquals(4, s.total());
        assertEquals(2, s.count(Status.COVERED));
        assertEquals(0, s.count(Status.BLOCKED));
        assertEquals(Status.values().length, s.byStatus().size());
        assertEquals(2, s.unassigned());
        assertFalse(s.complete());
        assertThrows(UnsupportedOperationException.class, () -> s.byStatus().put(Status.BLOCKED, 1));
    }

    @Test
    void emptyLedgerIsComplete() {
        assertTrue(CoverageSummary.of(List.of()).complete());
    }
}
