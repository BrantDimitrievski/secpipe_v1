package ca.carleton.secpipe.eval.remediation;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.FixAttempt;
import ca.carleton.secpipe.contracts.finding.Finding.TestEvidence;
import ca.carleton.secpipe.contracts.json.ContractJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Remediation scorecard (WO-E.6): a deterministic table over one or more {@code findings.json} files.
 *
 * <p>Usage: {@code RemediationScorecard [--patch-base PREFIX] --out FILE findings.json...}. The app name is the name
 * of the directory holding each findings.json. Patch links are {@code PREFIX/<app>/<patch_artifact>}. One row per
 * confirmed finding, sorted by app then fingerprint. Attempts that ended "fix unverified" and attempts that ended
 * {@code sandbox_unavailable} are counted separately from failures and are never shown as verified fixes.
 */
public final class RemediationScorecard {

    /** Strategy prefix written by WO-M.3 for a still_present attempt whose regression test was skipped. */
    public static final String UNVERIFIED_PREFIX = "regression test skipped after patch: fix unverified";

    /** Blocker prefix written by the remediation loop on findings that a candidate fix introduced. */
    public static final String INTRODUCED_PREFIX = "introduced by candidate fix ";

    public static final String PLACEHOLDER_NOTE =
            "pending: real run on a Docker machine with an LLM key (human check)";

    private static final String HEADER = "| App | Fingerprint | CWE | Attempts | Pre-fix test | Post-fix test | Suite | Rescan "
            + "| New findings | Adequacy | Final status | Patch |\n|---|---|---|---|---|---|---|---|---|---|---|---|\n";

    /** One scorecard row: every cell already rendered as text. */
    public record Row(String app, String fingerprint, String cwe, int attempts, String preFix, String postFix,
            String suite, String rescan, int newFindings, String adequacy, String finalStatus, String patch) { }

    /** Attempt-level and finding-level totals over all rows. */
    public record Totals(int confirmed, int fixed, int attempts, int attemptsOnFixed, int failedAttempts,
            int unverifiedAttempts, int sandboxUnavailableAttempts, int unverifiedFindings,
            int sandboxUnavailableFindings, int notAttempted, int introducedFindings) { }

    /** The findings of one app. */
    public record AppFindings(String app, List<Finding> findings) { }

    private RemediationScorecard() {
    }

    public static boolean isUnverified(FixAttempt a) {
        return a.outcome() == FixAttempt.Outcome.STILL_PRESENT
                && a.strategy().map(s -> s.startsWith(UNVERIFIED_PREFIX)).orElse(false);
    }

    public static List<Row> rows(List<AppFindings> apps, String patchBase) {
        List<Row> rows = new ArrayList<>();
        for (AppFindings app : apps) {
            for (Finding f : app.findings()) {
                if (f instanceof Finding.Confirmed c) {
                    rows.add(row(app.app(), c, patchBase));
                }
            }
        }
        rows.sort(Comparator.comparing(Row::app).thenComparing(Row::fingerprint));
        return List.copyOf(rows);
    }

    private static Row row(String app, Finding.Confirmed c, String patchBase) {
        Finding.SecPipeExt ext = c.secpipe();
        List<FixAttempt> attempts = ext.fixAttempts();
        Optional<FixAttempt> last = attempts.isEmpty() ? Optional.empty() : Optional.of(attempts.get(attempts.size() - 1));
        String pre = ext.testEvidence().stream().filter(t -> t.phase() == TestEvidence.Phase.PRE_FIX)
                .map(t -> name(t.outcome())).findFirst().orElse("-");
        String post = ext.testEvidence().stream().filter(t -> t.phase() == TestEvidence.Phase.POST_FIX)
                .map(t -> name(t.outcome())).reduce((a, b) -> b)
                .orElseGet(() -> last.flatMap(FixAttempt::regressionTestPassed)
                        .map(p -> p ? "passed" : "failed").orElse("-"));
        String adequacy = last.flatMap(FixAttempt::adequacy).map(a -> a.changedLinesCovered() + "/"
                + a.changedLinesTotal() + " lines" + (a.adequate() ? "" : " (adequacy limited)")).orElse("-");
        return new Row(app, c.fingerprint(), String.join(", ", ext.cwe()).isEmpty() ? "-" : String.join(", ", ext.cwe()),
                attempts.size(), pre, post, last.flatMap(FixAttempt::suitePassed).map(RemediationScorecard::passFail).orElse("-"),
                last.flatMap(FixAttempt::rescanClean).map(b -> b ? "clean" : "not clean").orElse("-"),
                last.map(a -> a.newFingerprints().size()).orElse(0), adequacy, finalStatus(ext, last),
                patchLink(app, attempts, patchBase));
    }

