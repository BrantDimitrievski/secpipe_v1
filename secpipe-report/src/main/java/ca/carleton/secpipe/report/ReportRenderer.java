package ca.carleton.secpipe.report;

/**
 * SPI for the human- and tool-facing report formats. Every method renders only from {@link ReportInputs} and must
 * be deterministic: sorted, LF line endings, one final newline, no timestamps.
 */
public interface ReportRenderer {

    /** {@code findings.json}: the contract findings array, sorted by fingerprint. */
    String json(ReportInputs inputs);

    /** {@code secpipe.sarif}: SARIF 2.1.0. */
    String sarif(ReportInputs inputs);

    /** {@code report.md}: the Markdown report. */
    String markdown(ReportInputs inputs);
}
