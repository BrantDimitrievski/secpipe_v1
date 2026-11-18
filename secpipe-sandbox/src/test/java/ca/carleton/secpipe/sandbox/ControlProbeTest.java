package ca.carleton.secpipe.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.spi.SandboxRunner.Capabilities;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Control;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ControlProbeTest {

    private final DockerSandboxRunnerFakeDocker docker = new DockerSandboxRunnerFakeDocker();
    private final ControlProbe probe = new ControlProbe(new DockerCli("docker", docker), "secpipe-runner:test");

    @Test
    void allThirteenEnforcedWhenEveryCheckPasses() throws InterruptedException {
        Capabilities c = probe.probe();
        assertTrue(c.available());
        assertEquals(EnumSet.allOf(Control.class), EnumSet.copyOf(c.enforceable()));
        assertEquals(Optional.of("29.4.2"), c.engineVersion());
        assertTrue(c.reason().isEmpty());
    }

    @Test
    void failedCheckMakesControlUnenforceableAndNamesIt() throws InterruptedException {
        docker.failing = EnumSet.of(Control.DISK_LIMIT);
        Capabilities c = probe.probe();
        assertFalse(c.enforceable().contains(Control.DISK_LIMIT));
        assertEquals(12, c.enforceable().size());
        assertTrue(c.reason().orElseThrow().contains("DISK_LIMIT"));
    }

    @Test
    void wallClockNeedsTheContainerGoneAfterKill() throws InterruptedException {
        docker.containerSurvivesKill = true;
        Capabilities c = probe.probe();
        assertFalse(c.enforceable().contains(Control.WALL_CLOCK_LIMIT));
        int kill = indexOf("kill");
        int ps = indexOf("ps");
        assertTrue(kill >= 0 && kill < ps, "probe kills by name, then checks docker ps -a: " + docker.argvs());
    }

    @Test
    void engineDownMeansUnavailableWithReason() throws InterruptedException {
        docker.engineUp = false;
        Capabilities c = probe.probe();
        assertFalse(c.available());
        assertTrue(c.enforceable().isEmpty());
        assertTrue(c.reason().orElseThrow().contains("not reachable"));
        assertEquals(1, docker.calls.size(), "no container is started when the engine is down");
    }

    @Test
    void controlsWithoutEvidenceAreNotEnforceable() {
        // flag acceptance is not proof: a control the script did not report on stays unenforceable
        Map<Control, String> parsed = ControlProbe.parse("PROBE NETWORK_NONE ok lo\nnoise\nPROBE BOGUS ok\n"
            + "PROBE CPU_LIMIT fail max\nPROBE READ_ONLY_ROOT ok\nPROBE READ_ONLY_ROOT fail later\n");
        Capabilities c = ControlProbe.capabilities(parsed, Optional.empty());
        assertEquals(EnumSet.of(Control.NETWORK_NONE), EnumSet.copyOf(c.enforceable()));
    }

    @Test
    void probeContainerUsesTheRunnerFlagsAndIsAlwaysRemoved() throws InterruptedException {
        probe.probe();
        List<String> run = docker.argvStartingWith("run");
        assertTrue(run.containsAll(List.of("--network", "none", "--read-only", "--cap-drop", "ALL")));
        assertTrue(run.stream().anyMatch(s -> s.startsWith("type=bind,") && s.endsWith(",readonly")));
        String name = run.get(run.indexOf("--name") + 1);
        assertEquals(List.of("docker", "rm", "-f", name), docker.argvs().get(docker.argvs().size() - 1));
        assertEquals("leaked", docker.calls.stream().filter(i -> i.argv().get(1).equals("run")).findFirst()
            .orElseThrow().extraEnv().get(ControlProbe.CANARY), "the host canary is set on the CLI process only");
    }

    private int indexOf(String verb) {
        List<List<String>> a = docker.argvs();
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).get(1).equals(verb)) {
                return i;
            }
        }
        return -1;
    }
}
