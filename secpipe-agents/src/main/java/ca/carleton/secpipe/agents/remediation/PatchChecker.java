package ca.carleton.secpipe.agents.remediation;

import ca.carleton.secpipe.agents.remediation.UnifiedDiff.FilePatch;
import ca.carleton.secpipe.agents.remediation.UnifiedDiff.Hunk;
import ca.carleton.secpipe.agents.remediation.UnifiedDiff.Line;
import ca.carleton.secpipe.agents.remediation.UnifiedDiff.PatchException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static policy check of a remediation diff before anything applies it (WO-G.10). Pure Java, no {@code git}.
 *
 * <p>Rejects a diff that: does not parse; touches more than {@link #MAX_FILES} files; names a path outside the
 * repository (absolute, {@code ..}, symlink escape); edits the regression test; edits a build file
 * ({@code pom.xml}, {@code mvnw}, {@code mvnw.cmd}, {@code .mvn/**}, Gradle build files); edits anything under
 * {@code src/test/}; adds {@code @SuppressWarnings}; or adds an empty {@code catch} block (comments only count as
 * empty). A rejection means the attempt is {@code refused}; the checker never edits the diff.
 */
public final class PatchChecker {

    /** Most files one remediation diff may touch (schema {@code files_changed.maxItems}). */
    public static final int MAX_FILES = 5;

    private static final Set<String> BUILD_FILE_NAMES = Set.of("pom.xml", "mvnw", "mvnw.cmd", "build.gradle",
            "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradlew", "gradlew.bat",
            "gradle.properties", "build.xml");
    private static final Pattern EMPTY_CATCH = Pattern.compile(
            "catch\\s*\\([^)]*\\)\\s*\\{(?:\\s|//[^\\n]*\\n|/\\*.*?\\*/)*}", Pattern.DOTALL);

    private PatchChecker() {
    }

    /** Why a diff was rejected. */
    public enum Kind {
        UNPARSEABLE, TOO_MANY_FILES, OUTSIDE_REPO, REGRESSION_TEST, BUILD_FILE, TEST_SOURCE, SUPPRESS_WARNINGS,
        EMPTY_CATCH
    }

    /** One rejection; {@code path} is empty for whole-diff problems. */
    public record Violation(Kind kind, String path, String detail) {
        public Violation {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(detail, "detail");
        }

        @Override
        public String toString() {
            return kind + (path.isEmpty() ? "" : " " + path) + ": " + detail;
        }
    }

    /**
     * Checks {@code diff} against the policy.
     *
     * @param repoRoot           the repository (or scratch copy) the diff is meant for
     * @param regressionTestPath repo-relative path of the failing regression test the fix must not edit
     * @return the violations sorted by kind, path and detail; empty means the diff may be dry-run and applied
     */
    public static List<Violation> check(String diff, Path repoRoot, String regressionTestPath) {
        Objects.requireNonNull(diff, "diff");
        Objects.requireNonNull(repoRoot, "repoRoot");
        Objects.requireNonNull(regressionTestPath, "regressionTestPath");
        List<FilePatch> patches;
        try {
            patches = UnifiedDiff.parse(diff);
        } catch (PatchException e) {
            return List.of(new Violation(Kind.UNPARSEABLE, "", e.getMessage()));
        }
        TreeSet<Violation> out = new TreeSet<>(Comparator.comparing(Violation::kind)
                .thenComparing(Violation::path).thenComparing(Violation::detail));
        Set<String> paths = new LinkedHashSet<>();
        for (FilePatch fp : patches) {
            paths.addAll(fp.paths());
        }
        if (paths.size() > MAX_FILES) {
            out.add(new Violation(Kind.TOO_MANY_FILES, "", paths.size() + " files changed, at most " + MAX_FILES
                    + " allowed"));
        }
        String testPath = regressionTestPath.replace('\\', '/');
        for (String p : paths) {
            try {
                UnifiedDiff.resolveInside(repoRoot, p);
            } catch (PatchException e) {
                out.add(new Violation(Kind.OUTSIDE_REPO, p, e.getMessage()));
                continue;
            }
            if (p.equals(testPath)) {
                out.add(new Violation(Kind.REGRESSION_TEST, p, "the fix must not edit the regression test"));
            }
            if (isBuildFile(p)) {
                out.add(new Violation(Kind.BUILD_FILE, p, "build files may not be changed by a fix"));
            }
            if (p.startsWith("src/test/") || p.contains("/src/test/")) {
                out.add(new Violation(Kind.TEST_SOURCE, p, "test sources may not be changed by a fix"));
            }
        }
        for (FilePatch fp : patches) {
            for (Hunk h : fp.hunks()) {
                checkAdded(fp.path(), h, out);
            }
        }
        return List.copyOf(out);
    }

    private static boolean isBuildFile(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return BUILD_FILE_NAMES.contains(name) || path.startsWith(".mvn/") || path.contains("/.mvn/")
                || path.startsWith("gradle/wrapper/") || path.contains("/gradle/wrapper/");
    }

    private static void checkAdded(String path, Hunk h, Set<Violation> out) {
        StringBuilder text = new StringBuilder();
        List<int[]> addedSpans = new ArrayList<>();
        for (Line l : h.lines()) {
            if (l.op() == '-') {
                continue;
            }
            int start = text.length();
            text.append(l.text()).append('\n');
            if (l.op() == '+') {
                addedSpans.add(new int[] {start, text.length()});
                if (l.text().contains("@SuppressWarnings")) {
                    out.add(new Violation(Kind.SUPPRESS_WARNINGS, path, "added line suppresses warnings: "
                            + l.text().strip()));
                }
            }
        }
        Matcher m = EMPTY_CATCH.matcher(text);
        while (m.find()) {
            for (int[] span : addedSpans) {
                if (span[0] < m.end() && m.start() < span[1]) {
                    out.add(new Violation(Kind.EMPTY_CATCH, path, "added an empty catch block (hunk at line "
                            + h.newStart() + ")"));
                    break;
                }
            }
        }
    }
}
