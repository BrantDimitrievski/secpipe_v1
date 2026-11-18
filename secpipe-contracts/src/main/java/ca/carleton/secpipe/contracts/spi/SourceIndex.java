package ca.carleton.secpipe.contracts.spi;

import java.util.List;
import java.util.Optional;

/**
 * Read-only view of the target project's source files (WO-CC.2). Produced by recon in {@code secpipe-core}
 * ({@code FileSourceIndex}, WO-K.4) and read by agents and grounding (WO-G.1, WO-G.4, WO-G.5).
 *
 * <p>Paths are repo-relative POSIX paths ({@code src/main/java/a/B.java}): no leading {@code /}, no drive letter, no
 * backslash, no {@code .}, {@code ..} or empty segments. Every method rejects such a path with
 * {@link IllegalArgumentException} (see {@link #requireRelativePath(String)}); a well-formed path that names no
 * indexed file is never an error, it yields {@code false} or {@link Optional#empty()}.
 */
public interface SourceIndex {

    /** All indexed files, repo-relative, sorted in natural {@link String} order. Immutable. */
    List<String> files();

    /** Whether {@code path} is an indexed file. */
    boolean exists(String path);

    /** All lines of {@code path} without line terminators (immutable), or empty if it is not indexed. */
    Optional<List<String>> lines(String path);

    /**
     * Line {@code oneBasedLine} of {@code path} without its terminator; empty if the file is not indexed or the line
     * is out of range ({@code < 1} or past the end).
     */
    Optional<String> line(String path, int oneBasedLine);

    /**
     * Checks that {@code path} is a canonical repo-relative POSIX path and returns it unchanged.
     *
     * @throws IllegalArgumentException if it is null, empty, absolute, contains a backslash, NUL or drive letter,
     *                                  or has an empty, {@code .} or {@code ..} segment
     */
    static String requireRelativePath(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path must be a non-empty repo-relative path");
        }
        if (path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0
                || hasDriveLetter(path)) {
            throw new IllegalArgumentException("path must be repo-relative POSIX: " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("path must not escape or contain empty/./.. segments: " + path);
            }
        }
        return path;
    }

    private static boolean hasDriveLetter(String path) {
        if (path.length() < 2 || path.charAt(1) != ':') {
            return false;
        }
        char c = path.charAt(0);
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }
}
