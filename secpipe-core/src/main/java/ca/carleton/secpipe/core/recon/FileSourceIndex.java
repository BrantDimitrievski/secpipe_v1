package ca.carleton.secpipe.core.recon;

import ca.carleton.secpipe.contracts.spi.SourceIndex;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link SourceIndex} over files on disk under a project root (WO-K.4). It only reads files; it never compiles or
 * runs them. Symbolic links are not followed. Lines are decoded as UTF-8 (malformed bytes become U+FFFD), split on
 * {@code \n}, {@code \r\n} or {@code \r}, and cached. A leading byte-order mark is dropped.
 */
public final class FileSourceIndex implements SourceIndex {

    /** Directory names never indexed by {@link #scan(Path)}: VCS metadata and build output. */
    public static final Set<String> SKIPPED_DIRS = Set.of(".git", ".hg", ".svn", ".idea", ".gradle", ".mvn",
            "target", "build", "node_modules");

    private final Path root;
    private final List<String> files;
    private final Set<String> fileSet;
    private final Map<String, Optional<List<String>>> cache = new ConcurrentHashMap<>();

    private FileSourceIndex(Path root, Collection<String> files) {
        this.root = root.toAbsolutePath().normalize();
        TreeSet<String> sorted = new TreeSet<>();
        for (String f : files) {
            sorted.add(SourceIndex.requireRelativePath(f));
        }
        this.files = List.copyOf(sorted);
        this.fileSet = Set.copyOf(sorted);
    }

    /** An index over exactly {@code files} (repo-relative POSIX paths) under {@code root}. */
    public static FileSourceIndex of(Path root, Collection<String> files) {
        return new FileSourceIndex(Objects.requireNonNull(root, "root"), Objects.requireNonNull(files, "files"));
    }

    /**
     * Indexes every regular file under {@code root}, skipping {@link #SKIPPED_DIRS}, hidden directories, symbolic
     * links and paths that are not valid repo-relative POSIX paths.
     */
    public static FileSourceIndex scan(Path root) {
        Path base = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        TreeSet<String> found = new TreeSet<>();
        if (Files.isDirectory(base)) {
            try {
                Files.walkFileTree(base, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (dir.equals(base)) {
                            return FileVisitResult.CONTINUE;
                        }
                        String name = dir.getFileName().toString();
                        return SKIPPED_DIRS.contains(name) || name.startsWith(".")
                                ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile()) {
                            String rel = toPosix(base.relativize(file));
                            if (isValid(rel)) {
                                found.add(rel);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException("cannot index " + base, e);
            }
        }
        return new FileSourceIndex(base, found);
    }

    /** The absolute, normalized project root. */
    public Path root() {
        return root;
    }

    @Override
    public List<String> files() {
        return files;
    }

    @Override
    public boolean exists(String path) {
        return fileSet.contains(SourceIndex.requireRelativePath(path));
    }

    @Override
    public Optional<List<String>> lines(String path) {
        SourceIndex.requireRelativePath(path);
        if (!fileSet.contains(path)) {
            return Optional.empty();
        }
        return cache.computeIfAbsent(path, this::read);
    }

    @Override
    public Optional<String> line(String path, int oneBasedLine) {
        return lines(path).flatMap(ls -> oneBasedLine < 1 || oneBasedLine > ls.size()
                ? Optional.empty() : Optional.of(ls.get(oneBasedLine - 1)));
    }

    private Optional<List<String>> read(String path) {
        try {
            String text = new String(Files.readAllBytes(root.resolve(path)), StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) {
                text = text.substring(1);
            }
            return Optional.of(List.copyOf(text.lines().toList()));
        } catch (IOException | UncheckedIOException e) {
            return Optional.empty();
        }
    }

    private static String toPosix(Path rel) {
        StringBuilder sb = new StringBuilder();
        for (Path part : rel) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part.toString());
        }
        return sb.toString();
    }

    private static boolean isValid(String rel) {
        try {
            SourceIndex.requireRelativePath(rel);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
