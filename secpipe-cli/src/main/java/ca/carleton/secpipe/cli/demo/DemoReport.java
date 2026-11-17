package ca.carleton.secpipe.cli.demo;

import ca.carleton.secpipe.contracts.finding.Finding;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders the demo-slice report.md from the contract findings plus their detector hits. */
public final class DemoReport {

    public static final String BANNER = "demo slice — deterministic detector only; no AI, no sandbox";

    private DemoReport() {
    }

    static String render(String target, DemoSqliDetector.Result result, List<Finding> findings) {
        StringBuilder md = new StringBuilder();
        md.append("# SecPipe scan report\n\n");
        md.append("> **").append(BANNER).append("**\n\n");
        md.append("- Scan target: `").append(target).append("`\n");
        md.append("- Java files parsed: ").append(result.filesParsed());
        md.append(" (skipped, unparseable: ").append(result.filesSkipped()).append(")\n");
        md.append("- Findings: ").append(findings.size())
                .append(" (all `needs_validation`: no sandbox evidence in this slice)\n\n");

        Map<Finding.Score5, Integer> counts = new EnumMap<>(Finding.Score5.class);
        for (Finding f : findings) {
            Finding.Score5 s = f.secpipe().toolSeverityHint().orElse(Finding.Score5.INFORMATIONAL);
            counts.merge(s, 1, Integer::sum);
        }
        md.append("## Counts by severity (tool hint)\n\n| Severity | Count |\n|---|---|\n");
        Finding.Score5[] order = {Finding.Score5.CRITICAL, Finding.Score5.HIGH, Finding.Score5.MEDIUM,
            Finding.Score5.LOW, Finding.Score5.INFORMATIONAL};
        for (Finding.Score5 s : order) {
            md.append("| ").append(s.name().toLowerCase(Locale.ROOT)).append(" | ")
                    .append(counts.getOrDefault(s, 0)).append(" |\n");
        }
        md.append('\n');

        // DemoScan.toFindings keeps hit order, so findings.get(k) belongs to hits.get(k).
        int i = 1;
        for (int k = 0; k < result.hits().size(); k++) {
            SqliHit hit = result.hits().get(k);
            String fp = findings.get(k).fingerprint();
            md.append("## ").append(i++).append(". ").append(DemoScan.CWE).append(": SQL injection in `")
                    .append(hit.scope()).append("`\n\n");
            md.append("- CWE: ").append(DemoScan.CWE).append('\n');
            md.append("- Location: `").append(hit.file()).append(':').append(hit.line()).append("`\n");
            md.append("- Sink: `").append(hit.sinkCall().replace('\n', ' ').replace("`", "'")).append("`\n");
            md.append("- Non-literal operands: ").append(String.join(", ", hit.variables())).append('\n');
            md.append("- Fingerprint: `").append(fp).append("`\n");
            md.append("- Verdict: needs_validation\n\n");
        }
        if (result.hits().isEmpty()) {
            md.append("No findings.\n");
        }
        return md.toString();
    }
}
