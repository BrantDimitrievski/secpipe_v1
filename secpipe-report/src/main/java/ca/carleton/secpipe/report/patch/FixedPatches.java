package ca.carleton.secpipe.report.patch;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.FixAttempt;
import ca.carleton.secpipe.contracts.finding.Finding.RemediationStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which patches are verified fixes, and the combined {@code secpipe-fixes.patch}. Only records that pass
 * {@link #verified} count. Records are taken in fingerprint order, so the output is byte-stable.
 */
public final class FixedPatches {

    /** The name of the combined patch file. */
    public static final String FILE_NAME = "secpipe-fixes.patch";

    private FixedPatches() {
    }

    /** One verified fix: the record and the patch artifact of its last attempt. */
    public record Fix(Finding.Confirmed finding, FixAttempt attempt, String artifact) {
    }

    /**
     * The one "verified fix" rule, used by report.md, SARIF {@code fixes[]} and {@value #FILE_NAME}: the record is
     * {@code confirmed}, its {@code remediation_status} is {@code fixed}, the <em>last</em> attempt's outcome is
     * {@code fixed}, and that attempt's patch artifact (a safe relative path) can be read through {@code source}.
     */
    public static Optional<Fix> verified(Finding f, PatchSource source) {
        if (!(f instanceof Finding.Confirmed c)
                || c.secpipe().remediationStatus().filter(s -> s == RemediationStatus.FIXED).isEmpty()) {
            return Optional.empty();
        }
        return lastAttempt(c).filter(a -> a.outcome() == FixAttempt.Outcome.FIXED)
                .flatMap(a -> a.patchArtifact().filter(FixedPatches::isSafeRelative)
                        .filter(p -> source.read(p).isPresent())
                        .map(p -> new Fix(c, a, p)));
    }

    /** The verified fixes (see {@link #verified}), sorted by fingerprint. */
    public static List<Fix> fixes(List<Finding> findings, PatchSource source) {
        return findings.stream()
                .sorted(Comparator.comparing(Finding::fingerprint))
                .flatMap(f -> verified(f, source).stream())
                .toList();
    }

    /** The attempt with the highest number. */
    public static Optional<FixAttempt> lastAttempt(Finding f) {
        return f.secpipe().fixAttempts().stream().max(Comparator.comparingInt(FixAttempt::attempt));
    }

    /**
     * The fixed patches concatenated in fingerprint order, or empty when no fixed record has a readable patch.
     * Each patch ends with a newline.
     */
    public static Optional<String> combine(List<Finding> findings, PatchSource source) {
        StringBuilder out = new StringBuilder();
        for (Fix fix : fixes(findings, source)) {
            source.read(fix.artifact()).filter(p -> !p.isBlank()).ifPresent(p -> {
                String lf = p.replace("\r\n", "\n");
                out.append(lf);
                if (!lf.endsWith("\n")) {
                    out.append('\n');
                }
            });
        }
        return out.length() == 0 ? Optional.empty() : Optional.of(out.toString());
    }

    /** A relative path without {@code ..}, drive letters, backslashes or a leading slash. */
    public static boolean isSafeRelative(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.indexOf('\\') >= 0
                || path.indexOf(':') >= 0) {
            return false;
        }
        for (String part : path.split("/", -1)) {
            if (part.equals("..") || part.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
