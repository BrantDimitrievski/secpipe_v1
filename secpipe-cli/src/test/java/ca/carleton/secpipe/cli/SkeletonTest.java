package ca.carleton.secpipe.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** WO-0.1 placeholder: the CLI module compiles, sees every module's types, and reports its build version. */
class SkeletonTest {

    @Test
    void packageInfoLoads() throws ClassNotFoundException {
        Class<?> packageInfo = Class.forName("ca.carleton.secpipe.cli.package-info");
        assertThat(packageInfo.getPackageName()).isEqualTo("ca.carleton.secpipe.cli");
    }

    @Test
    void contractsAreOnTheClasspath() {
        assertThat(PipelineGraph.class.getPackageName()).isEqualTo("ca.carleton.secpipe.contracts.pipeline");
    }

    @Test
    void versionFlagPrintsTheFilteredProjectVersion() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = SecPipeCli.run(new String[] {"--version"}, print(out), print(err));

        assertThat(code).isEqualTo(SecPipeCli.EXIT_OK);
        assertThat(out.toString(StandardCharsets.UTF_8).strip()).matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?");
        assertThat(err.size()).isZero();
    }

    @Test
    void unknownArgumentsAreAUsageErrorWithAHint() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = SecPipeCli.run(new String[] {"scan", "--no-such-option"}, print(out), print(err));

        assertThat(code).isEqualTo(SecPipeCli.EXIT_USAGE);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("error:").contains("hint:");
    }

    private static PrintStream print(ByteArrayOutputStream sink) {
        return new PrintStream(sink, true, StandardCharsets.UTF_8);
    }
}
