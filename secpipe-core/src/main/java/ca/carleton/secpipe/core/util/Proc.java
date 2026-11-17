package ca.carleton.secpipe.core.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The only place in {@code secpipe-core} that starts host processes. No shell is involved: commands are argument
 * lists, runs have a timeout, and captured output is capped at {@link #MAX_OUTPUT_BYTES} per stream.
 */
public final class Proc {

    /** Per-stream output cap (1 MiB). Excess output is read and discarded so the child never blocks. */
    public static final int MAX_OUTPUT_BYTES = 1024 * 1024;

    public record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private Proc() {
    }

    public static boolean isWindows() {
        return WINDOWS;
    }

    /** Finds an executable using the {@code PATH} of the real process environment. */
    public static Optional<Path> which(String name) {
        return which(name, System.getenv(), WINDOWS);
    }

    /**
     * Finds an executable on the {@code PATH} in {@code env}. On Windows the extensions in {@code PATHEXT} are tried
     * (default {@code .COM;.EXE;.BAT;.CMD}); elsewhere the bare name must be executable.
     */
    public static Optional<Path> which(String name, Map<String, String> env, boolean windows) {
        String path = lookup(env, "PATH", windows);
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        List<String> names = new ArrayList<>();
        names.add(name);
        if (windows) {
            String pathExt = lookup(env, "PATHEXT", true);
            String exts = pathExt == null || pathExt.isBlank() ? ".COM;.EXE;.BAT;.CMD" : pathExt;
            for (String ext : exts.split(";")) {
                if (!ext.isBlank()) {
                    names.add(name + ext.toLowerCase(Locale.ROOT));
                    names.add(name + ext.toUpperCase(Locale.ROOT));
                }
            }
        }
        for (String dir : path.split(windows ? ";" : File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String n : names) {
                Path candidate = Path.of(dir, n);
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static String lookup(Map<String, String> env, String key, boolean caseInsensitive) {
        String v = env.get(key);
        if (v != null || !caseInsensitive) {
            return v;
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Runs {@code command} (no shell). On timeout the process tree is killed and {@code timedOut} is set. */
    public static Result run(List<String> command, Path workDir, Duration timeout) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(List.copyOf(command));
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        Process p = pb.start();
        p.getOutputStream().close();
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> drain(p.getInputStream()));
        CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> drain(p.getErrorStream()));
        try {
            boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                return new Result(-1, out.getNow(""), err.getNow(""), true);
            }
            return new Result(p.exitValue(), out.join(), err.join(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("interrupted while running " + command.get(0), e);
        }
    }

    /** First line of {@code <tool> <args>}, or empty if the tool cannot run or times out. */
    public static Optional<String> firstLine(Path exe, Duration timeout, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.addAll(List.of(args));
        try {
            Result r = run(cmd, null, timeout);
            String text = (r.stdout() + "\n" + r.stderr()).strip();
            if (r.timedOut() || text.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(text.lines().findFirst().orElse("").strip());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String drain(InputStream in) {
        try (in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                int room = MAX_OUTPUT_BYTES - buf.size();
                if (room > 0) {
                    buf.write(chunk, 0, Math.min(room, n));
                }
            }
            return buf.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