    static String finalStatus(Finding.SecPipeExt ext, Optional<FixAttempt> last) {
        if (last.isPresent()) {
            FixAttempt a = last.get();
            if (isUnverified(a)) {
                return "fix unverified";
            }
            if (a.outcome() == FixAttempt.Outcome.SANDBOX_UNAVAILABLE) {
                return "sandbox unavailable";
            }
        }
        return ext.remediationStatus().map(RemediationScorecard::name).orElse("not_attempted");
    }

    private static String patchLink(String app, List<FixAttempt> attempts, String patchBase) {
        FixAttempt pick = null;
        for (FixAttempt a : attempts) {
            if (a.patchArtifact().isPresent() && (pick == null || pick.outcome() != FixAttempt.Outcome.FIXED)) {
                pick = a;
            }
        }
        if (pick == null) {
            return "-";
        }
        String prefix = patchBase.isEmpty() ? "" : patchBase.replaceAll("/+$", "") + "/";
        return "[attempt " + pick.attempt() + "](" + prefix + app + "/" + pick.patchArtifact().get() + ")";
    }

    public static Totals totals(List<AppFindings> apps) {
        int confirmed = 0;
        int fixed = 0;
        int attempts = 0;
        int onFixed = 0;
        int failed = 0;
        int unverified = 0;
        int sandbox = 0;
        int unverifiedF = 0;
        int sandboxF = 0;
        int notAttempted = 0;
        int introduced = 0;
        for (AppFindings app : apps) {
            for (Finding f : app.findings()) {
                if (f instanceof Finding.NeedsValidation nv
                        && nv.blockers().stream().anyMatch(b -> b.startsWith(INTRODUCED_PREFIX))) {
                    introduced++;
                }
                if (!(f instanceof Finding.Confirmed c)) {
                    continue;
                }
                confirmed++;
                List<FixAttempt> list = c.secpipe().fixAttempts();
                attempts += list.size();
                for (FixAttempt a : list) {
                    if (isUnverified(a)) {
                        unverified++;
                    } else if (a.outcome() == FixAttempt.Outcome.SANDBOX_UNAVAILABLE) {
                        sandbox++;
                    } else if (a.outcome() != FixAttempt.Outcome.FIXED) {
                        failed++;
                    }
                }
                String status = finalStatus(c.secpipe(), list.isEmpty() ? Optional.empty()
                        : Optional.of(list.get(list.size() - 1)));
                switch (status) {
                    case "fixed" -> {
                        fixed++;
                        onFixed += list.size();
                    }
                    case "fix unverified" -> unverifiedF++;
                    case "sandbox unavailable" -> sandboxF++;
                    case "not_attempted" -> notAttempted++;
                    default -> { }
                }
            }
        }
        return new Totals(confirmed, fixed, attempts, onFixed, failed, unverified, sandbox, unverifiedF, sandboxF,
                notAttempted, introduced);
    }

