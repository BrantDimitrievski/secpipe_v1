package ca.carleton.secpipe.sandbox;

import ca.carleton.secpipe.contracts.spi.SandboxRunner.Capabilities;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Control;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Limits;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Mount;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tests every {@link Control} on the live engine. Flag acceptance is not proof: spike S.3 showed Docker accepting
 * {@code --storage-opt size=} and ignoring it. The probe starts one container with exactly the flags
 * {@link DockerSandboxRunner} uses (small limits), runs a shell script inside it that checks the effect of each control,
 * and then checks {@code WALL_CLOCK_LIMIT} by timing out a {@code sleep} and verifying {@code docker kill} removed it.
 * A control is enforceable only when its check printed {@code ok}.
 */
public final class ControlProbe {

    /** Limits for the probe container: 0.5 CPU, 64 MiB JVM budget, 64 PIDs, 1 MiB files, 8 MiB scratch. */
    static final Limits PROBE_LIMITS = new Limits(0.5, 64, 64, 1, 8, Duration.ofSeconds(60));
    static final String CANARY = "SECPIPE_PROBE_CANARY";
    static final String ALLOWED = "SECPIPE_PROBE_ALLOWED";
    static final String CACHE = "/cache/m2";
    private static final Duration WALL_CLOCK_PROBE = Duration.ofSeconds(3);
    private static final Duration STEP = Duration.ofSeconds(60);

    private final DockerCli cli;
    private final String image;

    public ControlProbe(DockerCli cli, String image) {
        this.cli = cli;
        this.image = image;
    }

