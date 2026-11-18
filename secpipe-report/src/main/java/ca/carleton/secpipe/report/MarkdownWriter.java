package ca.carleton.secpipe.report;

import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.Score5;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.report.patch.FixedPatches;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code report.md}. Renders only from {@link ReportInputs}; carries no timestamps. Everything that came from the
 * scanned repository or from an agent is untrusted, so it is flattened to one line and Markdown/HTML-escaped
 * before it is printed.
 */
final class MarkdownWriter {

    private static final Score5[] SEVERITY_ORDER = {Score5.CRITICAL, Score5.HIGH, Score5.MEDIUM, Score5.LOW,
        Score5.INFORMATIONAL};

    private MarkdownWriter() {
    }

    static String render(ReportInputs in) {
        RunMetadata meta = in.metadata();
        List<Finding> findings = in.findings();
        StringBuilder md = new StringBuilder();

        md.append("# SecPipe scan report\n\n");
        md.append("- Run: ").append(code(meta.runId())).append('\n');
        md.append("- Target: ").append(text(meta.target().name())).append('\n');
        md.append("- Profile: ").append(lower(meta.profile())).append(", scope: ").append(lower(meta.scope().mode()))
                .append('\n');
        md.append("- Status: ").append(lower(meta.terminalState())).append('\n');
        meta.incompleteReason().ifPresent(r -> md.append("- Incomplete because: ").append(text(r)).append('\n'));
        md.append('\n');

        summary(md, findings);
        findingSections(md, findings);
        remediationSection(md, in);
        coverage(md, in.ledger());
        tools(md, meta);
        notes(md, in.state().notes());
        return md.toString().stripTrailing() + "\n";
    }

    // ------------------------------------------------------------------------------------------------------------

    private static void summary(StringBuilder md, List<Finding> findings) {
        md.append("## Summary\n\n");
        Map<Score5, int[]> bySeverity = new EnumMap<>(Score5.class);
        int[] unrated = new int[3];
        for (Finding f : findings) {
            Optional<Score5> s = SarifWriter.severity(f);
            int[] row = s.isPresent() ? bySeverity.computeIfAbsent(s.get(), k -> new int[3]) : unrated;
            row[f.verdict().ordinal()]++;
        }
        md.append("| Severity | Confirmed | Needs validation | Rejected |\n|---|---|---|---|\n");
        for (Score5 s : SEVERITY_ORDER) {
            row(md, lower(s), bySeverity.getOrDefault(s, new int[3]));
        }
        row(md, "unrated", unrated);
        long confirmed = findings.stream().filter(f -> f.verdict() == Finding.Verdict.CONFIRMED).count();
        long needs = findings.stream().filter(f -> f.verdict() == Finding.Verdict.NEEDS_VALIDATION).count();
        long rejected = findings.stream().filter(f -> f.verdict() == Finding.Verdict.REJECTED).count();
        md.append("\nTotals: ").append(confirmed).append(" confirmed, ").append(needs).append(" needs validation, ")
                .append(rejected).append(" rejected.\n");
        md.append("Severity is the overall severity for confirmed findings and the analyzer's hint otherwise.\n\n");
    }

    private static void row(StringBuilder md, String label, int[] counts) {
        // Finding.Verdict order: CONFIRMED, NEEDS_VALIDATION, REJECTED.
        md.append("| ").append(label).append(" | ").append(counts[0]).append(" | ").append(counts[1]).append(" | ")
                .append(counts[2]).append(" |\n");
    }

    private static void findingSections(StringBuilder md, List<Finding> findings) {
        md.append("## Findings\n\n");
        if (findings.isEmpty()) {
            md.append("No findings.\n\n");
            return;
        }
        int n = 1;
        for (Finding f : findings) {
            finding(md, n++, f);
        }
    }

