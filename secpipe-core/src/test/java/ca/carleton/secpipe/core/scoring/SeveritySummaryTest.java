package ca.carleton.secpipe.core.scoring;

import static ca.carleton.secpipe.core.scoring.ScoringFixtures.confirmed;
import static ca.carleton.secpipe.core.scoring.ScoringFixtures.needsValidation;
import static ca.carleton.secpipe.core.scoring.ScoringFixtures.rejected;
import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.Score5;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SeveritySummaryTest {

    private static final List<Finding> RECORDS = List.of(
            confirmed("fp-1", "CWE-89", Score5.HIGH, Score5.CRITICAL, Score5.CRITICAL),
            confirmed("fp-2", "CWE-22", Score5.HIGH, Score5.HIGH, Score5.HIGH),
            confirmed("fp-3", "CWE-89", Score5.LOW, Score5.HIGH, Score5.HIGH),
            needsValidation("fp-4", "CWE-89", Optional.of(Score5.MEDIUM)),
            rejected("fp-5"));

    @Test
    void countsVerdictsAndSeverities() {
        SeveritySummary s = SeveritySummary.of(RECORDS);
        assertThat(s.confirmed()).isEqualTo(3);
        assertThat(s.needsValidation()).isEqualTo(1);
        assertThat(s.rejected()).isEqualTo(1);
        assertThat(s.total()).isEqualTo(5);
        assertThat(s.count(Score5.CRITICAL)).isEqualTo(1);
        assertThat(s.count(Score5.HIGH)).isEqualTo(2);
        assertThat(s.count(Score5.LOW)).isZero();
        assertThat(s.bySeverityNames()).containsExactly(Map.entry("critical", 1), Map.entry("high", 2));
        assertThat(s.toString()).isEqualTo("confirmed=3 (critical=1, high=2, medium=0, low=0, informational=0)"
                + " needs_validation=1 rejected=1");
    }

    @Test
    void orderIndependentAndEmpty() {
        assertThat(SeveritySummary.of(RECORDS.reversed())).isEqualTo(SeveritySummary.of(RECORDS));
        SeveritySummary empty = SeveritySummary.of(List.of());
        assertThat(empty.total()).isZero();
        assertThat(empty.bySeverity()).hasSize(Score5.values().length);
        assertThat(empty.bySeverityNames()).isEmpty();
    }
}
