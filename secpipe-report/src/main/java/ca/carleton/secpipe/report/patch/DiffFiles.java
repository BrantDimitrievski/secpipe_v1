package ca.carleton.secpipe.report.patch;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small unified-diff reader for the SARIF {@code fixes[]} (the report module does not depend on the agents
 * module). It reads modified files only; a diff that creates or deletes a file, or that cannot be read, gives an
 * empty result so no half-described fix is emitted.
 */
public final class DiffFiles {

    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    private DiffFiles() {
    }

    /** One hunk: the old range and the text that replaces it (context and added lines, each ending in LF). */
    public record Hunk(int oldStart, int oldCount, String newText) {
    }

    /** One modified file with its hunks in diff order. */
    public record FileDiff(String path, List<Hunk> hunks) {
    }

    /** The files of {@code diff}, or empty when the diff is malformed or not a plain modification. */
    public static Optional<List<FileDiff>> parse(String diff) {
        List<FileDiff> files = new ArrayList<>();
        String lf = diff.replace("\r\n", "\n");
        String[] lines = (lf.endsWith("\n") ? lf.substring(0, lf.length() - 1) : lf).split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            if (!lines[i].startsWith("--- ")) {
                i++;
                continue;
            }
            if (i + 1 >= lines.length || !lines[i + 1].startsWith("+++ ")) {
                return Optional.empty();
            }
            String oldPath = path(lines[i].substring(4));
            String newPath = path(lines[i + 1].substring(4));
            if (oldPath.equals("/dev/null") || newPath.equals("/dev/null")) {
                return Optional.empty();
            }
            i += 2;
            List<Hunk> hunks = new ArrayList<>();
            while (i < lines.length && lines[i].startsWith("@@")) {
                Matcher m = HUNK.matcher(lines[i]);
                if (!m.matches()) {
                    return Optional.empty();
                }
                int oldStart = Integer.parseInt(m.group(1));
                int oldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
                int newCount = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
                i++;
                StringBuilder text = new StringBuilder();
                int oldSeen = 0;
                int newSeen = 0;
                while (i < lines.length && (oldSeen < oldCount || newSeen < newCount)) {
                    String l = lines[i];
                    if (l.startsWith("\\")) {
                        i++;
                        continue;
                    }
                    char op = l.isEmpty() ? ' ' : l.charAt(0);
                    String body = l.isEmpty() ? "" : l.substring(1);
                    switch (op) {
                        case ' ' -> {
                            oldSeen++;
                            newSeen++;
                            text.append(body).append('\n');
                        }
                        case '-' -> oldSeen++;
                        case '+' -> {
                            newSeen++;
                            text.append(body).append('\n');
                        }
                        default -> {
                            return Optional.empty();
                        }
                    }
                    i++;
                }
                if (oldSeen != oldCount || newSeen != newCount) {
                    return Optional.empty();
                }
                while (i < lines.length && lines[i].startsWith("\\")) {
                    i++;
                }
                hunks.add(new Hunk(oldStart, oldCount, text.toString()));
            }
            if (hunks.isEmpty()) {
                return Optional.empty();
            }
            files.add(new FileDiff(newPath, List.copyOf(hunks)));
        }
        return files.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(files));
    }

    private static String path(String header) {
        String p = header;
        int tab = p.indexOf('\t');
        if (tab >= 0) {
            p = p.substring(0, tab);
        }
        p = p.strip();
        if (p.startsWith("a/") || p.startsWith("b/")) {
            p = p.substring(2);
        }
        return p;
    }
}
