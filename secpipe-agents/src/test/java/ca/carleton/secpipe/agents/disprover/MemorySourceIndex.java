package ca.carleton.secpipe.agents.disprover;

import ca.carleton.secpipe.contracts.spi.SourceIndex;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** In-memory {@link SourceIndex} for tests. */
final class MemorySourceIndex implements SourceIndex {

    private final TreeMap<String, List<String>> files;

    MemorySourceIndex(Map<String, List<String>> files) {
        this.files = new TreeMap<>(files);
    }

    @Override
    public List<String> files() {
        return List.copyOf(files.keySet());
    }

    @Override
    public boolean exists(String path) {
        return files.containsKey(SourceIndex.requireRelativePath(path));
    }

    @Override
    public Optional<List<String>> lines(String path) {
        return Optional.ofNullable(files.get(SourceIndex.requireRelativePath(path)));
    }

    @Override
    public Optional<String> line(String path, int oneBasedLine) {
        return lines(path).filter(l -> oneBasedLine >= 1 && oneBasedLine <= l.size())
                .map(l -> l.get(oneBasedLine - 1));
    }
}
