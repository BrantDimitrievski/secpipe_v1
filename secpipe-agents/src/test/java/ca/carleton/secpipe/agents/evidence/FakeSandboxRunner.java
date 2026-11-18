package ca.carleton.secpipe.agents.evidence;

import ca.carleton.secpipe.contracts.spi.SandboxRunner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Scripted {@link SandboxRunner}: the Maven prefetch and dry check succeed unless told otherwise; each regression-test
 * run takes the next scripted {@link Step}, which may write a JUnit XML report into the scratch copy and promote it.
 */
final class FakeSandboxRunner implements SandboxRunner {

    /** One scripted regression-test run. */
    interface Step {
        Result run(Spec spec) throws SandboxRefusedException;
    }

    static final String FAILED_REPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="JUnit Jupiter" tests="1" failures="1" errors="0">
              <testcase name="usernamePayloadMustNotReachTheQueryText()" classname="secpipe.generated.T">
                <failure message="attacker-controlled username reached the SQL text ==&gt; expected: &lt;false&gt; \
            but was: &lt;true&gt;" type="org.opentest4j.AssertionFailedError">stack</failure>
              </testcase>
            </testsuite>
            """;
    static final String PASSED_REPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="JUnit Jupiter" tests="1" failures="0" errors="0">
              <testcase name="usernamePayloadMustNotReachTheQueryText()" classname="secpipe.generated.T"/>
            </testsuite>
            """;
    static final String ERROR_REPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="JUnit Jupiter" tests="1" failures="0" errors="1">
              <testcase name="usernamePayloadMustNotReachTheQueryText()" classname="secpipe.generated.T">
                <error message="boom" type="java.lang.NullPointerException">stack</error>
              </testcase>
            </testsuite>
            """;

    final List<Spec> specs = new ArrayList<>();
    private final Deque<Step> steps = new ArrayDeque<>();
    private Set<Control> enforceable = EnumSet.allOf(Control.class);
    private int prefetchExit;

    FakeSandboxRunner then(Step step) {
        steps.add(step);
        return this;
    }

    FakeSandboxRunner enforcing(Set<Control> controls) {
        this.enforceable = controls.isEmpty() ? EnumSet.noneOf(Control.class) : EnumSet.copyOf(controls);
        return this;
    }

    FakeSandboxRunner prefetchFails() {
        this.prefetchExit = 1;
        return this;
    }

    List<Spec> testRuns() {
        return specs.stream().filter(s -> s.purpose().startsWith(RegressionTestRun.PURPOSE_PREFIX)).toList();
    }

    @Override
    public Capabilities probe() {
        return new Capabilities(true, Set.copyOf(enforceable), Optional.empty(), Optional.of("fake"));
    }

    @Override
    public Result run(Spec spec) throws SandboxRefusedException {
        specs.add(spec);
        if (spec.purpose().equals("maven prefetch")) {
            return prefetchExit == 0 ? exit(0, "") : exit(prefetchExit,
                    "[ERROR] Failed to execute goal: Could not resolve dependencies for project com.shop:sqli-min:jar:0.1.0: "
                            + "Could not find artifact javax.servlet:javax.servlet-api:jar:4.0.1");
        }
        if (!spec.purpose().startsWith(RegressionTestRun.PURPOSE_PREFIX)) {
            return exit(0, "");
        }
        Step step = steps.poll();
        if (step == null) {
            throw new AssertionError("unexpected regression-test run " + spec.purpose());
        }
        return step.run(spec);
    }

    static Result exit(int code, String stdout) {
        return new Result(code, false, stdout, "", List.of(), Duration.ofSeconds(1), Set.of());
    }

    static Step report(int exit, String xml) {
        return spec -> {
            Path report = spec.scratchDir().resolve(RegressionTestRun.REPORT);
            try {
                Files.createDirectories(report.getParent());
                Files.writeString(report, xml, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new Result(exit, false, "", "", List.of(report), Duration.ofSeconds(2),
                    EnumSet.allOf(Control.class));
        };
    }

    static Step compileError() {
        return spec -> exit(RegressionTestRun.BUILD_FAILED_EXIT,
                "[ERROR] COMPILATION ERROR :\n[ERROR] /scratch/src/test/java/secpipe/generated/T.java:[3,1] "
                        + "cannot find symbol");
    }

    static Step timeout() {
        return spec -> new Result(-1, true, "", "", List.of(), Duration.ofMinutes(5), EnumSet.allOf(Control.class));
    }

    static Step oomKilled() {
        return spec -> exit(137, "");
    }

    static Step refused(Control missing) {
        return spec -> {
            throw new SandboxRefusedException("cannot enforce " + missing, Set.of(missing));
        };
    }
}
