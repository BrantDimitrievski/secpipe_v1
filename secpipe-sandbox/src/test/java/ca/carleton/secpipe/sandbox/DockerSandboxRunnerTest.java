package ca.carleton.secpipe.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Control;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Limits;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Mount;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Network;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Promotion;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Result;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.SandboxRefusedException;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Spec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerSandboxRunnerTest {

    private static final Set<Control> ALL = EnumSet.allOf(Control.class);
    private static final Limits LIMITS = new Limits(1.5, 1024, 256, 64, 512, Duration.ofMinutes(2));

    @TempDir
    Path scratch;

    private final DockerSandboxRunnerFakeDocker docker = new DockerSandboxRunnerFakeDocker();
    private final DockerCli cli = new DockerCli("docker", docker);
    private final DockerSandboxRunner runner = new DockerSandboxRunner(cli, "secpipe-runner:test",
        EnvAllowlist.DEFAULT);

    private Spec spec(Network network, List<Mount> mounts, Map<String, String> env, Set<Control> required,
                      List<Promotion> promote) {
        return new Spec("", List.of("java", "-version"), scratch, mounts, env, LIMITS, network, required, promote,
            "G.7 generated test");
    }

    private Spec spec() {
        return spec(Network.NONE, List.of(), Map.of("LANG", "C.UTF-8"), ALL, List.of());
    }

    @Test
    void everyControlMapsToItsDockerFlags() {
        Path env = scratch.resolve("env");
        Path cache = scratch.resolve("cache");
        List<String> a = cli.runDetachedArgs(new DockerCli.RunOptions("secpipe-x-1", "img:1", LIMITS, true,
            List.of(new Mount(cache, "/cache/m2", true)), env));
        long memory = 1024 + 512 + DockerCli.TMP_MB + DockerCli.HOME_MB;
        assertFlag(a, "--name", "secpipe-x-1");                                  // WALL_CLOCK_LIMIT: kill by name
        assertFlag(a, "--network", "none");                                      // NETWORK_NONE
        assertTrue(a.contains("--read-only"));                                   // READ_ONLY_ROOT
        assertTrue(a.contains("type=bind,src=" + cache + ",dst=/cache/m2,readonly")); // READ_ONLY_TOOLCHAIN
        assertFlag(a, "--env-file", env.toString());                             // ENV_ALLOWLIST
        assertFlag(a, "--cpus", "1.5");                                          // CPU_LIMIT
        assertFlag(a, "--memory", memory + "m");                                 // MEMORY_LIMIT
        assertFlag(a, "--memory-swap", memory + "m");
        assertFlag(a, "--pids-limit", "256");                                    // PID_LIMIT
        assertFlag(a, "--ulimit", "fsize=67108864:67108864");                    // FILE_SIZE_LIMIT, bytes
        assertTrue(a.contains("/scratch:rw,nosuid,nodev,size=512m,uid=10001,gid=10001")); // DISK_LIMIT
        assertTrue(a.contains("/tmp:rw,noexec,nosuid,nodev,size=256m"));         // SCRATCH_ONLY_WRITES
        assertTrue(a.contains("/home/sandbox:rw,noexec,nosuid,nodev,size=64m,uid=10001,gid=10001"));
        assertFlag(a, "--user", "10001:10001");                                  // NON_ROOT_USER
        assertFlag(a, "--security-opt", "no-new-privileges");                    // NO_NEW_PRIVILEGES
        assertFlag(a, "--cap-drop", "ALL");
        assertFalse(a.stream().anyMatch(s -> s.startsWith("--storage-opt")), "storage-opt is ignored by the engine");
        assertFalse(a.contains("-e"), "never -e KEY: it copies the host value");
        assertEquals(List.of("img:1", "sleep", "infinity"), a.subList(a.size() - 3, a.size()));
    }

    @Test
    void happyRunStartsNamedContainerCopiesInExecsPromotesAndRemoves() throws Exception {
        Files.writeString(scratch.resolve("pom.xml"), "<project/>");
        docker.promotionTar = new ArtifactPromoterTars().file("target/out.txt", "evidence").bytes();
        Result r = runner.run(spec(Network.NONE, List.of(), Map.of(), ALL,
            List.of(new Promotion("target/out.txt", 100))));
        assertEquals(0, r.exitCode());
        assertFalse(r.timedOut());
        assertEquals("hello\n", r.stdoutTail());
        assertEquals(ALL, EnumSet.copyOf(r.enforced()));
        assertEquals(List.of(scratch.toRealPath().resolve("target/out.txt")), r.promoted());
        assertEquals("evidence", Files.readString(scratch.resolve("target/out.txt")));

        List<String> run = docker.argvs().stream().filter(a -> a.get(1).equals("run")).reduce((x, y) -> y)
            .orElseThrow();
        String name = run.get(run.indexOf("--name") + 1);
        assertTrue(name.startsWith("secpipe-g-7-generated-test-"), name);
        assertTrue(run.contains("secpipe-runner:test"), "blank Spec.image means the default runner image");
        List<List<String>> after = docker.argvs().subList(docker.argvs().indexOf(run) + 1, docker.argvs().size());
        assertEquals("tar", after.get(0).get(0));
        assertTrue(after.get(1).containsAll(List.of("exec", "-i", name, "tar", "-x")));
        assertEquals(List.of("java", "-version"), after.get(2).subList(after.get(2).size() - 2, after.get(2).size()));
        assertTrue(after.get(3).containsAll(List.of("tar", "-c", "--no-recursion", "target/out.txt")));
        assertEquals(List.of("docker", "rm", "-f", name), after.get(after.size() - 1));
    }

    @Test
    void timeoutKillsContainerByNameThenRemovesIt() throws Exception {
        docker.commandOutcome = new DockerCli.Outcome(-1, true, "partial", "");
        Result r = runner.run(spec());
        assertTrue(r.timedOut());
        assertEquals(-1, r.exitCode());
        assertTrue(r.promoted().isEmpty());
        List<List<String>> a = docker.argvs();
        String name = a.stream().filter(x -> x.get(1).equals("run")).reduce((x, y) -> y).map(x -> x.get(
            x.indexOf("--name") + 1)).orElseThrow();
        int kill = a.lastIndexOf(List.of("docker", "kill", name));
        int rm = a.lastIndexOf(List.of("docker", "rm", "-f", name));
        assertTrue(kill > 0 && rm > kill, "docker kill <name> then docker rm -f <name>: " + a);
    }

    @Test
    void refusesWhenProbeShowsRequiredControlUnenforceable() {
        docker.failing = EnumSet.of(Control.DISK_LIMIT);
        SandboxRefusedException e = assertThrows(SandboxRefusedException.class, () -> runner.run(spec()));
        assertEquals(Set.of(Control.DISK_LIMIT), e.missing());
        assertTrue(e.getMessage().contains("DISK_LIMIT") && e.getMessage().contains("\nhint: "));
        assertEquals(1, docker.argvs().stream().filter(a -> a.get(1).equals("run")).count(),
            "only the probe container was started");
    }

    @Test
    void runsWhenTheUnenforceableControlIsNotRequired() throws Exception {
        docker.failing = EnumSet.of(Control.DISK_LIMIT);
        Set<Control> required = EnumSet.allOf(Control.class);
        required.remove(Control.DISK_LIMIT);
        Result r = runner.run(spec(Network.NONE, List.of(), Map.of(), required, List.of()));
        assertFalse(r.enforced().contains(Control.DISK_LIMIT), "enforced reports only what the probe proved");
    }

    @Test
    void refusesAllRequiredWhenEngineIsDown() {
        docker.engineUp = false;
        SandboxRefusedException e = assertThrows(SandboxRefusedException.class, () -> runner.run(spec()));
        assertEquals(ALL, EnumSet.copyOf(e.missing()));
        assertTrue(e.getMessage().contains("not reachable"));
    }

    @Test
    void refusesWritableMountWhenToolchainMustBeReadOnly() {
        Spec s = spec(Network.NONE, List.of(new Mount(scratch, "/cache/m2", false)), Map.of(), ALL, List.of());
        SandboxRefusedException e = assertThrows(SandboxRefusedException.class, () -> runner.run(s));
        assertEquals(Set.of(Control.READ_ONLY_TOOLCHAIN, Control.SCRATCH_ONLY_WRITES), e.missing());
    }

    @Test
    void refusesNetworkNoneRequiredWithPrefetchNetwork() {
        Spec s = spec(Network.PREFETCH_ONLY, List.of(), Map.of(), ALL, List.of());
        SandboxRefusedException e = assertThrows(SandboxRefusedException.class, () -> runner.run(s));
        assertEquals(Set.of(Control.NETWORK_NONE), e.missing());
    }

    @Test
    void prefetchWithoutNetworkNoneRequiredGetsNetworkAndNoNetworkFlag() throws Exception {
        Set<Control> required = EnumSet.allOf(Control.class);
        required.removeAll(Set.of(Control.NETWORK_NONE, Control.READ_ONLY_TOOLCHAIN, Control.SCRATCH_ONLY_WRITES));
        Result r = runner.run(spec(Network.PREFETCH_ONLY, List.of(new Mount(scratch, "/cache/m2", false)), Map.of(),
            required, List.of()));
        List<String> run = docker.argvs().stream().filter(a -> a.get(1).equals("run")).reduce((x, y) -> y)
            .orElseThrow();
        assertFalse(run.contains("none"));
        assertFalse(r.enforced().contains(Control.NETWORK_NONE));
        assertTrue(r.enforced().contains(Control.READ_ONLY_ROOT));
    }

    @Test
    void refusesEnvKeysOffTheAllowlist() {
        Spec s = spec(Network.NONE, List.of(), Map.of("ANTHROPIC_API_KEY", "sk-x"), ALL, List.of());
        SandboxRefusedException e = assertThrows(SandboxRefusedException.class, () -> runner.run(s));
        assertEquals(Set.of(Control.ENV_ALLOWLIST), e.missing());
        assertFalse(e.getMessage().contains("sk-x"), "secret values never appear in messages");
    }

    @Test
    void exit137IsAResourceLimitOutcome() throws Exception {
        docker.commandOutcome = new DockerCli.Outcome(137, false, "", "Killed");
        Result r = runner.run(spec());
        assertEquals(137, r.exitCode());
        assertTrue(DockerSandboxRunner.isResourceLimitExit(r));
        assertFalse(DockerSandboxRunner.isResourceLimitExit(new Result(1, false, "", "", List.of(), Duration.ZERO,
            Set.of())));
    }

    @Test
    void probeIsCachedPerRunner() throws Exception {
        runner.run(spec());
        runner.run(spec());
        assertEquals(1, docker.argvs().stream().filter(a -> a.get(1).equals("version")).count());
    }

    @Test
    void badSpecsAreRejectedBeforeAnythingRuns() throws IOException {
        Spec traversal = spec(Network.NONE, List.of(), Map.of(), ALL, List.of(new Promotion("../x", 1)));
        assertThrows(IllegalArgumentException.class, () -> runner.run(traversal));
        Spec mountOnScratch = spec(Network.NONE, List.of(new Mount(scratch, "/scratch/x", true)), Map.of(), ALL,
            List.of());
        assertThrows(IllegalArgumentException.class, () -> runner.run(mountOnScratch));
        assertTrue(docker.calls.isEmpty());
    }

    @Test
    void serviceLoaderFindsTheRunnerWithoutTouchingDocker() {
        List<SandboxRunner> found = ServiceLoader.load(SandboxRunner.class).stream().map(ServiceLoader.Provider::get)
            .toList();
        assertTrue(found.stream().anyMatch(r -> r instanceof DockerSandboxRunner), found.toString());
        assertFalse(DockerSandboxRunner.defaultImage().isBlank());
    }

    private static void assertFlag(List<String> argv, String flag, String value) {
        boolean found = false;
        for (int i = 0; i + 1 < argv.size(); i++) {
            found |= argv.get(i).equals(flag) && argv.get(i + 1).equals(value);
        }
        assertTrue(found, flag + " " + value + " missing from " + argv);
    }
}
