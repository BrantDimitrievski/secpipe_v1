package ca.carleton.secpipe.agents.remediation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java unified diff parser and applier (WO-G.10). No {@code patch} or {@code git} binary is involved.
 *
 * <p>Supported: several files per diff, several hunks per file, new files ({@code --- /dev/null}), deleted files
 * ({@code +++ /dev/null}), optional {@code a/} / {@code b/} prefixes, git header lines ({@code diff --git},
 * {@code index}, mode lines) between files, and {@code \ No newline at end of file}. CRLF is handled on both sides:
 * a trailing {@code \r} on diff lines is ignored when matching, and a patched file keeps its own line ending.
 *
 * <p>Matching uses fuzz 0: every context and removed line must match exactly. A hunk may be found at an offset from
 * the line its header names (the nearest exact match wins, searched after the previous hunk), as GNU patch does.
 * {@link #apply} computes every new file in memory first and only then writes, so a failing diff writes nothing.
 * Every path must stay inside {@code root}; {@link PatchChecker} adds the policy checks.
 */
public final class UnifiedDiff {

    /** The path marker for an absent side (new or deleted file). */
    public static final String DEV_NULL = "/dev/null";

    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    private UnifiedDiff() {
    }

    /** A diff that cannot be parsed or does not apply. The message names the file and hunk. */
    public static final class PatchException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public PatchException(String message) {
            super(message);
        }
    }

    /** One hunk line: {@code op} is {@code ' '} (context), {@code '-'} (removed) or {@code '+'} (added). */
    public record Line(char op, String text) {
        public Line {
            if (op != ' ' && op != '-' && op != '+') {
                throw new IllegalArgumentException("bad hunk line op: " + op);
            }
            Objects.requireNonNull(text, "text");
        }
    }

    /** One {@code @@ -a,b +c,d @@} hunk. */
    public record Hunk(int oldStart, int oldCount, int newStart, int newCount, List<Line> lines) {
        public Hunk {
            lines = List.copyOf(lines);
        }

        List<String> oldSide() {
            return lines.stream().filter(l -> l.op() != '+').map(Line::text).toList();
        }

        List<String> newSide() {
            return lines.stream().filter(l -> l.op() != '-').map(Line::text).toList();
        }
    }

    /**
     * The hunks for one file. Paths have their {@code a/}/{@code b/} prefix removed; an absent side is
     * {@link #DEV_NULL}. {@code newNoEol} is true when the new side ends without a final newline.
     */
    public record FilePatch(String oldPath, String newPath, List<Hunk> hunks, boolean newNoEol) {
        public FilePatch {
            Objects.requireNonNull(oldPath, "oldPath");
            Objects.requireNonNull(newPath, "newPath");
            hunks = List.copyOf(hunks);
        }

        /** The repo-relative path this patch changes (the new path, or the old one for a deletion). */
        public String path() {
            return isDeletion() ? oldPath : newPath;
        }

        public boolean isCreation() {
            return DEV_NULL.equals(oldPath);
        }

        public boolean isDeletion() {
            return DEV_NULL.equals(newPath);
        }

        /** Every path named by this patch (both sides when it renames), without {@link #DEV_NULL}. */
        public List<String> paths() {
            List<String> out = new ArrayList<>();
            if (!isCreation()) {
                out.add(oldPath);
            }
            if (!isDeletion() && !newPath.equals(oldPath)) {
                out.add(newPath);
            }
            return List.copyOf(out);
        }
    }

    /** Outcome of {@link #dryRun}: {@code problems} is empty when the diff applies cleanly. */
    public record DryRun(List<String> files, List<String> problems) {
        public DryRun {
            files = List.copyOf(files);
            problems = List.copyOf(problems);
        }

        public boolean applies() {
            return problems.isEmpty();
        }
    }

    /**
     * Parses {@code diff}.
     *
     * @throws PatchException if it is not a well-formed unified diff with at least one file and one hunk per file
     */
    public static List<FilePatch> parse(String diff) {
        Objects.requireNonNull(diff, "diff");
        String[] raw = diff.split("\n", -1);
        int n = raw.length;
        if (n > 0 && raw[n - 1].isEmpty()) {
            n--;
        }
        List<String> lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            lines.add(stripCr(raw[i]));
        }
        List<FilePatch> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String l = lines.get(i);
            if (l.startsWith("--- ")) {
                i = parseFile(lines, i, out);
            } else if (isGitHeader(l) || l.isEmpty()) {
                i++;
            } else {
                throw new PatchException("line " + (i + 1) + ": unexpected text outside a file patch: " + clip(l));
            }
        }
        if (out.isEmpty()) {
            throw new PatchException("no file patches found (expected '--- ' and '+++ ' headers)");
        }
        return List.copyOf(out);
    }

    /** Checks whether {@code diff} applies cleanly under {@code root} without writing anything. */
    public static DryRun dryRun(Path root, String diff) {
        List<FilePatch> patches;
        try {
            patches = parse(diff);
        } catch (PatchException e) {
            return new DryRun(List.of(), List.of(e.getMessage()));
        }
        List<String> files = patches.stream().map(FilePatch::path).toList();
        try {
            compute(root, patches);
            return new DryRun(files, List.of());
        } catch (PatchException e) {
            return new DryRun(files, List.of(e.getMessage()));
        }
    }

    /**
     * Applies {@code diff} under {@code root} (a scratch copy). Nothing is written unless every hunk applies.
     *
     * @return the changed repo-relative paths, in diff order
     * @throws PatchException if the diff is malformed or does not apply
     */
    public static List<String> apply(Path root, String diff) {
        List<FilePatch> patches = parse(diff);
        Map<Path, Optional<String>> result = compute(root, patches);
        try {
            for (Map.Entry<Path, Optional<String>> e : result.entrySet()) {
                if (e.getValue().isEmpty()) {
                    Files.deleteIfExists(e.getKey());
                } else {
                    Path parent = e.getKey().getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.writeString(e.getKey(), e.getValue().get(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write patched file: " + e.getMessage(), e);
        }
        return patches.stream().map(FilePatch::path).toList();
    }

    /**
     * Resolves a diff path under {@code root}, refusing absolute paths, {@code ..} segments and symlink escapes.
     *
     * @throws PatchException if the path would leave {@code root}
     */
    static Path resolveInside(Path root, String path) {
        if (path.isEmpty() || path.startsWith("/") || path.startsWith("\\") || path.contains("\\")
                || path.matches("^[A-Za-z]:.*") || path.contains("\0")) {
            throw new PatchException(path + ": path is absolute or not a plain repo-relative path");
        }
        for (String seg : path.split("/")) {
            if (seg.equals("..") || seg.equals(".") || seg.isEmpty()) {
                throw new PatchException(path + ": path has an empty, '.' or '..' segment");
            }
        }
        Path base = root.toAbsolutePath().normalize();
        Path p = base.resolve(path).normalize();
        if (!p.startsWith(base)) {
            throw new PatchException(path + ": path is outside the repository");
        }
        try {
            Path realBase = base.toRealPath();
            Path existing = p;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing != null && !existing.toRealPath().startsWith(realBase)) {
                throw new PatchException(path + ": path resolves outside the repository (symlink)");
            }
        } catch (IOException e) {
            throw new PatchException(path + ": cannot resolve path: " + e.getMessage());
        }
        return p;
    }

    private static int parseFile(List<String> lines, int start, List<FilePatch> out) {
        String oldPath = path(lines.get(start).substring(4), "a/");
        if (start + 1 >= lines.size() || !lines.get(start + 1).startsWith("+++ ")) {
            throw new PatchException("line " + (start + 2) + ": expected '+++ ' after '--- '");
        }
        String newPath = path(lines.get(start + 1).substring(4), "b/");
        if (DEV_NULL.equals(oldPath) && DEV_NULL.equals(newPath)) {
            throw new PatchException("line " + (start + 1) + ": both sides are /dev/null");
        }
        int i = start + 2;
        List<Hunk> hunks = new ArrayList<>();
        boolean newNoEol = false;
        while (i < lines.size() && lines.get(i).startsWith("@@")) {
            Matcher m = HUNK.matcher(lines.get(i));
            if (!m.matches()) {
                throw new PatchException("line " + (i + 1) + ": bad hunk header: " + clip(lines.get(i)));
            }
            int oldStart = Integer.parseInt(m.group(1));
            int oldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            int newStart = Integer.parseInt(m.group(3));
            int newCount = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
            i++;
            List<Line> body = new ArrayList<>();
            int seenOld = 0;
            int seenNew = 0;
            char lastOp = ' ';
            while (i < lines.size() && (seenOld < oldCount || seenNew < newCount)) {
                String l = lines.get(i);
                if (l.startsWith("\\")) {
                    if (lastOp != '-') {
                        newNoEol = true;
                    }
                    i++;
                    continue;
                }
                char op = l.isEmpty() ? ' ' : l.charAt(0);
                String text = l.isEmpty() ? "" : l.substring(1);
                switch (op) {
                    case ' ' -> {
                        seenOld++;
                        seenNew++;
                    }
                    case '-' -> seenOld++;
                    case '+' -> seenNew++;
                    default -> throw new PatchException("line " + (i + 1) + ": bad hunk line: " + clip(l));
                }
                body.add(new Line(op, text));
                lastOp = op;
                i++;
            }
            if (seenOld != oldCount || seenNew != newCount) {
                throw new PatchException(newPath + ": hunk at line " + oldStart + " has " + seenOld + " old and "
                        + seenNew + " new lines, header says " + oldCount + " and " + newCount);
            }
            while (i < lines.size() && lines.get(i).startsWith("\\")) {
                if (lastOp != '-') {
                    newNoEol = true;
                }
                i++;
            }
            hunks.add(new Hunk(oldStart, oldCount, newStart, newCount, body));
        }
        if (hunks.isEmpty()) {
            throw new PatchException((DEV_NULL.equals(newPath) ? oldPath : newPath) + ": file patch has no hunks");
        }
        out.add(new FilePatch(oldPath, newPath, hunks, newNoEol));
        return i;
    }

    /** New contents (empty = delete) for every touched file, keyed by absolute path, in diff order. */
    private static Map<Path, Optional<String>> compute(Path root, List<FilePatch> patches) {
        Map<Path, Optional<String>> result = new LinkedHashMap<>();
        for (FilePatch fp : patches) {
            for (String p : fp.paths()) {
                resolveInside(root, p);
            }
            Path target = resolveInside(root, fp.path());
            if (result.containsKey(target)) {
                throw new PatchException(fp.path() + ": file appears twice in the diff");
            }
            List<String> current;
            String eol = "\n";
            boolean finalNewline = true;
            if (fp.isCreation()) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new PatchException(fp.path() + ": new file already exists");
                }
                current = new ArrayList<>();
            } else {
                Path source = resolveInside(root, fp.oldPath());
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    throw new PatchException(fp.oldPath() + ": file does not exist");
                }
                String text;
                try {
                    text = Files.readString(source, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new PatchException(fp.oldPath() + ": cannot read: " + e.getMessage());
                }
                if (text.contains("\r\n")) {
                    eol = "\r\n";
                }
                finalNewline = text.isEmpty() || text.endsWith("\n");
                current = splitLines(text);
                if (!fp.newPath().equals(fp.oldPath()) && !fp.isDeletion()) {
                    result.put(source, Optional.empty());
                }
            }
            List<String> patched = applyHunks(fp, current);
            if (fp.isDeletion()) {
                if (!patched.isEmpty()) {
                    throw new PatchException(fp.oldPath() + ": deletion leaves " + patched.size() + " lines");
                }
                result.put(target, Optional.empty());
                continue;
            }
            boolean endNewline = fp.newNoEol() ? false : (fp.isCreation() || finalNewline || touchesEnd(fp, current));
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < patched.size(); k++) {
                b.append(patched.get(k));
                if (k < patched.size() - 1 || endNewline) {
                    b.append(eol);
                }
            }
            result.put(target, Optional.of(b.toString()));
        }
        return result;
    }

    private static boolean touchesEnd(FilePatch fp, List<String> current) {
        Hunk last = fp.hunks().get(fp.hunks().size() - 1);
        return last.oldStart() + last.oldCount() - 1 >= current.size();
    }

    private static List<String> applyHunks(FilePatch fp, List<String> current) {
        List<String> out = new ArrayList<>();
        int cursor = 0;
        int index = 0;
        for (Hunk h : fp.hunks()) {
            index++;
            List<String> old = h.oldSide();
            int expected = Math.max(0, h.oldCount() == 0 ? h.oldStart() : h.oldStart() - 1);
            int at = find(current, old, expected, cursor);
            if (at < 0) {
                throw new PatchException(fp.path() + ": hunk " + index + " (@@ -" + h.oldStart() + "," + h.oldCount()
                        + ") does not match the file (fuzz 0)");
            }
            out.addAll(current.subList(cursor, at));
            out.addAll(h.newSide());
            cursor = at + old.size();
        }
        out.addAll(current.subList(cursor, current.size()));
        return out;
    }

    /** The match position nearest to {@code expected} at or after {@code from}, or -1. */
    private static int find(List<String> lines, List<String> old, int expected, int from) {
        int max = lines.size() - old.size();
        if (max < from) {
            return -1;
        }
        int start = Math.min(Math.max(expected, from), max);
        for (int d = 0; start - d >= from || start + d <= max; d++) {
            if (start - d >= from && matchesAt(lines, old, start - d)) {
                return start - d;
            }
            if (d > 0 && start + d <= max && matchesAt(lines, old, start + d)) {
                return start + d;
            }
        }
        return -1;
    }

    private static boolean matchesAt(List<String> lines, List<String> old, int at) {
        for (int k = 0; k < old.size(); k++) {
            if (!lines.get(at + k).equals(old.get(k))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) {
            return out;
        }
        String[] parts = text.split("\n", -1);
        int n = text.endsWith("\n") ? parts.length - 1 : parts.length;
        for (int k = 0; k < n; k++) {
            out.add(stripCr(parts[k]));
        }
        return out;
    }

    private static String path(String header, String prefix) {
        String p = header;
        int tab = p.indexOf('\t');
        if (tab >= 0) {
            p = p.substring(0, tab);
        }
        p = p.strip();
        if (p.equals(DEV_NULL)) {
            return DEV_NULL;
        }
        if (p.startsWith(prefix)) {
            p = p.substring(prefix.length());
        }
        if (p.isEmpty()) {
            throw new PatchException("empty path in file header '" + clip(header) + "'");
        }
        return p;
    }

    private static boolean isGitHeader(String l) {
        return l.startsWith("diff ") || l.startsWith("index ") || l.startsWith("new file mode ")
                || l.startsWith("deleted file mode ") || l.startsWith("old mode ") || l.startsWith("new mode ")
                || l.startsWith("similarity index ");
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private static String clip(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
