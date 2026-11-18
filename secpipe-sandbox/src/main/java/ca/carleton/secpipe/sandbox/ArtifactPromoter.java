package ca.carleton.secpipe.sandbox;

import ca.carleton.secpipe.contracts.spi.SandboxRunner.Promotion;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The only code that copies files out of sandbox scratch (feature K). It reads the tar stream the container produced
 * and writes an entry to {@code outDir} only when the entry is a regular file, its path is exactly one of the declared
 * {@link Promotion} paths, and its size is at most that promotion's {@code maxBytes}. Symlinks, hard links, devices,
 * directories, undeclared or duplicate entries and oversize files are rejected. Nothing is ever written through a
 * symlink under {@code outDir}. Supports ustar, GNU long names and pax {@code path}/{@code size} records.
 */
public final class ArtifactPromoter {

    /** An entry or declared path that was not promoted, and why. */
    public record Rejection(String path, String reason) { }

    /** Promoted files (sorted) and rejections (in stream order, then missing declared paths). */
    public record Report(List<Path> promoted, List<Rejection> rejected) {
        public Report {
            promoted = List.copyOf(promoted);
            rejected = List.copyOf(rejected);
        }
    }

    private static final int BLOCK = 512;

    private ArtifactPromoter() {
    }

    /** Why {@code relativePath} is not an acceptable promotion path, or empty when it is. */
    public static Optional<String> checkRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return Optional.of("empty path");
        }
        if (relativePath.startsWith("/") || relativePath.contains("\\") || relativePath.indexOf('\0') >= 0
            || relativePath.matches("^[A-Za-z]:.*")) {
            return Optional.of("not a relative path");
        }
        for (String seg : relativePath.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                return Optional.of("path must not contain empty, '.' or '..' segments");
            }
        }
        if (relativePath.startsWith("-")) {
            return Optional.of("path must not start with '-'");
        }
        return Optional.empty();
    }

    public static Report promote(InputStream tarStream, List<Promotion> declared, Path outDir) throws IOException {
        Map<String, Promotion> wanted = new LinkedHashMap<>();
        for (Promotion p : declared) {
            Optional<String> bad = checkRelativePath(p.relativePath());
            if (bad.isPresent()) {
                throw new IllegalArgumentException("bad promotion path " + p.relativePath() + ": " + bad.get());
            }
            wanted.put(p.relativePath(), p);
        }
        Files.createDirectories(outDir);
        Path root = outDir.toRealPath();
        List<Path> promoted = new ArrayList<>();
        List<Rejection> rejected = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        String longName = null;
        Map<String, String> pax = Map.of();
        byte[] header = new byte[BLOCK];
        while (true) {
            if (!readBlock(tarStream, header)) {
                break;
            }
            if (allZero(header)) {
                break;
            }
            char type = (char) header[156];
            long size = parseSize(header, 124, 12);
            if (type == 'L') {
                longName = cString(readData(tarStream, size));
                continue;
            }
            if (type == 'x') {
                pax = parsePax(readData(tarStream, size));
                continue;
            }
            if (type == 'g' || type == 'K') {
                skip(tarStream, padded(size));
                continue;
            }
            String name = longName != null ? longName : ustarName(header);
            if (pax.containsKey("path")) {
                name = pax.get("path");
            }
            if (pax.containsKey("size")) {
                size = Long.parseLong(pax.get("size").strip());
            }
            longName = null;
            pax = Map.of();
            String rel = normalize(name);
            boolean regular = type == '0' || type == '\0';
            // only regular files carry data; hard links, symlinks, dirs, devices carry none
            long dataBytes = regular || type == '7' ? size : 0;

            Promotion p = wanted.get(rel);
            String reason = null;
            if (p == null) {
                reason = "undeclared";
            } else if (!regular) {
                reason = "not a regular file (" + describe(type) + ")";
            } else if (size > p.maxBytes()) {
                reason = "oversize (" + size + " > " + p.maxBytes() + " bytes)";
            } else if (!seen.add(rel)) {
                reason = "duplicate entry";
            }
            if (reason != null) {
                rejected.add(new Rejection(rel, reason));
                skip(tarStream, padded(dataBytes));
                continue;
            }
            Optional<String> failure = write(tarStream, root, rel, size);
            skip(tarStream, padded(size) - size);
            if (failure.isPresent()) {
                rejected.add(new Rejection(rel, failure.get()));
            } else {
                promoted.add(root.resolve(rel));
            }
        }
        for (String rel : wanted.keySet()) {
            if (!seen.contains(rel) && rejected.stream().noneMatch(r -> r.path().equals(rel))) {
                rejected.add(new Rejection(rel, "missing"));
            }
        }
        promoted.sort(Comparator.naturalOrder());
        return new Report(promoted, rejected);
    }

    /** Writes exactly {@code size} bytes of the current entry. The data is always consumed, even on failure. */
    private static Optional<String> write(InputStream in, Path root, String rel, long size) throws IOException {
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) {
            skip(in, size);
            return Optional.of("escapes the output directory");
        }
        Path dir = root;
        Path parentRel = root.relativize(target).getParent();
        if (parentRel != null) {
            for (Path seg : parentRel) {
                dir = dir.resolve(seg);
                if (Files.isSymbolicLink(dir)) {
                    skip(in, size);
                    return Optional.of("a parent directory is a symlink");
                }
                if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(dir);
                } else if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                    skip(in, size);
                    return Optional.of("a parent path is not a directory");
                }
            }
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                skip(in, size);
                return Optional.of("destination exists and is not a regular file");
            }
            Files.delete(target);
        }
        long left = size;
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS)) {
            byte[] buf = new byte[8192];
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    break;
                }
                out.write(buf, 0, n);
                left -= n;
            }
        }
        if (left > 0) {
            Files.deleteIfExists(target);
            throw new EOFException("truncated tar stream in " + rel);
        }
        return Optional.empty();
    }

    private static String normalize(String name) {
        String n = name;
        while (n.startsWith("./")) {
            n = n.substring(2);
        }
        while (n.endsWith("/")) {
            n = n.substring(0, n.length() - 1);
        }
        return n;
    }

    private static String describe(char type) {
        return switch (type) {
            case '1' -> "hard link";
            case '2' -> "symlink";
            case '3', '4' -> "device";
            case '5' -> "directory";
            case '6' -> "fifo";
            default -> "type " + type;
        };
    }

    private static String ustarName(byte[] h) {
        String name = cString(h, 0, 100);
        boolean posix = h[257] == 'u' && h[258] == 's' && h[259] == 't' && h[260] == 'a' && h[261] == 'r'
            && h[262] == 0;
        if (posix) {
            String prefix = cString(h, 345, 155);
            if (!prefix.isEmpty()) {
                return prefix + "/" + name;
            }
        }
        return name;
    }

    static long parseSize(byte[] h, int off, int len) throws IOException {
        if ((h[off] & 0x80) != 0) {
            // GNU base-256: far beyond any promotion cap
            long v = 0;
            for (int i = off + 1; i < off + len; i++) {
                if (v > (Long.MAX_VALUE >> 8)) {
                    return Long.MAX_VALUE;
                }
                v = (v << 8) | (h[i] & 0xff);
            }
            return v;
        }
        int i = off;
        int end = off + len;
        while (i < end && h[i] == ' ') {
            i++;
        }
        long v = 0;
        for (; i < end; i++) {
            byte b = h[i];
            if (b == 0 || b == ' ') {
                break;
            }
            if (b < '0' || b > '7') {
                throw new IOException("bad tar header size field");
            }
            v = (v << 3) + (b - '0');
        }
        return v;
    }

    private static Map<String, String> parsePax(byte[] data) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        while (i < data.length) {
            int sp = i;
            while (sp < data.length && data[sp] != ' ') {
                sp++;
            }
            if (sp >= data.length) {
                break;
            }
            int len;
            try {
                len = Integer.parseInt(new String(data, i, sp - i, StandardCharsets.UTF_8));
            } catch (NumberFormatException e) {
                throw new IOException("bad pax record");
            }
            if (len <= 0 || i + len > data.length) {
                throw new IOException("bad pax record length");
            }
            String rec = new String(data, sp + 1, i + len - sp - 2, StandardCharsets.UTF_8);
            int eq = rec.indexOf('=');
            if (eq > 0) {
                out.put(rec.substring(0, eq), rec.substring(eq + 1));
            }
            i += len;
        }
        return out;
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size > 1024 * 1024) {
            throw new IOException("tar metadata entry too large");
        }
        byte[] data = in.readNBytes((int) size);
        if (data.length != size) {
            throw new EOFException("truncated tar stream");
        }
        skip(in, padded(size) - size);
        return data;
    }

    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int n = in.readNBytes(block, 0, BLOCK);
        if (n == 0) {
            return false;
        }
        if (n != BLOCK) {
            throw new EOFException("truncated tar header");
        }
        return true;
    }

    private static void skip(InputStream in, long n) throws IOException {
        long left = n;
        byte[] buf = new byte[8192];
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) {
                throw new EOFException("truncated tar stream");
            }
            left -= r;
        }
    }

    private static long padded(long size) {
        return (size + BLOCK - 1) / BLOCK * BLOCK;
    }

    private static boolean allZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }

    private static String cString(byte[] data) {
        return cString(data, 0, data.length);
    }

    private static String cString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }
}
