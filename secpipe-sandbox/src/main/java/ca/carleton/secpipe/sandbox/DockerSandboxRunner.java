package ca.carleton.secpipe.sandbox;

import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link SandboxRunner} on the {@code docker} CLI, enforce-or-refuse (ADR-03, spike S.3).
 *
 * <p>Every run: a live {@link ControlProbe} (cached per runner instance) decides which controls the host enforces. If a
 * required control is not enforceable, or the spec itself defeats it (for example a writable mount with
 * {@code READ_ONLY_TOOLCHAIN} required), the run is refused with {@link SandboxRefusedException} and nothing starts.
 *
 * <p>Otherwise a named container is started idle with every control applied ({@link DockerCli#runDetachedArgs}),
 * {@code Spec.scratchDir} is copied into the tmpfs {@code /scratch} as a tar stream, the command runs through
 * {@code docker exec} under the wall-clock limit, the declared promotions come back as a tar stream through
 * {@link ArtifactPromoter} into {@code Spec.scratchDir}, and the container is always removed. On timeout the container
 * is killed by name ({@code docker kill}, then {@code docker rm -f}), because killing the CLI leaves it running.
 *
 * <p>Exit codes are reported as the command returned them. Exit 137 means the kernel killed the process (OOM on
 * cgroup v2 can end the whole container): callers must treat it as a resource-limit outcome, not a test failure; see
 * {@link #isResourceLimitExit(Result)}. A timed-out run has {@code timedOut=true} and {@code exitCode=-1}.
 */
public final class DockerSandboxRunner implements SandboxRunner {

    /** Environment variable that overrides the default runner image. */
    public static final String IMAGE_ENV = "SECPIPE_RUNNER_IMAGE";
    static final int OOM_EXIT = 137;
    private static final Duration MIN_STEP = Duration.ofSeconds(1);
    private static final Duration CLEANUP = Duration.ofSeconds(60);

    private final DockerCli cli;
    private final String probeImage;
    private final EnvAllowlist allowlist;
    private Capabilities cached;

    /** ServiceLoader constructor: the system {@code docker}, the default image, {@link EnvAllowlist#DEFAULT}. */
    public DockerSandboxRunner() {
        this(DockerCli.system(), defaultImage(), EnvAllowlist.DEFAULT);
    }

    public DockerSandboxRunner(DockerCli cli, String probeImage, EnvAllowlist allowlist) {
        this.cli = cli;
        this.probeImage = probeImage;
        this.allowlist = allowlist;
    }

    /** {@code SECPIPE_RUNNER_IMAGE} if set, otherwise the image named in {@code sandbox/runner-image.txt}. */
    public static String defaultImage() {
        String env = System.getenv(IMAGE_ENV);
        if (env != null && !env.isBlank()) {
            return env.strip();
        }
        try (InputStream in = DockerSandboxRunner.class.getResourceAsStream("/sandbox/runner-image.txt")) {
            if (in == null) {
                throw new IllegalStateException("sandbox/runner-image.txt is missing from the classpath");
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return r.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).findFirst()
                .orElseThrow(() -> new IllegalStateException("sandbox/runner-image.txt names no image"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** True when the process was killed by SIGKILL from the kernel or engine (exit 137), e.g. an OOM kill. */
    public static boolean isResourceLimitExit(Result result) {
        return !result.timedOut() && result.exitCode() == OOM_EXIT;
    }

    @Override
    public synchronized Capabilities probe() {
        if (cached == null) {
            try {
                cached = new ControlProbe(cli, probeImage).probe();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ControlProbe.unavailable("probe interrupted", Optional.empty());
            }
        }
        return cached;
    }

    @Override
    public Result run(Spec spec) throws SandboxRefusedException, InterruptedException {
        validate(spec);
        Capabilities caps = probe();
        Set<Control> missing = refusals(spec, caps, allowlist);
        if (!missing.isEmpty()) {
            String names = missing.stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
            throw new SandboxRefusedException("sandbox refused (" + spec.purpose() + "): cannot enforce " + names
                + "\nhint: " + caps.reason().orElse("the spec itself defeats these controls"), missing);
        }
        try {
            return execute(spec, enforcedFor(spec, caps));
        } catch (IOException e) {
            throw new UncheckedIOException("sandbox run failed (" + spec.purpose() + "): " + e.getMessage(), e);
        }
    }

    /** Controls the spec requires that cannot be enforced for it on this host. Empty means the run may start. */
    static Set<Control> refusals(Spec spec, Capabilities caps, EnvAllowlist allowlist) {
        Set<Control> missing = EnumSet.noneOf(Control.class);
        Set<Control> required = spec.required();
        if (!caps.available()) {
            // no engine: nothing can run, so every required control (or, if none, every control) is missing
            missing.addAll(required.isEmpty() ? EnumSet.allOf(Control.class) : required);
            return Set.copyOf(missing);
        }
        for (Control c : required) {
            if (!caps.enforceable().contains(c)) {
                missing.add(c);
            }
        }
        Set<Control> notApplied = EnumSet.allOf(Control.class);
        notApplied.removeAll(appliedFor(spec));
        for (Control c : required) {
            if (notApplied.contains(c)) {
                missing.add(c);
            }
        }
        if (!allowlist.violations(spec.env()).isEmpty()) {
            missing.add(Control.ENV_ALLOWLIST);
        }
        return Set.copyOf(missing);
    }

    /** Controls this spec lets the runner apply. Everything else is always applied. */
    static Set<Control> appliedFor(Spec spec) {
        Set<Control> applied = EnumSet.allOf(Control.class);
        if (spec.network() != Network.NONE) {
            applied.remove(Control.NETWORK_NONE);
        }
        if (spec.mounts().stream().anyMatch(m -> !m.readOnly())) {
            applied.remove(Control.READ_ONLY_TOOLCHAIN);
            applied.remove(Control.SCRATCH_ONLY_WRITES);
        }
        Limits l = spec.limits();
        if (!(l.cpus() > 0)) {
            applied.remove(Control.CPU_LIMIT);
        }
        if (l.memoryMb() <= 0) {
            applied.remove(Control.MEMORY_LIMIT);
        }
        if (l.pids() <= 0) {
            applied.remove(Control.PID_LIMIT);
        }
        if (l.fileSizeMb() <= 0) {
            applied.remove(Control.FILE_SIZE_LIMIT);
        }
        if (l.diskMb() <= 0) {
            applied.remove(Control.DISK_LIMIT);
        }
        if (l.wallClock() == null || l.wallClock().isNegative() || l.wallClock().isZero()) {
            applied.remove(Control.WALL_CLOCK_LIMIT);
        }
        return applied;
    }

    static Set<Control> enforcedFor(Spec spec, Capabilities caps) {
        Set<Control> e = EnumSet.noneOf(Control.class);
        e.addAll(appliedFor(spec));
        e.retainAll(caps.enforceable());
        return Set.copyOf(e);
    }

    static void validate(Spec spec) {
        if (spec.command() == null || spec.command().isEmpty()) {
            throw new IllegalArgumentException("sandbox spec has no command");
        }
        if (spec.scratchDir() == null || !Files.isDirectory(spec.scratchDir())) {
            throw new IllegalArgumentException("sandbox scratch directory does not exist: " + spec.scratchDir());
        }
        Limits l = spec.limits();
        if (!(l.cpus() > 0) || l.memoryMb() <= 0 || l.pids() <= 0 || l.fileSizeMb() <= 0 || l.diskMb() <= 0
            || l.wallClock() == null || l.wallClock().isNegative() || l.wallClock().isZero()) {
            throw new IllegalArgumentException("sandbox limits must all be positive: " + l);
        }
        for (Mount m : spec.mounts()) {
            String src = m.hostPath().toString();
            String dst = m.containerPath();
            if (src.contains(",") || src.contains("=") || dst.contains(",") || dst.contains("=")) {
                throw new IllegalArgumentException("mount paths must not contain ',' or '=': " + m);
            }
            if (!dst.startsWith("/") || dst.equals("/") || dst.equals(DockerCli.SCRATCH)
                || dst.startsWith(DockerCli.SCRATCH + "/")) {
                throw new IllegalArgumentException("mount target must be absolute and outside /scratch: " + dst);
            }
        }
        for (Promotion p : spec.promote()) {
            Optional<String> bad = ArtifactPromoter.checkRelativePath(p.relativePath());
            if (bad.isPresent()) {
                throw new IllegalArgumentException("bad promotion path " + p.relativePath() + ": " + bad.get());
            }
        }
    }

    static String containerName(String purpose) {
        String slug = purpose == null ? "" : purpose.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        return "secpipe-" + (slug.isEmpty() ? "run" : slug) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Result execute(Spec spec, Set<Control> enforced) throws IOException, InterruptedException {
        long start = System.nanoTime();
        long deadline = start + spec.limits().wallClock().toNanos();
        String name = containerName(spec.purpose());
        Path work = Files.createTempDirectory("secpipe-sbx");
        try {
            Path envFile = work.resolve("env");
            allowlist.writeEnvFile(spec.env(), envFile);
            DockerCli.RunOptions options = new DockerCli.RunOptions(name, imageOf(spec), spec.limits(),
                spec.network() == Network.NONE, spec.mounts(), envFile);
            DockerCli.Outcome started = step(cli.runDetachedArgs(options), Optional.empty(), Optional.empty(),
                deadline);
            if (started.timedOut()) {
                return timedOut(name, started, start, enforced);
            }
            if (started.exitCode() != 0) {
                throw new IOException("docker run failed: " + ControlProbe.firstLine(started.stderrTail()));
            }
            Path in = work.resolve("in.tar");
            DockerCli.Outcome packed = step(List.of("tar", "-c", "-f", in.toString(), "-C",
                spec.scratchDir().toString(), "."), Optional.empty(), Optional.empty(), deadline);
            if (packed.timedOut()) {
                return timedOut(name, packed, start, enforced);
            }
            if (packed.exitCode() != 0) {
                throw new IOException("packing scratch failed: " + ControlProbe.firstLine(packed.stderrTail()));
            }
            DockerCli.Outcome copied = step(cli.execArgs(name, true, List.of("tar", "-x", "-f", "-", "-C",
                DockerCli.SCRATCH, "--no-same-owner")), Optional.of(in), Optional.empty(), deadline);
            if (copied.timedOut()) {
                return timedOut(name, copied, start, enforced);
            }
            if (copied.exitCode() != 0) {
                throw new IOException("copying scratch in failed: " + ControlProbe.firstLine(copied.stderrTail()));
            }
            DockerCli.Outcome ran = step(cli.execArgs(name, false, spec.command()), Optional.empty(),
                Optional.empty(), deadline);
            if (ran.timedOut()) {
                return timedOut(name, ran, start, enforced);
            }
            List<Path> promoted = List.of();
            if (!spec.promote().isEmpty()) {
                promoted = promote(spec, name, work);
            }
            return new Result(ran.exitCode(), false, ran.stdoutTail(), ran.stderrTail(), promoted, since(start),
                enforced);
        } finally {
            cli.run(cli.removeArgs(name), CLEANUP);
            ControlProbe.deleteQuietly(work);
        }
    }

    private List<Path> promote(Spec spec, String name, Path work) throws IOException, InterruptedException {
        Path out = work.resolve("out.tar");
        List<String> tar = new ArrayList<>(List.of("tar", "-c", "-f", "-", "-C", DockerCli.SCRATCH, "--no-recursion",
            "--ignore-failed-read", "--"));
        spec.promote().forEach(p -> tar.add(p.relativePath()));
        // the container may be dead after an OOM kill; then nothing is promoted
        DockerCli.Outcome packed = cli.run(new DockerCli.Invocation(cli.execArgs(name, false, tar), Optional.empty(),
            Optional.of(out), CLEANUP, Map.of()));
        if (packed.timedOut() || !Files.isRegularFile(out) || Files.size(out) == 0) {
            return List.of();
        }
        try (InputStream in = Files.newInputStream(out)) {
            return ArtifactPromoter.promote(in, spec.promote(), spec.scratchDir()).promoted();
        }
    }

    private Result timedOut(String name, DockerCli.Outcome o, long start, Set<Control> enforced)
        throws IOException, InterruptedException {
        cli.run(cli.killArgs(name), CLEANUP);
        cli.run(cli.removeArgs(name), CLEANUP);
        return new Result(-1, true, o.stdoutTail(), o.stderrTail(), List.of(), since(start), enforced);
    }

    private DockerCli.Outcome step(List<String> argv, Optional<Path> stdin, Optional<Path> stdout, long deadline)
        throws IOException, InterruptedException {
        long left = deadline - System.nanoTime();
        Duration timeout = left <= 0 ? MIN_STEP : Duration.ofNanos(Math.max(left, MIN_STEP.toNanos()));
        if (left <= 0) {
            return new DockerCli.Outcome(-1, true, "", "wall-clock limit reached");
        }
        return cli.run(new DockerCli.Invocation(argv, stdin, stdout, timeout, Map.of()));
    }

    private String imageOf(Spec spec) {
        return spec.image() == null || spec.image().isBlank() ? probeImage : spec.image();
    }

    private static Duration since(long start) {
        return Duration.ofNanos(System.nanoTime() - start);
    }
}
