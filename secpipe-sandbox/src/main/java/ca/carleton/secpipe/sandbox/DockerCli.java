package ca.carleton.secpipe.sandbox;

import ca.carleton.secpipe.contracts.spi.SandboxRunner.Limits;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Mount;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Builds and runs {@code docker} CLI invocations for the sandbox. Argument building is pure, so every control's flags
 * can be unit-tested without a Docker daemon; processes run through a {@link ProcessRunner} that tests replace.
 *
 * <p>Mapping (spike S.3): {@code NETWORK_NONE} → {@code --network none}; {@code READ_ONLY_ROOT} → {@code --read-only};
 * {@code READ_ONLY_TOOLCHAIN} → image toolchain under {@code --read-only} plus {@code readonly} bind mounts;
 * {@code SCRATCH_ONLY_WRITES} → the only writable places are tmpfs scratch, {@code /tmp} and HOME;
 * {@code ENV_ALLOWLIST} → {@code --env-file}; {@code CPU_LIMIT} → {@code --cpus}; {@code MEMORY_LIMIT} →
 * {@code --memory} = {@code --memory-swap}; {@code PID_LIMIT} → {@code --pids-limit}; {@code FILE_SIZE_LIMIT} →
 * {@code --ulimit fsize=<bytes>:<bytes>}; {@code DISK_LIMIT} → size-capped tmpfs scratch ({@code --storage-opt} is
 * ignored by the engine); {@code WALL_CLOCK_LIMIT} → host timer then {@code docker kill <name>};
 * {@code NON_ROOT_USER} → {@code --user 10001:10001}; {@code NO_NEW_PRIVILEGES} → {@code --security-opt
 * no-new-privileges} and {@code --cap-drop ALL}.
 */
public final class DockerCli {

    public static final String SCRATCH = "/scratch";
    public static final String HOME = "/home/sandbox";
    public static final String TMP = "/tmp";
    public static final String USER = "10001:10001";
    /** Size of the {@code /tmp} tmpfs, in MiB. */
    public static final int TMP_MB = 256;
    /** Size of the HOME tmpfs, in MiB. */
    public static final int HOME_MB = 64;
    /** Label put on every sandbox container. */
    public static final String LABEL = "ca.carleton.secpipe.sandbox=1";

    private static final long MIB = 1024L * 1024L;
    private static final int TAIL_BYTES = 64 * 1024;

    /** Runs one process. The real implementation is {@link #systemRunner()}; tests supply a fake. */
    @FunctionalInterface
    public interface ProcessRunner {
        Outcome run(Invocation invocation) throws IOException, InterruptedException;
    }

    /**
     * One process invocation. {@code stdin} and {@code stdout} are optional host files to redirect from and to;
     * {@code extraEnv} is added to the CLI process's own environment (never to the container's).
     */
    public record Invocation(List<String> argv, Optional<Path> stdin, Optional<Path> stdout, Duration timeout,
                             Map<String, String> extraEnv) {
        public Invocation {
            argv = List.copyOf(argv);
            extraEnv = Map.copyOf(extraEnv);
        }
    }

    /** Exit status of an invocation; {@code exitCode} is -1 when the process was killed for timing out. */
    public record Outcome(int exitCode, boolean timedOut, String stdoutTail, String stderrTail) { }

    /** Everything {@code docker run -d} needs for one sandbox container. */
    public record RunOptions(String name, String image, Limits limits, boolean networkNone, List<Mount> mounts,
                             Path envFile) {
        public RunOptions {
            mounts = List.copyOf(mounts);
        }
    }

    private final String executable;
    private final ProcessRunner runner;

    public DockerCli(String executable, ProcessRunner runner) {
        this.executable = executable;
        this.runner = runner;
    }

    /** The {@code docker} on PATH, run as a real process. */
    public static DockerCli system() {
        return new DockerCli("docker", systemRunner());
    }

    public ProcessRunner runner() {
        return runner;
    }

    /** Total {@code --memory} in MiB: the JVM budget plus every tmpfs, because tmpfs pages count against it. */
    public static long memoryMb(Limits limits) {
        return (long) limits.memoryMb() + limits.diskMb() + TMP_MB + HOME_MB;
    }

    /** {@code --ulimit fsize} takes bytes, for both soft and hard limits. */
    public static long fileSizeBytes(Limits limits) {
        return limits.fileSizeMb() * MIB;
    }

    public List<String> versionArgs() {
        return List.of(executable, "version", "--format", "{{.Server.Version}}");
    }

