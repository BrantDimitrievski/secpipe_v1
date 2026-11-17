package ca.carleton.secpipe.contracts.config;

import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.map;
import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.resolve;
import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.writeYaml;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The implications WO-C.4 applies in one place: offline, no-ai, PR diff scope, enable-fixes=true. */
class ConfigResolverImplicationsTest {

    private static final Map<String, String> PR_JOB =
            map("GITHUB_ACTIONS", "true", "GITHUB_EVENT_NAME", "pull_request");

    @TempDir
    Path dir;

    @Test
    void offlineForcesProviderNone() {
        SecPipeConfig c = resolve(map("offline", "", "llm-provider", "anthropic"), map());

        assertThat(c.offline()).isTrue();
        assertThat(c.llmProvider()).isEqualTo(ProviderMode.NONE);
    }

    @Test
    void offlineKeepsOllama() {
        SecPipeConfig c = resolve(map("offline", "true"), map("SECPIPE_LLM_PROVIDER", "ollama"));

        assertThat(c.llmProvider()).isEqualTo(ProviderMode.OLLAMA);
    }

    @Test
    void offlineFromYamlAppliesToAProviderFromTheCli() throws IOException {
        Path yaml = writeYaml(dir, "offline: true\n");

        assertThat(resolve(map("llm-provider", "copilot"), map(), yaml).llmProvider()).isEqualTo(ProviderMode.NONE);
    }

    @Test
    void noAiForcesProviderNoneEvenForOllama() {
        assertThat(resolve(map("no-ai", "", "llm-provider", "ollama"), map()).llmProvider())
                .isEqualTo(ProviderMode.NONE);
        assertThat(resolve(map(), map("SECPIPE_NO_AI", "1", "SECPIPE_LLM_PROVIDER", "openai")).llmProvider())
                .isEqualTo(ProviderMode.NONE);
    }

    @Test
    void withoutOfflineOrNoAiTheRequestedProviderIsKept() {
        assertThat(resolve(map("llm-provider", "openai-compatible"), map()).llmProvider())
                .isEqualTo(ProviderMode.OPENAI_COMPATIBLE);
    }

    @Test
    void quickProfileOnAPullRequestJobDefaultsToDiffScope() {
        assertThat(resolve(map("profile", "quick"), PR_JOB).scope()).isEqualTo(SecPipeConfig.ScopeMode.DIFF);
    }

    @Test
    void diffDefaultNeedsQuickProfileAndAPullRequestEvent() {
        assertThat(resolve(map("profile", "standard"), PR_JOB).scope()).isEqualTo(SecPipeConfig.ScopeMode.FULL);
        assertThat(resolve(map("profile", "quick"), map("GITHUB_ACTIONS", "true", "GITHUB_EVENT_NAME", "push"))
                .scope()).isEqualTo(SecPipeConfig.ScopeMode.FULL);
        assertThat(resolve(map("profile", "quick"), map("GITHUB_EVENT_NAME", "pull_request")).scope())
                .isEqualTo(SecPipeConfig.ScopeMode.FULL);
    }

    @Test
    void anExplicitScopeIsNeverOverriddenOnPullRequests() throws IOException {
        assertThat(resolve(map("profile", "quick", "scope", "full"), PR_JOB).scope())
                .isEqualTo(SecPipeConfig.ScopeMode.FULL);
        assertThat(resolve(map("profile", "quick"), PR_JOB, writeYaml(dir, "scope: full\n")).scope())
                .isEqualTo(SecPipeConfig.ScopeMode.FULL);
    }

    @Test
    void enableFixesTrueMeansSuggest() {
        assertThat(resolve(map("enable-fixes", "true"), map()).enableFixes()).isEqualTo(SecPipeConfig.FixMode.SUGGEST);
        assertThat(resolve(map("enable-fixes", "pr"), map()).enableFixes()).isEqualTo(SecPipeConfig.FixMode.PR);
        assertThat(resolve(map("enable-fixes", "false"), map()).enableFixes()).isEqualTo(SecPipeConfig.FixMode.FALSE);
    }

    @Test
    void enableFixesBooleanTrueInYamlMeansSuggest() throws IOException {
        assertThat(resolve(map(), map(), writeYaml(dir, "enable-fixes: true\n")).enableFixes())
                .isEqualTo(SecPipeConfig.FixMode.SUGGEST);
        assertThat(resolve(map(), map(), writeYaml(dir, "enable-fixes: false\n")).enableFixes())
                .isEqualTo(SecPipeConfig.FixMode.FALSE);
    }

    @Test
    void baseRefFallsBackToGithubBaseRef() {
        Map<String, String> env = map("GITHUB_BASE_REF", "main");

        assertThat(resolve(map(), env).baseRef()).contains("main");
        assertThat(resolve(map("base-ref", "origin/dev"), env).baseRef()).contains("origin/dev");
    }

    @Test
    void noSandboxDisablesTheSandbox() {
        assertThat(resolve(map("no-sandbox", ""), map()).sandboxEnabled()).isFalse();
    }
}
