package ca.carleton.secpipe.contracts.config;

import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.map;
import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.resolve;
import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.writeYaml;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Defaults &lt; secpipe.yml &lt; environment &lt; overrides, for every combination of present layers. */
class ConfigResolverPrecedenceTest {

    @TempDir
    Path dir;

    @Test
    void everyCombinationOfLayersPicksTheHighestPresentLayer() throws IOException {
        Path yaml = writeYaml(dir, "max-fix-attempts: 11\nengine: langgraph4j\n");
        for (int mask = 0; mask < 8; mask++) {
            boolean useYaml = (mask & 1) != 0;
            boolean useEnv = (mask & 2) != 0;
            boolean useOverride = (mask & 4) != 0;
            Map<String, String> env = useEnv ? map("SECPIPE_MAX_FIX_ATTEMPTS", "12") : map();
            Map<String, String> overrides = useOverride ? map("max-fix-attempts", "13") : map();
            SecPipeConfig config = ConfigResolver.resolve(overrides, env,
                    useYaml ? Optional.of(yaml) : Optional.empty());

            int expected = useOverride ? 13 : useEnv ? 12 : useYaml ? 11 : SecPipeConfig.DEFAULT_MAX_FIX_ATTEMPTS;
            assertThat(config.maxFixAttempts()).as("layers yaml=%s env=%s override=%s", useYaml, useEnv, useOverride)
                    .isEqualTo(expected);
        }
    }

    @Test
    void enumKeysFollowTheSameMatrix() throws IOException {
        Path yaml = writeYaml(dir, "profile: quick\n");
        for (int mask = 0; mask < 8; mask++) {
            boolean useYaml = (mask & 1) != 0;
            boolean useEnv = (mask & 2) != 0;
            boolean useOverride = (mask & 4) != 0;
            SecPipeConfig config = ConfigResolver.resolve(
                    useOverride ? map("profile", "standard") : map(),
                    useEnv ? map("SECPIPE_PROFILE", "deep") : map(),
                    useYaml ? Optional.of(yaml) : Optional.empty());

            RunProfile expected = useOverride ? RunProfile.STANDARD
                    : useEnv ? RunProfile.DEEP
                    : useYaml ? RunProfile.QUICK
                    : RunProfile.STANDARD;
            assertThat(config.profile()).as("layers yaml=%s env=%s override=%s", useYaml, useEnv, useOverride)
                    .isEqualTo(expected);
        }
    }

    @Test
    void eachKeyResolvesFromItsOwnHighestLayer() throws IOException {
        Path yaml = writeYaml(dir, "profile: deep\nmodel: from-yaml\nengine: langgraph4j\nmax-sandbox-runs: 7\n");
        SecPipeConfig config = resolve(
                map("max-sandbox-runs", "9"),
                map("SECPIPE_MODEL", "from-env", "SECPIPE_MAX_SANDBOX_RUNS", "8"),
                yaml);

        assertThat(config.profile()).isEqualTo(RunProfile.DEEP);
        assertThat(config.defaultModel()).contains("from-env");
        assertThat(config.engine()).isEqualTo(SecPipeConfig.GraphEngine.LANGGRAPH4J);
        assertThat(config.maxSandboxRuns()).isEqualTo(9);
    }

    @Test
    void aHigherLayerReplacesAListInsteadOfMergingIt() throws IOException {
        Path yaml = writeYaml(dir, "tools: [semgrep, pmd]\ndeny-path: ['**/a/**', '**/b/**']\n");
        SecPipeConfig config = resolve(map("tools", "spotbugs"), map("SECPIPE_DENY_PATH", "**/c/**"), yaml);

        assertThat(config.tools()).containsExactly("spotbugs");
        assertThat(config.denyPaths()).containsExactly("**/c/**");
    }

    @Test
    void anExplicitFalseInAHigherLayerBeatsTrueBelowIt() throws IOException {
        Path yaml = writeYaml(dir, "offline: true\ninclude-tests: true\n");
        SecPipeConfig config = resolve(
                map("include-tests", "false"),
                map("SECPIPE_OFFLINE", "false", "SECPIPE_INCLUDE_TESTS", "true"),
                yaml);

        assertThat(config.offline()).isFalse();
        assertThat(config.includeTests()).isFalse();
    }

    @Test
    void blankEnvironmentValuesAreTreatedAsUnset() throws IOException {
        Path yaml = writeYaml(dir, "profile: deep\n");
        SecPipeConfig config = resolve(map(), map("SECPIPE_PROFILE", "  "), yaml);

        assertThat(config.profile()).isEqualTo(RunProfile.DEEP);
    }

    @Test
    void overrideKeysMayCarryLeadingDashesOrShortNames() {
        SecPipeConfig config = resolve(map("--out", "o1", "-q", "", "--max-agent-calls", "5"), map());

        assertThat(config.outDir()).isEqualTo(Path.of("o1"));
        assertThat(config.verbosity()).isEqualTo(SecPipeConfig.Verbosity.QUIET);
        assertThat(config.maxAgentCalls()).isEqualTo(5);

        assertThat(resolve(map("-o", "o2"), map()).outDir()).isEqualTo(Path.of("o2"));
    }

    @Test
    void unrelatedEnvironmentVariablesAreIgnored() {
        SecPipeConfig config = resolve(map(), map("SECPIPE_API_KEY", "sk-test-not-a-key", "PATH", "/usr/bin",
                "SECPIPE_SOMETHING_ELSE", "x"));

        assertThat(config).isEqualTo(resolve(map(), map()));
    }
}