    private static void finding(StringBuilder md, int n, Finding f) {
        md.append("### ").append(n).append(". ").append(text(f.title())).append("\n\n");
        md.append("- Fingerprint: ").append(code(f.fingerprint())).append('\n');
        md.append("- Verdict: ").append(lower(f.verdict())).append('\n');
        SarifWriter.severity(f).ifPresent(s -> md.append("- Severity: ").append(lower(s))
                .append(f instanceof Finding.Confirmed ? "" : " (analyzer hint)").append('\n'));
        if (!f.secpipe().cwe().isEmpty()) {
            md.append("- CWE: ").append(String.join(", ", f.secpipe().cwe().stream().map(MarkdownWriter::text)
                    .toList())).append('\n');
        }
        Finding.TraceStep sink = f.trace().getLast();
        md.append("- Location: ").append(code(sink.file() + ":" + sink.line())).append('\n');
        md.append("- Origin: ").append(lower(f.secpipe().origin())).append(", category: ")
                .append(lower(f.secpipe().category())).append("\n\n");
        md.append(text(f.description())).append("\n\n");

        switch (f) {
            case Finding.Confirmed c -> {
                md.append("**Root cause.** ").append(text(c.rootCause())).append("\n\n");
                traceAndEvidence(md, f);
                md.append("**Evidence.** ").append(text(c.execution().observedResult())).append("\n\n");
                md.append("**Confidence.** ").append(lower(c.confidence().score())).append(": ")
                        .append(text(c.confidence().reason())).append("\n\n");
                remediation(md, c);
            }
            case Finding.NeedsValidation nv -> {
                md.append("**Claimed root cause.** ").append(text(nv.claimedRootCause())).append("\n\n");
                traceAndEvidence(md, f);
                md.append("**Blockers.**\n\n");
                nv.blockers().forEach(b -> md.append("- ").append(text(b)).append('\n'));
                md.append('\n');
                nv.validationPlan().local().ifPresent(p -> md.append("**Validate locally.** ").append(text(p))
                        .append("\n\n"));
                nv.validationPlan().deployment().ifPresent(p -> md.append("**Validate in deployment.** ")
                        .append(text(p)).append("\n\n"));
                recommended(md, f);
            }
            case Finding.Rejected rj -> {
                md.append("**Claimed root cause.** ").append(text(rj.claimedRootCause())).append("\n\n");
                traceAndEvidence(md, f);
                md.append("**Why rejected.** ").append(text(rj.reason())).append("\n\n");
            }
        }
    }

    private static void traceAndEvidence(StringBuilder md, Finding f) {
        md.append("**Trace.**\n\n");
        int i = 1;
        for (Finding.TraceStep s : f.trace()) {
            md.append(i++).append(". ").append(lower(s.kind())).append(" ").append(code(s.file() + ":" + s.line()))
                    .append(" ").append(code(s.scope())).append(": ").append(text(s.description())).append('\n');
        }
        md.append('\n');
        if (!f.evidence().isEmpty()) {
            md.append("**Code evidence.**\n\n");
            for (Finding.Evidence e : f.evidence()) {
                md.append("- ").append(code(e.file() + ":" + e.line())).append(": ").append(text(e.description()))
                        .append('\n');
            }
            md.append('\n');
        }
    }

    private static void remediation(StringBuilder md, Finding.Confirmed c) {
        md.append("**Remediation.** ").append(text(c.remediation().strategy())).append("\n\n");
        for (Finding.CodeChange change : c.remediation().codeChanges()) {
            md.append("Fixed code for ").append(code(change.fileName())).append(":\n\n");
            fenced(md, change.fixedCode());
        }
        recommended(md, c);
    }