    /** Probes the engine. Never throws for engine problems: they become {@code available=false} with a reason. */
    public Capabilities probe() throws InterruptedException {
        DockerCli.Outcome version;
        try {
            version = cli.run(cli.versionArgs(), Duration.ofSeconds(30));
        } catch (IOException e) {
            return unavailable("docker CLI not found (" + e.getMessage() + ")", Optional.empty());
        }
        if (version.exitCode() != 0 || version.stdoutTail().isBlank()) {
            return unavailable("docker engine not reachable: " + firstLine(version.stderrTail()), Optional.empty());
        }
        Optional<String> engine = Optional.of(version.stdoutTail().strip());
        Path work = null;
        String name = "secpipe-probe-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            work = Files.createTempDirectory("secpipe-probe");
            Path envFile = work.resolve("env");
            EnvAllowlist.DEFAULT.with(ALLOWED).writeEnvFile(Map.of(ALLOWED, "yes"), envFile);
            Path cache = Files.createDirectory(work.resolve("cache"));
            Files.writeString(cache.resolve("probe.txt"), "probe\n", StandardCharsets.UTF_8);
            DockerCli.RunOptions options = new DockerCli.RunOptions(name, image, PROBE_LIMITS, true,
                List.of(new Mount(cache, CACHE, true)), envFile);
            DockerCli.Outcome started = cli.run(new DockerCli.Invocation(cli.runDetachedArgs(options),
                Optional.empty(), Optional.empty(), STEP, Map.of(CANARY, "leaked")));
            if (started.exitCode() != 0) {
                return unavailable("cannot start the runner image " + image + ": "
                    + firstLine(started.stderrTail()) + "\nhint: docker build -t " + image + " sandbox-images/runner",
                    engine);
            }
            DockerCli.Outcome checks = cli.run(cli.execArgs(name, false, List.of("sh", "-c", script())), STEP);
            Map<Control, String> results = new EnumMap<>(parse(checks.stdoutTail()));
            results.put(Control.WALL_CLOCK_LIMIT, wallClock(name));
            return capabilities(results, engine);
        } catch (IOException e) {
            return unavailable("probe failed: " + e.getMessage(), engine);
        } finally {
            removeQuietly(name);
            deleteQuietly(work);
        }
    }

    /** Times out a {@code sleep} in the probe container, kills it by name and checks it is gone. */
    private String wallClock(String name) throws IOException, InterruptedException {
        DockerCli.Outcome sleep = cli.run(cli.execArgs(name, false, List.of("sleep", "30")), WALL_CLOCK_PROBE);
        if (!sleep.timedOut()) {
            return "fail sleep was not timed out (exit " + sleep.exitCode() + ")";
        }
        cli.run(cli.killArgs(name), STEP);
        cli.run(cli.removeArgs(name), STEP);
        DockerCli.Outcome ps = cli.run(cli.psByNameArgs(name), STEP);
        if (ps.exitCode() == 0 && ps.stdoutTail().isBlank()) {
            return "ok killed and removed by name";
        }
        return "fail container still present after docker kill: " + firstLine(ps.stdoutTail());
    }

    /** Parses {@code PROBE <CONTROL> ok|fail [detail]} lines; unknown lines are ignored. */
    static Map<Control, String> parse(String stdout) {
        Map<Control, String> out = new EnumMap<>(Control.class);
        for (String line : stdout.split("\n")) {
            String[] parts = line.strip().split(" ", 3);
            if (parts.length < 3 || !parts[0].equals("PROBE")) {
                continue;
            }
            try {
                Control c = Control.valueOf(parts[1]);
                String detail = parts[2];
                // a control reported twice is only ok if every report is ok
                out.merge(c, detail, (a, b) -> a.startsWith("ok") ? b : a);
            } catch (IllegalArgumentException e) {
                // not a control name
            }
        }
        return out;
    }

    static Capabilities capabilities(Map<Control, String> results, Optional<String> engine) {
        Set<Control> ok = EnumSet.noneOf(Control.class);
        List<String> problems = new ArrayList<>();
        for (Control c : Control.values()) {
            String r = results.get(c);
            if (r != null && (r.equals("ok") || r.startsWith("ok "))) {
                ok.add(c);
            } else {
                problems.add(c + " (" + (r == null ? "no result" : r) + ")");
            }
        }
        Optional<String> reason = problems.isEmpty() ? Optional.empty()
            : Optional.of("unenforceable on this host: " + String.join(", ", problems));
        return new Capabilities(true, Set.copyOf(ok), reason, engine);
    }

    static Capabilities unavailable(String reason, Optional<String> engine) {
        return new Capabilities(false, Set.of(), Optional.of(reason), engine);
    }

    /** The in-container check script. Values are numbers computed here, never input from a target project. */
    static String script() {
        Limits l = PROBE_LIMITS;
        long memBytes = DockerCli.memoryMb(l) * 1024L * 1024L;
        long cpuQuota = Math.round(l.cpus() * 100000);
        long fsize = DockerCli.fileSizeBytes(l);
        long disk = l.diskMb() * 1024L * 1024L;
        return String.join("\n",
            "r() { echo \"PROBE $1 $2 $3\"; }",
            "mo() { awk -v m=\"$1\" '$2==m{o=$4} END{print o}' /proc/mounts; }",
            "ft() { awk -v m=\"$1\" '$2==m{t=$3} END{print t}' /proc/mounts; }",
            "has() { case \",$(mo \"$1\"),\" in *,$2,*) return 0;; *) return 1;; esac; }",
            "cg() { cat \"/sys/fs/cgroup/$1\" 2>/dev/null; }",
            // NETWORK_NONE: only the loopback interface exists
            "n=$(ls /sys/class/net | tr '\\n' ' ')",
            "if [ \"$n\" = 'lo ' ]; then r NETWORK_NONE ok \"$n\"; else r NETWORK_NONE fail \"$n\"; fi",
            // READ_ONLY_ROOT
            "if has / ro && ! touch /.p 2>/dev/null; then r READ_ONLY_ROOT ok; "
                + "else r READ_ONLY_ROOT fail \"$(mo /)\"; fi",
            // READ_ONLY_TOOLCHAIN: readable ro cache mount, unwritable toolchain
            "if has " + CACHE + " ro && [ -r " + CACHE + "/probe.txt ] && ! touch " + CACHE + "/x 2>/dev/null "
                + "&& ! touch /opt/java/.p 2>/dev/null && ! touch /opt/maven/.p 2>/dev/null; "
                + "then r READ_ONLY_TOOLCHAIN ok; else r READ_ONLY_TOOLCHAIN fail \"$(mo " + CACHE + ")\"; fi",
            // SCRATCH_ONLY_WRITES
            "w=ok; for d in / /etc /usr /var /opt /root /bin /cache; do "
                + "if touch \"$d/.p\" 2>/dev/null; then w=\"fail writable:$d\"; fi; done",
            "touch /scratch/.p 2>/dev/null || w='fail scratch-not-writable'; rm -f /scratch/.p",
            "has /tmp noexec || w='fail tmp-exec'; has " + DockerCli.HOME + " noexec || w='fail home-exec'",
            "r SCRATCH_ONLY_WRITES $w",
            // ENV_ALLOWLIST: the allowlisted value arrives, the host canary does not
            "if [ \"$" + ALLOWED + "\" = yes ] && [ -z \"${" + CANARY + "+x}\" ]; then r ENV_ALLOWLIST ok; "
                + "else r ENV_ALLOWLIST fail env; fi",
            // CPU_LIMIT
            "c=$(cg cpu.max); if [ \"$c\" = '" + cpuQuota + " 100000' ]; then r CPU_LIMIT ok; "
                + "else r CPU_LIMIT fail \"$c\"; fi",
            // MEMORY_LIMIT: memory.max set and no extra swap
            "m=$(cg memory.max); s=$(cg memory.swap.max); "
                + "if [ \"$m\" = " + memBytes + " ] && { [ -z \"$s\" ] || [ \"$s\" = 0 ]; }; "
                + "then r MEMORY_LIMIT ok; else r MEMORY_LIMIT fail \"max=$m swap=$s\"; fi",
            // PID_LIMIT
            "p=$(cg pids.max); if [ \"$p\" = " + l.pids() + " ]; then r PID_LIMIT ok; "
                + "else r PID_LIMIT fail \"$p\"; fi",
            // FILE_SIZE_LIMIT: a write of twice the limit stops at exactly the limit
            "( head -c " + (2 * fsize) + " /dev/zero > /scratch/fsz ) 2>/dev/null; "
                + "z=$(wc -c < /scratch/fsz 2>/dev/null); rm -f /scratch/fsz",
            "if [ \"$z\" = " + fsize + " ]; then r FILE_SIZE_LIMIT ok; else r FILE_SIZE_LIMIT fail \"$z\"; fi",
            // DISK_LIMIT: tmpfs scratch refuses writes past its size (files below the fsize limit)
            "i=0; while [ $i -lt 24 ]; do head -c 524288 /dev/zero > /scratch/d$i 2>/dev/null || break; "
                + "i=$((i+1)); done; t=$(cat /scratch/d* 2>/dev/null | wc -c); rm -f /scratch/d*",
            "if [ \"$(ft /scratch)\" = tmpfs ] && [ $i -lt 24 ] && [ \"$t\" -le " + disk + " ]; "
                + "then r DISK_LIMIT ok; else r DISK_LIMIT fail \"$(ft /scratch) files=$i bytes=$t\"; fi",
            // NON_ROOT_USER
            "u=\"$(id -u):$(id -g):$(id -G)\"; if [ \"$u\" = 10001:10001:10001 ]; then r NON_ROOT_USER ok; "
                + "else r NON_ROOT_USER fail \"$u\"; fi",
            // NO_NEW_PRIVILEGES: no_new_privs set and no capabilities at all
            "q=$(awk '/^NoNewPrivs:/{a=$2} /^CapEff:/{b=$2} /^CapBnd:/{c=$2} /^CapPrm:/{d=$2} "
                + "END{print a\" \"b\" \"c\" \"d}' /proc/self/status)",
            "if [ \"$q\" = '1 0000000000000000 0000000000000000 0000000000000000' ]; "
                + "then r NO_NEW_PRIVILEGES ok; else r NO_NEW_PRIVILEGES fail \"$q\"; fi",
            "");
    }

    private void removeQuietly(String name) throws InterruptedException {
        try {
            cli.run(cli.removeArgs(name), STEP);
        } catch (IOException e) {
            // best effort
        }
    }

    static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // best effort
                }
            });
        } catch (IOException e) {
            // best effort
        }
    }

    static String firstLine(String s) {
        String t = s.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl);
    }
}
