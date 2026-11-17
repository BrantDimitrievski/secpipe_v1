package ca.carleton.secpipe.contracts.spi;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Feature J. Executes untrusted target code (builds, generated tests, fixes) under enforced controls.
 * Implementations MUST refuse (SandboxRefusedException) rather than run with any requested control unenforced.
 * Discovered with ServiceLoader (secpipe-sandbox provides DockerSandboxRunner).
 */
public interface SandboxRunner {

    /** Which controls this host can enforce right now (cached per run). */
    Capabilities probe();

    Result run(Spec spec) throws SandboxRefusedException, InterruptedException;

    enum Control { NETWORK_NONE, READ_ONLY_ROOT, READ_ONLY_TOOLCHAIN, SCRATCH_ONLY_WRITES, ENV_ALLOWLIST,
        CPU_LIMIT, MEMORY_LIMIT, PID_LIMIT, FILE_SIZE_LIMIT, DISK_LIMIT, WALL_CLOCK_LIMIT, NON_ROOT_USER, NO_NEW_PRIVILEGES }

    enum Network { NONE, PREFETCH_ONLY }   // PREFETCH_ONLY is allowed only for MavenPrefetch before any target code runs

    record Capabilities(boolean available, Set<Control> enforceable, Optional<String> reason, Optional<String> engineVersion) { }

    record Limits(double cpus, int memoryMb, int pids, int fileSizeMb, int diskMb, Duration wallClock) {
        public static Limits defaults() { return new Limits(2.0, 2048, 512, 256, 2048, Duration.ofMinutes(5)); }
    }

    record Mount(Path hostPath, String containerPath, boolean readOnly) { }

    /** A file the host may copy out of scratch after exit (feature K). Path is relative to scratch. */
    record Promotion(String relativePath, long maxBytes) { }

    record Spec(String image, List<String> command, Path scratchDir, List<Mount> mounts, Map<String, String> env,
                Limits limits, Network network, Set<Control> required, List<Promotion> promote, String purpose) { }

    record Result(int exitCode, boolean timedOut, String stdoutTail, String stderrTail, List<Path> promoted,
                  Duration elapsed, Set<Control> enforced) { }

    class SandboxRefusedException extends Exception {
        private final Set<Control> missing;
        public SandboxRefusedException(String message, Set<Control> missing) { super(message); this.missing = Set.copyOf(missing); }
        public Set<Control> missing() { return missing; }
    }
}