    private static void recommended(StringBuilder md, Finding f) {
        f.secpipe().recommendedRemediation().ifPresent(r -> md.append("**Recommended remediation.** ")
                .append(text(r)).append("\n\n"));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Remediation
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Text WO-M.3 writes for a still-present attempt whose regression test was skipped after the patch: it starts
     * {@code fix_attempts[].strategy} on that attempt and is also in the run note
     * {@code remediation of <fp> attempt <n>: <text> (not retried)}.
     */
    static final String UNVERIFIED_MARKER = "regression test skipped after patch: fix unverified";
    private static final String NOT_ATTEMPTED_PREFIX = "remediation not attempted";

    private static void remediationSection(StringBuilder md, ReportInputs in) {
        md.append("## Remediation\n\n");
        List<Finding.Confirmed> confirmed = in.findings().stream().filter(Finding.Confirmed.class::isInstance)
                .map(Finding.Confirmed.class::cast).toList();
        if (confirmed.isEmpty()) {
            md.append("No confirmed findings to remediate.\n\n");
            return;
        }
        List<String> notes = in.state().notes();
        long fixed = confirmed.stream().filter(c -> status(c) == Finding.RemediationStatus.FIXED).count();
        md.append(fixed).append(" of ").append(confirmed.size()).append(" confirmed findings fixed. Patches are")
                .append(" suggestions: SecPipe never writes to your working tree. Only verified fixes are in ")
                .append(code(FixedPatches.FILE_NAME)).append(".\n\n");
        int n = 1;
        for (Finding.Confirmed c : confirmed) {
            remediationEntry(md, n++, c, in, notes);
        }
    }

    private static Finding.RemediationStatus status(Finding f) {
        return f.secpipe().remediationStatus().orElse(Finding.RemediationStatus.NOT_ATTEMPTED);
    }

    private static void remediationEntry(StringBuilder md, int n, Finding.Confirmed c, ReportInputs in,
            List<String> notes) {
        String fp = c.fingerprint();
        List<Finding.FixAttempt> attempts = c.secpipe().fixAttempts().stream()
                .sorted(Comparator.comparingInt(Finding.FixAttempt::attempt)).toList();
        Finding.RemediationStatus status = status(c);
        boolean unverified = status == Finding.RemediationStatus.STILL_PRESENT
                && (attempts.stream().anyMatch(a -> a.outcome() == Finding.FixAttempt.Outcome.STILL_PRESENT
                        && a.strategy().filter(st -> st.startsWith(UNVERIFIED_MARKER)).isPresent())
                    || notes.stream().anyMatch(note -> note.startsWith("remediation of " + fp + " attempt ")
                        && note.contains(UNVERIFIED_MARKER)));

        md.append("#### ").append(n).append(". ").append(text(c.title())).append("\n\n");
        md.append("- Fingerprint: ").append(code(fp)).append('\n');
        md.append("- Outcome: ").append(unverified ? "still_present (fix unverified: the regression test was skipped "
                + "after the patch)" : lower(status)).append('\n');
        if (status == Finding.RemediationStatus.NOT_ATTEMPTED) {
            md.append("- Reason: ").append(text(notAttemptedReason(c, in, attempts))).append('\n');
        }
        String stopPrefix = "remediation of " + fp + " stopped: ";
        notes.stream().filter(note -> note.startsWith(stopPrefix)).forEach(note -> md.append("- Stopped: ")
                .append(text(note.substring(stopPrefix.length()))).append('\n'));
        md.append("- Attempts: ").append(attempts.size()).append('\n');
        for (Finding.FixAttempt a : attempts) {
            attemptLine(md, a);
        }
        if (!attempts.isEmpty()) {
            Optional<String> patch = attempts.getLast().patchArtifact()
                    .filter(FixedPatches::isSafeRelative);
            patch.ifPresent(p -> md.append("- ").append(status == Finding.RemediationStatus.FIXED
                    ? "Patch (verified): " : "Patch (candidate, not verified): ").append(link(p)).append('\n'));
        }
        md.append('\n');
    }

    private static void attemptLine(StringBuilder md, Finding.FixAttempt a) {
        md.append("  ").append(a.attempt()).append(". ").append(lower(a.outcome()));
        List<String> parts = new ArrayList<>();
        a.strategy().filter(s -> !s.isBlank()).ifPresent(s -> parts.add("strategy: " + text(s)));
        a.regressionTestPassed().ifPresent(b -> parts.add("regression test " + (b ? "passed" : "failed")));
        a.suitePassed().ifPresent(b -> parts.add("suite " + (b ? "passed" : "failed")));
        a.rescanClean().ifPresent(b -> parts.add("rescan " + (b ? "clean" : "not clean")));
        if (!a.newFingerprints().isEmpty()) {
            parts.add("new findings: " + String.join(", ", a.newFingerprints().stream()
                    .sorted().map(MarkdownWriter::code).toList()));
        }
        a.adequacy().ifPresent(ad -> parts.add("adequacy " + ad.changedLinesCovered() + "/" + ad.changedLinesTotal()
                + " changed lines covered (" + (ad.adequate() ? "adequate" : "not adequate") + ")"
                + ad.note().filter(s -> !s.isBlank()).map(s -> ": " + text(s)).orElse("")));
        if (!parts.isEmpty()) {
            md.append(": ").append(String.join("; ", parts));
        }
        md.append('\n');
    }

    private static String notAttemptedReason(Finding.Confirmed c, ReportInputs in, List<Finding.FixAttempt> attempts) {
        String fp = c.fingerprint();
        for (String note : in.state().notes()) {
            String prefix = NOT_ATTEMPTED_PREFIX + " for " + fp + ": ";
            if (note.startsWith(prefix)) {
                return note.substring(prefix.length());
            }
        }
        if (in.state().config().enableFixes() == SecPipeConfig.FixMode.FALSE) {
            return "fixes are disabled (--enable-fixes false)";
        }
        for (String note : in.state().notes()) {
            String prefix = NOT_ATTEMPTED_PREFIX + ": ";
            if (note.startsWith(prefix)) {
                return note.substring(prefix.length());
            }
        }
        if (attempts.stream().anyMatch(a -> a.outcome() == Finding.FixAttempt.Outcome.SANDBOX_UNAVAILABLE)) {
            return "sandbox unavailable";
        }
        return "no remediation was recorded for this finding";
    }

    /** A relative patch path as a Markdown link (label and target are escaped). */
    private static String link(String path) {
        String target = path.replace(" ", "%20").replace("(", "%28").replace(")", "%29");
        return "[" + text(path) + "](" + target + ")";
    }

    private static void coverage(StringBuilder md, List<LedgerUnit> ledger) {
        md.append("## Coverage\n\n");
        Map<LedgerUnit.Status, Integer> byStatus = new EnumMap<>(LedgerUnit.Status.class);
        for (LedgerUnit.Status s : LedgerUnit.Status.values()) {
            byStatus.put(s, 0);
        }
        ledger.forEach(u -> byStatus.merge(u.status(), 1, Integer::sum));
        md.append("Ledger units: ").append(ledger.size()).append("\n\n| Status | Units |\n|---|---|\n");
        byStatus.forEach((s, count) -> md.append("| ").append(lower(s)).append(" | ").append(count).append(" |\n"));
        md.append('\n');
        List<LedgerUnit> open = ledger.stream()
                .filter(u -> u.status() == LedgerUnit.Status.BLOCKED || u.status() == LedgerUnit.Status.DEFERRED
                        || !u.unresolved().isEmpty())
                .sorted(Comparator.comparing(LedgerUnit::coverageId))
                .toList();
        if (!open.isEmpty()) {
            md.append("Units that need attention:\n\n");
            for (LedgerUnit u : open) {
                md.append("- ").append(code(u.coverageId())).append(" (").append(lower(u.status())).append("): ")
                        .append(text(u.surface()));
                if (!u.unresolved().isEmpty()) {
                    md.append("; unresolved: ").append(String.join("; ", u.unresolved().stream()
                            .map(MarkdownWriter::text).toList()));
                }
                md.append('\n');
            }
            md.append('\n');
        }
    }

    private static void tools(StringBuilder md, RunMetadata meta) {
        if (meta.tools().isEmpty()) {
            return;
        }
        md.append("## Tools\n\n| Tool | Status | Candidates | Note |\n|---|---|---|---|\n");
        meta.tools().stream().sorted(Comparator.comparing(RunMetadata.ToolRun::id)).forEach(t -> md.append("| ")
                .append(text(t.id())).append(" | ").append(lower(t.status())).append(" | ").append(t.candidates())
                .append(" | ").append(t.reason().map(MarkdownWriter::text).orElse("")).append(" |\n"));
        md.append('\n');
    }

    private static void notes(StringBuilder md, List<String> notes) {
        if (notes.isEmpty()) {
            return;
        }
        md.append("## Run notes\n\n");
        notes.forEach(n -> md.append("- ").append(text(n)).append('\n'));
        md.append('\n');
    }

    // ------------------------------------------------------------------------------------------------------------
    // Escaping
    // ------------------------------------------------------------------------------------------------------------

    private static String lower(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    /** One line of untrusted text with Markdown and HTML metacharacters escaped. */
    static String text(String s) {
        String flat = flatten(s);
        StringBuilder out = new StringBuilder(flat.length() + 8);
        for (int i = 0; i < flat.length(); i++) {
            char c = flat.charAt(i);
            if ("\\`*_[]<>|&~#".indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /** One line of untrusted text as an inline code span (backtick-safe). */
    static String code(String s) {
        String flat = flatten(s);
        int longest = 0;
        int run = 0;
        for (int i = 0; i < flat.length(); i++) {
            run = flat.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(longest + 1);
        boolean pad = flat.startsWith("`") || flat.endsWith("`");
        return fence + (pad ? " " : "") + flat + (pad ? " " : "") + fence;
    }

    private static void fenced(StringBuilder md, String content) {
        String body = content.replace("\r\n", "\n").replace('\r', '\n');
        int longest = 0;
        int run = 0;
        for (int i = 0; i < body.length(); i++) {
            run = body.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(Math.max(3, longest + 1));
        md.append(fence).append("java\n").append(body);
        if (!body.endsWith("\n")) {
            md.append('\n');
        }
        md.append(fence).append("\n\n");
    }

    private static String flatten(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                space = out.length() > 0;
            } else {
                if (space) {
                    out.append(' ');
                    space = false;
                }
                out.append(c);
            }
        }
        return out.toString();
    }
}
