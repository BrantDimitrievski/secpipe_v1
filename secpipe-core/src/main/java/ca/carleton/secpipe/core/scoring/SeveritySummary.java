package ca.carleton.secpipe.core.scoring;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.Score5;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Verdict and severity counts over a record set (FR-10). Only confirmed records carry severity, so
 * {@link #bySeverity()} counts confirmed records by {@code overall_severity}; every level is present (zero when
 * absent). Deterministic and order-independent.
 */
public record SeveritySummary(int confirmed, int needsValidation, int rejected, Map<Score5, Integer> bySeverity) {

    public SeveritySummary {
        EnumMap<Score5, Integer> copy = new EnumMap<>(Score5.class);
        for (Score5 s : Score5.values()) {
            copy.put(s, bySeverity.getOrDefault(s, 0));
        }
        bySeverity = Collections.unmodifiableMap(copy);
    }

    public static SeveritySummary of(Collection<? extends Finding> records) {
        int c = 0;
        int n = 0;
        int r = 0;
        Map<Score5, Integer> by = new EnumMap<>(Score5.class);
        for (Finding f : records) {
            switch (f) {
                case Finding.Confirmed conf -> {
                    c++;
                    by.merge(conf.severity().overallSeverity(), 1, Integer::sum);
                }
                case Finding.NeedsValidation ignored -> n++;
                case Finding.Rejected ignored -> r++;
            }
        }
        return new SeveritySummary(c, n, r, by);
    }

    public int total() {
        return confirmed + needsValidation + rejected;
    }

    public int count(Score5 severity) {
        return bySeverity.get(severity);
    }

    /** Non-zero severity counts keyed by lower-case level, sorted, as {@code run-metadata.json counts.by_severity}. */
    public Map<String, Integer> bySeverityNames() {
        Map<String, Integer> out = new TreeMap<>();
        bySeverity.forEach((k, v) -> {
            if (v > 0) {
                out.put(ScoringPolicy.lower(k), v);
            }
        });
        return Collections.unmodifiableMap(out);
    }

    /** One line, highest severity first, e.g. {@code confirmed=1 (critical=1, ...) needs_validation=0 rejected=0}. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("confirmed=").append(confirmed).append(" (");
        for (int i = Score5.values().length - 1; i >= 0; i--) {
            Score5 s = Score5.values()[i];
            sb.append(ScoringPolicy.lower(s)).append('=').append(bySeverity.get(s)).append(i > 0 ? ", " : "");
        }
        return sb.append(") needs_validation=").append(needsValidation).append(" rejected=").append(rejected)
                .toString();
    }
}
