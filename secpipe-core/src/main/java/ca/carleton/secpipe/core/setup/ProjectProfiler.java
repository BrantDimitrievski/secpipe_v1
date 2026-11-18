package ca.carleton.secpipe.core.setup;

import ca.carleton.secpipe.contracts.pipeline.ProjectProfile;
import ca.carleton.secpipe.core.recon.FileSourceIndex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds the {@link ProjectProfile} skeleton for a target directory (part of {@code setup}, folded from WO-K.3).
 * Pure file inspection: it never runs the target's build or any of its code (CLAUDE.md §7).
 *
 * <p>Files that match a deny glob (the built-in privacy denylist plus {@code --deny-path}) are left out of every
 * list, so no later node analyzes or prompts with them.
 */
public final class ProjectProfiler {

    /** Built-in deny globs from PLAN §4.3 ({@code --deny-path}); always applied. */
    public static final List<String> BUILT_IN_DENY = List.of("**/secrets/**", "**/*.env", "**/*.pem", "**/*.key",
            "**/id_rsa*");

    private static final Set<String> MANIFEST_NAMES = Set.of("pom.xml", "build.gradle", "build.gradle.kts",
            "settings.gradle", "settings.gradle.kts");

    private final List<PathMatcher> deny;

    public ProjectProfiler(List<String> extraDenyGlobs) {
        Objects.requireNonNull(extraDenyGlobs, "extraDenyGlobs");
        FileSystem fs = FileSystems.getDefault();
        List<PathMatcher> matchers = new ArrayList<>();
        for (String glob : BUILT_IN_DENY) {
            addGlob(fs, matchers, glob);
        }
        for (String glob : extraDenyGlobs) {
            if (!glob.isBlank()) {
                addGlob(fs, matchers, glob.strip());
            }
        }
        this.deny = List.copyOf(matchers);
    }

    private static void addGlob(FileSystem fs, List<PathMatcher> out, String glob) {
        out.add(fs.getPathMatcher("glob:" + glob));
        // "**/x" should also match "x" at the root of the target.
        if (glob.startsWith("**/")) {
            out.add(fs.getPathMatcher("glob:" + glob.substring(3)));
        }
    }

    /** Whether the repo-relative path {@code rel} is excluded by a deny glob. */
    public boolean denied(String rel) {
        Path p = Path.of(rel);
        return deny.stream().anyMatch(m -> m.matches(p));
    }

    /** The profile of {@code target}; an absent or empty directory gives an empty profile, never an exception. */
    public ProjectProfile profile(Path target) {
        Path root = target.toAbsolutePath().normalize();
        FileSourceIndex index = FileSourceIndex.scan(root);
        List<String> sources = new ArrayList<>();
        List<String> tests = new ArrayList<>();
        List<String> manifests = new ArrayList<>();
        long loc = 0;
        for (String rel : index.files()) {
            if (denied(rel)) {
                continue;
            }
            String name = rel.substring(rel.lastIndexOf('/') + 1);
            if (MANIFEST_NAMES.contains(name)) {
                manifests.add(rel);
            }
            if (!rel.endsWith(".java")) {
                continue;
            }
            if (isTestSource(rel)) {
                tests.add(rel);
            } else {
                sources.add(rel);
                loc += index.lines(rel).map(List::size).orElse(0);
            }
        }
        return new ProjectProfile(root, "java", buildTool(root), frameworks(root, manifests), List.copyOf(sources),
                List.copyOf(tests), List.copyOf(manifests), loc);
    }

    static boolean isTestSource(String rel) {
        return rel.startsWith("src/test/") || rel.contains("/src/test/");
    }

    static ProjectProfile.BuildTool buildTool(Path root) {
        if (Files.isRegularFile(root.resolve("mvnw"))) {
            return ProjectProfile.BuildTool.MAVEN_WRAPPER;
        }
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return ProjectProfile.BuildTool.MAVEN;
        }
        if (Files.isRegularFile(root.resolve("gradlew"))) {
            return ProjectProfile.BuildTool.GRADLE_WRAPPER;
        }
        if (Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            return ProjectProfile.BuildTool.GRADLE;
        }
        return ProjectProfile.BuildTool.NONE;
    }

    /** Frameworks named by dependency coordinates in the manifests (sorted; deterministic). */
    static Set<String> frameworks(Path root, List<String> manifests) {
        Set<String> out = new TreeSet<>();
        for (String rel : manifests) {
            String text = read(root.resolve(rel)).orElse("").toLowerCase(Locale.ROOT);
            if (text.contains("spring-boot")) {
                out.add("spring-boot");
            } else if (text.contains("org.springframework")) {
                out.add("spring");
            }
            if (text.contains("javax.servlet") || text.contains("jakarta.servlet")) {
                out.add("servlet");
            }
            if (text.contains("junit-jupiter")) {
                out.add("junit5");
            }
        }
        return Set.copyOf(out);
    }

    private static Optional<String> read(Path file) {
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
