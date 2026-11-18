package ca.carleton.secpipe.sandbox;

import ca.carleton.secpipe.contracts.spi.SandboxRunner.Control;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** A scripted stand-in for the {@code docker} and {@code tar} processes. Records every invocation. */
final class DockerSandboxRunnerFakeDocker implements DockerCli.ProcessRunner {

    final List<DockerCli.Invocation> calls = new ArrayList<>();
    boolean engineUp = true;
    Set<Control> failing = EnumSet.noneOf(Control.class);
    boolean containerSurvivesKill;
    DockerCli.Outcome commandOutcome = new DockerCli.Outcome(0, false, "hello\n", "");
    byte[] promotionTar = new byte[0];

    @Override
    public DockerCli.Outcome run(DockerCli.Invocation inv) throws IOException {
        calls.add(inv);
        List<String> a = inv.argv();
        if (a.get(0).equals("tar")) {
            return ok("");
        }
        switch (a.get(1)) {
            case "version":
                return engineUp ? ok("29.4.2\n") : new DockerCli.Outcome(1, false, "", "Cannot connect to the Docker "
                    + "daemon at unix:///var/run/docker.sock\n");
            case "run":
            case "kill":
            case "rm":
                return ok("");
            case "ps":
                return ok(containerSurvivesKill ? "3f2a\n" : "");
            case "exec":
                return exec(inv);
            default:
                throw new IllegalStateException("unexpected " + a);
        }
    }

    private DockerCli.Outcome exec(DockerCli.Invocation inv) throws IOException {
        List<String> a = inv.argv();
        List<String> cmd = a.subList(a.indexOf("--workdir") + 3, a.size());
        if (cmd.size() == 3 && cmd.get(0).equals("sh") && cmd.get(2).contains("PROBE")) {
            String lines = EnumSet.allOf(Control.class).stream().filter(c -> c != Control.WALL_CLOCK_LIMIT)
                .map(c -> "PROBE " + c + (failing.contains(c) ? " fail x" : " ok"))
                .collect(Collectors.joining("\n"));
            return ok(lines + "\n");
        }
        if (cmd.equals(List.of("sleep", "30"))) {
            return failing.contains(Control.WALL_CLOCK_LIMIT) ? ok("") : new DockerCli.Outcome(-1, true, "", "");
        }
        if (cmd.get(0).equals("tar") && cmd.get(1).equals("-x")) {
            return ok("");
        }
        if (cmd.get(0).equals("tar") && cmd.get(1).equals("-c")) {
            Files.write(inv.stdout().orElseThrow(), promotionTar);
            return ok("");
        }
        return commandOutcome;
    }

    List<List<String>> argvs() {
        return calls.stream().map(DockerCli.Invocation::argv).toList();
    }

    List<String> argvStartingWith(String verb) {
        return argvs().stream().filter(a -> a.size() > 1 && a.get(1).equals(verb)).findFirst().orElseThrow();
    }

    private static DockerCli.Outcome ok(String out) {
        return new DockerCli.Outcome(0, false, out, "");
    }
}
