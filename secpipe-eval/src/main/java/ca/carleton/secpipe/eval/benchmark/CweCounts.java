package ca.carleton.secpipe.eval.benchmark;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Confusion counts for one CWE (or a total). Rates are percentages with one decimal. */
public record CweCounts(int tp, int fp, int tn, int fn) {

    public double tpr() {
        return pct(tp, tp + fn);
    }

    public double fpr() {
        return pct(fp, fp + tn);
    }

    /** Benchmark score: TPR minus FPR, in percentage points. */
    public double score() {
        return round1(tpr() - fpr());
    }

    static double pct(int num, int den) {
        return den == 0 ? 0.0 : round1(100.0 * num / den);
    }

    static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
