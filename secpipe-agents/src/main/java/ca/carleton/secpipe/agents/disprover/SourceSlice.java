package ca.carleton.secpipe.agents.disprover;

import ca.carleton.secpipe.contracts.spi.SourceIndex;

import java.util.Objects;

/**
 * Lines {@code fromLine..toLine} (1-based, inclusive) of a repo file, shown to the disprover. {@code content} is raw
 * repository text: it reaches a prompt only through {@code PromptRenderer.untrusted(...)}.
 */
public record SourceSlice(String path, int fromLine, int toLine, String content) {

    public SourceSlice {
        SourceIndex.requireRelativePath(path);
        Objects.requireNonNull(content, "content");
        if (fromLine < 1 || toLine < fromLine) {
            throw new IllegalArgumentException("bad line range " + fromLine + "-" + toLine + " for " + path);
        }
    }
}
