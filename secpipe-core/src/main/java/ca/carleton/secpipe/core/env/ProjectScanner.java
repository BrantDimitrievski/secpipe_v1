package ca.carleton.secpipe.core.env;

import ca.carleton.secpipe.contracts.pipeline.ProjectProfile;
import ca.carleton.secpipe.contracts.pipeline.ProjectProfile.BuildTool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** File-system level detection: build tool, manifests, Java sources, test sources and line counts. */
public final class ProjectScanner {

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".svn", ".hg", ".idea", ".vscode", ".gradle", ".mvn", "target", "build", "out", "bin",
            "node_modules", "secpipe-out", ".settings", "generated-tests");

    private static final Set<String> MANIFEST_NAMES = Set.of("pom.xml", "build.gradle", "build.gradle.kts");

    private ProjectScanner() {
    }

    /**
     * Scans {@code root}. {@code sourceFiles} excludes test sources unless {@code includeTests}; test sources are
     * always listed in {@code testSourceFiles}. Lines of code count every line of {@code sourceFiles}.
     */
    public static ProjectProfile scan(Path root, boolean includeTests) {
        Path abs = root.toAbsolutePath().normalize();
        List<String> sources = new ArrayList<>();
        List<String> tests = new ArrayList<>();
        List<String> manifests = new ArrayList<>();
        long loc = 0;
        try {
            for (Path file : walk(abs)) {
                String rel = normalize(abs.relativize(file).toString());
                String name = file.getFileName().toString();
                if (MANIFEST_NAMES.contains(name)) {
                    manifests.add(rel);
                } else if (name.endsWith(".java")) {
                    boolean test = isTestSource(rel);
                    if (test) {
                        tests.add(rel);
                    }
                    if (!test || includeTests) {
                        sources.add(rel);
                        loc += countLines(file);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read project " + abs, e);
        }
        Collections.sort(sources);
        Collections.sort(tests);
        Collections.sort(manifests);
        return new ProjectProfile(abs, "java", detectBuildTool(abs), frameworks(abs, manifests),
                List.copyOf(sources), List.copyOf(tests), List.copyOf(manifests), loc);
    }

    public static BuildTool detectBuildTool(Path root) {
        boolean pom = Files.isRegularFile(root.resolve("pom.xml"));
        boolean gradle = Files.isRegularFile(root.resolve("build.gradle"))
                || Files.isRegularFile(root.resolve("build.gradle.kts"))
                || Files.isRegularFile(root.resolve("settings.gradle"))
                || Files.isRegularFile(root.resolve("settings.gradle.kts"));
        if (pom) {
            return hasWrapper(root, "mvnw") ? BuildTool.MAVEN_WRAPPER : BuildTool.MAVEN;
        }
        if (gradle) {
            return hasWrapper(root, "gradlew") ? BuildTool.GRADLE_WRAPPER : BuildTool.GRADLE;
        }
        return BuildTool.NONE;
    }

    static boolean hasWrapper(Path root, String name) {
        return Files.isRegularFile(root.resolve(name)) || Files.isRegularFile(root.resolve(name + ".cmd"))
                || Files.isRegularFile(root.resolve(name + ".bat"));
    }

    /** Repo-relative path with {@code /} separators, as {@code ProjectProfile} requires. */
    static String normalize(String path) {
        String p = path.replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        return p;
    }

    /** True for paths under conventional test source roots. The argument is normalized first. */
    static boolean isTestSource(String relative) {
        String p = normalize(relative);
        return p.contains("src/test/") || p.startsWith("test/") || p.contains("/test/java/")
                || p.contains("src/integrationTest/") || p.contains("src/it/");
    }

    private static Set<String> frameworks(Path root, List<String> manifests) {
        Set<String> out = new TreeSet<>();
        for (String m : manifests) {
            String text;
            try {
                text = Files.readString(root.resolve(m));
            } catch (IOException | UncheckedIOException e) {
                continue;
            }
            if (text.contains("spring-boot")) {
                out.add("spring-boot");
            }
            if (text.contains("javax.servlet") || text.contains("jakarta.servlet")) {
                out.add("servlet");
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private static long countLines(Path file) {
        try (var lines = Files.lines(file)) {
            return lines.count();
        } catch (IOException | UncheckedIOException e) {
            return 0;
        }
    }

    private static List<Path> walk(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && IGNORED_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    out.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }
}
