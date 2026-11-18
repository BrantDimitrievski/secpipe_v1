package ca.carleton.secpipe.sandbox;

import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Control;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Limits;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Mount;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Network;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Result;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.SandboxRefusedException;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Spec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two-phase Maven workspace (ADR-03 as corrected after spike S.3).
 *
 * <ol>
 *   <li>{@link #prepare(Path)} runs the {@link BuildExtensionPreflight}, copies the target into a host scratch
 *   directory (without {@code .git/}, {@code target/} or symlinks; the user's tree is never written), then runs the
 *   <b>MavenPrefetch</b> phase through {@link SandboxRunner}: {@link Network#PREFETCH_ONLY}, no env beyond the
 *   runner's defaults (no secrets), the cache mounted read-write at {@link #CACHE_MOUNT}, goals
 *   {@link #PREFETCH_GOALS}.</li>
 *   <li>If the prefetch succeeded, a dry check runs {@code mvn -o dependency:build-classpath} with
 *   {@code --network none}, all 13 controls and the cache mounted read-only as the local-repo tail. A failed
 *   prefetch, or "Could not resolve dependencies" (or any other offline resolution error) in the dry check, yields
 *   {@link PrefetchIncomplete} naming the missing artifacts: a {@code needs_validation} blocker {@value #BLOCKER},
 *   never a test failure.</li>
 * </ol>
 *
 * <p>Later offline runs use {@link #offlineCommand(String...)} inside {@link #offlineSpec}: the same read-only cache
 * as {@code -Dmaven.repo.local.tail} and a writable {@code /scratch/m2} local repo inside the tmpfs scratch.
 */
public final class MavenWorkspace {

    /** Blocker name for an incomplete prefetch (the finding stays {@code needs_validation}). */
    public static final String BLOCKER = "prefetch-incomplete";
    /** Container path of the dependency cache (rw in prefetch, ro afterwards). */
    public static final String CACHE_MOUNT = "/cache/m2";
    /** Writable local repository inside the tmpfs scratch for offline runs. */
    public static final String SCRATCH_REPO = DockerCli.SCRATCH + "/m2";
    /** Prefetch goals (corrected ADR-03): go-offline alone left a compile-scope jar out on OWASP Benchmark. */
    public static final List<String> PREFETCH_GOALS =
        List.of("dependency:go-offline", "dependency:resolve-plugins", "dependency:resolve");

    /** Controls the network-enabled prefetch cannot have: it needs egress and a writable cache mount. */
    static final Set<Control> PREFETCH_EXEMPT =
        EnumSet.of(Control.NETWORK_NONE, Control.READ_ONLY_TOOLCHAIN, Control.SCRATCH_ONLY_WRITES);

    private static final Set<String> SKIP_DIRS = Set.of(".git", "target");
    private static final Pattern NOT_MISSING = Pattern.compile("(?:for project|Failed to execute goal) \\S+");
    private static final String PART = "[A-Za-z0-9_.\\-]+";
    private static final Pattern COORD = Pattern.compile("(?<![\\w.\\-:])(" + PART + ":" + PART + ":" + PART
        + "(?::" + PART + "){1,2})(?![\\w.\\-:])");
    private static final Pattern PLUGIN = Pattern.compile("Plugin (" + PART + ":" + PART + ":" + PART
        + ") or one of its dependencies could not be resolved");
    private static final List<String> RESOLUTION_MARKERS = List.of("Could not resolve dependencies",
        "could not be resolved", "has not been downloaded from it before", "Failure to find",
        "Could not find artifact", "Cannot access");

    /** Settings for one workspace. */
    public record Options(String image, Limits prefetchLimits, Limits offlineLimits, boolean allowBuildExtensions) {

        /** Runner's default image (blank), 10-minute prefetch, default offline limits, extensions refused. */
        public static Options defaults() {
            Limits d = Limits.defaults();
            return new Options("", new Limits(d.cpus(), d.memoryMb(), d.pids(), d.fileSizeMb(), d.diskMb(),
                Duration.ofMinutes(10)), d, false);
        }

        public Options withAllowBuildExtensions(boolean allow) {
            return new Options(image, prefetchLimits, offlineLimits, allow);
        }
    }

    /** Outcome of {@link #prepare(Path)}. */
    public sealed interface Outcome permits Prepared, PrefetchIncomplete, ExtensionsRefused { }

    /** Ready: {@code cacheDir} is the filled cache (mount it read-only), {@code scratchCopy} the pristine copy. */
    public record Prepared(Path cacheDir, Path scratchCopy) implements Outcome { }

    /** Blocker {@value MavenWorkspace#BLOCKER}: {@code missing} lists Maven coordinates, sorted; may be empty. */
    public record PrefetchIncomplete(List<String> missing, String reason) implements Outcome {
        public PrefetchIncomplete {
            missing = List.copyOf(missing);
        }

        public String blocker() {
            return BLOCKER;
        }
    }

    /** Blocker {@value BuildExtensionPreflight#BLOCKER}: nothing ran. */
    public record ExtensionsRefused(List<BuildExtensionPreflight.Finding> findings) implements Outcome {
        public ExtensionsRefused {
            findings = List.copyOf(findings);
        }

        public String message() {
            return BuildExtensionPreflight.message(findings);
        }
    }

    private final SandboxRunner runner;
    private final Path workRoot;
    private final Options options;

    /** {@code workRoot} is a run-private host directory; {@code cache-m2/} and {@code scratch/} are created in it. */
    public MavenWorkspace(SandboxRunner runner, Path workRoot, Options options) {
        this.runner = runner;
        this.workRoot = workRoot;
        this.options = options;
    }

    public Path cacheDir() {
        return workRoot.resolve("cache-m2");
    }

    public Path scratchDir() {
        return workRoot.resolve("scratch");
    }

    /**
     * Pre-flight, copy, prefetch and offline dry check. Call once per run; reuse the result for every offline run.
     *
     * @throws SandboxRefusedException a required control is unenforceable: record a {@code needs_validation} blocker
     *     naming {@link SandboxRefusedException#missing()}
     */
    public Outcome prepare(Path target) throws SandboxRefusedException, InterruptedException {
        if (!Files.isRegularFile(target.resolve("pom.xml"))) {
            throw new IllegalArgumentException("not a Maven project (no pom.xml): " + target);
        }
        List<BuildExtensionPreflight.Finding> findings = BuildExtensionPreflight.check(target);
        if (!findings.isEmpty() && !options.allowBuildExtensions()) {
            return new ExtensionsRefused(findings);
        }
        Path cache = cacheDir();
        Path scratch = scratchDir();
        try {
            Files.createDirectories(workRoot);
            setPermissions(workRoot, "rwx------");
            Files.createDirectories(cache);
            setPermissions(cache, "rwxrwxrwx");
            copyTree(target, scratch);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot prepare workspace in " + workRoot + ": " + e.getMessage(), e);
        }
        Result prefetch = runner.run(prefetchSpec());
        if (prefetch.timedOut() || prefetch.exitCode() != 0) {
            return new PrefetchIncomplete(missingArtifacts(prefetch.stdoutTail() + "\n" + prefetch.stderrTail()),
                "prefetch " + describe(prefetch));
        }
        Result dry = runner.run(offlineSpec(offlineCommand("dependency:build-classpath"), "maven offline dry check"));
        if (dry.timedOut() || dry.exitCode() != 0) {
            return new PrefetchIncomplete(missingArtifacts(dry.stdoutTail() + "\n" + dry.stderrTail()),
                "offline dependency check " + describe(dry));
        }
        return new Prepared(cache, scratch);
    }

    /** The network-enabled, secret-free MavenPrefetch spec. Runs no lifecycle phase, so no tests or build plugins. */
    Spec prefetchSpec() {
        List<String> cmd = new ArrayList<>(List.of("mvn", "-B", "-q", "-Dmaven.repo.local=" + CACHE_MOUNT,
            "-DskipTests"));
        cmd.addAll(PREFETCH_GOALS);
        Set<Control> required = EnumSet.allOf(Control.class);
        required.removeAll(PREFETCH_EXEMPT);
        return new Spec(options.image(), List.copyOf(cmd), scratchDir(),
            List.of(new Mount(cacheDir(), CACHE_MOUNT, false)), Map.of(), options.prefetchLimits(),
            Network.PREFETCH_ONLY, Set.copyOf(required), List.of(), "maven prefetch");
    }

    /** An offline spec: {@code --network none}, cache read-only, all 13 controls required, no promotions. */
    public Spec offlineSpec(List<String> command, String purpose) {
        return new Spec(options.image(), List.copyOf(command), scratchDir(),
            List.of(new Mount(cacheDir(), CACHE_MOUNT, true)), Map.of(), options.offlineLimits(), Network.NONE,
            Set.copyOf(EnumSet.allOf(Control.class)), List.of(), purpose);
    }

    /** {@code mvn -o -B -q -Dmaven.repo.local=/scratch/m2 -Dmaven.repo.local.tail=/cache/m2 <goals...>}. */
    public static List<String> offlineCommand(String... goals) {
        List<String> cmd = new ArrayList<>(List.of("mvn", "-o", "-B", "-q", "-Dmaven.repo.local=" + SCRATCH_REPO,
            "-Dmaven.repo.local.tail=" + CACHE_MOUNT));
        cmd.addAll(Arrays.asList(goals));
        return List.copyOf(cmd);
    }

    /** Maven coordinates named in resolution errors of {@code output}, sorted and de-duplicated. */
    static List<String> missingArtifacts(String output) {
        TreeSet<String> out = new TreeSet<>();
        for (String line : output.split("\\R")) {
            if (RESOLUTION_MARKERS.stream().noneMatch(line::contains)) {
                continue;
            }
            Matcher plugin = PLUGIN.matcher(line);
            while (plugin.find()) {
                out.add(plugin.group(1));
            }
            String cleaned = NOT_MISSING.matcher(PLUGIN.matcher(line).replaceAll("")).replaceAll("");
            Matcher m = COORD.matcher(cleaned);
            while (m.find()) {
                String c = m.group(1);
                if (!c.contains("://") && !c.startsWith("http")) {
                    out.add(c);
                }
            }
        }
        return List.copyOf(out);
    }

    private static String describe(Result r) {
        if (r.timedOut()) {
            return "timed out";
        }
        if (DockerSandboxRunner.isResourceLimitExit(r)) {
            return "was killed at a resource limit (exit 137)";
        }
        String all = r.stdoutTail() + "\n" + r.stderrTail();
        Optional<String> err = all.lines().filter(l -> l.contains("[ERROR]")).findFirst()
            .or(() -> all.lines().filter(l -> !l.isBlank()).findFirst());
        return "failed with exit " + r.exitCode() + err.map(e -> ": " + e.strip()).orElse("");
    }

    /**
     * POSIX only. The container runs as uid 10001, so the cache root must be world-writable during prefetch; the
     * enclosing {@code workRoot} is 0700 so other local users cannot reach (and tamper with) that cache.
     */
    private static void setPermissions(Path dir, String perms) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(dir, PosixFileAttributeView.class);
        if (view != null) {
            view.setPermissions(PosixFilePermissions.fromString(perms));
        }
    }

    /** Copies regular files and directories; skips symlinks, {@code .git/} and {@code target/}. */
    static void copyTree(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(from) && (attrs.isSymbolicLink() || SKIP_DIRS.contains(dir.getFileName().toString()))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && !Files.isSymbolicLink(file)) {
                    Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING,
                        LinkOption.NOFOLLOW_LINKS);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