    /** {@code docker run -d}: starts the named container idle ({@code sleep infinity}) with every control applied. */
    public List<String> runDetachedArgs(RunOptions o) {
        Limits l = o.limits();
        long memory = memoryMb(l);
        long fsize = fileSizeBytes(l);
        List<String> a = new ArrayList<>(List.of(executable, "run", "-d", "--pull", "never", "--name", o.name(),
            "--label", LABEL));
        if (o.networkNone()) {
            a.addAll(List.of("--network", "none"));
        }
        a.addAll(List.of("--read-only", "--user", USER, "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
            "--cpus", formatCpus(l.cpus()),
            "--memory", memory + "m", "--memory-swap", memory + "m",
            "--pids-limit", Integer.toString(l.pids()),
            "--ulimit", "fsize=" + fsize + ":" + fsize,
            "--tmpfs", SCRATCH + ":rw,nosuid,nodev,size=" + l.diskMb() + "m,uid=10001,gid=10001",
            "--tmpfs", TMP + ":rw,noexec,nosuid,nodev,size=" + TMP_MB + "m",
            "--tmpfs", HOME + ":rw,noexec,nosuid,nodev,size=" + HOME_MB + "m,uid=10001,gid=10001",
            "--env-file", o.envFile().toString(),
            "--workdir", SCRATCH));
        for (Mount m : o.mounts()) {
            a.add("--mount");
            a.add("type=bind,src=" + m.hostPath() + ",dst=" + m.containerPath() + (m.readOnly() ? ",readonly" : ""));
        }
        a.add(o.image());
        a.addAll(List.of("sleep", "infinity"));
        return List.copyOf(a);
    }

    /** {@code docker exec} as the sandbox user in scratch; {@code -i} when stdin is fed. */
    public List<String> execArgs(String name, boolean interactive, List<String> command) {
        List<String> a = new ArrayList<>(List.of(executable, "exec"));
        if (interactive) {
            a.add("-i");
        }
        a.addAll(List.of("--user", USER, "--workdir", SCRATCH, name));
        a.addAll(command);
        return List.copyOf(a);
    }

    public List<String> killArgs(String name) {
        return List.of(executable, "kill", name);
    }

    public List<String> removeArgs(String name) {
        return List.of(executable, "rm", "-f", name);
    }

    /** Lists containers (running or not) whose name is exactly {@code name}. */
    public List<String> psByNameArgs(String name) {
        return List.of(executable, "ps", "-a", "-q", "--filter", "name=^/?" + name + "$");
    }

    public Outcome run(List<String> argv, Duration timeout) throws IOException, InterruptedException {
        return runner.run(new Invocation(argv, Optional.empty(), Optional.empty(), timeout, Map.of()));
    }

    public Outcome run(Invocation invocation) throws IOException, InterruptedException {
        return runner.run(invocation);
    }

    static String formatCpus(double cpus) {
        String s = String.format(Locale.ROOT, "%.3f", cpus);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** Runs processes with {@link ProcessBuilder}; keeps the last 64 KiB of stdout and stderr. */
    public static ProcessRunner systemRunner() {
        return DockerCli::runProcess;
    }

    private static Outcome runProcess(Invocation inv) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(inv.argv());
        pb.environment().putAll(inv.extraEnv());
        inv.stdin().ifPresent(p -> pb.redirectInput(p.toFile()));
        inv.stdout().ifPresent(p -> pb.redirectOutput(p.toFile()));
        Process process = pb.start();
        if (inv.stdin().isEmpty()) {
            process.getOutputStream().close();
        }
        Tail out = new Tail(inv.stdout().isEmpty() ? process.getInputStream() : InputStream.nullInputStream());
        Tail err = new Tail(process.getErrorStream());
        out.start();
        err.start();
        boolean done = process.waitFor(Math.max(1L, inv.timeout().toMillis()), TimeUnit.MILLISECONDS);
        if (!done) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        out.join(2000);
        err.join(2000);
        return new Outcome(done ? process.exitValue() : -1, !done, out.text(), err.text());
    }

    private static final class Tail extends Thread {
        private final InputStream in;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        Tail(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(chunk)) >= 0) {
                    synchronized (buf) {
                        buf.write(chunk, 0, n);
                        if (buf.size() > 2 * TAIL_BYTES) {
                            byte[] all = buf.toByteArray();
                            buf.reset();
                            buf.write(all, all.length - TAIL_BYTES, TAIL_BYTES);
                        }
                    }
                }
            } catch (IOException e) {
                // the process went away; keep what was read
            }
        }

        String text() {
            synchronized (buf) {
                byte[] all = buf.toByteArray();
                int from = Math.max(0, all.length - TAIL_BYTES);
                return new String(all, from, all.length - from, StandardCharsets.UTF_8);
            }
        }
    }
}
