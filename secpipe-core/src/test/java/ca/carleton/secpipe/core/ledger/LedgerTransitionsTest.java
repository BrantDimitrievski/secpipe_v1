package ca.carleton.secpipe.core.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.carleton.secpipe.contracts.ledger.LedgerUnit.Status;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The transition table is exactly docs/PLAN.md §4.1 L3. */
class LedgerTransitionsTest {

    @Test
    void tableMatchesPlanRuleL3() {
        Map<Status, Set<Status>> expected = Map.of(
                Status.PLANNED, EnumSet.of(Status.IN_PROGRESS, Status.COVERED, Status.CANDIDATE, Status.DEFERRED,
                        Status.OUT_OF_SCOPE, Status.NOT_APPLICABLE),
                Status.IN_PROGRESS, EnumSet.of(Status.COVERED, Status.CANDIDATE, Status.BLOCKED, Status.PLANNED),
                Status.BLOCKED, EnumSet.of(Status.IN_PROGRESS, Status.DEFERRED),
                Status.COVERED, EnumSet.of(Status.PLANNED),
                Status.CANDIDATE, EnumSet.of(Status.CANDIDATE),
                Status.DEFERRED, EnumSet.noneOf(Status.class),
                Status.OUT_OF_SCOPE, EnumSet.noneOf(Status.class),
                Status.NOT_APPLICABLE, EnumSet.noneOf(Status.class));
        for (Status from : Status.values()) {
            assertEquals(expected.get(from), LedgerTransitions.allowedTargets(from), from.toString());
        }
    }

    @Test
    void closeRejectsNonClosingStatuses() {
        assertThrows(IllegalArgumentException.class, () -> LedgerTransitions.close("cov:x", Status.COVERED, "r"));
    }
}
