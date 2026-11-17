package ca.carleton.secpipe.contracts.config;

import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.failure;
import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.map;
import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.resolve;
import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.finding.Finding;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Override and environment values are validated with "problem + hint" messages naming their source. */
class ConfigResolverValidationTest {

    @Test
    void unknownOverrideIsRejected() {
        ConfigException e = failure(map("--profil", "quick"), map(), Optional.empty());

        assertThat(e.problem()).contains("unknown option '--profil'").contains("did you mean 'profile'");
        assertThat(e.getMessage()).contains("hint:");
    }

    @Test
    void badEnvironmentValueNamesTheVariable() {
        ConfigException e = failure(map(), map("SECPIPE_PROFILE", "thorough"), Optional.empty());

        assertThat(e.problem()).contains("SECPIPE_PROFILE").contains("thorough");
        assertThat(e.getMessage()).contains("hint: use one of quick, standard, deep");
    }

    @Test
    void numbersMustBeWholeAndInRange() {
        assertThat(failure(map("max-agent-calls", "ten"), map(), Optional.empty()).problem())
                .contains("--max-agent-calls").contains("whole number");
        assertThat(failure(map("max-files-per-call", "0"), map(), Optional.empty()).problem())
                .contains(">= 1");
        assertThat(failure(map(), map("SECPIPE_MAX_SANDBOX_RUNS", "-1"), Optional.empty()).problem())
                .contains("SECPIPE_MAX_SANDBOX_RUNS");
    }

    @Test
    void flagsAcceptOnlyBooleanSpellings() {
        assertThat(resolve(map("offline", "yes"), map()).offline()).isTrue();
        assertThat(failure(map("offline", "maybe"), map(), Optional.empty()).getMessage())
                .contains("--offline").contains("hint:");
    }

    @Test
    void quietAndVerboseTogetherAreRejected() {
        ConfigException e = failure(map("-q", "", "-v", ""), map(), Optional.empty());

        assertThat(e.getMessage()).contains("hint: use only one of -q and -v");
    }

    @Test
    void repeatableOptionsUseTheRepeatSeparator() {
        String sep = ConfigResolver.REPEAT_SEPARATOR;
        SecPipeConfig c = resolve(map(
                "model-for", "disprover=m-strong" + sep + "hunter=m-cheap",
                "model-tier", "org/model=v1=small" + sep + "m-strong=STRONG",
                "deny-path", "**/{x,y}/**" + sep + "**/z/**",
                "format", "json,sarif" + sep + "html"), map());

        assertThat(c.modelForRole()).containsEntry(AgentRole.DISPROVER, "m-strong")
                .containsEntry(AgentRole.HUNTER, "m-cheap");
        assertThat(c.tierOverrides()).containsEntry("org/model=v1", Finding.VerifierRef.CapabilityTier.SMALL)
                .containsEntry("m-strong", Finding.VerifierRef.CapabilityTier.STRONG);
        assertThat(c.denyPaths()).containsExactly("**/{x,y}/**", "**/z/**");
        assertThat(c.formats()).containsExactlyInAnyOrder(SecPipeConfig.OutputFormat.JSON,
                SecPipeConfig.OutputFormat.SARIF, SecPipeConfig.OutputFormat.HTML);
    }

    @Test
    void malformedPairsAndUrlsAreRejected() {
        assertThat(failure(map("model-for", "wizard=m"), map(), Optional.empty()).getMessage())
                .contains("--model-for").contains("hint:");
        assertThat(failure(map("model-tier", "m=huge"), map(), Optional.empty()).getMessage())
                .contains("--model-tier").contains("hint:");
        assertThat(failure(map("llm-base-url", "localhost:11434"), map(), Optional.empty()).getMessage())
                .contains("--llm-base-url").contains("hint:");
        assertThat(failure(map("format", "pdf"), map(), Optional.empty()).getMessage())
                .contains("--format").contains("hint:");
        assertThat(failure(map("tool-timeout", "soon"), map(), Optional.empty()).getMessage())
                .contains("--tool-timeout").contains("hint:");
    }

    @Test
    void emptyValueForAValuedOptionIsRejected() {
        ConfigException e = failure(map("model", " "), map(), Optional.empty());

        assertThat(e.problem()).contains("--model").contains("non-empty");
    }

    @Test
    void envNameUsesUpperSnakeCase() {
        assertThat(ConfigResolver.envName("max-agent-calls")).isEqualTo("SECPIPE_MAX_AGENT_CALLS");
        assertThat(ConfigResolver.envName("no-ai")).isEqualTo("SECPIPE_NO_AI");
    }
}