    public static String toMarkdown(List<AppFindings> apps, String patchBase, boolean pending) {
        List<Row> rows = rows(apps, patchBase);
        Totals t = totals(apps);
        StringBuilder sb = new StringBuilder("# Remediation scorecard\n\n");
        if (pending) {
            sb.append("Status: ").append(PLACEHOLDER_NOTE).append(".\n\n");
        }
        sb.append("## Summary\n\n| Metric | Value |\n|---|---|\n");
        sb.append("| Confirmed findings | ").append(t.confirmed()).append(" |\n");
        sb.append("| Fixed | ").append(t.fixed()).append(" |\n");
        sb.append("| Fix rate (fixed / confirmed) | ")
                .append(t.confirmed() == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%", 100.0 * t.fixed() / t.confirmed()))
                .append(" |\n");
        sb.append("| Attempts per fix (all attempts / fixed) | ")
                .append(t.fixed() == 0 ? "n/a" : String.format(Locale.ROOT, "%.2f", (double) t.attempts() / t.fixed()))
                .append(" |\n");
        sb.append("| Attempts (total) | ").append(t.attempts()).append(" |\n");
        sb.append("| Failed attempts | ").append(t.failedAttempts()).append(" |\n");
        sb.append("| Fix unverified (attempts / findings) | ").append(t.unverifiedAttempts()).append(" / ")
                .append(t.unverifiedFindings()).append(" |\n");
        sb.append("| Sandbox unavailable (attempts / findings) | ").append(t.sandboxUnavailableAttempts()).append(" / ")
                .append(t.sandboxUnavailableFindings()).append(" |\n");
        sb.append("| Not attempted | ").append(t.notAttempted()).append(" |\n");
        sb.append("| Introduced findings | ").append(t.introducedFindings()).append(" |\n\n");
        sb.append("Fix unverified and sandbox unavailable attempts are not failures and not verified fixes. ")
                .append("Attempts per fix counts every attempt, including failed ones.\n\n");
        sb.append("## Findings\n\n");
        if (rows.isEmpty()) {
            sb.append("No confirmed findings.\n");
            return sb.toString();
        }
        sb.append(HEADER);
        for (Row r : rows) {
            sb.append("| ").append(String.join(" | ", r.app(), r.fingerprint(), r.cwe(), Integer.toString(r.attempts()),
                    r.preFix(), r.postFix(), r.suite(), r.rescan(), Integer.toString(r.newFindings()), r.adequacy(),
                    r.finalStatus(), r.patch())).append(" |\n");
        }
        return sb.toString();
    }

    private static String passFail(boolean b) {
        return b ? "pass" : "fail";
    }

    private static String name(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    /** CLI entry point; exit 0 ok, 2 usage or input error (one-line problem plus a {@code hint:} line). */
    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Path out = null;
        String patchBase = "";
        boolean pending = false;
        List<Path> inputs = new ArrayList<>();
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--out", "--patch-base" -> {
                        if (i + 1 >= args.length) {
                            return usage("option " + args[i] + " needs a value");
                        }
                        String v = args[++i];
                        if (args[i - 1].equals("--out")) {
                            out = Path.of(v);
                        } else {
                            patchBase = v;
                        }
                    }
                    case "--pending" -> pending = true;
                    default -> {
                        if (args[i].startsWith("--")) {
                            return usage("unknown option " + args[i]);
                        }
                        inputs.add(Path.of(args[i]));
                    }
                }
            }
            if (out == null || inputs.isEmpty()) {
                return usage("--out and at least one findings.json are required");
            }
            List<AppFindings> apps = new ArrayList<>();
            for (Path p : inputs) {
                Path abs = p.toAbsolutePath().normalize();
                apps.add(new AppFindings(String.valueOf(abs.getParent().getFileName()), ContractJson.readFindings(abs)));
            }
            apps.sort(Comparator.comparing(AppFindings::app));
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(out, toMarkdown(apps, patchBase, pending), StandardCharsets.UTF_8);
            return 0;
        } catch (RuntimeException | IOException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println("hint: check the findings.json files exist and were produced by secpipe scan");
            return 2;
        }
    }

    private static int usage(String problem) {
        System.err.println("error: " + problem);
        System.err.println("hint: RemediationScorecard [--patch-base PREFIX] [--pending] --out FILE findings.json...");
        return 2;
    }
}
