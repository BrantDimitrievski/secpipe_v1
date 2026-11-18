package ca.carleton.secpipe.agents.disprover;

import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.ProjectProfile;
import ca.carleton.secpipe.contracts.spi.SourceIndex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Read-only {@link SourceIndex} over the target for the disprover node. {@code secpipe-agents} may not depend on
 * {@code secpipe-core}'s {@code FileSourceIndex}, so this is a minimal equivalent: the files are the profile's source
 * and test files (or, without a profile, every {@code .java} file under the target outside build and VCS folders).
 * It only reads files; symbolic links are not followed.
 */
final class ProjectSourceIndex implements SourceIndex {

    private static final Set<String> SKIPPED = Set.of(".git", ".hg", ".svn", ".idea", ".gradle", ".mvn", "target",
            "build", "node_modules");

    private final Path root;
    private final List<String> files;
    private final Set<String> fileSet;
    private final Map<String, Optional<List<String>>> cache = new ConcurrentHashMap<>();

    private ProjectSourceIndex(Path root, List<String> files) {
        this.root = root.toAbsolutePath().normalize();
        TreeSet<String> sorted = new TreeSet<>();
        for (String f : files) {
            try {
                sorted.add(SourceIndex.requireRelativePath(f));
            } catch (IllegalArgumentException e) {
                // not a canonical repo path: never indexed
            }
        }
        this.files = List.copyOf(sorted);
        this.fileSet = Set.copyOf(sorted);
    }

    static ProjectSourceIndex of(PipelineState state) {
        Optional<ProjectProfile> profile = state.profile();
        if (profile.isPresent()) {
            List<String> all = new ArrayList<>(profile.get().sourceFiles());
            all.addAll(profile.get().testSourceFiles());
            return new ProjectSourceIndex(profile.get().root(), all);
        }
        return scan(state.config().target());
    }

    static ProjectSourceIndex scan(Path root) {
        Path base = root.toAbsolutePath().normalize();
        List<String> found = new ArrayList<>();
        if (Files.isDirectory(base)) {
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .map(p -> base.relativize(p))
                        .filter(rel -> {
                            for (Path seg : rel) {
                                if (SKIPPED.contains(seg.toString())) {
                                    return false;
                                }
                            }
                            return true;
                        })
                        .forEach(rel -> found.add(rel.toString().replace('\\', '/')));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot list " + base, e);
            }
        }
        return new ProjectSourceIndex(base, found);
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
        if (!exists(path)) {
            return Optional.empty();
        }
        return cache.computeIfAbsent(path, p -> {
            Path file = root.resolve(p);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            try {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                if (text.startsWith("﻿")) {
                    text = text.substring(1);
                }
                List<String> lines = new ArrayList<>(List.of(text.split("\r\n|\r|\n", -1)));
                if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                    lines.remove(lines.size() - 1);
                }
                return Optional.of(List.copyOf(lines));
            } catch (IOException e) {
                return Optional.empty();
            }
        });
    }

    @Override
    public Optional<String> line(String path, int oneBasedLine) {
        return lines(path).filter(l -> oneBasedLine >= 1 && oneBasedLine <= l.size())
                .map(l -> l.get(oneBasedLine - 1));
    }
}
