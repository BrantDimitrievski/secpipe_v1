package ca.carleton.secpipe.contracts.config;

import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.map;
import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.resolve;
import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.writeYaml;
import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.finding.Finding;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Built-in defaults (PLAN §4.3) and the profile-dependent agent-call budget. */
class ConfigResolverDefaultsTest {

    @TempDir
    Path dir;

    @Test
    void noInputGivesTheDocumentedCliDefaults() {
        SecPipeConfig c = resolve(map(), map());

        assertThat(c.target()).isEqualTo(Path.of("."));
        assertThat(c.outDir()).isEqualTo(Path.of("secpipe-out"));
        assertThat(c.profile()).isEqualTo(RunProfile.STANDARD);
        assertThat(c.scope()).isEqualTo(SecPipeConfig.ScopeMode.FULL);
        assertThat(c.baseRef()).isEmpty();
        assertThat(c.headRef()).isEmpty();
        assertThat(c.llmProvider()).isEqualTo(ProviderMode.NONE);
        assertThat(c.defaultModel()).isEmpty();
        assertThat(c.modelForRole()).isEmpty();
        assertThat(c.tierOverrides()).isEmpty();
        assertThat(c.llmBaseUrl()).isEmpty();
        assertThat(c.tools()).isEmpty();
        assertThat(c.excludeTools()).isEmpty();
        assertThat(c.maxAgentCalls()).isEqualTo(200);
        assertThat(c.maxFixAttempts()).isEqualTo(3);
        assertThat(c.maxSandboxRuns()).isEqualTo(25);
        assertThat(c.enableFixes()).isEqualTo(SecPipeConfig.FixMode.SUGGEST);
        assertThat(c.sandboxEnabled()).isTrue();
        assertThat(c.offline()).isFalse();
        assertThat(c.failOnSeverity()).contains(Finding.Score5.HIGH);
        assertThat(c.failOnNeedsValidation()).isFalse();
        assertThat(c.failOnIncomplete()).isFalse();
        assertThat(c.formats()).containsExactlyInAnyOrder(SecPipeConfig.OutputFormat.values());
        assertThat(c.priorRunDir()).isEmpty();
        assertThat(c.printPrompts()).isFalse();
        assertThat(c.denyPaths()).isEmpty();
        assertThat(c.maxSnippetLines()).isEqualTo(80);
        assertThat(c.maxFilesPerCall()).isEqualTo(6);
        assertThat(c.disabledNodes()).isEmpty();
        assertThat(c.includeTests()).isFalse();
        assertThat(c.allowBuildExtensions()).isFalse();
        assertThat(c.acceptCodeqlTerms()).isFalse();
        assertThat(c.engine()).isEqualTo(SecPipeConfig.GraphEngine.PLAIN);
        assertThat(c.toolTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(c.verbosity()).isEqualTo(SecPipeConfig.Verbosity.NORMAL);
    }

    @Test
    void quickProfileDefaultsToFortyAgentCalls() {
        assertThat(resolve(map("profile", "quick"), map()).maxAgentCalls()).isEqualTo(40);
    }

    @Test
    void standardProfileDefaultsToTwoHundredAgentCalls() {
        assertThat(resolve(map("profile", "standard"), map()).maxAgentCalls()).isEqualTo(200);
    }

    @Test
    void deepProfileDefaultsToEightHundredAgentCalls() {
        assertThat(resolve(map("profile", "deep"), map()).maxAgentCalls()).isEqualTo(800);
    }

    @Test
    void profileDefaultFollowsTheProfileFromAnyLayer() throws IOException {
        assertThat(resolve(map(), map("SECPIPE_PROFILE", "quick")).maxAgentCalls()).isEqualTo(40);
        assertThat(resolve(map(), map(), writeYaml(dir, "profile: deep\n")).maxAgentCalls()).isEqualTo(800);
    }

    @Test
    void anExplicitBudgetInAnyLayerBeatsTheProfileDefault() throws IOException {
        Path yaml = writeYaml(dir, "max-agent-calls: 100\n");

        assertThat(resolve(map("profile", "deep"), map(), yaml).maxAgentCalls()).isEqualTo(100);
        assertThat(resolve(map("profile", "quick"), map("SECPIPE_MAX_AGENT_CALLS", "0")).maxAgentCalls())
                .isZero();
    }

    @Test
    void failOnNoneMeansNeverFailOnSeverity() {
        assertThat(resolve(map("fail-on", "none"), map()).failOnSeverity()).isEmpty();
        assertThat(resolve(map("fail-on", "critical"), map()).failOnSeverity()).contains(Finding.Score5.CRITICAL);
    }

    @Test
    void durationsAcceptUnitsIsoAndBareSeconds() {
        assertThat(resolve(map("tool-timeout", "500ms"), map()).toolTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(resolve(map("tool-timeout", "2m"), map()).toolTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(resolve(map("tool-timeout", "PT1H"), map()).toolTimeout()).isEqualTo(Duration.ofHours(1));
        assertThat(resolve(map("tool-timeout", "45"), map()).toolTimeout()).isEqualTo(Duration.ofSeconds(45));
    }
}
