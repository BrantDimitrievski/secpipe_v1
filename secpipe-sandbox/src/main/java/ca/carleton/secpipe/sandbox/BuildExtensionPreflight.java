package ca.carleton.secpipe.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ADR-03 pre-flight: Maven build extensions execute code while the POM loads, before any goal runs, so a target that
 * declares them is refused unless the user passed {@code --allow-build-extensions}.
 *
 * <p>Flags {@code .mvn/extensions.xml} at the target root and every {@code pom.xml} (outside {@code target/},
 * {@code .git/} and {@code node_modules/}) that, outside XML comments, contains {@code <extensions>true</extensions>}
 * or an {@code <extensions>} element with an {@code <extension>} child ({@code <build><extensions>}). The check is
 * purely textual and runs on the host without executing anything, so extensions inherited from a parent POM that is
 * not in the target tree (a remote parent) are not detected.
 */
public final class BuildExtensionPreflight {

    /** Blocker name recorded when the pre-flight refuses a target. */
    public static final String BLOCKER = "build-extensions";

    private static final Set<String> SKIP_DIRS = Set.of("target", ".git", "node_modules");
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern EXTENSIONS_TRUE = Pattern.compile("<extensions>\\s*true\\s*</extensions>");
    private static final Pattern EXTENSIONS_BLOCK = Pattern.compile("<extensions\\b[^>]*>(.*?)</extensions>",
        Pattern.DOTALL);
    private static final Pattern EXTENSION = Pattern.compile("<extension\\b");

    /** One reason to refuse: a target-relative path (forward slashes) and what it declares. */
    public record Finding(String path, String reason) { }

    private BuildExtensionPreflight() {
    }

    /** All findings under {@code root}, sorted by path. Empty means the target may be built. */
    public static List<Finding> check(Path root) {
        List<Finding> out = new ArrayList<>();
        Path extensionsXml = root.resolve(".mvn").resolve("extensions.xml");
        if (Files.exists(extensionsXml, LinkOption.NOFOLLOW_LINKS)) {
            out.add(new Finding(".mvn/extensions.xml", "declares core build extensions"));
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isRegularFile() && file.getFileName().toString().equals("pom.xml")) {
                        String pom = COMMENT.matcher(Files.readString(file, StandardCharsets.UTF_8)).replaceAll("");
                        if (EXTENSIONS_TRUE.matcher(pom).find()) {
                            out.add(new Finding(relative(root, file),
                                "declares a plugin with <extensions>true</extensions>"));
                        }
                        if (declaresBuildExtensions(pom)) {
                            out.add(new Finding(relative(root, file),
                                "declares <build><extensions><extension> build extensions"));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot scan " + root + " for build extensions: " + e.getMessage(), e);
        }
        out.sort(Comparator.comparing(Finding::path));
        return List.copyOf(out);
    }

    /** True when the POM text enables extensions of either kind outside XML comments. */
    static boolean declaresExtensions(String pom) {
        String stripped = COMMENT.matcher(pom).replaceAll("");
        return EXTENSIONS_TRUE.matcher(stripped).find() || declaresBuildExtensions(stripped);
    }

    /** An {@code <extensions>} element that contains an {@code <extension>} (comments already stripped). */
    private static boolean declaresBuildExtensions(String strippedPom) {
        Matcher m = EXTENSIONS_BLOCK.matcher(strippedPom);
        while (m.find()) {
            if (EXTENSION.matcher(m.group(1)).find()) {
                return true;
            }
        }
        return false;
    }

    /** User-facing refusal: a one-line problem plus a one-line {@code hint:}. */
    public static String message(List<Finding> findings) {
        StringBuilder sb = new StringBuilder("target declares Maven build extensions, which run code at load:");
        for (Finding f : findings) {
            sb.append(' ').append(f.path()).append(" (").append(f.reason()).append(')');
        }
        return sb.append("\nhint: review them, then re-run with --allow-build-extensions (extensions inherited from a")
            .append(" remote parent POM cannot be detected by this text scan)").toString();
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
